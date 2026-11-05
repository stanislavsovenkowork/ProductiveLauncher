package dev.stanislav.focusdeck.ui.restrictions

import android.app.BroadcastOptions
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Fixed signed protocol: schedules a bounded wake, never changes administrator policy. */
class GuardWakeClient(context: Context) {
    private val context = context.applicationContext
    enum class Reason { FOCUS_GRACE, EMERGENCY }

    fun managed(): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java).isLockTaskPermitted(context.packageName)
    }.getOrDefault(false)

    suspend fun schedule(epochMillis: Long, reason: Reason): GuardWakeReceipt {
        if (Build.VERSION.SDK_INT < 36 || !managed()) return GuardWakeReceipt.UNAVAILABLE
        val signed = runCatching { context.packageManager.checkSignatures(context.packageName, GUARD) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)
        if (!signed) return GuardWakeReceipt.UNAVAILABLE
        val receipt = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { continuation ->
                val intent = Intent("$GUARD.SCHEDULE_WAKE")
                    .setComponent(ComponentName(GUARD, "$GUARD.WakeRequestReceiver"))
                    .putExtra("epochMillis", epochMillis).putExtra("reason", reason.name)
                val callback = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (continuation.isActive) continuation.resume(GuardWakeReceipt.fromResult(resultCode, resultData))
                    }
                }
                try {
                    context.sendOrderedBroadcast(intent, null,
                        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                        callback, Handler(Looper.getMainLooper()), -1, null, null)
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(GuardWakeReceipt.UNAVAILABLE)
                }
            }
        } ?: GuardWakeReceipt.UNAVAILABLE
        val durable = withContext(Dispatchers.IO) {
            context.getSharedPreferences("guard-wake-receipts", Context.MODE_PRIVATE).edit()
                .putLong("deadline", epochMillis).putString("reason", reason.name)
                .putBoolean("registered", receipt.registered)
                .putBoolean("backgroundLaunchSupported", receipt.backgroundLaunchSupported)
                .putBoolean("exactRequested", receipt.exactRequested).commit()
        }
        return if (durable) receipt else GuardWakeReceipt.UNAVAILABLE
    }

    private companion object { const val GUARD = "dev.stanislav.focusdeck.guard" }
}
