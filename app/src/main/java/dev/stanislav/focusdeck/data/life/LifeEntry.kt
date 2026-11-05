package dev.stanislav.focusdeck.data.life

import java.time.LocalDate
import java.util.UUID

enum class LifeKind(val folder: String, val label: String, val singular: String, val hint: String) {
    AREA("Области", "Области", "Область жизни", "То, что важно поддерживать постоянно"),
    GOAL("Цели", "Цели", "Цель", "Результат, который ты выбираешь получить"),
    PROJECT("Проекты", "Проекты", "Проект", "Конечная работа с понятным результатом"),
    DECISION("Решения", "Решения", "Решение", "Выбор, основания и проверка результата"),
    REVIEW("Обзоры", "Обзоры", "Обзор", "Что произошло и что стоит изменить"),
}

enum class LifeStatus(val label: String) {
    DRAFT("Обдумываю"), ACTIVE("В работе"), PAUSED("На паузе"), DONE("Завершено"), ARCHIVED("В архиве"),
}

/** Body fields are readable headings in Obsidian, not opaque application state. */
enum class LifeField(val heading: String, val hint: String) {
    WHY("Почему это важно", "Это мой выбор? Что он изменит в моей жизни?"),
    SUCCESS("Как пойму, что готово", "Наблюдаемый результат или критерий завершения"),
    STANDARD("Что значит достаточно", "Как выглядит здоровое состояние этой области?"),
    NEXT_ACTION("Следующее действие", "Один конкретный шаг, который можно начать"),
    METRIC("Показатель", "Число, по которому видно движение: цель, старт и замеры по датам"),
    MILESTONES("Этапы", "Вехи на пути к результату — отмечаются галочкой и здесь, и в Obsidian"),
    OBSTACLE("Что может помешать", "Главная внутренняя или внешняя помеха"),
    IF_THEN("Если — то", "Если возникнет помеха, то я…"),
    EVIDENCE("Признаки прогресса", "Факты и изменения, которые уже заметны"),
    COST("Цена выбора", "От чего откажусь: время, силы, другие обязательства"),
    OPTIONS("Варианты", "Какие есть варианты, включая пока ничего не менять?"),
    CRITERIA("Критерии выбора", "Что важнее всего и чем готов пожертвовать?"),
    CHOICE("Мой выбор и основания", "Что выбираю и почему при известных сейчас фактах?"),
    PREDICTION("Ожидаемый результат", "Что, по моему прогнозу, произойдёт и к какому сроку?"),
    OUTCOME("Что произошло", "Фактический результат — отдельно от прежних ожиданий"),
    LESSON("Что я понял", "Какой вывод перенесу в следующий выбор?"),
    WINS("Что получилось", "Что сработало? На какие факты опираюсь?"),
    FRICTION("Что мешало", "Где план расходился с реальностью?"),
    CHANGE("Что изменю", "Один эксперимент на следующую неделю"),
    COMMITMENT("Главное на неделю", "Что действительно заслуживает времени сейчас?"),
    REVIEW_NOTE("Что стало ясно", "Что изменилось? Продолжаю, меняю подход или откладываю?"),
    REVIEW_HISTORY("История пересмотров", ""),
    LINKS("Связи FocusDeck", ""),
    HISTORY("История решений", ""),
}

fun meaningfulLifeText(value: String): Boolean = value.any { it.isLetterOrDigit() }

fun fieldsFor(kind: LifeKind): List<LifeField> = when (kind) {
    LifeKind.AREA -> listOf(LifeField.STANDARD, LifeField.WHY, LifeField.EVIDENCE, LifeField.REVIEW_NOTE)
    LifeKind.GOAL -> listOf(LifeField.WHY, LifeField.SUCCESS, LifeField.METRIC, LifeField.MILESTONES, LifeField.COST, LifeField.OBSTACLE, LifeField.IF_THEN, LifeField.EVIDENCE, LifeField.REVIEW_NOTE)
    LifeKind.PROJECT -> listOf(LifeField.SUCCESS, LifeField.NEXT_ACTION, LifeField.MILESTONES, LifeField.OBSTACLE, LifeField.IF_THEN, LifeField.EVIDENCE, LifeField.REVIEW_NOTE)
    LifeKind.DECISION -> listOf(LifeField.OPTIONS, LifeField.CRITERIA, LifeField.CHOICE, LifeField.PREDICTION, LifeField.COST, LifeField.OUTCOME, LifeField.LESSON)
    LifeKind.REVIEW -> listOf(LifeField.WINS, LifeField.FRICTION, LifeField.CHANGE, LifeField.COMMITMENT)
}

data class LifeEntry(
    val id: String,
    val kind: LifeKind,
    val title: String,
    val status: LifeStatus = LifeStatus.DRAFT,
    val areaId: String? = null,
    val goalId: String? = null,
    val projectId: String? = null,
    val dueDate: LocalDate? = null,
    val reviewDate: LocalDate? = null,
    val decidedOn: LocalDate? = null,
    val reviewedOn: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val created: LocalDate = LocalDate.now(),
    val updated: LocalDate = LocalDate.now(),
    val fields: Map<LifeField, String> = emptyMap(),
    val path: String = "",
    val raw: String = "",
    /** Local provenance only; never written into an Obsidian note. */
    val sourceRoot: String = "",
    val problems: List<String> = emptyList(),
) {
    fun text(field: LifeField): String = fields[field].orEmpty()
    val statusLabel: String get() = when {
        status == LifeStatus.ACTIVE && kind == LifeKind.DECISION -> "Принято"
        status == LifeStatus.ACTIVE && kind == LifeKind.AREA -> "Поддерживаю"
        status == LifeStatus.DONE && kind == LifeKind.GOAL -> "Достигнута"
        status == LifeStatus.DRAFT && kind == LifeKind.REVIEW -> "Черновик"
        else -> status.label
    }
    val fileName: String get() = title.replace(Regex("""[\\/:*?"<>|#^\r\n]"""), " ")
        .replace("[", "").replace("]", "").trim().take(60).ifBlank { kind.singular } + " -- $id.md"
    val inUse: Boolean get() = status == LifeStatus.ACTIVE
    val archived: Boolean get() = status == LifeStatus.ARCHIVED
    val needsReview: Boolean get() = status == LifeStatus.ACTIVE &&
        reviewDate?.let { !it.isAfter(LocalDate.now()) } == true
    val relativePath: String get() = path.ifBlank { "FocusDeck/${kind.folder}/$fileName" }
    val wikiLink: String get() = "[[${relativePath.removeSuffix(".md")}|${title.replace("|", " ").replace("[", "").replace("]", "")}]]"

    fun activationProblems(): List<String> = buildList {
        if (!meaningfulLifeText(title)) add("Добавь букву или цифру в название")
        when (kind) {
            LifeKind.GOAL -> {
                if (!meaningfulLifeText(text(LifeField.WHY))) add("Запиши, почему цель важна тебе")
                if (!meaningfulLifeText(text(LifeField.SUCCESS))) add("Определи критерий результата")
            }
            LifeKind.PROJECT -> {
                if (!meaningfulLifeText(text(LifeField.SUCCESS))) add("Определи, когда проект завершён")
                if (!meaningfulLifeText(text(LifeField.NEXT_ACTION))) add("Выбери следующее действие")
            }
            LifeKind.DECISION -> {
                if (!meaningfulLifeText(text(LifeField.OPTIONS))) add("Рассмотри варианты")
                if (!meaningfulLifeText(text(LifeField.CHOICE))) add("Запиши выбор и основания")
                if (!meaningfulLifeText(text(LifeField.PREDICTION))) add("Зафиксируй ожидания до результата")
                if (reviewDate == null) add("Выбери дату проверки решения")
            }
            else -> Unit
        }
    }

    companion object {
        fun draft(kind: LifeKind, title: String = "", today: LocalDate = LocalDate.now()) = LifeEntry(
            id = UUID.randomUUID().toString(), kind = kind, title = title, created = today, updated = today,
            reviewDate = if (kind == LifeKind.DECISION) today.plusDays(7) else null,
        )
    }
}

class LifeConflictException(val fields: List<String>) : IllegalStateException(
    "Заметка изменена в Obsidian: ${fields.joinToString()}. Обнови её перед сохранением.",
)
