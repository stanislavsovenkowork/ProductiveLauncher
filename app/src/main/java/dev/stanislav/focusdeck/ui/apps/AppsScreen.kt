package dev.stanislav.focusdeck.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.repo.InstalledApp
import dev.stanislav.focusdeck.ui.components.FocusTextField
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.tabularNums

@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    onOpenApp: (InstalledApp) -> Unit,
    onClose: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("work") }
    val focus = remember { FocusRequester() }
    // Opened from the search row, typing is the point. The keyboard waits until the panel has
    // mostly risen, so the two motions follow each other instead of fighting over the layout.
    LaunchedEffect(autoFocus) { if (autoFocus) { delay(260); runCatching { focus.requestFocus() } } }

    val filteredApps = remember(state.apps, query) {
        state.apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Строка поиска: 52dp, полное скругление, залитый контейнер — по спеке.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(52.dp)
                .clip(FocusRadius.pill)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onClose != null) {
                Box(Modifier.size(32.dp).clip(FocusRadius.pill).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            } else Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            FocusTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Поиск приложений",
                container = false,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selecting = !selecting }) {
                Text(if (selecting) "Готово" else "Выбрать ограничения")
            }
            if (!selecting && state.protection != null) Text("Статус контроля — на главном экране", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == "work", onClick = { category = "work" }, label = { Text("Работа") })
                FilterChip(selected = category == "study", onClick = { category = "study" }, label = { Text("Учёба") })
            }
            Text("Без системной службы ограничение действует только при запуске через FocusDeck. Telegram и почта ограничиваются целиком, не отдельные чаты. Выбор изменяет будущие окна; текущее остаётся прежним. Разрешение службы можно отозвать в Android.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                val exempt = viewModel.isSafetyExempt(app.packageName)
                val selected = !exempt && app.packageName in if (category == "work") state.selectedWork else state.selectedStudy
                val restricted = viewModel.isRestricted(app.packageName)
                AppRow(app = app, selecting = selecting, selected = selected, restricted = restricted,
                    suggested = isSuggested(app, category), exempt = exempt,
                    onClick = { if (selecting) viewModel.toggle(category, app.packageName)
                        else viewModel.requestOpen(app, onOpenApp) })
            }
            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        text = "Ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
    // Registered after the search field: its own back-to-clear-focus must not eat the gesture
    // (the keyboard itself is still closed first by the system).
    if (onClose != null) BackHandler(onBack = onClose)
}

@Composable
private fun AppRow(app: InstalledApp, selecting: Boolean, selected: Boolean, restricted: Boolean, suggested: Boolean, exempt: Boolean, onClick: () -> Unit) {
    // Иконок нет намеренно: список читается как текстовый указатель,
    // строка 56dp, единый левый край с остальными экранами.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FocusRadius.field)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selecting && exempt) Text("Всегда доступно", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selecting && suggested && !exempt) Text("Предложение", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selecting) Checkbox(checked = selected, onCheckedChange = null, enabled = !exempt)
            else Text(
                text = if (restricted) "Ограничено" else if (app.minutesToday > 0) "${app.minutesToday}м" else "—",
                style = MaterialTheme.typography.labelMedium.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Suggestions only: no package is selected or blocked without an explicit choice. */
internal fun isSuggested(app: InstalledApp, category: String): Boolean {
    val name = "${app.packageName} ${app.label}".lowercase()
    val social = listOf("youtube", "instagram", "tiktok", "tik tok").any(name::contains)
    val messaging = listOf("telegram", "outlook").any(name::contains)
    return social || (category == "study" && messaging)
}
