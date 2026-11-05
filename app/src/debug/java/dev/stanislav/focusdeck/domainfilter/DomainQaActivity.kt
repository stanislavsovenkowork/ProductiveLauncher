package dev.stanislav.focusdeck.domainfilter

import android.app.Activity
import android.app.NotificationManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager
import java.util.UUID
import kotlin.concurrent.thread
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, fictional domains and the QA package only; never starts without the on-screen action. */
class DomainQaActivity : Activity() {
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var lastQuery = ""
    private val update = object : Runnable {
        override fun run() {
            val s = DomainFilterVpnService.status.value
            statusText.text = "${s.message}\nРаботает: ${s.active}; блокировано: ${s.blockedQueries}; " +
                "пропущено: ${s.forwardedQueries}; неизвестно: ${s.unknownQueries}\n$lastQuery"
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "Только QA: blocked.test и example.com, только тестовое приложение. " +
                "Android допускает один VPN; его разрешение может заменить другое. " +
                "Проба не начнётся при действующем VPN или Private DNS; сеть может измениться между проверками."
        })
        statusText = TextView(this); layout.addView(statusText)
        fun button(label: String, action: () -> Unit) = Button(this).also { it.text = label; it.setOnClickListener { action() }; layout.addView(it) }
        button("Начать короткую DNS-пробу") { prepareTrial() }
        button("Разрешить уведомление QA-пробы") {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        button("Проверить blocked.test") { query("blocked.test") }
        button("Проверить example.com") { query("example.com") }
        button("Остановить DNS-пробу") {
            startService(Intent(this, DomainFilterVpnService::class.java).setAction(DomainFilterVpnService.ACTION_STOP))
        }
        setContentView(layout)
        handler.post(update)
    }
    private fun ready(): Boolean {
        if (DomainFilterVpnService.hasVpn(connectivity)) { lastQuery = "Есть активный VPN; проба не начата."; return false }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            lastQuery = "Уведомления выключены; проба не начата."; return false
        }
        val safe = connectivity.allNetworks.any { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            val links = connectivity.getLinkProperties(network)
            caps != null && links != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !links.isPrivateDnsActive && links.privateDnsServerName == null && links.dnsServers.isNotEmpty()
        }
        if (!safe) lastQuery = "Нет подтверждённой сети без Private DNS; проба не начата."
        return safe
    }
    private fun prepareTrial() {
        if (!ready()) return
        val request = VpnService.prepare(this)
        if (request == null) startTrial() else @Suppress("DEPRECATION") startActivityForResult(request, 42)
    }
    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) startTrial()
    }
    private fun startTrial() {
        if (!ready()) return
        val policy = JSONObject().put("sessionId", UUID.randomUUID().toString())
            .put("blockedDomains", JSONArray().put("blocked.test"))
            .put("allowedPackages", JSONArray().put(packageName)).put("durationSeconds", 120)
            .put("consentVpnReplacementRisk", true)
        startForegroundService(Intent(this, DomainFilterVpnService::class.java)
            .setAction(DomainFilterVpnService.ACTION_START).putExtra(DomainFilterVpnService.EXTRA_POLICY, policy.toString())
            .putExtra(DomainFilterVpnService.EXTRA_QA_TRIAL, true))
    }
    private fun query(host: String) {
        val executor = Executor { work -> thread(isDaemon = true) { work.run() } }
        val flags = DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE
        DnsResolver.getInstance().rawQuery(null, host, DnsResolver.CLASS_IN, DnsResolver.TYPE_A, flags, executor, null,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    handler.post { lastQuery = "$host: DNS rcode=$rcode, ответ ${answer.size} байт" }
                }
                override fun onError(error: DnsResolver.DnsException) {
                    handler.post { lastQuery = "$host: ошибка DNS" }
                }
            })
    }
    override fun onDestroy() { handler.removeCallbacks(update); super.onDestroy() }
}
