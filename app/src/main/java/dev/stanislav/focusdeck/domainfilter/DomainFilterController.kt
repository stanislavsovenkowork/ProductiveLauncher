package dev.stanislav.focusdeck.domainfilter

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.app.NotificationManager
import androidx.core.content.ContextCompat
import dev.stanislav.focusdeck.FocusDeckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

fun interface DomainAuditSink { fun record(event:DomainAuditEvent) }
data class DomainAuditEvent(val type:String,val reason:String,val sessionId:String?,val category:String?) {
    fun jsonLine():String=JSONObject().put("schemaVersion",1).put("eventId",UUID.randomUUID().toString())
        .put("sessionId",sessionId ?: JSONObject.NULL).put("occurredAt",Instant.now().toString()).put("source","android")
        .put("category",category ?: JSONObject.NULL).put("type",type).put("evidence","activity").put("coverage","partial")
        .put("metrics",JSONObject().put("focusSeconds",JSONObject.NULL).put("distractionSeconds",JSONObject.NULL).put("maintenanceSeconds",JSONObject.NULL))
        .put("reason",reason).toString()
}
sealed interface DomainConsentAction {
    data class Launch(val intent:Intent):DomainConsentAction
    data object Done:DomainConsentAction
    data object Refused:DomainConsentAction
}

/** App-owned singleton. Construction loads settings and status only: never starts/obtains VPN consent. */
class DomainFilterController(context:Context,private val audit:DomainAuditSink=DomainAuditSink {}) {
    private val app=context.applicationContext as FocusDeckApp
    private val store=DomainSetupStore(app)
    private val protection get()=app.protectionStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val loaded=store.load()
    private val _state=MutableStateFlow(DomainPanelState(setup=loaded,message=if(loaded.enabledForWindows)"Выбор для будущих окон сохранён; активность службы проверяется отдельно" else "Выключено по умолчанию · сначала выберите браузеры и прочитайте границы"))
    val state:StateFlow<DomainPanelState> = _state
    private var pendingWindow:String?=null
    private var pendingEnable=false
    private var lastAudit:Pair<Boolean,String>?=null
    private var lastActiveSession:String?=null
    private var lastActiveCategory:String?=null
    init {
        scope.launch { DomainFilterVpnService.status.collect { runtime ->
            _state.value=_state.value.copy(runtime=runtime)
            val identity=runtime.active to runtime.message
            if(lastAudit!=identity) {
                val prior=lastAudit
                lastAudit=identity
                if(runtime.active) {
                    lastActiveSession=runtime.sessionId
                    lastActiveCategory=protection.current()?.takeIf { it.sessionId==runtime.sessionId }?.category
                    record("health","dns_trial_active_partial",lastActiveSession,lastActiveCategory)
                } else if(prior?.first==true)record("disabled","dns_trial_inactive",lastActiveSession,lastActiveCategory)
                else if(runtime.refused)record("health","dns_service_unavailable")
            }
        } }
    }
    private fun record(type:String,reason:String,sessionId:String?=null,category:String?=null) {
        runCatching { audit.record(DomainAuditEvent(type,reason,sessionId,category)) }
    }
    private fun message(value:String,reason:String?=null) {
        _state.value=_state.value.copy(message=value,busy=false)
        if(reason!=null)record("health",reason)
    }
    fun canSelectPackage(packageName:String)=!domainSafetyExempt(app,packageName)
    fun notificationsResult(granted:Boolean) { message(if(granted)"Уведомления разрешены; запуск требует отдельного согласия" else "Уведомления не разрешены; откройте настройки для безопасного выхода") }
    fun selectPackage(packageName:String,selected:Boolean) {
        if(selected && !canSelectPackage(packageName)){message("Приложение нужно для связи, ввода или восстановления и исключено","dns_refused_safety_scope");return}
        val old=_state.value.setup
        save(old.copy(packages=if(selected)old.packages+packageName else old.packages-packageName,
            enabledForWindows=false))
    }
    fun selectDomain(domain:String,selected:Boolean) {
        val name=runCatching { DomainPolicy.canonical(domain.trim()) }.getOrNull()
            ?: return message("Введите точное имя домена без адреса страницы")
        if (selected && (DomainPolicy.INFRASTRUCTURE + _state.value.setup.additionalProtected).any {
                DomainPolicy.inTree(name,it) || DomainPolicy.inTree(it,name) }) return message("Рабочий домен защищён от блокировки")
        val old=_state.value.setup
        save(old.copy(blocked=if(selected)old.blocked+name else old.blocked-name,enabledForWindows=false))
    }
    fun addProtected(value:String) {
        val name=runCatching { DomainPolicy.canonical(value.trim()) }.getOrNull()
            ?: return message("Введите имя домена без адреса страницы")
        val old=_state.value.setup
        val next=old.copy(additionalProtected=old.additionalProtected+name,enabledForWindows=false)
        if(next.blocked.any { DomainPolicy.inTree(it,name) || DomainPolicy.inTree(name,it) })return message("Это имя пересекается с блокируемым: сначала снимите его выбор")
        save(next)
    }
    private fun save(value:DomainSetup) {
        if(store.save(value)){_state.value=_state.value.copy(setup=value);message("Настройка сохранена для будущего окна; действующая политика не меняется")}
        else message("Настройка не сохранена · прежнее состояние оставлено","dns_settings_unavailable")
    }
    fun disableFutureWindows() {
        if (!protection.disableFutureDomainSnapshots()) { message("Не удалось изменить будущие окна", "dns_settings_unavailable"); return }
        save(_state.value.setup.copy(enabledForWindows=false));record("disabled","dns_future_windows_disabled")
    }
    /** Called by Home before creating ProtectionWindow. Caller persists this frozen JSON separately from app-gate packages. */
    fun snapshotForSession(sessionId:String):String? {
        if(_state.value.setup.packages.any { !canSelectPackage(it) }){message("Область приложений изменилась: проверьте безопасные исключения","dns_refused_safety_scope");return null}
        return runCatching { _state.value.setup.snapshot(sessionId)?.json() }.getOrNull()
    }
    private fun preflight(packages:Set<String> = _state.value.setup.packages):Boolean = try { preflightUnsafe(packages) } catch(_:Exception) {
        message("Не удалось проверить сеть/разрешения · запуск не выполнялся","dns_preflight_unavailable");false
    }
    private fun preflightUnsafe(packages:Set<String>):Boolean {
        if(packages.any { !canSelectPackage(it) }) {
            message("Выбранное приложение теперь нужно для восстановления","dns_refused_safety_scope");return false
        }
        val manager=app.getSystemService(ConnectivityManager::class.java)
        if(DomainFilterVpnService.hasVpn(manager)) {
            message("Другой или уже действующий VPN обнаружен. Мы не будем его заменять","dns_refused_existing_vpn");return false
        }
        val network=manager.allNetworks.firstOrNull { network -> manager.getNetworkCapabilities(network)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }==true }
        val links=network?.let(manager::getLinkProperties)
        if(links==null || links.dnsServers.isEmpty() || links.isPrivateDnsActive || links.privateDnsServerName!=null) {
            message("Нет проверенной сети без активного Private DNS. Настройки DNS не меняем","dns_refused_network_or_private_dns");return false
        }
        if(!app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            message("Разрешите уведомления приложения для видимого выхода из DNS-пробы","dns_refused_notifications");return false
        }
        return true
    }
    fun enableForFutureWindows(risksAccepted:Boolean=false):DomainConsentAction {
        if(_state.value.busy)return DomainConsentAction.Refused
        if(!risksAccepted){message("Сначала подтвердите границы DNS-пробы");return DomainConsentAction.Refused}
        if(runCatching { _state.value.setup.validated() }.isFailure) {
            message("Выберите хотя бы один браузер и одно имя домена","dns_settings_incomplete");return DomainConsentAction.Refused
        }
        if(_state.value.runtime.active) {
            save(_state.value.setup.afterConsent(true));message("Выбор сохранён для будущих окон; действующий VPN не менялся")
            return DomainConsentAction.Done
        }
        if(!preflight())return DomainConsentAction.Refused
        pendingEnable=true;pendingWindow=null
        val consent=try { VpnService.prepare(app) } catch(_:Exception) {
            pendingEnable=false;pendingWindow=null;message("Системное согласие VPN недоступно","dns_service_unavailable");return DomainConsentAction.Refused
        }
        if(consent!=null){_state.value=_state.value.copy(busy=true);return DomainConsentAction.Launch(consent)}
        consentResult(true);return DomainConsentAction.Done
    }
    fun consentUnavailable() { pendingEnable=false;pendingWindow=null;message("Системный диалог VPN недоступен · запуск не выполнялся","dns_service_unavailable") }
    fun consentResult(granted:Boolean) {
        val windowId=pendingWindow
        val enabling=pendingEnable
        pendingEnable=false;pendingWindow=null
        _state.value=_state.value.copy(busy=false)
        if(!granted){message("Системное согласие не получено · DNS-проба не запущена","dns_consent_declined");return}
        if(enabling){
            if(runCatching { _state.value.setup.validated() }.isFailure){message("Выбор изменился; повторите явное включение");return}
            save(_state.value.setup.afterConsent(true));message("Выбрано: DNS-проба для будущих окон. Сейчас VPN не запускался");return}
        if(windowId!=null && protection.current()?.id==windowId)onWindowReady()
    }
    /** Only explicit Home/start/resume invokes this; settings restore alone cannot activate the service. */
    fun onWindowReady():Boolean {
        val window=protection.current() ?: return false
        val frozen=window.domainPolicyJson ?: return false
        val policyIdentity=if(window.policyVersion==2) window.id else window.sessionId
        if(_state.value.runtime.active && _state.value.runtime.sessionId==policyIdentity)return true
        val policy=runCatching { DomainFilterVpnService.parse(frozen) }.getOrNull() ?: return false
        if(policy.sessionId!=policyIdentity){message("Снимок DNS не относится к этому окну","dns_snapshot_mismatch");return false}
        if(!preflight(policy.allowedPackages))return false
        val consent=runCatching { VpnService.prepare(app) }
        if(consent.isFailure){message("Системное согласие VPN недоступно","dns_service_unavailable");return false}
        if(consent.getOrNull()!=null){message("Нужно явное системное согласие в настройке DNS-пробы","dns_consent_required");return false}
        return try {
            ContextCompat.startForegroundService(app,Intent(app,DomainFilterVpnService::class.java).setAction(DomainFilterVpnService.ACTION_START)
                .putExtra(DomainFilterVpnService.EXTRA_POLICY,frozen))
            message("Запуск запрошен · активность подтвердит служба");true
        } catch(_:Exception){message("DNS-служба недоступна · защита не объявляется активной","dns_service_unavailable");false}
    }
    fun resumeCurrentWindow(risksAccepted:Boolean=false):DomainConsentAction {
        if(_state.value.busy)return DomainConsentAction.Refused
        if(!risksAccepted){message("Сначала подтвердите границы DNS-пробы");return DomainConsentAction.Refused}
        val window=protection.current()?.takeIf { it.domainPolicyJson!=null } ?: return DomainConsentAction.Refused
        val frozen=runCatching { DomainFilterVpnService.parse(requireNotNull(window.domainPolicyJson)) }.getOrNull() ?: return DomainConsentAction.Refused
        if(!preflight(frozen.allowedPackages))return DomainConsentAction.Refused
        pendingWindow=window.id;pendingEnable=false
        val consent=try { VpnService.prepare(app) } catch(_:Exception) {
            pendingEnable=false;pendingWindow=null;message("Системное согласие VPN недоступно","dns_service_unavailable");return DomainConsentAction.Refused
        }
        if(consent!=null){_state.value=_state.value.copy(busy=true);return DomainConsentAction.Launch(consent)}
        consentResult(true);return DomainConsentAction.Done
    }
    fun stopCurrent() {
        if (!_state.value.runtime.active) return
        runCatching { app.startService(Intent(app,DomainFilterVpnService::class.java).setAction(DomainFilterVpnService.ACTION_STOP)) }
            .onFailure { message("Не удалось выключить DNS-службу · проверьте системные настройки VPN", "dns_service_unavailable") }
    }
    fun hasFrozenWindow()=protection.current()?.domainPolicyJson!=null
    fun dispose(){scope.cancel()}
}
