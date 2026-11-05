package dev.stanislav.focusdeck.data.repo

import android.content.Context
import android.util.AtomicFile
import dev.stanislav.focusdeck.data.db.FocusBlock
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pure encoding for a single immutable event. */
internal fun auditEventLine(reason: String, block: FocusBlock?, id: UUID, occurredAt: Instant, windowId: String? = null): String {
    val sid = block?.sessionId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
    val category = block?.category?.takeIf { sid != null && it in setOf("work", "study") }
    val task = block?.taskStableId?.takeIf { sid != null && it.matches(Regex("[A-Za-z0-9_-]+")) }
    val disabled = reason in setOf("user_disabled", "ritual_optout", "protection_exit", "dns_future_windows_disabled", "dns_trial_inactive")
    val type = if (disabled) "disabled" else "health"
    val evidence = if (reason == "window_started") "unknown" else if (disabled && reason != "dns_trial_inactive") "self_report" else "verified"
    val protection = windowId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        ?.let { ",\"protectionId\":\"$it\"" } ?: ""
    return "{\"schemaVersion\":1,\"eventId\":\"$id\",\"sessionId\":" +
        (sid?.let { "\"$it\"" } ?: "null") + ",\"occurredAt\":\"$occurredAt\",\"source\":\"android\",\"category\":" +
        (category?.let { "\"$it\"" } ?: "null") + ",\"type\":\"$type\",\"evidence\":\"$evidence\"," +
        "\"metrics\":{\"focusSeconds\":null,\"distractionSeconds\":null,\"maintenanceSeconds\":null},\"reason\":\"$reason\",\"taskRef\":" +
        (task?.let { "\"$it\"" } ?: "null") + protection + "}\n"
}

/** Private immutable event lines. No app package, window contents, task text or URLs are stored. */
class SessionAudit(context: Context) {
    private val target = AtomicFile(File(context.filesDir, "harness/audit.jsonl"))
    private val lock = Any()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed
    private var lastDeniedAt: Long = Long.MIN_VALUE

    fun snapshot(): String = synchronized(lock) { read() }

    fun record(reason: String, block: FocusBlock? = null, oncePerSession: Boolean = false,
               uptimeMillis: Long? = null, occurredAt: Instant = Instant.now(), windowId: String? = null): Boolean = synchronized(lock) {
        require(reason in setOf("accessibility_connected", "accessibility_disconnected", "user_disabled",
            "launcher_attempt_denied", "system_app_denied", "bounded_relief_started", "rest_without_feeds", "rest_returned", "bounded_relief_expired", "relief_ended_early", "ritual_optout", "window_started", "protection_started", "protection_expired", "protection_exit",
            "dns_consent_declined", "dns_consent_required", "dns_future_windows_disabled", "dns_refused_existing_vpn",
            "dns_refused_network_or_private_dns", "dns_refused_notifications", "dns_service_unavailable", "dns_settings_incomplete",
            "dns_settings_unavailable", "dns_snapshot_mismatch", "dns_trial_active_partial", "dns_trial_inactive",
            "dns_preflight_unavailable", "dns_refused_safety_scope"))
        if (oncePerSession && block?.sessionId == null) return@synchronized false
        if (reason.endsWith("attempt_denied") || reason == "system_app_denied") {
            val now = uptimeMillis ?: return@synchronized false
            if (lastDeniedAt != Long.MIN_VALUE && now >= lastDeniedAt && now - lastDeniedAt < 1_000) return@synchronized true
            lastDeniedAt = now
        }
        try {
            val old = read()
            val sid = block?.sessionId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            if (oncePerSession && sid == null) return@synchronized false
            if (oncePerSession && old.lineSequence().any { it.contains("\"sessionId\":\"$sid\"") && it.contains("\"reason\":\"$reason\"") }) return@synchronized true
            // Lifecycle milestones occur at most once per window. Repeated rest/return
            // transitions need distinct immutable IDs; the persisted event is retried unchanged.
            val stableId = windowId?.takeIf { reason in setOf("window_started", "protection_started", "protection_expired", "protection_exit", "bounded_relief_started", "bounded_relief_expired") && runCatching { UUID.fromString(it) }.isSuccess }
                ?.let { UUID.nameUUIDFromBytes("focusdeck:android:protection:$it:$reason".toByteArray(StandardCharsets.UTF_8)) }
            if (stableId != null && old.lineSequence().any { it.contains("\"eventId\":\"$stableId\"") }) return@synchronized true
            val line = auditEventLine(reason, block, stableId ?: UUID.randomUUID(), occurredAt, windowId)
            val bytes = (old + line).toByteArray(StandardCharsets.UTF_8)
            check(bytes.size <= 2 * 1024 * 1024)
            val parent = target.baseFile.parentFile
            check(parent != null && (parent.isDirectory || parent.mkdirs()))
            var out: java.io.FileOutputStream? = null
            try { out = target.startWrite(); out.write(bytes); target.finishWrite(out) }
            catch (error: Exception) { if (out != null) target.failWrite(out); throw error }
            _failed.value = false
            _revision.value++
            true
        } catch (_: Exception) {
            _failed.value = true
            false
        }
    }

    private fun read(): String = if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists())
        String(target.readFully(), StandardCharsets.UTF_8) else ""
}
