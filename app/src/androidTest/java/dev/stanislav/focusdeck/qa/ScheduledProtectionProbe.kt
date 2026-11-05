package dev.stanislav.focusdeck.qa

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindowStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import dev.stanislav.focusdeck.data.repo.SessionAudit
import dev.stanislav.focusdeck.data.sync.TelemetrySnapshotCodec
import java.io.File

/** Isolated preferences; no Activity, real schedule, personal data, or app targets. */
object ScheduledProtectionProbe {
    fun run(context: Context): Bundle {
        val prefix = "qa-v2-${UUID.randomUUID()}-"
        val root = File(context.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        try {
            val zone = ZoneId.of("Europe/Minsk")
            val date = LocalDate.now(zone)
            val start = date.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
            val first = ProtectionWindowStore(isolated)
            check(first.schedule("study", setOf("qa.fictional"), start, end, (1..7).toSet()))
            val saved = checkNotNull(first.reserved())
            val restored = checkNotNull(ProtectionWindowStore(isolated).reserved())
            check(saved.id == restored.id && saved.endWall == restored.endWall && restored.policyVersion == 2)
            check(!restored.reliefUsed && restored.packages == setOf("qa.fictional"))
            val audit = SessionAudit(isolated)
            check(audit.record("bounded_relief_started", windowId = saved.id))
            check(audit.record("rest_without_feeds", windowId = saved.id))
            check(audit.record("rest_without_feeds", windowId = saved.id))
            val projected = TelemetrySnapshotCodec.entries(audit.snapshot(), strict = true)
            check(projected.size == 3 && projected.map { it.eventId }.toSet().size == 3)
            check(first.disableSchedule())
            check(!first.scheduleEnabled() && first.reserved() == null)
            return Bundle().apply { putString("status", "ok"); putString("snapshot", "recurring_isolated_restore_cancel"); putString("events", projected.joinToString("\n") { it.json }) }
        } finally {
            root.deleteRecursively()
            context.deleteSharedPreferences(prefix + "protection-window")
            context.deleteSharedPreferences(prefix + "protection-schedule-v2")
        }
    }
}
