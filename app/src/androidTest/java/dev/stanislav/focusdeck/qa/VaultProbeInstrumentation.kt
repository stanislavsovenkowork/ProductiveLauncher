package dev.stanislav.focusdeck.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Disposable QA SAF fixture bridge; never included in the application APK.
 * Args: action=list|read|write, path (relative), treeUri (optional with one grant).
 * write: base64, expectedSha256 (mandatory for an existing file).
 * read: offset=0, length=65536; returns base64, total size/hash and nextOffset/eof.
 * list: returns text containing a JSON array of immediate children.
 * Optional launchUi=true|false (default false), delayMs/holdMs=0..60000 (default 0).
 * UI launches before delay; result is sent via sendStatus before hold and finish.
 * Delays run on the instrumentation thread, leaving the UI thread responsive.
 * Parents must already exist. No delete, private app APIs or storage permissions.
 * The hash check is optimistic, not an atomic provider compare-and-swap: pause
 * competing writers while applying a fixture. A failed write may have modified it.
 */
class VaultProbeInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private val resolver get() = targetContext.contentResolver

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = Bundle(arguments ?: Bundle())
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var holdMs = 0
        var resultCode = Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName == QA_PACKAGE) { "Разрешён только пакет $QA_PACKAGE" }
            val launchUi = when (args.getString("launchUi") ?: "false") {
                "true" -> true
                "false" -> false
                else -> error("launchUi должен быть true или false")
            }
            val delay = integerArg("delayMs", 0)
            val hold = integerArg("holdMs", 0)
            require(delay in 0..60000 && hold in 0..60000) { "delayMs и holdMs должны быть в пределах 0..60000" }
            val action = args.getString("action") ?: error("Нужен action=list|read|write")
            if (action == "telemetry-grant-status") {
                check(!launchUi && delay == 0 && hold == 0)
                finish(Activity.RESULT_OK, TelemetryGrantStatusProbe.run(targetContext))
                return
            }
            if (action == "migration-7-8") {
                check(!launchUi && delay == 0 && hold == 0) { "Migration probe runs without UI or delays" }
                finish(Activity.RESULT_OK, RoomMigrationProbe.run(targetContext))
                return
            }
            if (action == "protection-ledger") {
                check(!launchUi && delay == 0 && hold == 0) { "Ledger probe runs without UI or delays" }
                finish(Activity.RESULT_OK, ProtectionLedgerProbe.run(targetContext))
                return
            }
            if (action == "scheduled-protection") {
                check(!launchUi && delay == 0 && hold == 0) { "Scheduled probe runs without UI or delays" }
                finish(Activity.RESULT_OK, ScheduledProtectionProbe.run(targetContext))
                return
            }
            if (action == "protection-snapshot") {
                check(!launchUi && delay == 0 && hold == 0) { "Protection probe runs without UI or delays" }
                finish(Activity.RESULT_OK, ProtectionSnapshotProbe.run(targetContext))
                return
            }
            if (action == "nutrition-retry") {
                check(!launchUi && delay == 0 && hold == 0) { "Nutrition probe runs without UI or delays" }
                finish(Activity.RESULT_OK, NutritionRetryProbe.run(targetContext) { runOnMainSync(it) })
                return
            }
            if (action == "summary-sync") {
                check(!launchUi && delay == 0 && hold == 0) { "Summary probe runs without UI or delays" }
                finish(Activity.RESULT_OK, SummarySyncProbe.run(targetContext) { runOnMainSync(it) })
                return
            }
            require(action in setOf("list", "read", "write")) { "Неизвестное действие" }
            val tree = grantedTree(action == "write")
            val segments = segments(args.getString("path").orEmpty())
            holdMs = hold
            if (launchUi) {
                startActivitySync(Intent().setClassName(QA_PACKAGE, "dev.stanislav.focusdeck.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (delay > 0) Thread.sleep(delay.toLong())
            check(grantedTree(action == "write") == tree) { "SAF-разрешение изменилось во время ожидания" }
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var document = document(tree, rootId)
            if (action == "write") {
                require(segments.isNotEmpty()) { "Нельзя записывать корень" }
                // Validate all input before creating or opening a document for writing.
                val encoded = args.getString("base64") ?: error("Нужен аргумент base64")
                require(encoded.length <= ((MAX_BYTES + 2) / 3) * 4) { "Лимит файла: 512 КБ" }
                require(encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")) && encoded.length % 4 == 0) {
                    "Нужен стандартный Base64 с padding, без пробелов"
                }
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size <= MAX_BYTES) { "Лимит файла: 512 КБ" }
                val expected = args.getString("expectedSha256")?.lowercase()
                require(expected == null || expected.matches(Regex("[0-9a-f]{64}"))) {
                    "expectedSha256 должен содержать 64 hex-символа"
                }
                for (name in segments.dropLast(1)) document = child(tree, document, name)
                    ?: error("Родительская папка не найдена: $name")
                val existing = child(tree, document, segments.last())
                if (existing != null) {
                    require(!existing.directory) { "Путь указывает на папку" }
                    require(expected != null) { "Для перезаписи обязателен expectedSha256" }
                    check(sha256(read(existing.uri)) == expected) { "Конфликт SHA-256; запись отменена" }
                } else {
                    check(expected == null) { "Ожидаемый файл исчез; запись отменена" }
                }
                // Recheck the persisted grant immediately before the mutation.
                check(grantedTree(true) == tree) { "SAF-разрешение изменилось" }
                val destination = existing?.uri ?: DocumentsContract.createDocument(
                    resolver, document.uri, "text/markdown", segments.last(),
                )?.also { created ->
                    check(document(tree, DocumentsContract.getDocumentId(created)).name == segments.last()) {
                        "Провайдер изменил имя нового файла"
                    }
                } ?: error("Не удалось создать файл")
                resolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
                    ?: error("Не удалось открыть файл для записи")
                val actual = read(destination)
                check(actual.contentEquals(bytes)) { "Проверка после записи не прошла; проверь содержимое файла" }
                result.putString("sha256", sha256(actual))
                result.putInt("size", actual.size)
                result.putString("text", if (existing == null) "created" else "overwritten")
            } else {
                for (name in segments) document = child(tree, document, name)
                    ?: error("Путь не найден: $name")
                if (action == "list") {
                    val listing = JSONArray()
                    var outputBytes = 2
                    children(tree, document) { item ->
                        val row = JSONObject().put("name", item.name)
                            .put("directory", item.directory).put("size", item.size)
                        outputBytes += row.toString().toByteArray(Charsets.UTF_8).size + 1
                        require(outputBytes <= CHUNK_BYTES) { "Список больше 64 КБ; выбери вложенную папку" }
                        listing.put(row)
                    }
                    result.putString("text", listing.toString())
                } else {
                    require(!document.directory) { "Нельзя читать папку как файл" }
                    val bytes = read(document.uri)
                    val offset = integerArg("offset", 0)
                    val length = integerArg("length", CHUNK_BYTES)
                    require(offset in 0..bytes.size && length in 1..CHUNK_BYTES) { "Неверные offset/length (length: 1..65536)" }
                    val end = minOf(bytes.size, offset + length)
                    result.putString("base64", Base64.encodeToString(bytes.copyOfRange(offset, end), Base64.NO_WRAP))
                    result.putInt("size", bytes.size)
                    result.putString("sha256", sha256(bytes))
                    result.putInt("offset", offset)
                    result.putInt("nextOffset", end)
                    result.putBoolean("eof", end == bytes.size)
                }
            }
            result.putString("status", "ok")
            resultCode = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("status", "error")
            result.putString("text", failure.message.orEmpty().take(2000))
        }
        sendStatus(0, Bundle(result))
        try {
            if (holdMs > 0) Thread.sleep(holdMs.toLong())
        } finally {
            finish(resultCode, result)
        }
    }

    private fun grantedTree(write: Boolean): Uri {
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }
        val explicit = args.getString("treeUri")?.let(Uri::parse)
        val grant = if (explicit != null) grants.singleOrNull { it.uri == explicit }
            ?: error("Нет сохранённого SAF-разрешения для treeUri")
        else grants.singleOrNull() ?: error("Нужен treeUri: сохранённых разрешений не ровно одно")
        check(!write || grant.isWritePermission) { "Нет SAF-разрешения на запись" }
        val tree = grant.uri
        require(tree.scheme == "content" && tree.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.isTreeUri(tree)) { "Нужен SAF tree внешнего хранилища" }
        val id = DocumentsContract.getTreeDocumentId(tree)
        val relative = id.substringAfter(':', "")
        val parts = segments(relative)
        val inDocuments = parts.firstOrNull() == "Documents"
        val qaNamed = parts.lastOrNull()?.matches(Regex("(?i)(qa|focusdeckqa|.*[-_ ]qa(?:[-_ ].*)?)")) == true
        val secondaryUser = Process.myUid() / 100000 > 0 && targetContext.applicationInfo.uid / 100000 > 0
        require(inDocuments && (qaNamed || secondaryUser)) {
            "Разрешена только QA-папка внутри Documents либо Documents вторичного пользователя"
        }
        return tree
    }

    private fun segments(path: String): List<String> {
        require(path.length <= 2048 && '\\' !in path && path.none { it.code < 32 || it.code == 127 }) {
            "Недопустимый относительный путь"
        }
        if (path.isEmpty()) return emptyList()
        return path.split('/').also { parts ->
            require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
                "Абсолютные пути, пустые компоненты и переходы . / .. запрещены"
            }
        }
    }

    private data class Document(val uri: Uri, val name: String, val directory: Boolean, val size: Long)

    private fun document(tree: Uri, id: String): Document {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        require(id == rootId || id.startsWith("$rootId/")) { "Документ вне QA-корня" }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "Документ недоступен" }
            Document(uri, cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                if (cursor.isNull(3)) -1 else cursor.getLong(3))
        } ?: error("Не удалось прочитать сведения о документе")
    }

    private fun children(tree: Uri, parent: Document, visit: (Document) -> Unit) {
        require(parent.directory) { "Родитель не является папкой" }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                require(id.startsWith("$rootId/")) { "Документ вне QA-корня" }
                visit(Document(DocumentsContract.buildDocumentUriUsingTree(tree, id), cursor.getString(1),
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (cursor.isNull(3)) -1 else cursor.getLong(3)))
            }
        } ?: error("Не удалось прочитать папку")
    }

    private fun child(tree: Uri, parent: Document, name: String): Document? {
        var match: Document? = null
        children(tree, parent) { item ->
            if (item.name == name) {
                check(match == null) { "Неоднозначное имя: $name" }
                match = item
            }
        }
        return match
    }

    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Лимит файла: 512 КБ" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Не удалось открыть файл для чтения")

    private fun integerArg(name: String, default: Int): Int = args.getString(name)?.let {
        it.toIntOrNull() ?: error("$name должен быть целым числом")
    } ?: default

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val QA_PACKAGE = "dev.stanislav.focusdeck.qa"
        const val MAX_BYTES = 512 * 1024
        const val CHUNK_BYTES = 64 * 1024
        val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
    }
}
