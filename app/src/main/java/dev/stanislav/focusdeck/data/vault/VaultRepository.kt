package dev.stanislav.focusdeck.data.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext

/**
 * Доступ к Obsidian vault через Storage Access Framework.
 *
 * SAF, а не прямой путь к файлу: на Android 11+ произвольные папки читаются
 * только так, без разрешения на весь диск. Пользователь выбирает папку один раз,
 * права сохраняются между перезапусками.
 */
class VaultRepository(private val context: Context) {

    private val _changes = MutableSharedFlow<LocalDate>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = _changes.asSharedFlow()

    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private val _vaultUri = MutableStateFlow(loadSavedUri())
    val vaultUri: StateFlow<Uri?> = _vaultUri.asStateFlow()

    val isConnected: Boolean get() = _vaultUri.value != null

    private fun loadSavedUri(): Uri? = prefs.getString(KEY_URI, null)?.let { saved ->
        val uri = Uri.parse(saved)
        // Право могло быть отозвано системой — проверяем, а не верим сохранённому.
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) uri else null
    }

    /** Сохраняет выбранную папку и берёт стойкое право доступа. */
    fun connect(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        cachedNotes.clear()
        _vaultUri.value = uri
    }

    fun disconnect() {
        _vaultUri.value?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(KEY_URI).apply()
        cachedNotes.clear()
        _vaultUri.value = null
    }

    /** Имя папки vault — показываем в настройках, чтобы было видно, что подключено. */
    fun vaultName(): String? = _vaultUri.value
        ?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /**
     * Найденные файлы заметок по датам, чтобы не искать их заново на каждую операцию.
     *
     * Поиск через SAF — это запрос к провайдеру со сборкой объектов на все файлы
     * папки. Одна правка задачи делала его трижды: на чтение, на запись и на
     * перечитывание. Теперь путь известен после первого раза, а промах по кэшу
     * (файл удалили или переименовали) просто приводит к обычному поиску.
     * Здесь не держим текст в памяти: каждое чтение видит свежие правки Obsidian.
     */
    // The ritual reads yesterday/today/tomorrow in quick succession. A single
    // cached URI used to make each alternating read scan the whole vault again.
    private val cachedNotes = ConcurrentHashMap<LocalDate, CachedNote>()

    private data class CachedNote(val date: LocalDate, val uri: Uri, val name: String, val root: Uri)

    private fun readFrom(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    suspend fun readDailyNote(date: LocalDate): DailyNote? = withContext(Dispatchers.IO) {
        val root = _vaultUri.value ?: return@withContext null
        cachedNotes[date]?.takeIf { it.root == root }?.let { cached ->
            readFrom(cached.uri)?.let { content ->
                if (_vaultUri.value != root) return@withContext null
                return@withContext DailyNoteParser.parse(date, cached.name, content)
            }
            cachedNotes.remove(date, cached)
        }
        val file = findDailyNoteFile(date, root) ?: return@withContext null
        val name = file.name ?: ""
        val content = readFrom(file.uri) ?: return@withContext null
        if (_vaultUri.value != root) return@withContext null
        cachedNotes[date] = CachedNote(date, file.uri, name, root)
        DailyNoteParser.parse(date, name, content)
    }

    /**
     * Пишем целиком, но только тот текст, который сами же и собрали из исходных строк —
     * то есть с сохранением чужого содержимого. Полная перезапись здесь неизбежна:
     * SAF не умеет править файл по месту.
     */
    suspend fun writeDailyNote(date: LocalDate, content: String, expectedContent: String? = null, expectedRoot: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val root = _vaultUri.value ?: return@withContext false
            if (expectedRoot != null && root.toString() != expectedRoot) return@withContext false
            val uri = cachedNotes[date]?.takeIf { it.root == root }?.uri
                ?: findDailyNoteFile(date, root)?.also {
                    cachedNotes[date] = CachedNote(date, it.uri, it.name ?: "", root)
                }?.uri
                ?: return@withContext false
            if (_vaultUri.value != root) return@withContext false
            val written = runCatching {
                val previous = readFrom(uri) ?: return@runCatching false
                if (expectedContent != null && previous.replace("\r\n", "\n") != expectedContent.replace("\r\n", "\n")) return@runCatching false
                if (previous == content) return@runCatching true
                val backupFolder = java.io.File(context.filesDir, "daily-note-backups/$date").apply { mkdirs() }
                java.io.File(backupFolder, "${System.currentTimeMillis()}.md").writeText(previous)
                backupFolder.listFiles().orEmpty().sortedByDescending { it.name }.drop(8).forEach { it.delete() }
                if (_vaultUri.value != root || readFrom(uri) != previous) return@runCatching false
                // "wt" — обрезать перед записью, иначе хвост старого файла останется.
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@runCatching false
                stream.use { out ->
                    out.write(content.toByteArray())
                }
                readFrom(uri) == content
            }.getOrDefault(false)
            if (!written) cachedNotes.remove(date)
            else _changes.tryEmit(date)
            written
        }

    /**
     * Какие дни в vault уже заведены — по именам файлов, без чтения содержимого.
     *
     * Дата зашита в имя «00 - ГГГГ-ММ-ДД.md», поэтому одного обхода папки хватает
     * на всю полосу дней. Читать каждый день ради числа задач нельзя: при прокрутке
     * это давало по чтению на день на каждый кадр, и полоса ощутимо тормозила.
     */
    suspend fun datesWithNotes(): Set<LocalDate> = withContext(Dispatchers.IO) {
        val root = _vaultUri.value?.let { DocumentFile.fromTreeUri(context, it) }
            ?: return@withContext emptySet()
        val found = mutableSetOf<LocalDate>()
        fun collect(dir: DocumentFile) {
            dir.listFiles().forEach { file ->
                val name = file.name ?: return@forEach
                NOTE_NAME.matchEntire(name)?.let { match ->
                    runCatching {
                        found += LocalDate.of(
                            match.groupValues[1].toInt(),
                            match.groupValues[2].toInt(),
                            match.groupValues[3].toInt(),
                        )
                    }
                }
            }
        }
        collect(root)
        root.listFiles().filter { it.isDirectory }.forEach { collect(it) }
        found
    }

    /**
     * Заметки за период одним обходом папки. По одному `readDailyNote` на день
     * каждый промах кэша сканировал бы vault заново — для четырёх недель это
     * два десятка полных обходов. Корень, как и в [findDailyNoteFile], важнее подпапок.
     * Дни, чей файл есть, но не читается, попадают в [DailyNotesRead.failed].
     */
    suspend fun readDailyNotes(from: LocalDate, to: LocalDate): DailyNotesRead = withContext(Dispatchers.IO) {
        val rootUri = _vaultUri.value ?: return@withContext DailyNotesRead(emptyMap(), emptySet())
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext DailyNotesRead(emptyMap(), emptySet())
        val files = mutableMapOf<LocalDate, DocumentFile>()
        fun collect(dir: DocumentFile, overwrite: Boolean) {
            dir.listFiles().forEach { file ->
                val match = NOTE_NAME.matchEntire(file.name ?: return@forEach) ?: return@forEach
                val date = runCatching {
                    LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
                }.getOrNull() ?: return@forEach
                if (date.isBefore(from) || date.isAfter(to)) return@forEach
                if (overwrite || date !in files) files[date] = file
            }
        }
        root.listFiles().filter { it.isDirectory }.forEach { collect(it, overwrite = false) }
        collect(root, overwrite = true)
        val notes = mutableMapOf<LocalDate, DailyNote>()
        val failed = mutableSetOf<LocalDate>()
        for ((date, file) in files) {
            if (_vaultUri.value != rootUri) break
            val content = readFrom(file.uri)
            if (content == null) { failed += date; continue }
            val name = file.name.orEmpty()
            cachedNotes[date] = CachedNote(date, file.uri, name, rootUri)
            notes[date] = DailyNoteParser.parse(date, name, content)
        }
        DailyNotesRead(notes, failed)
    }

    /** Есть ли файл заметки — отличает «заметки нет» от «не смог прочитать». */
    suspend fun dailyNoteExists(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        cachedNotes[date] != null || findDailyNoteFile(date) != null
    }

    /**
     * Создаёт заметку дня по шаблону. Всегда в корне vault — там же, где лежат
     * остальные daily notes; угадывать подпапку по одной существующей заметке
     * было бы хуже, чем промахнуться предсказуемо.
     */
    suspend fun createDailyNote(date: LocalDate, expectedRoot: String? = null): Boolean = withContext(Dispatchers.IO) {
        val rootUri = _vaultUri.value ?: return@withContext false
        if (expectedRoot != null && rootUri.toString() != expectedRoot) return@withContext false
        if (findDailyNoteFile(date, rootUri) != null) return@withContext true
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext false
        if (_vaultUri.value != rootUri) return@withContext false
        val name = DailyNoteParser.fileNameFor(date)
        runCatching {
            // Провайдер вправе подправить имя под тип: если он не знает про .md,
            // получится «00 - 2026-09-12.md.txt», и заметку потом никто не найдёт.
            // Поэтому после создания сверяем имя и при расхождении переименовываем.
            val file = root.createFile("text/markdown", name) ?: return@runCatching false
            if (file.name != name && !file.renameTo(name)) {
                file.delete()
                return@runCatching false
            }
            val stream = context.contentResolver.openOutputStream(file.uri, "wt")
            if (stream == null) {
                file.delete()
                return@runCatching false
            }
            try {
                stream.use { out ->
                    out.write(DailyNoteParser.template(date).toByteArray())
                }
            } catch (error: Exception) {
                file.delete()
                return@runCatching false
            }
            cachedNotes[date] = CachedNote(date, file.uri, name, root.uri)
            _changes.tryEmit(date)
            true
        }.getOrDefault(false)
    }

    /** Ищет заметку дня в корне vault и в подпапках первого уровня. */
    private fun findDailyNoteFile(date: LocalDate, rootUri: Uri? = _vaultUri.value): DocumentFile? {
        val root = rootUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        val target = DailyNoteParser.fileNameFor(date)

        root.findFile(target)?.let { return it }

        // Заметка могла лежать в подпапке — обходим один уровень, глубже не спускаемся,
        // чтобы не сканировать весь vault на каждом открытии экрана.
        root.listFiles()
            .filter { it.isDirectory }
            .forEach { dir -> dir.findFile(target)?.let { return it } }

        return null
    }

    private companion object {
        const val KEY_URI = "vault_tree_uri"
        val NOTE_NAME = Regex("""00 - (\d{4})-(\d{2})-(\d{2})\.md""")
    }
}

data class DailyNotesRead(val notes: Map<LocalDate, DailyNote>, val failed: Set<LocalDate>)
