package dev.stanislav.focusdeck.data.life

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Meaningful transitions live here so they can be tested without Android or a real vault. */
object LifeRules {
    private val basis = listOf(LifeField.OPTIONS, LifeField.CRITERIA, LifeField.CHOICE, LifeField.PREDICTION, LifeField.COST)
    private val result = listOf(LifeField.OUTCOME, LifeField.LESSON)
    private val eventId = Regex("\\^life-event-([a-zA-Z0-9-]+)")

    /** Resolve an interrupted create only when the actual note already contains the same user intent. */
    fun sameDraftContent(draft: LifeEntry, saved: LifeEntry): Boolean =
        draft.id == saved.id && draft.kind == saved.kind && draft.title.trim() == saved.title &&
            draft.status == saved.status && draft.areaId == saved.areaId && draft.goalId == saved.goalId &&
            draft.projectId == saved.projectId && draft.dueDate == saved.dueDate && draft.reviewDate == saved.reviewDate &&
            draft.periodStart == saved.periodStart && draft.periodEnd == saved.periodEnd &&
            (draft.sourceRoot.isBlank() || draft.sourceRoot == saved.sourceRoot) &&
            fieldsFor(draft.kind).all { draft.text(it).trim() == saved.text(it).trim() }

    fun prepare(base: LifeEntry?, proposal: LifeEntry, now: LocalDateTime = LocalDateTime.now(), recordReview: Boolean = false, recordLifeReview: Boolean = false): LifeEntry {
        require(base == null || (base.id == proposal.id && base.kind == proposal.kind)) { "Нельзя менять тип или идентификатор существующей записи" }
        var entry = proposal.copy(title = proposal.title.replace('\n', ' ').replace('\r', ' ').trim())
        val today = now.toLocalDate()
        if (recordReview) {
            require(entry.kind == LifeKind.DECISION && base != null &&
                (base.decidedOn != null || base.status in listOf(LifeStatus.ACTIVE, LifeStatus.DONE))) { "Сначала прими решение, которое хочешь проверить" }
            require(meaningfulLifeText(entry.text(LifeField.OUTCOME))) { "Запиши фактический результат проверки" }
        }
        if (recordLifeReview) {
            require(base != null && entry.kind in listOf(LifeKind.AREA, LifeKind.GOAL, LifeKind.PROJECT)) { "Сначала сохрани запись, которую хочешь пересмотреть" }
            require(meaningfulLifeText(entry.text(LifeField.REVIEW_NOTE))) { "Запиши, что стало ясно после пересмотра" }
            require(entry.reviewDate == null || entry.reviewDate.isAfter(today)) { "Выбери будущую дату следующего пересмотра или убери напоминание" }
            val event = buildString {
                append("### ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} · Пересмотр\n\n")
                append("Состояние: ${base.statusLabel} → ${entry.statusLabel}.\n\n")
                listOf(LifeField.REVIEW_NOTE, LifeField.EVIDENCE, LifeField.NEXT_ACTION).forEach { field ->
                    if (entry.text(field).isNotBlank()) {
                        append("**${field.heading}**\n")
                        append(entry.text(field).lines().joinToString("\n") { "> $it" })
                        append("\n\n")
                    }
                }
                append("Следующее напоминание: ${entry.reviewDate ?: "не назначено"}.\n\n")
                append("^life-review-${UUID.randomUUID()}")
            }
            entry = entry.copy(reviewedOn = today, fields = entry.fields +
                (LifeField.REVIEW_HISTORY to (base.text(LifeField.REVIEW_HISTORY).trimEnd() + "\n\n" + event).trim()))
        }
        if (entry.kind == LifeKind.DECISION) {
            val firstAcceptance = entry.status == LifeStatus.ACTIVE && entry.decidedOn == null &&
                (base == null || base.status == LifeStatus.DRAFT)
            val knownDecision = base != null && (base.decidedOn != null || base.status == LifeStatus.ACTIVE || base.status == LifeStatus.DONE)
            val basisChanged = knownDecision && (basis.any { base!!.text(it) != entry.text(it) } || base!!.dueDate != entry.dueDate)
            val resultChanged = recordReview || result.any { base?.text(it).orEmpty() != entry.text(it) }
            var history = base?.text(LifeField.HISTORY).orEmpty().trimEnd()
            fun appendEvent(label: String, snapshot: LifeEntry, fields: List<LifeField>, note: String = "") {
                val previous = eventId.findAll(history).lastOrNull()?.groupValues?.get(1)
                val id = UUID.randomUUID().toString()
                val event = buildString {
                    append("### ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} · $label\n\n")
                    if (note.isNotBlank()) append("$note\n\n")
                    previous?.let { append("Предыдущая запись: [[${entry.relativePath.removeSuffix(".md")}#^life-event-$it]]\n\n") }
                    fields.forEach { field ->
                        if (snapshot.text(field).isNotBlank()) {
                            append("**${field.heading}**\n")
                            append(snapshot.text(field).lines().joinToString("\n") { "> $it" })
                            append("\n\n")
                        }
                    }
                    append("Срок результата: ${snapshot.dueDate ?: "не указан"}. Проверка: ${snapshot.reviewDate ?: "не назначена"}.\n\n")
                    append("^life-event-$id")
                }
                history = (history + "\n\n" + event).trim()
            }
            if (firstAcceptance) {
                val late = result.any { entry.text(it).isNotBlank() }
                appendEvent(if (late) "Записано после результата" else "Решение принято", entry, basis,
                    if (late) "Ожидание внесено вместе с результатом; это не прогноз, зафиксированный заранее." else "")
                entry = entry.copy(decidedOn = today)
            }
            if (knownDecision && history.isBlank() && (basisChanged || resultChanged)) {
                appendEvent("Состояние до правки", base!!, basis + result,
                    "Исходная история отсутствует. Это текущая запись из Obsidian, а не подтверждение того, что было известно при принятии решения.")
            }
            // The observation belongs to the previous expectation when both change in one save.
            if (resultChanged && result.any { entry.text(it).isNotBlank() } && (firstAcceptance || knownDecision)) {
                val expectation = if (basisChanged) base!! else entry
                appendEvent(if (recordReview || base?.text(LifeField.OUTCOME).isNullOrBlank()) "Проверка результата" else "Уточнение записи результата", expectation.copy(fields = expectation.fields + result.associateWith { entry.text(it) }), basis + result)
                entry = entry.copy(reviewedOn = if (recordReview || firstAcceptance) today else base?.reviewedOn)
            }
            if (basisChanged) {
                appendEvent("Решение пересмотрено", entry, basis,
                    "Новая версия выбора и ожиданий. Предыдущая версия остаётся в истории.")
                check(entry.status != LifeStatus.DONE) { "Для новых ожиданий сначала верни решение в работу" }
                // A new expectation has no observed result yet. The old observation stays in history.
                entry = entry.copy(fields = entry.fields - LifeField.OUTCOME - LifeField.LESSON, reviewedOn = null)
            }
            if (knownDecision && base!!.reviewDate != entry.reviewDate && !basisChanged && !resultChanged) {
                appendEvent("Дата проверки изменена", entry, emptyList(),
                    "Предыдущая дата: ${base.reviewDate ?: "не назначена"}. Перенос даты не означает состоявшийся обзор.")
            }
            if (history.isNotBlank()) entry = entry.copy(fields = entry.fields + (LifeField.HISTORY to history))
        }
        if (entry.status == LifeStatus.DONE && base?.status != LifeStatus.DONE) {
            when (entry.kind) {
                LifeKind.GOAL -> check(meaningfulLifeText(entry.text(LifeField.EVIDENCE))) { "Запиши факт, который подтверждает достижение цели" }
                LifeKind.DECISION -> check(meaningfulLifeText(entry.text(LifeField.OUTCOME))) { "Запиши, что произошло после решения" }
                LifeKind.REVIEW -> check(meaningfulLifeText(entry.text(LifeField.CHANGE))) { "Выбери, что изменишь или продолжишь на следующей неделе" }
                else -> Unit
            }
            if (entry.kind != LifeKind.DECISION || recordReview) entry = entry.copy(reviewedOn = today)
        }
        validate(entry)
        return entry
    }

    fun validate(entry: LifeEntry) {
        require(meaningfulLifeText(entry.title)) { "Добавь букву или цифру в название" }
        check(entry.problems.isEmpty()) { entry.problems.joinToString("; ") }
        if (entry.status == LifeStatus.ACTIVE) check(entry.activationProblems().isEmpty()) { entry.activationProblems().joinToString(". ") }
        if (entry.status == LifeStatus.DONE) when (entry.kind) {
            LifeKind.GOAL -> require(meaningfulLifeText(entry.text(LifeField.EVIDENCE))) { "У достигнутой цели должно остаться подтверждение результата" }
            LifeKind.DECISION -> require(meaningfulLifeText(entry.text(LifeField.OUTCOME))) { "У завершённого решения должен остаться результат проверки" }
            LifeKind.REVIEW -> require(meaningfulLifeText(entry.text(LifeField.CHANGE))) { "У завершённого обзора должен остаться вывод" }
            else -> Unit
        }
        entry.fields.forEach { (field, value) ->
            var fence: Char? = null
            var fenceLength = 0
            value.lineSequence().forEach { line ->
                val text = line.trimStart()
                val marker = text.firstOrNull()
                val length = text.takeWhile { it == marker }.length
                if ((marker == '`' || marker == '~') && length >= 3) {
                    if (fence == null) { fence = marker; fenceLength = length }
                    else if (fence == marker && length >= fenceLength) fence = null
                } else if (fence == null) {
                    require(!text.startsWith("# ") && !text.startsWith("## ")) {
                        "В поле «${field.heading}» используй ### для вложенного заголовка. Черновик сохранён целиком."
                    }
                }
            }
            require(fence == null) { "Закрой блок кода в поле «${field.heading}». Черновик сохранён целиком." }
        }
        require(entry.periodStart == null || entry.periodEnd == null || !entry.periodEnd.isBefore(entry.periodStart)) { "Конец периода раньше начала" }
    }

    fun merge(base: LifeEntry, edited: LifeEntry, fresh: LifeEntry, today: LocalDate): String {
        if (base.kind in listOf(LifeKind.GOAL, LifeKind.PROJECT)) {
            val localCriterionChanged = base.text(LifeField.SUCCESS) != edited.text(LifeField.SUCCESS)
            val externalCriterionChanged = base.text(LifeField.SUCCESS) != fresh.text(LifeField.SUCCESS)
            val localEvidenceChanged = base.text(LifeField.EVIDENCE) != edited.text(LifeField.EVIDENCE)
            val externalEvidenceChanged = base.text(LifeField.EVIDENCE) != fresh.text(LifeField.EVIDENCE)
            val completing = base.status != LifeStatus.DONE && edited.status == LifeStatus.DONE
            // Completion confirms the criterion the user actually saw. Likewise,
            // do not pair evidence with a criterion independently changed elsewhere.
            if (externalCriterionChanged && (completing || localEvidenceChanged)) {
                throw LifeConflictException(listOf("критерий результата — проверь результат заново"))
            }
            if (localCriterionChanged && externalEvidenceChanged) {
                throw LifeConflictException(listOf("подтверждение результата — сверь его с новым критерием"))
            }
        }
        if (base.text(LifeField.REVIEW_HISTORY) != edited.text(LifeField.REVIEW_HISTORY)) {
            val reviewedFields = fieldsFor(base.kind) + LifeField.REVIEW_HISTORY
            val externalReviewChange = reviewedFields.any { base.text(it) != fresh.text(it) } ||
                base.status != fresh.status || base.reviewDate != fresh.reviewDate || base.reviewedOn != fresh.reviewedOn
            if (externalReviewChange) throw LifeConflictException(listOf("запись, по которой выполнялся пересмотр"))
        }
        if (base.kind == LifeKind.DECISION) {
            val localSemanticChange = (basis + result + LifeField.HISTORY).any { base.text(it) != edited.text(it) } ||
                base.status != edited.status || base.dueDate != edited.dueDate || base.reviewDate != edited.reviewDate
            val externalSemanticChange = (basis + result + LifeField.HISTORY).any { base.text(it) != fresh.text(it) } ||
                base.status != fresh.status || base.dueDate != fresh.dueDate || base.reviewDate != fresh.reviewDate
            if (localSemanticChange && externalSemanticChange) throw LifeConflictException(listOf("основания или результат решения"))
        }
        val content = LifeNoteCodec.merge(base, edited, fresh, today)
        val merged = LifeNoteCodec.parse(fresh.path, content) ?: error("Не удалось проверить итоговую заметку")
        // A fence in an untouched external section may swallow newly appended managed sections.
        // Validate the intended changes after serializing, not only the fields the parser can still see.
        val lostChanges = LifeField.entries.filter { base.text(it) != edited.text(it) &&
            edited.text(it).trim() != merged.text(it).trim() }
        check(lostChanges.isEmpty()) { "Разметка заметки скрывает изменения: ${lostChanges.joinToString { it.heading }}. Закрой блоки кода в Obsidian; черновик сохранён." }
        validate(merged)
        return content
    }
}
