package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правки заметки в условиях, когда файл меняют одновременно с двух сторон.
 *
 * Это главный риск всей затеи: между чтением и записью заметку успевает изменить
 * Obsidian на телефоне, скрипт на Mac или синхронизация. Номера строк съезжают,
 * и правка по старому индексу молча портит соседнюю задачу.
 */
class DailyNoteEditTest {

    private val date: LocalDate = LocalDate.of(2026, 9, 12)

    private val note = """
        ---
        day: 2026-09-12
        tags:
          - daily
          - planning
        ---

        ## Задачи

        - [ ] Найти баг в нотификейшене
        - [ ] Зарелизить таску с фин моном
        - [ ] Съездить в сервис

        # Day planner

        ```movebreak-daily-plan
        ```

        ## Итоги дня

        > [!summary] Краткая сводка дня

        <details class="daily-journal">
        <summary>Дневник</summary>

        - Утро:
        - День:
        - Вечер:
        - Выводы:

        </details>
    """.trimIndent()

    private fun parse(content: String) = DailyNoteParser.parse(date, "00 - 2026-09-12.md", content)

    @Test
    fun `задача находится по названию, когда строки съехали`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Съездить в сервис" }

        // Кто-то дописал две задачи в начало секции — индексы всех последующих сдвинулись.
        val shifted = parse(
            note.replace(
                "- [ ] Найти баг в нотификейшене",
                "- [ ] Новая раз\n- [ ] Новая два\n- [ ] Найти баг в нотификейшене",
            ),
        )

        val resolved = DailyNoteParser.resolveTask(shifted, target.lineIndex, target.title)

        assertNotNull(resolved)
        assertEquals("Съездить в сервис", resolved!!.title)
        assertEquals(target.lineIndex + 2, resolved.lineIndex)
    }

    @Test
    fun `галочка ставится той задаче, которую выбрали, а не соседней`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Съездить в сервис" }

        val shifted = parse(
            note.replace(
                "- [ ] Найти баг в нотификейшене",
                "- [ ] Новая раз\n- [ ] Новая два\n- [ ] Найти баг в нотификейшене",
            ),
        )
        val resolved = DailyNoteParser.resolveTask(shifted, target.lineIndex, target.title)!!
        val updated = parse(DailyNoteParser.toggleTask(shifted, resolved.lineIndex, done = true))

        assertTrue(updated.tasks.first { it.title == "Съездить в сервис" }.done)
        assertEquals(0, updated.tasks.count { it.done && it.title != "Съездить в сервис" })
    }

    @Test
    fun `удалённая задача не находится и правка не применяется`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Зарелизить таску с фин моном" }

        val without = parse(note.replace("- [ ] Зарелизить таску с фин моном\n", ""))

        assertNull(DailyNoteParser.resolveTask(without, target.lineIndex, target.title))
    }

    @Test
    fun `время и длительность проставляются и снимаются, текст задачи не меняется`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Найти баг в нотификейшене" }

        val scheduled = parse(
            DailyNoteParser.scheduleTask(original, target.lineIndex, 9 * 60 + 30, 45),
        ).tasks.first { it.title == "Найти баг в нотификейшене" }

        assertEquals(9 * 60 + 30, scheduled.startMinuteOfDay)
        assertEquals(45, scheduled.durationMinutes)

        val cleared = parse(
            DailyNoteParser.unscheduleTask(
                parse(DailyNoteParser.scheduleTask(original, target.lineIndex, 9 * 60 + 30, 45)),
                target.lineIndex,
            ),
        ).tasks.first { it.title == "Найти баг в нотификейшене" }

        assertNull(cleared.startMinuteOfDay)
        assertNull(cleared.durationMinutes)
    }

    @Test
    fun `перепланирование не плодит вторую отметку времени`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Найти баг в нотификейшене" }

        var content = DailyNoteParser.scheduleTask(original, target.lineIndex, 9 * 60, 50)
        repeat(3) {
            val current = parse(content)
            val task = current.tasks.first { it.title == "Найти баг в нотификейшене" }
            content = DailyNoteParser.scheduleTask(current, task.lineIndex, 11 * 60, 25)
        }

        val line = content.lines().first { it.contains("Найти баг в нотификейшене") }
        assertEquals(1, Regex("""⏳""").findAll(line).count())
        assertEquals(1, Regex("""\[dur::""").findAll(line).count())
        assertEquals(11 * 60, parse(content).tasks.first { it.title == "Найти баг в нотификейшене" }.startMinuteOfDay)
    }

    @Test
    fun `правка задач не трогает блок плагина и сводку`() {
        val original = parse(note)
        val target = original.tasks.first()
        val updated = DailyNoteParser.toggleTask(original, target.lineIndex, done = true)

        assertTrue(updated.contains("```movebreak-daily-plan"))
        assertTrue(updated.contains("> [!summary] Краткая сводка дня"))
        assertTrue(updated.contains("<details class=\"daily-journal\">"))
        assertEquals(note.lines().size, updated.lines().size)
    }

    @Test
    fun `дневник пишется в свои поля и не ломает обёртку`() {
        val original = parse(note)
        val updated = parse(
            DailyNoteParser.writeJournal(
                original,
                VaultJournal(morning = "Встал в 7", conclusions = "День удался"),
            ),
        )

        assertEquals("Встал в 7", updated.journal.morning)
        assertEquals("День удался", updated.journal.conclusions)
        assertEquals("", updated.journal.day)
        assertTrue(updated.lines.any { it.trim() == "</details>" })
    }

    @Test
    fun `созданная по шаблону заметка разбирается обратно`() {
        val fresh = parse(DailyNoteParser.template(date))

        assertTrue(fresh.tasks.isEmpty())
        assertTrue(fresh.journal.isEmpty)
        assertTrue(DailyNoteParser.template(date).contains("day: 2026-09-12"))

        // В пустую заметку можно сразу добавить задачу и прочитать её обратно.
        val withTask = parse(DailyNoteParser.addTask(fresh, "Первая задача", 10 * 60, 30))
        assertEquals(1, withTask.tasks.size)
        assertEquals("Первая задача", withTask.tasks.first().title)
        assertEquals(10 * 60, withTask.tasks.first().startMinuteOfDay)
        assertEquals(30, withTask.tasks.first().durationMinutes)
    }


    @Test
    fun `первая задача в пустом дне не слипается со следующим заголовком`() {
        val fresh = parse(DailyNoteParser.template(date))
        val content = DailyNoteParser.addTask(fresh, "Первая", durationMinutes = 50)
        val lines = content.lines()

        val taskIndex = lines.indexOfFirst { it.contains("Первая") }
        val headingIndex = lines.indexOfFirst { it.trimStart().startsWith("# Day planner") }

        assertTrue(taskIndex < headingIndex)
        assertTrue("между задачей и заголовком нужна пустая строка", lines[taskIndex + 1].isBlank())
        assertTrue("пустая строка после «## Задачи» должна остаться", lines[taskIndex - 1].isBlank())
    }

    @Test
    fun `задача дописывается после последней, а не в начало секции`() {
        val original = parse(note)
        val content = DailyNoteParser.addTask(original, "Четвёртая", durationMinutes = 25)
        val titles = parse(content).tasks.map { it.title }

        assertEquals(
            listOf(
                "Найти баг в нотификейшене",
                "Зарелизить таску с фин моном",
                "Съездить в сервис",
                "Четвёртая",
            ),
            titles,
        )
        assertTrue(content.lines().first { it.contains("# Day planner") }.isNotBlank())
    }


    @Test
    fun `переименование сохраняет галочку, время и длительность`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Найти баг в нотификейшене" }
        val scheduled = parse(
            DailyNoteParser.scheduleTask(original, target.lineIndex, 9 * 60 + 30, 45),
        )
        val done = parse(
            DailyNoteParser.toggleTask(scheduled, target.lineIndex, done = true),
        )

        val renamed = parse(DailyNoteParser.renameTask(done, target.lineIndex, "Новое название"))
        val task = renamed.tasks.first { it.title == "Новое название" }

        assertTrue(task.done)
        assertEquals(9 * 60 + 30, task.startMinuteOfDay)
        assertEquals(45, task.durationMinutes)
        assertEquals(note.lines().size, renamed.lines.size)
    }

    @Test
    fun `длительность ставится задаче без времени и не добавляет время`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Съездить в сервис" }

        val updated = parse(DailyNoteParser.setDuration(original, target.lineIndex, 25))
        val task = updated.tasks.first { it.title == "Съездить в сервис" }

        assertEquals(25, task.durationMinutes)
        assertNull("время начала появляться не должно", task.startMinuteOfDay)
    }

    @Test
    fun `повторная смена длительности не плодит вторую отметку`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Съездить в сервис" }

        var content = DailyNoteParser.setDuration(original, target.lineIndex, 25)
        repeat(3) {
            val current = parse(content)
            val task = current.tasks.first { it.title == "Съездить в сервис" }
            content = DailyNoteParser.setDuration(current, task.lineIndex, 90)
        }

        val line = content.lines().first { it.contains("Съездить в сервис") }
        assertEquals(1, Regex("""\[dur::""").findAll(line).count())
        assertEquals(90, parse(content).tasks.first { it.title == "Съездить в сервис" }.durationMinutes)
    }

    @Test
    fun `удаление убирает только свою строку`() {
        val original = parse(note)
        val target = original.tasks.first { it.title == "Зарелизить таску с фин моном" }

        val updated = parse(DailyNoteParser.deleteTask(original, target.lineIndex))

        assertEquals(
            listOf("Найти баг в нотификейшене", "Съездить в сервис"),
            updated.tasks.map { it.title },
        )
        assertTrue(updated.lines.any { it.contains("```movebreak-daily-plan") })
        assertEquals(note.lines().size - 1, updated.lines.size)
    }

    @Test
    fun `имя файла совпадает с форматом vault`() {
        assertEquals("00 - 2026-09-12.md", DailyNoteParser.fileNameFor(date))
        assertEquals("00 - 2026-01-05.md", DailyNoteParser.fileNameFor(LocalDate.of(2026, 1, 5)))
    }
}
