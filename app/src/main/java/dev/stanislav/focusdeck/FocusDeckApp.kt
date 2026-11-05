package dev.stanislav.focusdeck

import android.app.Application
import android.util.AtomicFile
import android.util.Log
import dev.stanislav.focusdeck.domainfilter.DomainFilterController
import dev.stanislav.focusdeck.domainfilter.DomainAuditSink
import dev.stanislav.focusdeck.data.repo.FocusEventExporter
import dev.stanislav.focusdeck.data.repo.SessionAudit
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.sync.TelemetrySyncClient
import dev.stanislav.focusdeck.ui.home.BreakReminder
import dev.stanislav.focusdeck.ui.apps.reliefRemainingMillis
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindowStore
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow
import dev.stanislav.focusdeck.ui.restrictions.remaining
import dev.stanislav.focusdeck.ui.restrictions.reliefRemaining
import dev.stanislav.focusdeck.ui.restrictions.windowActive
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.room.Room
import dev.stanislav.focusdeck.data.life.LifeRepository
import dev.stanislav.focusdeck.data.db.FocusDeckDatabase
import dev.stanislav.focusdeck.data.repo.AppsRepository
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import dev.stanislav.focusdeck.data.repo.UsageStatsRepository
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultRepository

class FocusDeckApp : Application() {
    val processId: String = java.util.UUID.randomUUID().toString()
    private val harnessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class AuditRequest(val reason: String, val block: FocusBlock?, val uptime: Long?,
                                    val occurredAt: Instant, val oncePerSession: Boolean, val windowId: String?,
                                    val resolveActive: Boolean)
    private val auditQueue = Channel<AuditRequest>(Channel.UNLIMITED)
    private val _harnessStatus = MutableStateFlow("Подготовка локальных событий…")
    val harnessStatus: StateFlow<String> = _harnessStatus
    internal lateinit var protectionStore: ProtectionWindowStore
        private set
    lateinit var domainFilter: DomainFilterController
        private set
    lateinit var sessionAudit: SessionAudit
        private set
    lateinit var telemetrySync: TelemetrySyncClient
        private set

    lateinit var database: FocusDeckDatabase
        private set

    lateinit var focusBlockRepository: FocusBlockRepository
        private set

    lateinit var usageStatsRepository: UsageStatsRepository
        private set

    lateinit var nutritionRepository: NutritionRepository
        private set

    lateinit var appsRepository: AppsRepository
        private set

    lateinit var vaultRepository: VaultRepository
        private set

    /**
     * Заметка дня — одна на всё приложение. Экземпляр здесь, а не по одному на экран:
     * это один файл, и расхождение в том, какая версия текста считается текущей,
     * означало бы потерянные правки.
     */
    lateinit var lifeRepository: LifeRepository
        private set

    lateinit var dayStore: VaultDayStore
        private set

    fun recordAudit(reason: String, block: FocusBlock? = null, uptimeMillis: Long? = null,
                    oncePerSession: Boolean = false, windowId: String? = null, resolveActive: Boolean = true) {
        auditQueue.trySend(AuditRequest(reason, block, uptimeMillis, Instant.now(), oncePerSession, windowId, resolveActive))
    }

    internal fun auditContext(window: ProtectionWindow): FocusBlock? = window.sessionId?.let {
        FocusBlock(title = "", plannedMinutes = 25, startEpochMillis = window.startWall, dayEpochDay = 0,
            category = window.category, sessionId = it, taskStableId = window.taskRef)
    }

    fun refreshBreakReminder() {
        harnessScope.launch { runCatching { BreakReminder.update(this@FocusDeckApp, focusBlockRepository.observeActive().first()) } }
    }

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, FocusDeckDatabase::class.java, "focusdeck.db")
            // Неизвестная старая схема должна остановить открытие, а не стереть еду/вес/историю.
            .addMigrations(FocusDeckDatabase.MIGRATION_6_7, FocusDeckDatabase.MIGRATION_7_8, FocusDeckDatabase.MIGRATION_8_9)
            .build()
        protectionStore = ProtectionWindowStore(this)
        sessionAudit = SessionAudit(this)
        domainFilter = DomainFilterController(this, DomainAuditSink { event ->
            recordAudit(event.reason, resolveActive = false)
        })
        telemetrySync = TelemetrySyncClient(this)
        focusBlockRepository = FocusBlockRepository(database.focusBlockDao(), sessionAudit)
        harnessScope.launch {
            focusBlockRepository.observeActive().collectLatest { block ->
                runCatching { BreakReminder.update(this@FocusDeckApp, block) }
            }
        }
        harnessScope.launch {
            for (request in auditQueue) {
                val current = request.block ?: if (request.resolveActive) runCatching { focusBlockRepository.observeActive().first() }.getOrNull() else null
                sessionAudit.record(request.reason, current, request.oncePerSession, request.uptime, request.occurredAt, request.windowId)
            }
        }
        // Room is authoritative. Rebuild an atomic snapshot on every change and after process restart.
        harnessScope.launch {
            combine(focusBlockRepository.observeForExport(), sessionAudit.revision) { blocks, _ -> blocks }.collectLatest { blocks ->
                val directory = File(filesDir, "harness")
                if (!directory.isDirectory && !directory.mkdirs()) {
                    _harnessStatus.value = "Не удалось сохранить локальные события."
                    Log.e("FocusDeckHarness", "Cannot create local event directory")
                    return@collectLatest
                }
                val target = AtomicFile(File(directory, "events.jsonl"))
                var output: java.io.FileOutputStream? = null
                try {
                    output = target.startWrite()
                    val snapshot = FocusEventExporter.jsonl(blocks) + sessionAudit.snapshot()
                    output.write(snapshot.toByteArray(Charsets.UTF_8))
                    target.finishWrite(output)
                    runCatching { telemetrySync.updateSnapshot(snapshot) }
                    _harnessStatus.value = "Локальные события обновлены"
                } catch (error: Exception) {
                    if (output != null) target.failWrite(output)
                    _harnessStatus.value = "Не удалось сохранить локальные события."
                    Log.e("FocusDeckHarness", "Local event snapshot failed", error)
                }
            }
        }
        harnessScope.launch {
            protectionStore.window.collectLatest { window ->
                var reliefReported = false
                var startReported = false
                if (window != null) while (true) {
                    protectionStore.current() // Re-anchor an exit request after a reboot.
                    val now = protectionStore.clock()
                    if (window.policyVersion == 2 && !startReported && windowActive(window, now)) {
                        recordAudit("window_started", auditContext(window), windowId = window.id, resolveActive = false)
                        startReported = true
                    }
                    if (!reliefReported && window.reliefWall != null && reliefRemaining(window, now) == 0L) {
                        recordAudit(if (window.reliefEndedEarly) "relief_ended_early" else "bounded_relief_expired",
                            auditContext(window), windowId = window.id, resolveActive = false)
                        reliefReported = true
                    }
                    if (remaining(window, now) == 0L) {
                        recordAudit("protection_expired", auditContext(window), windowId = window.id, resolveActive = false)
                        break
                    }
                    delay(1_000)
                }
            }
        }
        usageStatsRepository = UsageStatsRepository(this)
        nutritionRepository = NutritionRepository(this, database.mealDao(), database.weightDao())
        appsRepository = AppsRepository(this, usageStatsRepository)
        vaultRepository = VaultRepository(this)
        dayStore = VaultDayStore(vaultRepository)
        lifeRepository = LifeRepository(this, vaultRepository)
    }
}
