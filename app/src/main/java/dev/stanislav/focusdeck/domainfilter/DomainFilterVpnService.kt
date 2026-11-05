package dev.stanislav.focusdeck.domainfilter

import android.app.Notification
import android.content.pm.ApplicationInfo
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.MainActivity
import dev.stanislav.focusdeck.ui.restrictions.reliefRemaining
import java.security.MessageDigest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Experimental DNS-only split VPN. Never starts at boot or replaces another VPN deliberately. */
class DomainFilterVpnService : VpnService() {
    @Volatile private var debugTrial=false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task,"FocusDeckDnsTun").apply { isDaemon=true } }
    private val callbacks = Executors.newSingleThreadExecutor { task -> Thread(task,"FocusDeckDnsResolver").apply { isDaemon=true } }
    private val pending = ConcurrentHashMap.newKeySet<CancellationSignal>()
    private val slots = Semaphore(32)
    private val writeLock = Any()
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var session: DomainSession? = null
    @Volatile private var upstream: Network? = null
    private val protection get()=(application as FocusDeckApp).protectionStore
    @Volatile private var protectionId:String?=null
    private val blockedCount=AtomicLong(0)
    private val forwardedCount=AtomicLong(0)
    private val unknownCount=AtomicLong(0)
    private val watchdog=object:Runnable {
        override fun run() {
            val active=session ?: return
            if(!withinWindow(active)) { stop("Срок DNS-пробы окончен");return }
            if(active.policy.allowedPackages.any { domainSafetyExempt(this@DomainFilterVpnService,it,debugTrial) }) { stop("Системная роль приложения изменилась · DNS-проба остановлена");return }
            if(upstream?.let(::upstreamSafe)!=true) { stop("Сеть/DNS изменились · проба остановлена без отката Private DNS");return }
            publish(null)
            main.postDelayed(this,1000)
        }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_STOP -> {
                stop("DNS-проба выключена пользователем; окно приложений сохраняется")
            }
            ACTION_PAUSE -> {
                val active=session
                val accepted=if(debugTrial)active?.pause(SystemClock.elapsedRealtime(),intent.getLongExtra(EXTRA_PAUSE_SECONDS,120))==true else active!=null && protection.beginRelief()
                if(accepted)publish("Один ограниченный перерыв DNS-пробы")
            }
            ACTION_START -> {
                if(session!=null) { publish("Действующая DNS-политика неизменна; сначала остановите её");return START_NOT_STICKY }
                try {
                    val policy=parse(requireNotNull(intent.getStringExtra(EXTRA_POLICY)))
                    val requested=intent.getBooleanExtra(EXTRA_QA_TRIAL,false)
                    val synthetic=syntheticQaPolicy(policy,packageName,applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,requested)
                    require(!requested || synthetic)
                    debugTrial=synthetic
                    start(policy)
                }
                catch(_:Exception) { stop("DNS-проба не включена · проверьте уведомления, согласие, VPN и Private DNS",refused=true) }
            }
            else -> stop("DNS-проба не восстанавливается автоматически")
        }
        return START_NOT_STICKY
    }
    private fun start(policy:DomainPolicy) {
        require(!hasVpn(connectivity))
        require(policy.allowedPackages.none { domainSafetyExempt(this,it,debugTrial) })
        if(!debugTrial) {
            val window=requireNotNull(protection.current())
            require((if(window.policyVersion==2)window.id else window.sessionId)==policy.sessionId)
            require(parse(requireNotNull(window.domainPolicyJson))==policy)
            val fingerprint=MessageDigest.getInstance("SHA-256").digest(listOf(policy.sessionId,policy.blockedDomains.sorted().joinToString(","),
                policy.blockedSubdomainTrees.sorted().joinToString(","),policy.protectedDomains.sorted().joinToString(","),
                policy.allowedPackages.sorted().joinToString(","),policy.durationSeconds.toString()).joinToString("|").toByteArray()).joinToString(""){"%02x".format(it)}
            val prefs=getSharedPreferences("domainfilter-policy",MODE_PRIVATE)
            if(prefs.getString("window",null)==window.id)require(prefs.getString("hash",null)==fingerprint)
            else check(prefs.edit().putString("window",window.id).putString("hash",fingerprint).commit())
            protectionId=window.id
        }
        val network=connectivity.allNetworks.firstOrNull(::upstreamSafe) ?: error("No safe non-VPN network")
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Локальная DNS-проба",NotificationManager.IMPORTANCE_LOW))
        require(notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        val stopIntent=if(debugTrial)PendingIntent.getService(this,1,Intent(this,DomainFilterVpnService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java).putExtra(EXTRA_REQUEST_EXIT,true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FocusDeck · DNS-проба выбранных приложений")
            .setContentText(if(debugTrial)"Частичный контроль · обходы возможны · остановить в любой момент" else "Частичный контроль · открыть карточку полного выхода")
            .setOngoing(true).addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,if(debugTrial)"Остановить" else "К полному выходу",stopIntent).build()).build()
        startForeground(NOTIFICATION,notification)
        val builder=Builder().setSession("FocusDeck · частичная DNS-проба").setMtu(1280)
            .addAddress("10.111.0.1",32).addDnsServer("10.111.0.2").addRoute("10.111.0.2",32)
            .allowFamily(OsConstants.AF_INET6).setUnderlyingNetworks(arrayOf(network)).setBlocking(false)
        installDomainAppAllowlist(policy.allowedPackages) { builder.addAllowedApplication(it) }
        // Check immediately before establish too. Android provides no atomic 'establish only if none' API.
        require(!hasVpn(connectivity) && upstreamSafe(network) && policy.allowedPackages.none { domainSafetyExempt(this,it,debugTrial) })
        val descriptor=builder.establish() ?: error("VPN consent absent or revoked")
        val active=DomainSession(policy,SystemClock.elapsedRealtime(),if(debugTrial)120_000 else 1800_000)
        synchronized(writeLock) { tunnel=descriptor;upstream=network;session=active }
        blockedCount.set(0);forwardedCount.set(0);unknownCount.set(0)
        publish("DNS-проба работает · только выбранные приложения и DNS через VPN-резолвер (IPv4 UDP)")
        main.post(watchdog)
        io.execute { readPackets(descriptor,active,network) }
    }
    private fun upstreamSafe(network:Network):Boolean {
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return false
        val links=connectivity.getLinkProperties(network) ?: return false
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !links.isPrivateDnsActive && links.privateDnsServerName==null && links.dnsServers.isNotEmpty()
    }
    private fun readPackets(descriptor:ParcelFileDescriptor,active:DomainSession,network:Network) {
        val buffer=ByteArray(32768)
        try {
            while(session===active && tunnel===descriptor) {
                val poll=StructPollfd().apply { fd=descriptor.fileDescriptor;events=OsConstants.POLLIN.toShort() }
                if(Os.poll(arrayOf(poll),500)<=0)continue
                val length=Os.read(descriptor.fileDescriptor,buffer,0,buffer.size)
                if(length<=0)continue
                val query=DnsTunnelCodec.question(buffer.copyOf(length))
                if(query==null) { unknownCount.incrementAndGet();continue }
                if(!upstreamSafe(network)) { main.post { if(session===active)stop("Private DNS/сеть изменились · DNS-проба остановлена") };break }
                if(blocks(active,query.domain)) {
                    if(write(DnsTunnelCodec.blocked(query),active))blockedCount.incrementAndGet() else unknownCount.incrementAndGet()
                    publish(null)
                } else forward(query,active,network)
            }
        } catch(_:Exception) {
            if(session===active)main.post { if(session===active)stop("DNS-интерфейс недоступен · проба остановлена") }
        }
    }
    private fun forward(query:TunnelQuestion,active:DomainSession,network:Network) {
        if(!slots.tryAcquire()) { unknownCount.incrementAndGet();return }
        val cancellation=CancellationSignal()
        pending.add(cancellation)
        val finished=AtomicBoolean(false)
        fun finishOnce():Boolean {
            if(!finished.compareAndSet(false,true))return false
            pending.remove(cancellation);slots.release();return true
        }
        main.postDelayed({
            if(finishOnce()) {
                cancellation.cancel()
                if(session===active){unknownCount.incrementAndGet();publish(null)}
            }
        },5000)
        try {
            DnsResolver.getInstance().rawQuery(network,query.raw,DnsResolver.FLAG_EMPTY,callbacks,cancellation,
                object:DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer:ByteArray,rcode:Int) {
                        if(!finishOnce() || session!==active)return
                        if(upstreamSafe(network)) {
                            val response=DnsTunnelCodec.response(query,answer)
                            if(response!=null && write(response,active))forwardedCount.incrementAndGet() else unknownCount.incrementAndGet()
                        } else unknownCount.incrementAndGet()
                        publish(null)
                    }
                    override fun onError(error:DnsResolver.DnsException) {
                        if(finishOnce() && session===active){unknownCount.incrementAndGet();publish(null)}
                    }
                })
        } catch(_:Exception){if(finishOnce() && session===active){unknownCount.incrementAndGet();publish(null)}}
    }
    private fun write(packet:ByteArray,active:DomainSession):Boolean = synchronized(writeLock) {
        val descriptor=tunnel ?: return@synchronized false
        if(session!==active || !withinWindow(active))return@synchronized false
        runCatching { Os.write(descriptor.fileDescriptor,packet,0,packet.size)==packet.size }.getOrDefault(false)
    }

    private fun withinWindow(active:DomainSession):Boolean = if(debugTrial)SystemClock.elapsedRealtime()<active.deadline
        else domainWindowActive(protection.current(), protectionId, active.policy, protection.clock())
    private fun blocks(active:DomainSession,host:String):Boolean = if(debugTrial)active.blocks(host,SystemClock.elapsedRealtime())
        else domainWindowBlocks(protection.current(), protectionId, active.policy, protection.clock(), host)
    private fun publish(message:String?,refused:Boolean=false) {
        val active=session
        _status.value=DomainFilterStatus(active!=null,message ?: _status.value.message,active?.policy?.sessionId,
            blockedCount.get(),forwardedCount.get(),unknownCount.get(),active?.let { if(debugTrial)it.paused(SystemClock.elapsedRealtime()) else protection.current()?.let { window -> reliefRemaining(window,protection.clock())>0 } ?: false } ?: false,refused)
    }
    private fun stop(reason:String,refused:Boolean=false) {
        main.removeCallbacks(watchdog)
        synchronized(writeLock){session=null;upstream=null;closeDomainTunnel(tunnel);tunnel=null}
        pending.forEach { it.cancel() }
        publish(reason,refused)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onRevoke(){stop("Разрешение VPN отозвано · автоматического переподключения нет");super.onRevoke()}
    override fun onDestroy(){if(session!=null)stop("DNS-служба остановлена");io.shutdownNow();callbacks.shutdownNow();super.onDestroy()}

    companion object {
        const val ACTION_START="dev.stanislav.focusdeck.domainfilter.START"
        const val ACTION_STOP="dev.stanislav.focusdeck.domainfilter.STOP"
        const val ACTION_PAUSE="dev.stanislav.focusdeck.domainfilter.PAUSE"
        const val EXTRA_POLICY="policy"
        const val EXTRA_QA_TRIAL="syntheticQaTrial"
        const val EXTRA_REQUEST_EXIT="focusdeck.requestDomainExit"
        const val EXTRA_PAUSE_SECONDS="seconds"
        private const val CHANNEL="focusdeck_dns_trial"
        private const val NOTIFICATION=48732
        private val _status=MutableStateFlow(DomainFilterStatus())
        val status:StateFlow<DomainFilterStatus> = _status
        fun hasVpn(manager:ConnectivityManager)=manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true }
        fun parse(json:String):DomainPolicy {
            require(json.length<=32768)
            val objectValue=JSONObject(json)
            fun strings(key:String):Set<String> {
                val array=objectValue.optJSONArray(key) ?: return emptySet()
                return (0 until array.length()).map { array.getString(it) }.toSet()
            }
            return DomainPolicy(objectValue.getString("sessionId"),strings("blockedDomains"),strings("blockedSubdomainTrees"),
                strings("protectedDomains")+DomainPolicy.INFRASTRUCTURE,strings("allowedPackages"),
                objectValue.optLong("durationSeconds",1500),objectValue.optBoolean("consentVpnReplacementRisk",false))
        }
    }
}

data class DomainFilterStatus(val active:Boolean=false,val message:String="DNS-проба выключена",val sessionId:String?=null,
    val blockedQueries:Long=0,val forwardedQueries:Long=0,val unknownQueries:Long=0,val paused:Boolean=false,val refused:Boolean=false)
