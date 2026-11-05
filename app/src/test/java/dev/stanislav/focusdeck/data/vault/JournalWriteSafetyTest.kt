package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalWriteSafetyTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val open = "<details class=\"daily-journal\">"
    private val close = "</details>"
    private val wrapper = """
        <details class="daily-journal">
        <summary>Дневник</summary>

        - Утро: Начало
        - День: Работа
        - Вечер: Отдых
        - Выводы: Итог

        </details>
    """.trimIndent()

    private fun parse(content: String) = DailyNoteParser.parse(date, "safety.md", content)

    private inline fun <reified T : Throwable> rejected(block: () -> Unit) {
        try {
            block()
            fail("An unsafe journal write must fail rather than return content")
        } catch (error: Throwable) {
            if (error !is T) throw error
            assertFalse("The failure must explain why the write was refused", error.message.isNullOrBlank())
        }
    }

    @Test fun missingClosingTagFailsInsteadOfAcknowledgingUnchangedContent() {
        val source = "# Existing note\n" + wrapper.removeSuffix(close) + "\n## Other section\nKeep this"
        val note = parse(source)
        val originalLines = note.lines.toList()

        rejected<IllegalStateException> {
            DailyNoteParser.writeJournal(note, VaultJournal(evening = "New reflection"))
        }

        assertEquals(originalLines, note.lines)
        assertEquals(source, note.lines.joinToString("\n"))
    }

    @Test fun duplicateWrappersFailEvenWhenTheirFieldsAgree() {
        val note = parse("$wrapper\n\n$wrapper")
        rejected<IllegalStateException> { DailyNoteParser.writeJournal(note, note.journal) }
    }

    @Test fun nestedDetailsOrExtraClosingTagCannotBeMistakenForJournalBoundary() {
        listOf(
            wrapper.replace("- День: Работа", "<details>\n- День: Работа\n</details>"),
            "$wrapper\n$close",
            "$close\n$wrapper",
            wrapper.replace(close, "text $close"),
            wrapper.replace(close, "</details >"),
        ).forEach { content ->
            rejected<IllegalStateException> {
                DailyNoteParser.writeJournal(parse(content), VaultJournal(morning = "New beginning"))
            }
        }
    }

    @Test fun unsupportedJournalWrapperFailsInsteadOfAppendingAnotherJournal() {
        listOf(
            "<details class='daily-journal'>",
            "<details open class=\"daily-journal\">",
            "<details class=\"daily-journal\"",
            "<details\nclass=\"daily-journal\">",
            "<DETAILS CLASS=\"daily-journal\">",
            "prefix $open",
        ).forEach { opening ->
            rejected<IllegalStateException> {
                DailyNoteParser.writeJournal(parse(wrapper.replace(open, opening)), VaultJournal())
            }
        }
    }

    @Test fun duplicateFieldLabelsFailEvenWhenTheirValuesAgree() {
        val source = wrapper.replace("- Утро: Начало", "- Утро: Начало\n- Утро: Начало")
        val note = parse(source)
        rejected<IllegalStateException> { DailyNoteParser.writeJournal(note, note.journal) }
    }

    @Test fun indentedFieldLabelIsAmbiguousWithPreviousFieldContinuation() {
        val note = parse(wrapper.replace("- День: Работа", "  - День: Работа"))
        rejected<IllegalStateException> { DailyNoteParser.writeJournal(note, note.journal) }
    }

    @Test fun detailsMarkupInAnyInputFieldIsRejectedBeforeReturningAWrite() {
        val note = parse(wrapper)
        listOf("$close\n## Injected section", "first\n  $close\nlast", "</DETAILS>", open).forEach { text ->
            listOf(
                note.journal.copy(morning = text),
                note.journal.copy(day = text),
                note.journal.copy(evening = text),
                note.journal.copy(conclusions = text),
            ).forEach { journal ->
                rejected<IllegalArgumentException> { DailyNoteParser.writeJournal(note, journal) }
            }
        }
    }

    @Test fun newSectionAndTaskSyntaxInInputStayInsideTheJournalField() {
        val prefix = "## Задачи\n- [ ] Existing task\n\n# Day planner\nPlugin content\n\n"
        val note = parse(prefix + wrapper)
        val journal = note.journal.copy(evening = "Reflection\n\n## Задачи\n- [ ] A quoted idea\n# Another heading")

        val written = DailyNoteParser.writeJournal(note, journal)
        val readBack = parse(written)

        assertEquals(journal, readBack.journal)
        assertEquals(note.tasks, readBack.tasks)
        assertTrue(written.startsWith(prefix))
        assertTrue(written.contains("\n  ## Задачи\n  - [ ] A quoted idea\n  # Another heading"))
        assertEquals(1, written.lines().count { it == "## Задачи" })
    }

    @Test fun fieldLabelsInsideContinuationCannotSilentlyOverwriteOtherFields() {
        val note = parse(wrapper)
        val journal = note.journal.copy(morning = "Beginning\n- День: Работа")
        rejected<IllegalStateException> { DailyNoteParser.writeJournal(note, journal) }
    }

    @Test fun legitimateMultilineTextRoundTripsAllFourFieldsAndRepeatedWrites() {
        val journal = VaultJournal(
            morning = "План ☀\n\nПервый шаг\n  Второй уровень",
            day = "Сделано главное\n- Обычный список\nПродолжение",
            evening = "Первое наблюдение\nСередина с пробелами  \n\nПоследнее наблюдение",
            conclusions = "Вывод\n> Цитата\n[[Ссылка]] и **важное**",
        )

        val written = DailyNoteParser.writeJournal(parse(wrapper), journal)

        assertEquals(journal, parse(written).journal)
        assertEquals(written, DailyNoteParser.writeJournal(parse(written), journal))
    }

    @Test fun unrepresentableWhitespaceFailsRatherThanBeingSilentlyTrimmed() {
        val note = parse(wrapper)
        listOf(" leading", "trailing ", "first  \nsecond", "first\n", "first\r\nsecond").forEach { text ->
            rejected<IllegalArgumentException> {
                DailyNoteParser.writeJournal(note, note.journal.copy(evening = text))
            }
        }
    }

    @Test fun editPreservesUnknownContentAndUnchangedFieldFormattingExactly() {
        val prefix = "# Original\n<details class=\"plugin\"><summary>Plugin</summary>Keep</details>\n\n"
        val body = wrapper
            .replace("<summary>Дневник</summary>", "<summary>Дневник</summary>\n<!-- plugin marker -->")
            .replace("- Утро: Начало", "- Утро:   Начало  ")
            .replace("- День: Работа", "- Пользовательское поле: Не менять\n  Продолжение\n- День: Работа")
        val suffix = "\n\n## Other notes\nUnknown text  \n<details>Other block</details>\n"
        val source = prefix + body + suffix
        val note = parse(source)
        val journal = note.journal.copy(evening = "Новый вечер\nПродолжение")

        val written = DailyNoteParser.writeJournal(note, journal)

        assertEquals(source.replace("- Вечер: Отдых", "- Вечер: Новый вечер\n  Продолжение"), written)
        assertEquals(journal, parse(written).journal)
        assertEquals(source, note.lines.joinToString("\n"))
    }

    @Test fun indentedClosingTagAndFollowingContentAreNeverConsumedAsContinuation() {
        val source = "$open\n<summary>Дневник</summary>\n- Выводы: Старый вывод\n  $close\n  Keep this outside\n"
        val journal = VaultJournal(conclusions = "Новый вывод\nВторая строка")

        val written = DailyNoteParser.writeJournal(parse(source), journal)

        assertEquals(journal, parse(written).journal)
        assertTrue(written.endsWith("  $close\n  Keep this outside\n"))
    }

    @Test fun absentJournalCanBeAppendedWithoutRewritingExistingContent() {
        val source = "# Personal note\nCustom content  \n<details>Plugin notes</details>\n"
        val journal = VaultJournal(morning = "Начало\nПродолжение", conclusions = "Итог")

        val written = DailyNoteParser.writeJournal(parse(source), journal)

        assertTrue(written.startsWith(source))
        assertEquals(journal, parse(written).journal)
        assertEquals(1, written.lines().count { it == open })
    }

    @Test fun missingFieldsAreAddedWithoutRemovingUnknownJournalLines() {
        val source = "$open\n<summary>Custom summary</summary>\n- День: Работа\n- Other: Keep\n  Extra\n$close"
        val journal = VaultJournal(morning = "Утро", day = "Работа", evening = "Вечер", conclusions = "Вывод")

        val written = DailyNoteParser.writeJournal(parse(source), journal)

        assertEquals(journal, parse(written).journal)
        assertTrue(written.contains("<summary>Custom summary</summary>"))
        assertTrue(written.contains("- Other: Keep\n  Extra"))
    }
}
