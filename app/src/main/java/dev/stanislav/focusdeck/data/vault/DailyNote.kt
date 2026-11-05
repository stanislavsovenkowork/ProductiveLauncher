package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate

/** Задача-чекбокс из секции `## Задачи` daily note. */
data class VaultTask(
    /** Номер строки в исходном тексте — по нему находим чекбокс перед сохранением файла. */
    val lineIndex: Int,
    val title: String,
    val done: Boolean,
    /** `⏳ 09:00` или `[time:: 09:00]` — если проставлено время начала. */
    val startMinuteOfDay: Int? = null,
    /** `[dur:: 50]` — оценка длительности. */
    val durationMinutes: Int? = null,
    val stableId: String? = null,
    val projectId: String? = null,
    val goalId: String? = null,
    val areaId: String? = null,
    val noteLink: String? = null,
) {
    /** Stable UI identity for new tasks; legacy notes remain compatible without migration. */
    val id: Long = stableId?.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
        ?.or(Long.MIN_VALUE) ?: lineIndex.toLong()

    val isScheduled: Boolean get() = startMinuteOfDay != null
}

/** Блок дневника `<details class="daily-journal">` с фиксированными полями. */
data class VaultJournal(
    val morning: String = "",
    val day: String = "",
    val evening: String = "",
    val conclusions: String = "",
) {
    val isEmpty: Boolean
        get() = morning.isBlank() && day.isBlank() && evening.isBlank() && conclusions.isBlank()
}

/** Разобранная daily note. */
data class DailyNote(
    val date: LocalDate,
    val path: String,
    val tasks: List<VaultTask>,
    val journal: VaultJournal,
    /** Исходные строки файла — нужны, чтобы писать точечно и не терять чужое содержимое. */
    val lines: List<String>,
) {
    val doneCount: Int get() = tasks.count { it.done }
}

/** Сводка, которую лаунчер дописывает в конце дня. */
data class DailySummary(
    val screenMinutes: Long,
    val focusMinutes: Int,
    val tasksDone: Int,
    val tasksTotal: Int,
    val interruptedBlocks: Int,
    val calories: Int,
    val calorieTarget: Int,
)
