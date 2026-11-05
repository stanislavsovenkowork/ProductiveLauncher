package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты на реальном формате daily note из vault'а, а не на выдуманном.
 * Ошибка в парсере означает испорченные заметки, поэтому проверяем и чтение, и запись.
 */
class DailyNoteParserTest {

    private val realNote = """
        ---
        day: 2026-08-25
        tags:
          - daily
          - planning
        ---

        ## Задачи

        - [ ] Сделать письмо безопастникам
        - [x] Допилить документацию под текущую версию и залить в конфлюенс
        - [ ] Внести правки по код ревью и вынести список вопросов
        - [ ]

        # Day planner

        ```movebreak-daily-plan
        ```

        ## Итоги дня

        > [!summary] Краткая сводка дня
        > - Экранное время: **<1 мин**.
        > - MoveBreak-фокус: **0 мин**.
        > - Работа: **0 мин**; обучение: **0 мин**.
        > - Шаги: **нет данных**.

        <details class="daily-journal">
        <summary>Дневник</summary>

        - Утро:
        - День:
        - Вечер:
        - Выводы:

        </details>
    """.trimIndent()

    private fun parse() = DailyNoteParser.parse(
        LocalDate.of(2026, 8, 25),
        "00 - 2026-08-25.md",
        realNote,
    )

    @Test
    fun `имя файла совпадает с форматом vault`() {
        assertEquals(
            "00 - 2026-08-25.md",
            DailyNoteParser.fileNameFor(LocalDate.of(2026, 8, 25)),
        )
    }

    @Test
    fun `читает задачи из секции Задачи и пропускает пустой чекбокс`() {
        val note = parse()
        assertEquals(3, note.tasks.size)
        assertEquals("Сделать письмо безопастникам", note.tasks[0].title)
        assertFalse(note.tasks[0].done)
        assertTrue(note.tasks[1].done)
        assertEquals(1, note.doneCount)
    }

    @Test
    fun `не тащит чекбоксы из других секций`() {
        val withOtherSection = realNote + "\n\n## Другое\n\n- [ ] Не задача дня\n"
        val note = DailyNoteParser.parse(LocalDate.of(2026, 8, 25), "x.md", withOtherSection)
        assertTrue(note.tasks.none { it.title == "Не задача дня" })
    }

    @Test
    fun `переключение галочки меняет только свою строку`() {
        val note = parse()
        val target = note.tasks.first { !it.done }
        val updated = DailyNoteParser.toggleTask(note, target.lineIndex, done = true)

        val updatedNote = DailyNoteParser.parse(note.date, note.path, updated)
        assertTrue(updatedNote.tasks.first { it.title == target.title }.done)
        // Блоки плагинов и прочий текст на месте.
        assertTrue(updated.contains("```movebreak-daily-plan"))
        assertTrue(updated.contains("<details class=\"daily-journal\">"))
        assertEquals(note.lines.size, updated.lines().size)
    }

    @Test
    fun `добавление задачи не ломает структуру`() {
        val note = parse()
        val updated = DailyNoteParser.addTask(note, "Новая задача", 9 * 60 + 30, 50)
        val updatedNote = DailyNoteParser.parse(note.date, note.path, updated)

        val added = updatedNote.tasks.first { it.title == "Новая задача" }
        assertEquals(9 * 60 + 30, added.startMinuteOfDay)
        assertEquals(50, added.durationMinutes)
        assertTrue(updated.contains("# Day planner"))
    }

    @Test
    fun `время и длительность не попадают в название задачи`() {
        val withMeta = realNote.replace(
            "- [ ] Внести правки по код ревью и вынести список вопросов",
            "- [ ] Внести правки по код ревью ⏳ 14:15 [dur:: 90]",
        )
        val note = DailyNoteParser.parse(LocalDate.of(2026, 8, 25), "x.md", withMeta)
        val task = note.tasks.first { it.title.startsWith("Внести правки") }
        assertEquals("Внести правки по код ревью", task.title)
        assertEquals(14 * 60 + 15, task.startMinuteOfDay)
        assertEquals(90, task.durationMinutes)
    }

    @Test
    fun `планирование задачи не дублирует метки при повторном вызове`() {
        val note = parse()
        val once = DailyNoteParser.scheduleTask(note, note.tasks[0].lineIndex, 10 * 60, 25)
        val onceNote = DailyNoteParser.parse(note.date, note.path, once)
        val twice = DailyNoteParser.scheduleTask(onceNote, onceNote.tasks[0].lineIndex, 11 * 60, 50)

        val line = twice.lines().first { it.contains("Сделать письмо") }
        assertEquals(1, Regex("⏳").findAll(line).count())
        assertEquals(1, Regex("""\[dur::""").findAll(line).count())
        assertTrue(line.contains("11:00"))
        assertTrue(line.contains("[dur:: 50]"))
    }

    @Test
    fun `дневник читается и записывается по полям`() {
        val note = parse()
        assertTrue(note.journal.isEmpty)

        val updated = DailyNoteParser.writeJournal(
            note,
            VaultJournal(morning = "встал в 7", conclusions = "день удался"),
        )
        val updatedNote = DailyNoteParser.parse(note.date, note.path, updated)
        assertEquals("встал в 7", updatedNote.journal.morning)
        assertEquals("день удался", updatedNote.journal.conclusions)
        assertEquals("", updatedNote.journal.day)
        assertTrue(updated.contains("</details>"))
    }

    @Test
    fun `сводка дописывает свои строки и не трогает чужие`() {
        val note = parse()
        val updated = DailyNoteParser.writeSummary(
            note,
            DailySummary(
                screenMinutes = 257,
                focusMinutes = 95,
                tasksDone = 1,
                tasksTotal = 3,
                interruptedBlocks = 2,
                calories = 1840,
                calorieTarget = 2200,
            ),
        )
        // Строки Mac-скрипта остались.
        assertTrue(updated.contains("MoveBreak-фокус"))
        assertTrue(updated.contains("Шаги: **нет данных**"))
        // Свои строки добавились.
        assertTrue(updated.contains("Экранное время (телефон): **4 ч 17 мин**"))
        assertTrue(updated.contains("Фокус (телефон): **1 ч 35 мин**"))
        assertTrue(updated.contains("Задачи: **1 из 3**"))
        assertTrue(updated.contains("Калории (телефон): **1840 из 2200**"))
    }

    @Test
    fun `повторная запись сводки не плодит дубли`() {
        val note = parse()
        val summary = DailySummary(120, 60, 2, 3, 0, calories = 900, calorieTarget = 2000)
        val once = DailyNoteParser.writeSummary(note, summary)
        val onceNote = DailyNoteParser.parse(note.date, note.path, once)
        val twice = DailyNoteParser.writeSummary(onceNote, summary)

        assertEquals(1, Regex("""Экранное время \(телефон\)""").findAll(twice).count())
        assertEquals(1, Regex("""Задачи: \*\*""").findAll(twice).count())
        assertEquals(1, Regex("""Калории \(телефон\)""").findAll(twice).count())
    }

    @Test
    fun `заметка без нужных секций дополняется без потери текста`() {
        val bare = "---\nday: 2026-08-25\n---\n\nПросто текст.\n"
        val note = DailyNoteParser.parse(LocalDate.of(2026, 8, 25), "x.md", bare)
        assertTrue(note.tasks.isEmpty())
        assertTrue(note.journal.isEmpty)
        val updated = DailyNoteParser.addTask(note, "Задача")
        assertTrue(updated.startsWith(bare.trimEnd()))
        assertEquals("Задача", DailyNoteParser.parse(note.date, note.path, updated).tasks.single().title)
    }
}
