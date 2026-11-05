package dev.stanislav.focusdeck.ui.settings

import android.net.Uri
import android.content.Context
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.vault.VaultRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VaultSettingsState(
    val connected: Boolean = false,
    val vaultName: String? = null,
    val checking: Boolean = false,
    /** Что реально удалось прочитать — честный признак, что интеграция работает. */
    val todayNoteFound: Boolean? = null,
    val todayTaskCount: Int = 0,
    val todayNotePath: String? = null,
    val exportStatus: String? = null,
    val exporting: Boolean = false,
)

class VaultSettingsViewModel(private val repository: VaultRepository, private val focus: FocusBlockRepository, private val context: Context) : ViewModel() {

    private val _state = MutableStateFlow(
        VaultSettingsState(
            connected = repository.isConnected,
            vaultName = repository.vaultName(),
        ),
    )
    val state: StateFlow<VaultSettingsState> = _state.asStateFlow()

    init {
        if (repository.isConnected) checkToday()
    }

    fun connect(uri: Uri) {
        if (repository.vaultUri.value != uri)
            (context.applicationContext as dev.stanislav.focusdeck.FocusDeckApp).telemetrySync.disable()
        repository.connect(uri)
        _state.value = _state.value.copy(
            connected = true,
            vaultName = repository.vaultName(),
        )
        checkToday()
    }

    fun disconnect() {
        (context.applicationContext as dev.stanislav.focusdeck.FocusDeckApp).telemetrySync.disable()
        repository.disconnect()
        _state.value = VaultSettingsState()
    }

    /** Пробует прочитать сегодняшнюю заметку — иначе «подключено» ничего не значит. */
    fun checkToday() {
        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true)
            val note = repository.readDailyNote(LocalDate.now())
            _state.value = _state.value.copy(
                checking = false,
                todayNoteFound = note != null,
                todayTaskCount = note?.tasks?.size ?: 0,
                todayNotePath = note?.path,
            )
        }
    }

    fun exportSessions(uri: Uri) {
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exporting = true, exportStatus = null)
            try {
                val data = focus.exportJsonl()
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Файл недоступен" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                }
                _state.value = _state.value.copy(exportStatus = "Экспорт сохранён")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = _state.value.copy(exportStatus = "Не удалось сохранить экспорт. Попробуй ещё раз.")
            } finally {
                _state.value = _state.value.copy(exporting = false)
            }
        }
    }

    class Factory(private val repository: VaultRepository, private val focus: FocusBlockRepository, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VaultSettingsViewModel(repository, focus, context.applicationContext) as T
    }
}
