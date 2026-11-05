package dev.stanislav.focusdeck.qa

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import dev.stanislav.focusdeck.ui.restrictions.ProtectionClock
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindowStore
import dev.stanislav.focusdeck.ui.restrictions.exitRemaining
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Actual isolated SharedPreferences, synthetic time, and a failed instance commit. */
object ProtectionLedgerProbe {
    fun run(context: Context): Bundle {
        val prefix = "qa-ledger-${UUID.randomUUID()}-"
        var failInstanceCommit = false
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = super.getSharedPreferences(prefix + name, mode)
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        var instance = false
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                if (key == "schedule.lastDate") instance = true
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit(): Boolean {
                                if (instance && failInstanceCommit) { failInstanceCommit = false; return false }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        try {
            val zone = ZoneId.of("Europe/Minsk")
            val date = LocalDate.of(2026, 10, 28)
            fun at(hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            var clock = ProtectionClock(at(15, 0), 100_000, 7)
            val source = { clock }
            val first = ProtectionWindowStore(isolated, source)
            failInstanceCommit = true
            check(!first.schedule("study", setOf("qa.fictional"), at(15, 10), at(16, 0), (1..7).toSet()))
            check(first.failure.value)
            val raw = isolated.getSharedPreferences("protection-window", Context.MODE_PRIVATE)
            check(!raw.contains("schedule.lastDate") && !raw.contains("id"))
            val restarted = ProtectionWindowStore(isolated, source)
            check(!restarted.scheduleEnabled() && restarted.reserved() == null)
            check(restarted.schedule("study", setOf("qa.fictional"), at(15, 10), at(16, 0), (1..7).toSet()))
            val scheduled = checkNotNull(restarted.reserved())
            check(raw.getString("schedule.lastDate", null) == date.toString() && raw.getString("id", null) == scheduled.id)
            clock = clock.copy(wall = at(15, 10), elapsed = 700_000)
            check(restarted.beginRelief())
            check(restarted.requestExit())
            check(!restarted.exit())
            clock = clock.copy(wall = at(15, 10) + 5_000, elapsed = 705_000)
            val activeAfterRestart = ProtectionWindowStore(isolated, source)
            val restoredActive = checkNotNull(activeAfterRestart.current())
            check(restoredActive.id == scheduled.id && restoredActive.category == "study")
            check(restoredActive.packages == setOf("qa.fictional") && restoredActive.reliefUsed)
            check(restoredActive.endWall == scheduled.endWall && exitRemaining(restoredActive, clock) == 10_000L)
            check(!activeAfterRestart.schedule("work", setOf("qa.other"), at(15, 20), at(16, 0), (1..7).toSet()))
            check(activeAfterRestart.disableSchedule())
            val stillActive = checkNotNull(ProtectionWindowStore(isolated, source).current())
            check(stillActive.id == scheduled.id && stillActive.reliefUsed && exitRemaining(stillActive, clock) == 10_000L)
            clock = clock.copy(wall = at(15, 10) + 15_000, elapsed = 715_000)
            check(activeAfterRestart.exit())
            check(activeAfterRestart.disableSchedule())
            check(activeAfterRestart.schedule("study", setOf("qa.fictional"), at(15, 20), at(16, 0), (1..7).toSet()))
            val rearmed = checkNotNull(ProtectionWindowStore(isolated, source).reserved())
            check(rearmed.id != scheduled.id && rearmed.reliefUsed)
            check(raw.getString("ledger.reliefDate", null) == date.toString())
            clock = clock.copy(wall = at(15, 20), elapsed = 1_300_000, boot = -1)
            val unknownBoot = ProtectionWindowStore(isolated, source)
            check(unknownBoot.requestExit())
            clock = clock.copy(wall = clock.wall + 5_000, elapsed = clock.elapsed + 5_000)
            check(!unknownBoot.exit())
            val relaunched = ProtectionWindowStore(isolated, source)
            check(!relaunched.exit()) // Must restart the complete delay after process/store loss.
            clock = clock.copy(wall = clock.wall + 14_999, elapsed = clock.elapsed + 14_999)
            check(!relaunched.exit())
            clock = clock.copy(wall = clock.wall + 1, elapsed = clock.elapsed + 1)
            check(relaunched.exit())
            check(relaunched.disableSchedule())
            val optedAt = clock.wall
            check(relaunched.schedule("study", setOf("qa.fictional"), at(15, 10), at(16, 0), (1..7).toSet()))
            val immediate = checkNotNull(relaunched.current())
            check(immediate.startWall == optedAt && immediate.endWall == at(16, 0) && immediate.reliefUsed)
            check(ProtectionWindowStore(isolated, source).current()?.id == immediate.id)
            clock = clock.copy(wall = at(16, 0), elapsed = clock.elapsed + at(16, 0) - clock.wall, boot = 7)
            check(relaunched.disableSchedule())
            check(!relaunched.schedule("study", emptySet(), at(15, 0), at(19, 0), (1..7).toSet(), strict = true))
            check(!relaunched.schedule("study", setOf("qa.fictional"), at(15, 0), at(19, 0), (1..7).toSet(), strict = true))
            val experimental = ProtectionWindowStore(isolated, source, allowExperimentalStrict = true)
            check(experimental.schedule("study", setOf("qa.fictional"), at(15, 0), at(19, 0), (1..7).toSet(), strict = true))
            val strict = checkNotNull(ProtectionWindowStore(isolated, source).current())
            check(strict.strict && strict.exitDelayMillis == 3_600_000L)
            val strictStore = ProtectionWindowStore(isolated, source)
            check(!strictStore.beginRelief() && strictStore.beginRest())
            clock = clock.copy(wall = clock.wall - 60_000, elapsed = clock.elapsed + 1_000)
            check(strictStore.disableSchedule())
            check(ProtectionWindowStore(isolated, source).current()?.id == strict.id)
            check(strictStore.requestExit() && !strictStore.exit())
            clock = clock.copy(wall = at(19, 0) + 86_400_000, elapsed = 2_000, boot = 8)
            val recovered = ProtectionWindowStore(isolated, source)
            val recoveredWindow = checkNotNull(recovered.current())
            check(recoveredWindow.id == strict.id && recoveredWindow.strictRecovery && recoveredWindow.strictStarted)
            check(recoveredWindow.endWall == strict.endWall && recoveredWindow.exitDelayMillis == 3_600_000L)
            check(!recovered.exit())
            clock = clock.copy(wall = clock.wall + 3_599_000, elapsed = clock.elapsed + 3_599_000)
            check(!ProtectionWindowStore(isolated, source).exit())
            clock = clock.copy(wall = clock.wall + 1_000, elapsed = clock.elapsed + 1_000)
            check(ProtectionWindowStore(isolated, source).exit())
            return Bundle().apply { putString("status", "ok"); putString("snapshot", "atomic_recovery_same_day_budget") }
        } finally {
            context.deleteSharedPreferences(prefix + "protection-window")
            context.deleteSharedPreferences(prefix + "protection-schedule-v2")
        }
    }
}
