package dev.stanislav.focusdeck.ui.planning

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.components.FocusTextField
import dev.stanislav.focusdeck.ui.theme.FocusRadius

/** Options unfold upward into the editor's existing space, without a window or focus transfer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineProjectPicker(
    projects: List<LifeEntry>,
    selectedProjectId: String?,
    onSelect: (LifeEntry?) -> Unit,
    enabled: Boolean = true,
    showProject: Boolean = true,
    onRestoreEditorFocus: () -> Unit = {},
    editor: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var restoreEditorFocus by rememberSaveable { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    val choosing = expanded && enabled && showProject
    val editorAlpha by animateFloatAsState(if (choosing) 0f else 1f, tween(180), label = "project-editor-fade")
    val arrowAngle by animateFloatAsState(if (choosing) 180f else 0f, tween(280), label = "project-arrow")
    val available = projects.filter { it.status in setOf(LifeStatus.DRAFT, LifeStatus.ACTIVE, LifeStatus.PAUSED) }
    val visible = available.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val current = projects.firstOrNull { it.id == selectedProjectId }
    val title = if (selectedProjectId == null) "Без проекта" else current?.title ?: "Проект недоступен"

    fun close() {
        expanded = false
        query = ""
        // Searching is the only action that can move focus away from the title.
        if (restoreEditorFocus && keyboardVisible) onRestoreEditorFocus()
    }
    BackHandler(enabled = choosing) { close() }

    Column(Modifier.fillMaxWidth()) {
        // Keep both the bounds and the title's text-input connection alive while
        // choosing. A popup or removing the focused editor would dismiss the IME.
        Box(Modifier.fillMaxWidth().height(if (showProject) 120.dp else 48.dp)) {
            Box(Modifier.fillMaxWidth().alpha(editorAlpha)
                .then(if (choosing) Modifier.clearAndSetSemantics { } else Modifier)) {
                editor()
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = choosing,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) +
                    slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(220)),
                exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) +
                    slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(160)),
            ) {
                Column(Modifier.fillMaxWidth().height(120.dp).pointerInput(Unit) {
                    // Padding in the list must not activate invisible editor controls.
                    detectTapGestures { }
                }) {
                    if (available.size > 8) FocusTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Найти проект",
                        container = false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            .semantics { contentDescription = "Поиск проекта" },
                    )
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup()) {
                        item(key = "no-project") {
                            ProjectOption("Без проекта", null, selectedProjectId == null) {
                                onSelect(null)
                                close()
                            }
                        }
                        if (selectedProjectId != null && available.none { it.id == selectedProjectId }) {
                            item(key = "unavailable") {
                                PickerNote(if (current == null) "Текущий проект недоступен"
                                    else "${current.title} · ${current.statusLabel}")
                            }
                        }
                        items(visible, key = { "project:${it.id}" }) { project ->
                            ProjectOption(project.title, if (project.status != LifeStatus.ACTIVE) project.statusLabel else null,
                                selectedProjectId == project.id) {
                                onSelect(project)
                                close()
                            }
                        }
                        if (visible.isEmpty()) item(key = "empty") {
                            PickerNote(if (available.isEmpty()) "Нет доступных проектов" else "Ничего не найдено")
                        }
                    }
                }
            }
        }
        if (showProject) Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(FocusRadius.field)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Выбрать проект") {
                    if (choosing) close() else {
                        restoreEditorFocus = keyboardVisible
                        expanded = true
                    }
                }
                .semantics { contentDescription = "Проект задачи" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp).rotate(arrowAngle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(FocusRadius.field)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PickerNote(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
