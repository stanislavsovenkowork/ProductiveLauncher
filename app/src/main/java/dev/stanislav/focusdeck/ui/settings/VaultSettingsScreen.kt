package dev.stanislav.focusdeck.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.domainfilter.DomainSettingsPanel
import dev.stanislav.focusdeck.domainfilter.DomainAppChoice
import dev.stanislav.focusdeck.ui.restrictions.AppGateService
import dev.stanislav.focusdeck.ui.restrictions.AppGateStatus
import dev.stanislav.focusdeck.ui.restrictions.appGateStatus
import android.content.Intent
import android.Manifest
import android.os.Build
import android.app.NotificationManager
import kotlinx.coroutines.delay
import android.provider.Settings
import dev.stanislav.focusdeck.ui.components.FocusCard
import dev.stanislav.focusdeck.ui.components.ScreenHeader
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors

@Composable
fun VaultSettingsScreen(viewModel: VaultSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var detailsExpanded by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as FocusDeckApp
    val harnessStatus by app.harnessStatus.collectAsState()
    val telemetryStatus by app.telemetrySync.status.collectAsState()
    val telemetryReview by app.telemetrySync.review.collectAsState()
    val vaultUri by app.vaultRepository.vaultUri.collectAsState()
    val auditFailed by app.sessionAudit.failed.collectAsState()
    val gateConnected by AppGateService.connected.collectAsState()
    val gateReady by AppGateService.ready.collectAsState()
    val gateFailed by AppGateService.failure.collectAsState()
    val protection by app.protectionStore.window.collectAsState()
    var clockTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(protection) { while (protection != null) { delay(1_000); clockTick++ } }
    val activeProtection = remember(protection, clockTick) { app.protectionStore.current() != null }
    var domainChoices by remember { mutableStateOf<List<DomainAppChoice>>(emptyList()) }
    LaunchedEffect(app) { domainChoices = app.appsRepository.launchableApps().map { DomainAppChoice(it.packageName, it.label) } }
    val context = LocalContext.current
    var notificationsEnabled by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        app.refreshBreakReminder()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.connect(it) }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportSessions)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(title = "Obsidian", onBack = onBack) }
        item {
            FocusCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.vaultName ?: "Папка не подключена", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picker.launch(null) }) {
                            Text(if (state.connected) "Сменить папку" else "Выбрать папку")
                        }
                        if (state.connected) TextButton(onClick = viewModel::disconnect) { Text("Отключить") }
                    }
                }
            }
        }
        if (state.connected) item {
            FocusCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сегодня", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = viewModel::checkToday, enabled = !state.checking) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Text("Проверить", Modifier.padding(start = 6.dp))
                        }
                    }
                    StatusRow(state)
                }
            }
        }
        item {
            FocusCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("История сессий", style = MaterialTheme.typography.titleMedium)
                    Text(if (auditFailed) "Не удалось сохранить события ограничений." else harnessStatus,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Экспорт без названий задач и текста заметок. Старые неклассифицированные таймеры не входят.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { exportPicker.launch("focusdeck-android.jsonl") }, enabled = !state.exporting) {
                        Text("Сохранить JSONL")
                    }
                    state.exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            FocusCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Контроль приложений", style = MaterialTheme.typography.titleMedium)
                    Text(when (appGateStatus(gateConnected, gateReady, gateFailed)) {
                        AppGateStatus.UNAVAILABLE -> "Служба подключена, но контроль недоступен."
                        AppGateStatus.READY -> "Служба подключена. Проверяются только выбранные приложения."
                        AppGateStatus.INITIALIZING -> "Служба подключена, контроль запускается…"
                        AppGateStatus.DISCONNECTED -> "Системная служба не подключена; действует только ограничение внутри FocusDeck. Проверь разрешение в настройках Android."
                    }, style = MaterialTheme.typography.bodySmall)
                    Text("Определяет открытое приложение, не читает текст или содержимое окон. Телефон, настройки, домашний экран и клавиатура доступны. Сама эта служба сайты не блокирует. Разрешение можно отозвать в Android.",
                        style = MaterialTheme.typography.bodySmall)
                    if (activeProtection) Text("Для выхода из текущей защиты используй действие на главном экране.",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Настройки Android")
                        }
                        if (gateConnected && !activeProtection)
                            TextButton(onClick = AppGateService::disableControl) { Text("Выключить контроль") }
                    }
                }
            }
        }
        item {
            FocusCard(contentPadding = 12.dp) {
                DomainSettingsPanel(controller = app.domainFilter, installedApps = domainChoices,
                    onRequestFullExit = onBack)
            }
        }
        item {
            FocusCard(contentPadding = 12.dp) {
                Column {
                    Text("Напоминание о возвращении", style = MaterialTheme.typography.titleMedium)
                    Text("После перерыва Android может прислать уведомление с задержкой. Оно не возобновляет таймер; разрешение необязательно.", style = MaterialTheme.typography.bodySmall)
                    Text(if (notificationsEnabled) "Уведомления приложения разрешены" else "Уведомления приложения выключены", style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) TextButton(onClick = {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Разрешить") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Настройки Android") }
                    }
                }
            }
        }
        item {
            FocusCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Обмен метриками через Syncthing", style = MaterialTheme.typography.titleMedium)
                    Text("Для обмена выбери папку заметок, которая уже синхронизируется Syncthing. При включении события будут записаны в отдельную папку FocusDeck/Telemetry/v1, без текста заметок и названий задач. Устойчивый ID задачи может быть читаемым и остаётся личными данными. Доставку на Mac обеспечивает Syncthing.",
                        style = MaterialTheme.typography.bodySmall)
                    Text(telemetryStatus, style = MaterialTheme.typography.bodySmall)
                    if (app.telemetrySync.enabled) {
                        val timeLabel: (Double?) -> String = { seconds ->
                            seconds?.let { "${(it / 60).toInt()} мин" } ?: "нет данных"
                        }
                        Text("Сегодня по таймеру: работа — ${timeLabel(telemetryReview.workTimerSeconds)}, учёба — ${timeLabel(telemetryReview.studyTimerSeconds)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(telemetryReview.message, style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        TextButton(onClick = { vaultUri?.let { app.telemetrySync.configure(it) } }, enabled = vaultUri != null) { Text("Включить") }
                        TextButton(onClick = app.telemetrySync::disable) { Text("Выключить") }
                        TextButton(onClick = app.telemetrySync::retry) { Text("Повторить") }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Скрыть формат заметок" else "Формат заметок")
            }
            if (detailsExpanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Дневная заметка: 00 - ГГГГ-ММ-ДД.md в папке или подпапке первого уровня.",
                    "Задачи: чекбоксы в секции «## Задачи».",
                    "Дневник: daily-journal — утро, день, вечер и выводы.",
                    "Итоги дня: строки с пометкой «(телефон)».",
                    "Остальные строки заметки сохраняются."
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.todayNotePath?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StatusRow(state: VaultSettingsState) {
    val (icon, tint, text) = when {
        state.checking -> Triple(Icons.Filled.Refresh, MaterialTheme.colorScheme.onSurfaceVariant, "Читаю…")
        state.todayNoteFound == true -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary,
            "Задач: ${state.todayTaskCount}")
        state.todayNoteFound == false -> Triple(Icons.Filled.Warning, LocalFocusDeckColors.current.attention,
            "Заметка за сегодня не найдена")
        else -> Triple(Icons.Filled.Refresh, MaterialTheme.colorScheme.onSurfaceVariant, "Не проверено")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
