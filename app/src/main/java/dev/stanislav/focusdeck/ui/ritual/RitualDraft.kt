package dev.stanislav.focusdeck.ui.ritual

import java.time.LocalDate

data class RitualDraft(val window: RitualWindow, val field: String, val text: String, val base: String?, val sourceRoot: String? = null, private val storedKey: String? = null) {
    val day get() = if (this.field == "journal") window.journalDate else window.planDate
    val key get() = storedKey ?: key(window, this.field, sourceRoot)
    val normalized get() = normalize(this.field, text)
    /** Unknown legacy baselines are never silently written over a nonempty external field. */
    fun canApplyTo(current: String, explicitLegacySave: Boolean = false): Boolean {
        val clean = normalize(field, current)
        return clean == normalized || clean == base?.let { normalize(field, it) } ||
            (base == null && (clean.isEmpty() || explicitLegacySave))
    }
    companion object {
        fun key(window: RitualWindow, field: String, sourceRoot: String? = null): String {
            val suffix = sourceRoot?.let { root -> java.security.MessageDigest.getInstance("SHA-256")
                .digest(root.toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) } }
            return "draft_${window.phase}_${window.journalDate}_$field" + (suffix?.let { "_vault_$it" } ?: "")
        }
        fun normalize(field: String, text: String) = if (field == "ifthen")
            text.replace('\n', ' ').replace('\r', ' ').trim() else text.trim()
        fun parse(key: String, text: String, base: String?, sourceRoot: String? = null): RitualDraft? {
            val match = Regex("draft_(MORNING|EVENING)_(\\d{4}-\\d{2}-\\d{2})_(journal|ifthen)(?:_vault_[0-9a-f]{16})?").matchEntire(key) ?: return null
            return runCatching {
                val date = LocalDate.parse(match.groupValues[2])
                val phase = RitualPhase.valueOf(match.groupValues[1])
                RitualDraft(RitualWindow(phase, date, if (phase == RitualPhase.EVENING) date.plusDays(1) else date), match.groupValues[3], text, base, sourceRoot, key)
            }.getOrNull()
        }
    }
}
