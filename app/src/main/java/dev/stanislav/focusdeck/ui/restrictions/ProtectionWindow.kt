package dev.stanislav.focusdeck.ui.restrictions

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.stanislav.focusdeck.domainfilter.DomainFilterVpnService
import dev.stanislav.focusdeck.domainfilter.json
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val PROTECTION_MILLIS = 25 * 60_000L
internal const val MAX_PROTECTION_MILLIS = 30 * 60_000L
internal const val RELIEF_MILLIS = 5 * 60_000L
internal const val EXIT_COOLDOWN_MILLIS = 15_000L
internal const val STRICT_EXIT_DELAY_MILLIS = 60 * 60_000L
internal fun strictSchedulingAllowed(strict: Boolean, experimental: Boolean): Boolean = !strict || experimental

internal data class ProtectionClock(val wall: Long, val elapsed: Long, val boot: Int)
data class ProtectionWindow(
    val id: String, val category: String, val packages: Set<String>,
    val sessionId: String? = null, val taskRef: String? = null,
    val startWall: Long, val endWall: Long, val startElapsed: Long, val endElapsed: Long, val boot: Int,
    val reliefWall: Long? = null, val reliefElapsed: Long? = null, val reliefBoot: Int? = null,
    val reliefUsed: Boolean = false, val reliefEndedEarly: Boolean = false,
    val exitWall: Long? = null, val exitElapsed: Long? = null, val exitBoot: Int? = null,
    val domainPolicyJson: String? = null,
    val policyVersion: Int = 1, val resting: Boolean = false, val exitDelayMillis: Long = EXIT_COOLDOWN_MILLIS, val exitReanchored: Boolean = false, val exitProcessToken: String? = null,
    val strict: Boolean = false,
    val strictStarted: Boolean = false, val strictRecovery: Boolean = false, val recoveryProcessToken: String? = null,
)

private fun reliableElapsed(window: ProtectionWindow, now: ProtectionClock): Boolean =
    (now.boot >= 0 && window.boot == now.boot) ||
        (window.strict && window.strictRecovery && now.boot < 0 && window.recoveryProcessToken != null)

/** After boot uncertainty, preserve the original context and allow an explicit one-hour exit. */
internal fun recoverStrictWindow(window: ProtectionWindow, now: ProtectionClock, processToken: String): ProtectionWindow? {
    if (!window.strict || !window.strictStarted) return null
    val uncertain = if (now.boot >= 0) window.boot != now.boot else window.recoveryProcessToken != processToken
    if (!uncertain) return null
    val duration = (window.endWall - window.startWall).coerceIn(0, 24 * 60 * 60_000L)
    return window.copy(startElapsed = now.elapsed, endElapsed = now.elapsed + duration, boot = now.boot,
        strictRecovery = true, recoveryProcessToken = if (now.boot < 0) processToken else null)
}

internal fun remaining(window: ProtectionWindow, now: ProtectionClock): Long =
    if (window.policyVersion == 2) {
        val duration = (window.endWall - window.startWall).coerceIn(0, 24 * 60 * 60_000L)
        if (reliableElapsed(window, now) && now.elapsed >= 0)
            (window.endElapsed - now.elapsed).coerceIn(0, duration)
        else (window.endWall - now.wall).coerceIn(0, duration)
    } else if (now.boot >= 0 && window.boot == now.boot && now.elapsed >= window.startElapsed)
        (window.endElapsed - now.elapsed).coerceIn(0, MAX_PROTECTION_MILLIS)
    else if (now.wall < window.startWall) 0 else (window.endWall - now.wall).coerceIn(0, MAX_PROTECTION_MILLIS)

internal fun reliefRemaining(window: ProtectionWindow, now: ProtectionClock): Long {
    window.reliefWall ?: return 0
    val elapsed = window.reliefElapsed
    return if (window.policyVersion == 2) {
        if (now.boot >= 0 && window.reliefBoot == now.boot && elapsed != null && now.elapsed >= elapsed - RELIEF_MILLIS)
            (elapsed - now.elapsed).coerceIn(0, RELIEF_MILLIS)
        else (window.reliefWall - now.wall).coerceIn(0, RELIEF_MILLIS)
    } else if (now.boot >= 0 && window.reliefBoot == now.boot && elapsed != null && now.elapsed >= elapsed - RELIEF_MILLIS)
        (elapsed - now.elapsed).coerceIn(0, RELIEF_MILLIS)
    else 0 // Reboot or uncertain clock ends temporary relaxation conservatively.
}

internal fun exitRemaining(window: ProtectionWindow, now: ProtectionClock): Long {
    window.exitWall ?: return EXIT_COOLDOWN_MILLIS
    val elapsed = window.exitElapsed
    return if (window.policyVersion == 2) {
        if (((now.boot >= 0 && window.exitBoot == now.boot) || (now.boot < 0 && window.exitBoot == -2 && window.exitProcessToken != null)) && elapsed != null && now.elapsed >= elapsed - window.exitDelayMillis)
            (elapsed - now.elapsed).coerceIn(0, window.exitDelayMillis)
        else window.exitDelayMillis // Re-anchor on this boot before accepting confirmation.
    }
    else if (now.boot >= 0 && window.exitBoot == now.boot && elapsed != null && now.elapsed >= elapsed - EXIT_COOLDOWN_MILLIS)
        (elapsed - now.elapsed).coerceIn(0, EXIT_COOLDOWN_MILLIS)
    else 0 // Once exit was requested, uncertain clock must not trap the user.
}

internal fun exitClockUncertain(window: ProtectionWindow, now: ProtectionClock): Boolean {
    if (window.policyVersion != 2 || window.exitWall == null) return false
    val elapsed = window.exitElapsed ?: return true
    if (now.boot < 0 || window.exitBoot != now.boot || now.elapsed < elapsed - window.exitDelayMillis) return true
    val wallElapsed = now.wall - (window.exitWall - window.exitDelayMillis)
    val monotonicElapsed = now.elapsed - (elapsed - window.exitDelayMillis)
    return window.exitReanchored || kotlin.math.abs(wallElapsed - monotonicElapsed) > 5_000
}

internal fun grantRelief(window: ProtectionWindow, now: ProtectionClock): ProtectionWindow? {
    if (window.strict || window.reliefUsed || !windowActive(window, now)) return null
    if (window.policyVersion == 2) {
        val duration = minOf(RELIEF_MILLIS, remaining(window, now))
        return window.copy(reliefWall = now.wall + duration, reliefElapsed = now.elapsed + duration,
            reliefBoot = now.boot, reliefUsed = true)
    }
    return window.copy(
        endWall = minOf(window.endWall + RELIEF_MILLIS, window.startWall + MAX_PROTECTION_MILLIS),
        endElapsed = minOf(window.endElapsed + RELIEF_MILLIS, window.startElapsed + MAX_PROTECTION_MILLIS),
        reliefWall = now.wall + RELIEF_MILLIS, reliefElapsed = now.elapsed + RELIEF_MILLIS,
        reliefBoot = now.boot, reliefUsed = true,
    )
}

/** Rest consumes the same single extension, while selected apps remain restricted. */
internal fun grantRest(window: ProtectionWindow, now: ProtectionClock): ProtectionWindow? {
    if (!windowActive(window, now)) return null
    if (window.policyVersion == 2) return if (reliefRemaining(window, now) == 0L) window.copy(resting = true) else null
    if (window.reliefUsed) return null
    return window.copy(
        endWall = minOf(window.endWall + RELIEF_MILLIS, window.startWall + MAX_PROTECTION_MILLIS),
        endElapsed = minOf(window.endElapsed + RELIEF_MILLIS, window.startElapsed + MAX_PROTECTION_MILLIS),
        reliefUsed = true,
    )
}

internal fun windowActive(window: ProtectionWindow, now: ProtectionClock): Boolean =
    remaining(window, now) > 0 && (window.policyVersion != 2 ||
        if (reliableElapsed(window, now)) now.elapsed >= window.startElapsed else now.wall >= window.startWall)

internal fun scheduledWindowIsFuture(window: ProtectionWindow, now: ProtectionClock): Boolean =
    window.policyVersion == 2 && if (window.strictStarted) false else if (now.boot >= 0 && now.boot == window.boot)
        now.elapsed < window.startElapsed else now.wall < window.startWall

internal fun windowClockUncertain(window: ProtectionWindow, now: ProtectionClock): Boolean {
    if (window.policyVersion != 2) return false
    if (window.strictRecovery || now.boot < 0 || window.boot != now.boot) return true
    return kotlin.math.abs((now.wall - window.startWall) - (now.elapsed - window.startElapsed)) > 5_000
}

internal fun protectionRestricted(window: ProtectionWindow?, packageName: String, now: ProtectionClock): Boolean =
    window != null && windowActive(window, now) && packageName in window.packages && reliefRemaining(window, now) == 0L

/** Persisted independently of the work timer. Editing future selections never changes this snapshot. */
internal class ProtectionWindowStore(private val context: Context, private val clockSource: (() -> ProtectionClock)? = null,
                                     private val allowExperimentalStrict: Boolean = false) {
    private val processToken = UUID.randomUUID().toString()
    private val prefs = context.getSharedPreferences("protection-window", Context.MODE_PRIVATE)
    private val _failure = MutableStateFlow(false)
    val failure: StateFlow<Boolean> = _failure
    private val migrated = migrateSchedule()
    private val _window = MutableStateFlow(read())
    val window: StateFlow<ProtectionWindow?> = _window
    init { recoverStrictIfNeeded() }
    fun clock() = clockSource?.invoke() ?: ProtectionClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
    private fun recoverStrictIfNeeded(): Boolean {
        val old = _window.value ?: return true
        val recovered = recoverStrictWindow(old, clock(), processToken) ?: return true
        return save(recovered)
    }
    @Synchronized fun current(): ProtectionWindow? {
        if (!recoverStrictIfNeeded()) return null
        ensureScheduled()
        val now = clock()
        var old = _window.value
        if (old != null && old.strict && !old.strictStarted && windowActive(old, now)) {
            if (!save(old.copy(strictStarted = true, strictRecovery = now.boot < 0, recoveryProcessToken = if (now.boot < 0) processToken else null))) return null
            old = _window.value
        }
        if (old != null && old.policyVersion == 2 && old.exitWall != null && windowActive(old, now) &&
            ((if (now.boot >= 0) old.exitBoot != now.boot else old.exitBoot != -2 || old.exitProcessToken != processToken) ||
                old.exitElapsed == null || now.elapsed < old.exitElapsed - old.exitDelayMillis)) {
            if (!save(old.copy(exitWall = now.wall + old.exitDelayMillis, exitElapsed = now.elapsed + old.exitDelayMillis,
                exitBoot = if (now.boot >= 0) now.boot else -2, exitProcessToken = if (now.boot < 0) processToken else null,
                exitReanchored = true))) return null
        }
        return _window.value?.takeIf { windowActive(it, now) }
    }
    @Synchronized fun reserved(): ProtectionWindow? { if (!recoverStrictIfNeeded()) return null; ensureScheduled(); return _window.value?.takeIf { remaining(it, clock()) > 0 } }
    fun scheduleEnabled(): Boolean = prefs.getBoolean("schedule.enabled", false)
    @Synchronized fun disableSchedule(): Boolean {
        if (!recoverStrictIfNeeded()) return false
        if (!prefs.edit().putBoolean("schedule.enabled", false).commit()) { _failure.value = true; return false }
        val old = _window.value
        if (old != null && scheduledWindowIsFuture(old, clock())) return clear()
        return true
    }
    @Synchronized fun disableFutureDomainSnapshots(): Boolean {
        val e = prefs.edit().remove("schedule.domainTemplate")
        if (prefs.getStringSet("schedule.packages", emptySet()).orEmpty().isEmpty()) e.putBoolean("schedule.enabled", false)
        if (!e.commit()) { _failure.value = true; return false }
        return true
    }
    private fun ensureScheduled() {
        if (!migrated || _failure.value || !scheduleEnabled()) return
        val now = clock()
        if (_window.value?.let { remaining(it, now) > 0 } == true) return
        if (!strictSchedulingAllowed(prefs.getBoolean("schedule.strict", false), allowExperimentalStrict)) { _failure.value = true; return }
        val zone = java.time.ZoneId.of("Europe/Minsk")
        val today = java.time.Instant.ofEpochMilli(now.wall).atZone(zone).toLocalDate()
        val startMinute = prefs.getInt("schedule.startMinute", -1)
        val endMinute = prefs.getInt("schedule.endMinute", -1)
        val days = prefs.getStringSet("schedule.days", emptySet()).orEmpty()
        val category = prefs.getString("schedule.category", null) ?: return
        val packages = prefs.getStringSet("schedule.packages", emptySet()).orEmpty().toSet()
        val template = prefs.getString("schedule.domainTemplate", null)
        if (startMinute !in 900..1439 || endMinute <= startMinute || endMinute > 1439 || (packages.isEmpty() && template == null)) return
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            val lastDate = prefs.getString("schedule.lastDate", null)
            val explicitSameDay = prefs.getBoolean("schedule.explicitRearm", false) && date == today && (date.toString() == lastDate || date.toString() == prefs.getString("ledger.terminalDate", null))
            if (date.dayOfWeek.value.toString() !in days || (lastDate != null && date.toString() <= lastDate && !explicitSameDay)) continue
            val start = date.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
            if (start <= now.wall) {
                // No persisted instance for an already-started eligible day is ambiguous.
                // Do not claim protection or silently move it to tomorrow.
                if (now.wall < end && (lastDate == null || lastDate < date.toString())) _failure.value = true
                if (_failure.value) return
                continue
            }
            val id = UUID.randomUUID().toString()
            val domain = template?.let { runCatching { DomainFilterVpnService.parse(it).copy(sessionId = id).json() }.getOrNull() }
            if (template != null && domain == null) { _failure.value = true; return }
            val window = ProtectionWindow(id, category, packages, domainPolicyJson = domain,
                startWall = start, endWall = end, startElapsed = now.elapsed + start - now.wall,
                endElapsed = now.elapsed + end - now.wall, boot = now.boot, policyVersion = 2,
                reliefUsed = prefs.getString("ledger.reliefDate", null) == date.toString(),
                exitDelayMillis = if (prefs.getBoolean("schedule.strict", false)) STRICT_EXIT_DELAY_MILLIS else prefs.getLong("schedule.exitDelayMillis", EXIT_COOLDOWN_MILLIS).coerceIn(15_000, 300_000),
                strict = prefs.getBoolean("schedule.strict", false))
            // One atomic preferences commit contains both the instance and its budget date.
            save(window, date.toString())
            return
        }
    }

    @Synchronized fun begin(category: String, packages: Set<String>, sessionId: String?, taskRef: String?,
                            domainPolicyJson: String? = null): Boolean {
        require(category in setOf("work", "study"))
        require(domainPolicyJson == null || validDomainPolicy(domainPolicyJson, sessionId))
        require(packages.isNotEmpty() || domainPolicyJson != null)
        if (reserved() != null) return false
        val now = clock()
        return save(ProtectionWindow(UUID.randomUUID().toString(), category, packages.toSet(), sessionId, taskRef, now.wall,
            now.wall + PROTECTION_MILLIS, now.elapsed, now.elapsed + PROTECTION_MILLIS, now.boot,
            domainPolicyJson = domainPolicyJson))
    }
    /** Explicit opt-in recurring local schedule; the daily instance owns its budget. */
    @Synchronized fun schedule(category: String, packages: Set<String>, startWall: Long, endWall: Long, days: Set<Int>, exitDelayMillis: Long = EXIT_COOLDOWN_MILLIS, domainTemplate: String? = null, strict: Boolean = false): Boolean {
        val zone = java.time.ZoneId.of("Europe/Minsk")
        val start = java.time.Instant.ofEpochMilli(startWall).atZone(zone)
        val end = java.time.Instant.ofEpochMilli(endWall).atZone(zone)
        if (category !in setOf("work", "study") || (packages.isEmpty() && domainTemplate == null) || !strictSchedulingAllowed(strict, allowExperimentalStrict) || (strict && (packages.isEmpty() || domainTemplate != null)) || reserved() != null ||
            (domainTemplate != null && runCatching { DomainFilterVpnService.parse(domainTemplate) }.isFailure) ||
            start.toLocalTime().isBefore(java.time.LocalTime.of(15, 0)) || start.toLocalDate() != end.toLocalDate() ||
            endWall <= startWall || days.isEmpty() || days.any { it !in 1..7 } || (!strict && exitDelayMillis !in 15_000..300_000)) return false
        if (!migrated || _failure.value) return false
        val now = clock()
        val today = java.time.Instant.ofEpochMilli(now.wall).atZone(zone).toLocalDate()
        val startMinute = start.hour * 60 + start.minute
        val endMinute = end.hour * 60 + end.minute
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value !in days) continue
            val plannedStart = date.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
            val plannedEnd = date.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
            if (plannedEnd <= now.wall) continue
            // Explicit opt-in during a window starts now; past time is never attributed.
            val actualStart = maxOf(plannedStart, now.wall)
            val id = UUID.randomUUID().toString()
            val domain = domainTemplate?.let { DomainFilterVpnService.parse(it).copy(sessionId = id).json() }
            val window = ProtectionWindow(id, category, packages.toSet(), domainPolicyJson = domain,
                startWall = actualStart, endWall = plannedEnd, startElapsed = now.elapsed + actualStart - now.wall,
                endElapsed = now.elapsed + plannedEnd - now.wall, boot = now.boot, policyVersion = 2,
                reliefUsed = prefs.getString("ledger.reliefDate", null) == date.toString(), exitDelayMillis = if (strict) STRICT_EXIT_DELAY_MILLIS else exitDelayMillis, strict = strict,
                strictStarted = strict && actualStart == now.wall, strictRecovery = strict && actualStart == now.wall && now.boot < 0,
                recoveryProcessToken = if (strict && actualStart == now.wall && now.boot < 0) processToken else null)
            return save(window, date.toString()) { e ->
                e.putBoolean("schedule.enabled", true).putString("schedule.domainTemplate", domainTemplate).putString("schedule.category", category)
                    .putStringSet("schedule.packages", packages.toSet()).putStringSet("schedule.days", days.map(Int::toString).toSet())
                    .putLong("schedule.exitDelayMillis", if (strict) STRICT_EXIT_DELAY_MILLIS else exitDelayMillis).putBoolean("schedule.strict", strict).putInt("schedule.startMinute", startMinute)
                    .putInt("schedule.endMinute", endMinute)
            }
        }
        return false
    }

    @Synchronized fun beginRelief(): Boolean {
        val old = current() ?: return false
        if (old.reliefUsed) return false
        val next = grantRelief(old, clock()) ?: return false
        val reliefDate = if (old.policyVersion == 2) java.time.Instant.ofEpochMilli(old.startWall)
            .atZone(java.time.ZoneId.of("Europe/Minsk")).toLocalDate().toString() else null
        return save(next, reliefDate = reliefDate)
    }
    @Synchronized fun beginRest(): Boolean {
        val old = current() ?: return false
        return save(grantRest(old, clock()) ?: return false)
    }
    @Synchronized fun endRelief(): Boolean {
        val old = current() ?: return false
        val now = clock()
        if (old.resting) return save(old.copy(resting = false))
        if (reliefRemaining(old, now) == 0L) return true
        return save(old.copy(reliefWall = now.wall, reliefElapsed = now.elapsed,
            reliefBoot = now.boot, reliefEndedEarly = true))
    }
    @Synchronized fun requestExit(): Boolean {
        val old = current() ?: return false
        if (old.exitWall != null) return true
        val now = clock()
        val delay = old.exitDelayMillis
        return save(old.copy(exitWall = now.wall + delay, exitElapsed = now.elapsed + delay, exitBoot = if (now.boot >= 0) now.boot else -2, exitProcessToken = if (now.boot < 0) processToken else null))
    }
    @Synchronized fun cancelExit(): Boolean {
        val old = current() ?: return false
        return save(old.copy(exitWall = null, exitElapsed = null, exitBoot = null, exitReanchored = false, exitProcessToken = null))
    }
    @Synchronized fun exit(): Boolean {
        val old = current() ?: return false
        if (old.exitWall == null || exitRemaining(old, clock()) > 0) return false
        val terminalDate = if (old.policyVersion == 2) java.time.Instant.ofEpochMilli(old.startWall)
            .atZone(java.time.ZoneId.of("Europe/Minsk")).toLocalDate().toString() else null
        return clear(terminalDate)
    }
    @Synchronized fun rollbackStart(id: String): Boolean {
        if (_window.value?.id != id) return false
        return clear()
    }
    private val windowKeys = listOf("id", "category", "packages", "sessionId", "taskRef", "startWall", "endWall",
        "startElapsed", "endElapsed", "boot", "reliefWall", "reliefElapsed", "reliefBoot", "reliefUsed",
        "reliefEndedEarly", "exitWall", "exitElapsed", "exitBoot", "domainPolicyJson", "policyVersion", "resting", "exitDelayMillis", "exitReanchored", "exitProcessToken", "strict", "strictStarted", "strictRecovery", "recoveryProcessToken")
    private fun clear(terminalDate: String? = null): Boolean {
        val e = prefs.edit(); windowKeys.forEach(e::remove)
        terminalDate?.let { e.putString("ledger.terminalDate", it) }
        if (!e.commit()) { _failure.value = true; return false }
        _window.value = null
        return true
    }
    private fun save(w: ProtectionWindow, lastDate: String? = null, reliefDate: String? = null, configure: ((android.content.SharedPreferences.Editor) -> Unit)? = null): Boolean {
        val e = prefs.edit(); windowKeys.forEach(e::remove)
        e.putString("id", w.id).putString("category", w.category)
            .putStringSet("packages", w.packages).putString("sessionId", w.sessionId).putString("taskRef", w.taskRef).putLong("startWall", w.startWall).putLong("endWall", w.endWall)
            .putLong("startElapsed", w.startElapsed).putLong("endElapsed", w.endElapsed).putInt("boot", w.boot)
            .putString("exitProcessToken", w.exitProcessToken).putBoolean("exitReanchored", w.exitReanchored).putLong("exitDelayMillis", w.exitDelayMillis).putBoolean("resting", w.resting).putInt("policyVersion", w.policyVersion).putBoolean("reliefUsed", w.reliefUsed).putBoolean("reliefEndedEarly", w.reliefEndedEarly)
            .putString("domainPolicyJson", w.domainPolicyJson).putBoolean("strict", w.strict)
            .putBoolean("strictStarted", w.strictStarted).putBoolean("strictRecovery", w.strictRecovery).putString("recoveryProcessToken", w.recoveryProcessToken)
        w.reliefWall?.let { e.putLong("reliefWall", it) }; w.reliefElapsed?.let { e.putLong("reliefElapsed", it) }
        w.reliefBoot?.let { e.putInt("reliefBoot", it) }; w.exitWall?.let { e.putLong("exitWall", it) }
        w.exitElapsed?.let { e.putLong("exitElapsed", it) }; w.exitBoot?.let { e.putInt("exitBoot", it) }
        configure?.invoke(e)
        lastDate?.let { e.putString("schedule.lastDate", it); e.remove("schedule.explicitRearm") }
        reliefDate?.let { e.putString("ledger.reliefDate", it) }
        if (!e.commit()) { _failure.value = true; return false }
        _window.value = w
        return true
    }
    private fun read(): ProtectionWindow? {
        val id = prefs.getString("id", null) ?: return null
        val category = prefs.getString("category", null)?.takeIf { it in setOf("work", "study") } ?: return null
        val packages = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
        val sessionId = prefs.getString("sessionId", null)
        val version = prefs.getInt("policyVersion", 1)
        val storedDomain = prefs.getString("domainPolicyJson", null)
        val domain = storedDomain?.takeIf { validDomainPolicy(it, if (version == 2) id else sessionId) }
        if (storedDomain != null && domain == null) _failure.value = true
        if (packages.isEmpty() && domain == null) return null
        fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
        fun int(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, -1) else null
        return ProtectionWindow(id, category, packages, sessionId, prefs.getString("taskRef", null), prefs.getLong("startWall", 0), prefs.getLong("endWall", 0),
            prefs.getLong("startElapsed", 0), prefs.getLong("endElapsed", 0), prefs.getInt("boot", -1),
            long("reliefWall"), long("reliefElapsed"), int("reliefBoot"), prefs.getBoolean("reliefUsed", false),
            prefs.getBoolean("reliefEndedEarly", false), long("exitWall"), long("exitElapsed"), int("exitBoot"), domain, prefs.getInt("policyVersion", 1), prefs.getBoolean("resting", false), if (prefs.getBoolean("strict", false)) STRICT_EXIT_DELAY_MILLIS else prefs.getLong("exitDelayMillis", EXIT_COOLDOWN_MILLIS).coerceIn(15_000, 300_000), prefs.getBoolean("exitReanchored", false), prefs.getString("exitProcessToken", null), prefs.getBoolean("strict", false), prefs.getBoolean("strictStarted", false), prefs.getBoolean("strictRecovery", false), prefs.getString("recoveryProcessToken", null))
    }

    private fun migrateSchedule(): Boolean {
        if (prefs.getBoolean("schedule.migrated", false)) return true
        val old = context.getSharedPreferences("protection-schedule-v2", Context.MODE_PRIVATE)
        val e = prefs.edit().putBoolean("schedule.migrated", true)
        // Carry a previously reserved v2 grant into the daily ledger during migration.
        if (prefs.getInt("policyVersion", 1) == 2 && prefs.getBoolean("reliefUsed", false) && prefs.contains("startWall")) {
            val date = java.time.Instant.ofEpochMilli(prefs.getLong("startWall", 0))
                .atZone(java.time.ZoneId.of("Europe/Minsk")).toLocalDate().toString()
            e.putString("ledger.reliefDate", date)
        }
        for ((key, value) in old.all) when (value) {
            is Boolean -> e.putBoolean("schedule.$key", value)
            is Int -> e.putInt("schedule.$key", value)
            is Long -> e.putLong("schedule.$key", value)
            is String -> e.putString("schedule.$key", value)
            is Set<*> -> e.putStringSet("schedule.$key", value.filterIsInstance<String>().toSet())
        }
        if (!e.commit()) { _failure.value = true; return false }
        return true
    }

    private fun validDomainPolicy(json: String, sessionId: String?): Boolean =
        sessionId != null && runCatching { DomainFilterVpnService.parse(json).sessionId == sessionId }.getOrDefault(false)
}
