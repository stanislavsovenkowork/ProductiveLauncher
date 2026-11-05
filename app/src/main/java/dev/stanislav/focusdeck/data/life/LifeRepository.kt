package dev.stanislav.focusdeck.data.life

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.stanislav.focusdeck.data.vault.VaultRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LifeSnapshot(
    val entries: List<LifeEntry> = emptyList(),
    val connected: Boolean = false,
    val loading: Boolean = false,
    val problems: List<String> = emptyList(),
    val error: String? = null,
    val rootKey: String? = null,
) {
    fun entry(id: String?): LifeEntry? = entries.firstOrNull { it.id == id }
    val activeProjects get() = entries.filter { it.kind == LifeKind.PROJECT && it.inUse }
    val activeGoals get() = entries.filter { it.kind == LifeKind.GOAL && it.inUse }
    val reviewsDue get() = entries.filter { it.needsReview }
}

/** One file per entity; no bulk rewrites of the vault and no separate source of truth. */
class LifeRepository(private val context: Context, private val vault: VaultRepository) {
    private val lock = Mutex()
    private var loadedRoot: Uri? = null
    private val uris = mutableMapOf<String, Uri>()
    private val _snapshot = MutableStateFlow(LifeSnapshot(connected = vault.isConnected))
    val snapshot: StateFlow<LifeSnapshot> = _snapshot.asStateFlow()

    suspend fun refresh(quiet: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val rootUri = vault.vaultUri.value
            if (rootUri == null) {
                uris.clear(); loadedRoot = null
                _snapshot.value = LifeSnapshot()
                return@withLock
            }
            if (loadedRoot != rootUri) {
                uris.clear()
                _snapshot.value = LifeSnapshot(connected = true, rootKey = rootUri.toString())
            }
            if (!quiet) _snapshot.value = _snapshot.value.copy(loading = true, error = null)
            try {
                val root = DocumentFile.fromTreeUri(context, rootUri)
                    ?: error("Не удалось открыть папку Obsidian")
                check(root.canRead()) { "Нет доступа к папке Obsidian" }
                val folder = root.findFile("FocusDeck")
                val entries = mutableListOf<LifeEntry>()
                val issues = mutableListOf<String>()
                val foundUris = mutableMapOf<String, Uri>()
                for (kind in LifeKind.entries) {
                    val directory = folder?.findFile(kind.folder) ?: continue
                    check(directory.isDirectory) { "FocusDeck/${kind.folder} не является папкой" }
                    directory.listFiles().filter { it.isFile && it.name?.endsWith(".md") == true }.forEach { file ->
                        val name = file.name.orEmpty()
                        if (name.contains(".sync-conflict-")) {
                            issues += "Конфликт синхронизации: ${kind.folder}/$name"
                            return@forEach
                        }
                        if (file.length() > MAX_NOTE_BYTES) {
                            issues += "Слишком большая заметка: ${kind.folder}/$name"
                            return@forEach
                        }
                        val text = read(file.uri)
                        val entry = LifeNoteCodec.parse("FocusDeck/${kind.folder}/$name", text)
                        if (entry == null) {
                            if (text.contains("focusdeck:")) issues += "Не удалось разобрать ${kind.folder}/$name"
                            return@forEach
                        }
                        if (entry.kind != kind) {
                            issues += "Тип заметки не совпадает с папкой: $name"
                            return@forEach
                        }
                        entries += entry.copy(sourceRoot = rootUri.toString())
                        foundUris[entry.id] = file.uri
                        issues += entry.problems.map { "${entry.title}: $it" }
                    }
                }
                val duplicates = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
                issues += duplicates.map { "Повтор идентификатора $it — проверь копии заметок" }
                check(vault.vaultUri.value == rootUri) { "Папка изменилась во время чтения" }
                uris.clear(); uris.putAll(foundUris.filterKeys { it !in duplicates })
                loadedRoot = rootUri
                _snapshot.value = LifeSnapshot(entries.filter { it.id !in duplicates }, true, problems = issues, rootKey = rootUri.toString())
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _snapshot.value = _snapshot.value.copy(loading = false,
                    error = error.message ?: "Не удалось прочитать цели и проекты")
            }
        }
    }

    suspend fun save(base: LifeEntry?, proposal: LifeEntry, recordDecisionReview: Boolean = false, recordLifeReview: Boolean = false): LifeEntry = withContext(Dispatchers.IO) {
        refresh()
        check(snapshot.value.error == null) { snapshot.value.error.orEmpty() }
        lock.withLock {
            val knownPath = if (base != null) snapshot.value.entry(base.id)?.path ?: base.path else proposal.path
            val coherent = LifeHierarchy.canonical(proposal.copy(path = knownPath), snapshot.value.entries)
            val edited = prepare(base, coherent, recordDecisionReview, recordLifeReview)
            require(meaningfulLifeText(edited.title)) { "Добавь букву или цифру в название" }
            if (edited.status == LifeStatus.ACTIVE) check(edited.activationProblems().isEmpty()) {
                edited.activationProblems().joinToString(". ")
            }
            val rootUri = vault.vaultUri.value ?: error("Подключи папку Obsidian")
            check(proposal.sourceRoot.isBlank() || proposal.sourceRoot == rootUri.toString()) {
                "Черновик относится к другой папке Obsidian. Верни прежнюю папку, чтобы сохранить его."
            }
            check(loadedRoot == rootUri && snapshot.value.rootKey == rootUri.toString()) {
                "Папка изменилась. Обнови список перед сохранением."
            }
            val today = LocalDate.now()
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Нет доступа к папке Obsidian")
            check(root.canWrite()) { "Нет права записи в Obsidian" }
            val uri: Uri
            val content: String
            val path: String
            if (base == null) {
                check(_snapshot.value.entries.none { it.id == edited.id }) { "Такая заметка уже существует" }
                val folder = directory(directory(root, "FocusDeck"), edited.kind.folder)
                val name = edited.fileName
                check(folder.findFile(name) == null) { "Файл уже существует. Обнови список." }
                content = LifeNoteCodec.create(edited.copy(created = today, updated = today))
                check(content.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "Заметка слишком большая" }
                val checked = LifeNoteCodec.parse(edited.relativePath, content) ?: error("Не удалось проверить структуру заметки")
                LifeRules.validate(checked)
                check(LifeField.entries.all { edited.text(it).trim() == checked.text(it).trim() }) { "Структура разделов неоднозначна; черновик сохранён" }
                val file = folder.createFile("text/markdown", name) ?: error("Не удалось создать заметку")
                check(file.name == name || file.renameTo(name)) { "Android изменил расширение заметки" }
                uri = file.uri
                path = "FocusDeck/${edited.kind.folder}/$name"
            } else {
                uri = uris[base.id] ?: error("Заметка не найдена. Возможно, она переименована в Obsidian: обнови список.")
                val oldText = read(uri)
                val fresh = LifeNoteCodec.parse(knownPath, oldText) ?: error("Заметка изменила формат: правка отменена")
                content = LifeRules.merge(base, edited, fresh, today)
                val merged = LifeNoteCodec.parse(knownPath, content) ?: error("Не удалось проверить связи записи")
                val canonical = LifeHierarchy.canonical(merged, snapshot.value.entries)
                if (merged.areaId != canonical.areaId || merged.goalId != canonical.goalId || merged.projectId != canonical.projectId) {
                    throw LifeConflictException(listOf("связи с областью, целью или проектом изменены одновременно"))
                }
                check(content.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "Заметка слишком большая" }
                path = knownPath
                if (content == oldText) {
                    val saved = fresh.copy(sourceRoot = rootUri.toString())
                    _snapshot.value = _snapshot.value.copy(entries = _snapshot.value.entries.filterNot { it.id == saved.id } + saved)
                    return@withLock saved
                }
                backup(base.id, oldText)
                // Catch external changes arriving while the editor was open or backup was saved.
                if (read(uri) != oldText) throw LifeConflictException(listOf("файл обновился во время сохранения"))
            }
            check(vault.vaultUri.value == rootUri) { "Папка изменилась. Сохранение отменено." }
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть запись")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            check(read(uri) == content) { "Проверка записи не прошла. Предыдущая версия сохранена локально; обнови заметку." }
            val saved = LifeNoteCodec.parse(path, content)?.copy(sourceRoot = rootUri.toString()) ?: error("Не удалось проверить сохранённую заметку")
            uris[saved.id] = uri
            loadedRoot = rootUri
            _snapshot.value = _snapshot.value.copy(connected = true, loading = false, error = null,
                entries = _snapshot.value.entries.filterNot { it.id == saved.id } + saved, rootKey = rootUri.toString())
            saved
        }
    }

    /** Resolve current ancestors for a NEW daily action; past task history is not rewritten. */
    suspend fun taskParents(source: LifeEntry): LifeTaskParents {
        refresh()
        val index = snapshot.value
        check(index.error == null) { index.error.orEmpty() }
        check(source.sourceRoot.isBlank() || source.sourceRoot == index.rootKey) {
            "Запись относится к другой папке Obsidian"
        }
        val current = index.entry(source.id) ?: error("Связанная запись больше не найдена. Обнови список.")
        return LifeHierarchy.taskParents(current, index.entries)
    }

    /** Explicit setup only; never invent personal goals or silently fill a real vault. */
    suspend fun initializeAreas(): Int {
        val root = vault.vaultUri.value?.toString() ?: error("Подключи папку Obsidian")
        refresh()
        check(snapshot.value.error == null) { snapshot.value.error.orEmpty() }
        val names = listOf("Здоровье и энергия", "Работа и развитие", "Отношения", "Дом и финансы", "Интересы и отдых")
        var added = 0
        for (name in names) {
            check(vault.vaultUri.value?.toString() == root && snapshot.value.rootKey == root) {
                "Папка изменилась. Создание сфер остановлено."
            }
            if (snapshot.value.entries.any { it.kind == LifeKind.AREA && it.title == name }) continue
            save(null, LifeEntry.draft(LifeKind.AREA, name).copy(status = LifeStatus.ACTIVE, sourceRoot = root))
            added++
        }
        return added
    }

    private fun prepare(base: LifeEntry?, proposal: LifeEntry, recordDecisionReview: Boolean, recordLifeReview: Boolean): LifeEntry {
        val today = LocalDate.now()
        val references = listOf(proposal.areaId to LifeKind.AREA, proposal.goalId to LifeKind.GOAL,
            proposal.projectId to LifeKind.PROJECT)
        references.filter { it.first != null }.forEach { (id, kind) ->
            check(id != proposal.id) { "Запись не может ссылаться сама на себя" }
            val parent = snapshot.value.entry(id)
            check(parent != null && parent.kind == kind) { "Связанная запись не найдена. Обнови список или выбери другую связь." }
        }
        var entry = proposal.copy(title = proposal.title.replace('\n', ' ').trim())
        val links = references.mapNotNull { (id, _) -> snapshot.value.entry(id) }.distinctBy { it.id }
            .joinToString("\n") { "- ${it.wikiLink}" }
        if (base == null || base.areaId != entry.areaId || base.goalId != entry.goalId || base.projectId != entry.projectId) {
            entry = entry.copy(fields = entry.fields + (LifeField.LINKS to links))
        }
        return LifeRules.prepare(base, entry, recordReview = recordDecisionReview, recordLifeReview = recordLifeReview)
    }

    suspend fun openUri(entry: LifeEntry): Uri? = withContext(Dispatchers.IO) { lock.withLock { uris[entry.id] } }

    private fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)
        ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
                check(result.length <= MAX_NOTE_BYTES) { "Заметка слишком большая" }
            }
            result.toString()
        } ?: error("Не удалось прочитать заметку")

    private fun directory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) { check(existing.isDirectory) { "$name уже занят файлом" }; return existing }
        return parent.createDirectory(name) ?: error("Не удалось создать папку $name")
    }

    private fun backup(id: String, content: String) {
        val folder = File(context.filesDir, "life-backups/$id").apply { mkdirs() }
        File(folder, "${System.currentTimeMillis()}.md").writeText(content, Charsets.UTF_8)
        folder.listFiles().orEmpty().sortedByDescending { it.name }.drop(8).forEach { it.delete() }
    }

    private companion object { const val MAX_NOTE_BYTES = 512 * 1024 }
}
