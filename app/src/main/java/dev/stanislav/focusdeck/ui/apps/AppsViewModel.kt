package dev.stanislav.focusdeck.ui.apps

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow
import dev.stanislav.focusdeck.ui.restrictions.protectionRestricted
import dev.stanislav.focusdeck.data.repo.AppsRepository
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import dev.stanislav.focusdeck.data.repo.InstalledApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    val activeBlock: FocusBlock? = null,
    internal val protection: ProtectionWindow? = null,
    val selectedWork: Set<String> = emptySet(),
    val selectedStudy: Set<String> = emptySet(),
    val message: String? = null,
    val elapsedRealtime: Long = 0,
)

class AppsViewModel(
    private val appsRepository: AppsRepository,
    private val focusBlockRepository: FocusBlockRepository,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val app = appContext as FocusDeckApp
    private val prefs = appContext.getSharedPreferences("launcher-restrictions", Context.MODE_PRIVATE)
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val work = MutableStateFlow(prefs.getStringSet("work", emptySet()).orEmpty().toSet())
    private val study = MutableStateFlow(prefs.getStringSet("study", emptySet()).orEmpty().toSet())
    private val message = MutableStateFlow<String?>(null)
    private val clock = MutableStateFlow(SystemClock.elapsedRealtime())
    private fun bootCount(): Int = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    val uiState: StateFlow<AppsUiState> = combine(
        apps, focusBlockRepository.observeActive(), work, study, message, clock, app.protectionStore.window,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AppsUiState(values[0] as List<InstalledApp>, values[1] as FocusBlock?, values[6] as ProtectionWindow?,
            values[2] as Set<String>, values[3] as Set<String>, values[4] as String?, values[5] as Long)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    init {
        refresh()
        viewModelScope.launch { while (true) { delay(1_000); clock.value = SystemClock.elapsedRealtime() } }
    }

    fun refresh() { viewModelScope.launch { apps.value = appsRepository.launchableApps() } }

    fun toggle(category: String, packageName: String) {
        require(category in setOf("work", "study"))
        if (safetyExempt(appContext, packageName)) {
            message.value = "Телефон, настройки, домашний экран и восстановление всегда доступны."
            return
        }
        val target = if (category == "work") work else study
        val next = target.value.toMutableSet().apply { if (!add(packageName)) remove(packageName) }.toSet()
        if (prefs.edit().putStringSet(category, next).commit()) target.value = next
        else message.value = "Не удалось сохранить выбор приложений."
    }

    fun isSafetyExempt(packageName: String) = safetyExempt(appContext, packageName)

    fun isRestricted(packageName: String): Boolean {
        if (isSafetyExempt(packageName)) return false
        return protectionRestricted(app.protectionStore.current(), packageName, app.protectionStore.clock())
    }

    /** Re-read Room at the moment of the tap; a stale screen must not extend relief. */
    fun requestOpen(app: InstalledApp, open: (InstalledApp) -> Unit) {
        viewModelScope.launch {
            try {
                val block = focusBlockRepository.observeActive().first()
                if (isRestricted(app.packageName)) {
                    message.value = "Приложение ограничено до конца окна защиты."
                    val window = this@AppsViewModel.app.protectionStore.current()
                    this@AppsViewModel.app.recordAudit("launcher_attempt_denied", if (window?.policyVersion == 2) null else block,
                        SystemClock.uptimeMillis(), windowId = window?.id, resolveActive = false)
                } else { message.value = null; open(app) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message.value = "Не удалось проверить ограничение. Попробуй ещё раз."
            }
        }
    }

    class Factory(
        private val appsRepository: AppsRepository,
        private val focusBlockRepository: FocusBlockRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppsViewModel(appsRepository, focusBlockRepository, context) as T
    }
}
