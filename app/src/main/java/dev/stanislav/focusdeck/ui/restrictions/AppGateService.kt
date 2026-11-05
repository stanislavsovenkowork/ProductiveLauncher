package dev.stanislav.focusdeck.ui.restrictions

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.ui.restrictions.protectionRestricted
import dev.stanislav.focusdeck.ui.apps.safetyExempt
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Package-only, opt-in guard. No node tree, text, window title, URL or input is read or stored. */
class AppGateService : AccessibilityService() {
    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready
        private val _failure = MutableStateFlow(false)
        val failure: StateFlow<Boolean> = _failure
        private var instance: WeakReference<AppGateService>? = null
        fun disableControl() {
            instance?.get()?.let { service ->
                val app = service.application as FocusDeckApp
                if (app.protectionStore.current() != null) return
                app.recordAudit("user_disabled", service.block)
                service.disableSelf()
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var block: FocusBlock? = null
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        GateDiagnostics.emit("service_connected_callback")
        instance = WeakReference(this)
        _connected.value = true
        _ready.value = false
        _failure.value = false
        (application as FocusDeckApp).recordAudit("accessibility_connected")
        GateDiagnostics.emit("audit_enqueued")
        scope.launch { (application as FocusDeckApp).protectionStore.window.collect { enforce() } }
        scope.launch {
            try {
                (application as FocusDeckApp).focusBlockRepository.observeActive().collect {
                    block = it
                    if (!_ready.value) GateDiagnostics.emit("room_ready")
                    _ready.value = true
                    enforce()
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                GateDiagnostics.emit("room_error", error)
                _ready.value = false
                _failure.value = true
            }
        }
        // Reevaluate the last observed app when relief expires, even without another window event.
        scope.launch { while (true) { delay(1_000); enforce() } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        foregroundPackage = event.packageName?.toString()
        enforce()
    }

    private fun enforce() {
        val app = foregroundPackage ?: return
        if (safetyExempt(this, app) || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val store = (application as FocusDeckApp).protectionStore
        if (!protectionRestricted(store.current(), app, store.clock())) return
        // Clear the cached app to avoid a loop. A fresh window event re-evaluates it.
        foregroundPackage = null
        val window = store.current()
        val redirected = performGlobalAction(GLOBAL_ACTION_HOME)
        _failure.value = !redirected
        if (redirected) (application as FocusDeckApp).recordAudit("system_app_denied", if (window?.policyVersion == 2) null else block,
            SystemClock.uptimeMillis(), windowId = window?.id, resolveActive = false)
    }

    override fun onInterrupt() { foregroundPackage = null }
    override fun onDestroy() {
        GateDiagnostics.emit("service_destroyed")
        if (instance?.get() === this) instance = null
        (application as FocusDeckApp).recordAudit("accessibility_disconnected", block)
        _connected.value = false
        _ready.value = false
        foregroundPackage = null
        scope.cancel()
        super.onDestroy()
    }
}
