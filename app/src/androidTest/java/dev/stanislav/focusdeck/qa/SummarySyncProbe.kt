package dev.stanislav.focusdeck.qa

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.db.FocusDeckDatabase
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import dev.stanislav.focusdeck.data.repo.UsageStatsRepository
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.DailySummary
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultRepository
import dev.stanislav.focusdeck.ui.home.HomeViewModel
import dev.stanislav.focusdeck.ui.theme.launcherColors
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Test APK only. main must synchronously execute its callback on the main thread.
 * No UI, manual summary refresh, real database or real outbox is used.
 * The one root-level yesterday fixture is restored byte-for-byte (or deleted if new).
 */
internal object SummarySyncProbe {
    private const val QA_ROOT = "FocusDeck-Life-QA-20260929"
    private const val MAX_BYTES = 512 * 1024

    fun run(context: Context, main: (() -> Unit) -> Unit): Bundle = runBlocking {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run probe off the main thread" }
        check(context.packageName == "dev.stanislav.focusdeck.qa") { "QA package only" }
        val uri = Uri.parse(context.getSharedPreferences("vault", Context.MODE_PRIVATE)
            .getString("vault_tree_uri", null) ?: error("Connect the QA vault first"))
        check(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.isTreeUri(uri)) { "Expected external-storage SAF tree" }
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        check(treeId.substringAfter(':').split('/').last() == QA_ROOT &&
            !treeId.contains("DB1", ignoreCase = true)) { "Only the dedicated QA root is allowed" }
        check(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }) { "QA SAF read/write grant missing" }
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("QA root unavailable")
        check(root.isDirectory && root.name == QA_ROOT) { "Unexpected QA root" }

        val token = UUID.randomUUID().toString()
        val prefix = "qa_summary_probe_${token}_"
        val databaseName = "${prefix}disposable.db"
        val privateFiles = File(context.filesDir, prefix)
        val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = privateFiles.apply { check(exists() || mkdirs()) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name
                preferenceNames.add(scoped)
                return context.getSharedPreferences(scoped, mode)
            }
        }
        val store = ViewModelStore()
        var db: FocusDeckDatabase? = null
        var modelJob: Job? = null
        var observer: Job? = null
        var fixture: DocumentFile? = null
        var original: ByteArray? = null
        var created = false
        var fixtureTouched = false
        var stage = "setup"
        val result = Bundle()
        fun read(file: DocumentFile): ByteArray = context.contentResolver.openInputStream(file.uri)?.use {
            val bytes = it.readBytesLimited()
            bytes
        } ?: error("Cannot read QA fixture")
        fun write(file: DocumentFile, bytes: ByteArray) {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
                ?: error("Cannot write QA fixture")
            check(read(file).contentEquals(bytes)) { "QA fixture write verification failed" }
        }
        try {
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val fileName = DailyNoteParser.fileNameFor(yesterday)
            val matching = root.listFiles().filter { it.name == fileName }
            check(matching.size <= 1) { "Ambiguous QA fixture name" }
            val existing = matching.singleOrNull()
            if (existing != null) {
                check(existing.isFile) { "QA fixture path is a directory" }
                original = read(existing)
                check(privateFiles.exists() || privateFiles.mkdirs())
                File(privateFiles, "original-daily.md").outputStream().use {
                    it.write(checkNotNull(original))
                    it.fd.sync()
                }
                fixture = existing
            } else {
                fixture = root.createFile("text/markdown", fileName) ?: error("Cannot create QA fixture")
                created = true
                check(fixture.name == fileName || fixture.renameTo(fileName)) { "Provider changed fixture name" }
            }
            val file = checkNotNull(fixture)
            val marker = "<!-- summary-probe-$token -->"
            val template = DailyNoteParser.parse(yesterday, fileName, DailyNoteParser.template(yesterday))
            val seed = DailyNoteParser.writeSummary(template, DailySummary(0, 999, 0, 0, 0, 999, 2200)) + "\n$marker\n"
            fixtureTouched = true
            write(file, seed.toByteArray(Charsets.UTF_8))
            check(isolated.getSharedPreferences("vault", Context.MODE_PRIVATE).edit()
                .putString("vault_tree_uri", uri.toString()).commit())
            val database = Room.databaseBuilder(isolated, FocusDeckDatabase::class.java, databaseName).build()
            db = database
            val focus = FocusBlockRepository(database.focusBlockDao())
            val nutrition = NutritionRepository(isolated, database.mealDao(), database.weightDao())
            val usage = UsageStatsRepository(isolated)
            val vault = VaultRepository(isolated)
            val dayStore = VaultDayStore(vault)
            check(dayStore.rootIdentity == uri.toString())
            // A timer that started yesterday remains unfinished across midnight.
            val start = yesterday.atTime(23, 50).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val blockId = focus.start(FocusBlock(title = "QA yesterday timer", plannedMinutes = 10,
                startEpochMillis = start, dayEpochDay = yesterday.toEpochDay()))
            lateinit var model: HomeViewModel
            main {
                model = ViewModelProvider(store, HomeViewModel.Factory(launcherColors, dayStore,
                    focus, usage, nutrition, isolated))[HomeViewModel::class.java]
                modelJob = model.viewModelScope.coroutineContext[Job]
            }
            observer = launch { model.uiState.collect { } }
            val outbox = isolated.getSharedPreferences("home-summary-outbox", Context.MODE_PRIVATE)
            suspend fun awaitSummary(calories: Int, minutes: Int) {
                withTimeout(20_000) {
                    while (true) {
                        check(LocalDate.now() == today) { "Date rolled over during probe; rerun" }
                        val text = read(file).toString(Charsets.UTF_8)
                        check(marker in text) { "Fixture replaced externally; stop probe" }
                        val expected = DailyNoteParser.summaryLines(DailySummary(0, minutes, 0, 0, 0,
                            calories, nutrition.calorieTarget.value))
                        // Real usage permission may exist: do not assert screen-time zero.
                        if (expected.drop(1).all { it in text.lines() } &&
                            outbox.getStringSet("pending", emptySet()).orEmpty().isEmpty() &&
                            !model.uiState.value.summaryBusy && model.uiState.value.summaryStatus == null) break
                        delay(100)
                    }
                }
            }
            stage = "initial_zero_summary"
            awaitSummary(0, 0)
            stage = "late_yesterday_meal"
            nutrition.addMeal(yesterday.toEpochDay(), MealType.DINNER, "QA late meal", 520)
            awaitSummary(520, 0)
            stage = "finish_yesterday_timer"
            withTimeout(10_000) { while (model.uiState.value.activeBlock?.id != blockId) delay(50) }
            main { model.finishActiveBlock() }
            val finished = withTimeout(10_000) {
                focus.observeForDay(yesterday.toEpochDay()).first { blocks ->
                    blocks.any { it.id == blockId && it.endEpochMillis != null }
                }.single { it.id == blockId }
            }
            val minutes = ((checkNotNull(finished.endEpochMillis) - finished.startEpochMillis) / 60_000).toInt()
            check(minutes > 0)
            awaitSummary(520, minutes)
            result.putString("status", "ok")
            result.putString("text", "Initial zero summary; late yesterday meal 520; finished yesterday timer; persisted outbox empty")
            result.putInt("focusMinutes", minutes)
            result.putBoolean("usagePermission", usage.hasPermission())
        } catch (failure: Exception) {
            result.putString("status", "error")
            result.putString("text", "$stage: ${failure.javaClass.simpleName}")
        } finally {
            val cleanupErrors = mutableListOf<String>()
            var stopped = false
            try {
                observer?.cancelAndJoin()
                main { store.clear() }
                withTimeout(15_000) { modelJob?.join() }
                stopped = true
            } catch (_: Exception) { cleanupErrors += "model_stop" }
            // Never restore a fixture while this model may still write to it.
            if (stopped) {
                try { db?.close(); check(context.deleteDatabase(databaseName)) }
                catch (_: Exception) { cleanupErrors += "database_cleanup" }
                try {
                    val file = fixture
                    if (file != null) {
                        if (created) check(file.delete())
                        else if (fixtureTouched) write(file, checkNotNull(original))
                    }
                } catch (_: Exception) { cleanupErrors += "fixture_restore" }
                for (name in preferenceNames.toList()) {
                    try { check(context.deleteSharedPreferences(name)) }
                    catch (_: Exception) { cleanupErrors += "preferences_cleanup" }
                }
                if ("fixture_restore" !in cleanupErrors) {
                    try { if (privateFiles.exists()) check(privateFiles.deleteRecursively()) }
                    catch (_: Exception) { cleanupErrors += "private_backup_cleanup" }
                }
            }
            if (cleanupErrors.isNotEmpty()) {
                result.putString("status", "error")
                result.putString("cleanup", cleanupErrors.joinToString(","))
                if (privateFiles.exists()) result.putString("recoveryDirectory", privateFiles.absolutePath)
            } else result.putString("cleanup", "restored")
        }
        result
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            check(output.size() + count <= MAX_BYTES) { "QA fixture exceeds 512 KiB" }
            output.write(buffer, 0, count)
        }
    }
}
