package dev.stanislav.focusdeck

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.ui.life.LifeViewModel
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.stanislav.focusdeck.ui.apps.AppsViewModel
import dev.stanislav.focusdeck.ui.home.HomeViewModel
import dev.stanislav.focusdeck.ui.journal.JournalViewModel
import dev.stanislav.focusdeck.ui.nutrition.NutritionViewModel
import dev.stanislav.focusdeck.ui.planning.PlanningViewModel
import dev.stanislav.focusdeck.ui.ritual.RitualViewModel
import dev.stanislav.focusdeck.ui.root.RootScreen
import dev.stanislav.focusdeck.ui.root.RootViewModels
import dev.stanislav.focusdeck.ui.settings.VaultSettingsViewModel
import dev.stanislav.focusdeck.ui.theme.FocusDeckTheme
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private var externalNoteRefresh: Job? = null

    private var homeResetCount by mutableIntStateOf(0)
    // Keep listening while the launcher is behind the lock screen or another app.
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                resetHome()
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    ritualViewModel.refresh()
                    homeViewModel.refreshDate()
                }
            }
        }
    }

    private fun resetHome() {
        homeResetCount++
        ritualViewModel.closePreview()
        lifecycleScope.launch { app.dayStore.resetToToday() }
    }

    private var restrictionStatus by mutableStateOf(
        "Ограничение внутри лаунчера: шторка и последние приложения доступны.",
    )
    private var canPinScreen by mutableStateOf(true)
    private val restrictionHost by lazy {
        dev.stanislav.focusdeck.ui.restrictions.RitualRestrictionHost(this) { text, mode ->
            restrictionStatus = text
            canPinScreen = mode == dev.stanislav.focusdeck.ui.restrictions.RestrictionController.Mode.NONE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) restrictionHost.onFocus()
    }

    private var usageAccessGranted by mutableStateOf(true)

    private val app get() = application as FocusDeckApp


    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            dev.stanislav.focusdeck.ui.theme.launcherColors,
            app.dayStore,
            app.focusBlockRepository,
            app.usageStatsRepository,
            app.nutritionRepository,
            applicationContext,
        )
    }
    private val lifeViewModel: LifeViewModel by viewModels {
        LifeViewModel.Factory(app.lifeRepository, app.dayStore, this)
    }
    private val planningViewModel: PlanningViewModel by viewModels {
        PlanningViewModel.Factory(app.dayStore, app.lifeRepository)
    }
    private val journalViewModel: JournalViewModel by viewModels {
        JournalViewModel.Factory(app.dayStore, applicationContext)
    }
    private val nutritionViewModel: NutritionViewModel by viewModels {
        NutritionViewModel.Factory(app.nutritionRepository, applicationContext)
    }
    private val appsViewModel: AppsViewModel by viewModels {
        AppsViewModel.Factory(app.appsRepository, app.focusBlockRepository, applicationContext)
    }
    private val vaultSettingsViewModel: VaultSettingsViewModel by viewModels {
        VaultSettingsViewModel.Factory(app.vaultRepository, app.focusBlockRepository, applicationContext)
    }
    private val dayStatsViewModel: dev.stanislav.focusdeck.ui.journal.DayStatsViewModel by viewModels {
        dev.stanislav.focusdeck.ui.journal.DayStatsViewModel.Factory(
            app.dayStore.selectedDate, app.nutritionRepository, app.focusBlockRepository, app.usageStatsRepository,
        )
    }
    private val ritualViewModel: RitualViewModel by viewModels {
        RitualViewModel.Factory(app.dayStore, app.nutritionRepository, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            FocusDeckTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootWithReset(resetSignal = homeResetCount)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        externalNoteRefresh?.cancel()
        externalNoteRefresh = lifecycleScope.launch {
            var reads = 0
            // SAF does not reliably notify us when Syncthing replaces a file.
            // Read only the displayed day (+ today), never scan the whole vault.
            while (true) {
                delay(2_000)
                if (window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) continue
                try {
                    app.dayStore.refresh()
                    // Project/goal cards use separate notes. Keep those current too,
                    // without the full activity-history scan or a flashing spinner.
                    if (++reads % 3 == 0) app.lifeRepository.refresh(quiet = true)
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Existing read state/retry handles a temporarily unavailable grant. */ }
            }
        }
        QaScreenControl.update(this, resumed = true)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) resetHome()
        // Доступ к статистике выдаётся вручную в системных настройках — проверяем при каждом возврате.
        usageAccessGranted = app.usageStatsRepository.hasPermission()
        app.refreshBreakReminder()
        appsViewModel.refresh()
        lifeViewModel.refresh()
        ritualViewModel.refresh()
        homeViewModel.refreshDate()
        // Процесс лаунчера живёт неделями, а выбранный день — состояние экрана, а не
        // настройка. Если за время в фоне наступила полночь, вчерашний выбор уже врёт.
        // Будущий день при коротком уходе в другое приложение сохраняем: человек его
        // выбрал только что и вернулся дочитать.
        lifecycleScope.launch {
            if (app.dayStore.selectedDate.value < LocalDate.now()) {
                app.dayStore.resetToToday()
                // Сутки сменились, пока мы были в фоне, — вчерашний день закрыт,
                // и его итог пора дописать. В init это не повторится: процесс
                // лаунчера переживает и полночь, и неделю.
                homeViewModel.writeYesterdaySummary()
            }
            // Пока приложение было в фоне, заметку мог изменить Obsidian, Mac или синхронизация.
            homeViewModel.refreshNote()
        }
    }

    override fun onPause() {
        externalNoteRefresh?.cancel(); externalNoteRefresh = null
        QaScreenControl.update(this, resumed = false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        QaScreenControl.update(this, resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        resetHome()
        ritualViewModel.refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(lockReceiver)
        super.onDestroy()
    }

    private fun openLifeNote(entry: LifeEntry) {
        val root = app.dayStore.rootIdentity
        lifecycleScope.launch {
            try {
                check(root != null && (entry.sourceRoot.isBlank() || entry.sourceRoot == root)) {
                    "Запись относится к другой папке Obsidian"
                }
                val vaultName = withContext(Dispatchers.IO) { app.vaultRepository.vaultName() }
                    ?: error("Подключи папку Obsidian")
                check(app.dayStore.rootIdentity == root) { "Папка изменилась. Открой запись заново." }
                val uri = Uri.Builder().scheme("obsidian").authority("open")
                    .appendQueryParameter("vault", vaultName)
                    .appendQueryParameter("file", entry.relativePath.removeSuffix(".md"))
                    .build()
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("md.obsidian"))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(this@MainActivity,
                    if (error is android.content.ActivityNotFoundException) "Открой эту папку как хранилище в Obsidian"
                    else error.message ?: "Не удалось открыть заметку в Obsidian", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    @Composable
    private fun RootWithReset(resetSignal: Int) {
        key(resetSignal) {
            RootScreen(
                viewModels = RootViewModels(
                    home = homeViewModel,
                    planning = planningViewModel,
                    journal = journalViewModel,
                    nutrition = nutritionViewModel,
                    apps = appsViewModel,
                    vaultSettings = vaultSettingsViewModel,
                    ritual = ritualViewModel,
                    life = lifeViewModel,
                    dayStats = dayStatsViewModel,
                ),
                usageAccessGranted = usageAccessGranted,
                onRequestUsageAccess = { openUsageAccessSettings() },
                onMandatoryGateChanged = restrictionHost::update,
                restrictionStatus = restrictionStatus,
                canPinScreen = canPinScreen,
                onPinScreen = restrictionHost::pinExplicitly,
                onOpenLifeNote = ::openLifeNote,
            )
        }
    }
}
