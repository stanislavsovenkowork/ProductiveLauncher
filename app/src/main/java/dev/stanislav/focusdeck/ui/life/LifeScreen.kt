package dev.stanislav.focusdeck.ui.life

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeNoteCodec
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.LocalDate
import kotlinx.coroutines.delay

/** Where the screen is. Depth drives the direction of the transition. */
private enum class LifeRoute(val depth: Int) { OVERVIEW(0), LIST(1), DETAIL(2), EDITOR(3), WIZARD(3), FLOW(3) }

/**
 * Navigation is local; entity drafts and every file mutation belong to LifeViewModel.
 * One compact 48 dp bar, then content. No page titles repeating what the content already says.
 */
@Composable
fun LifeScreen(
    viewModel: LifeViewModel,
    onBack: () -> Unit,
    onOpenPlan: (LocalDate) -> Unit,
    onOpenVault: () -> Unit,
    onOpenNote: (LifeEntry) -> Unit,
    asTab: Boolean = false,
    onRootChanged: (Boolean) -> Unit = {},
) {
    val state = viewModel.uiState.collectAsState().value
    val draft = state.draft
    val selected = state.selected
    val selectedId = state.selectedId
    val kindFilter = state.kindFilter
    val mode = state.draftPurpose
    var editorHidden by rememberSaveable { mutableStateOf(false) }
    var detailTrail by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var createSheet by rememberSaveable { mutableStateOf(false) }
    var createParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var planSheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOpenPlan by rememberUpdatedState(onOpenPlan)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) { viewModel.refresh() }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.openPlanDate) {
        state.openPlanDate?.let { date ->
            // Consume before the parent changes composition; never navigate twice after return.
            viewModel.consumePlanNavigation()
            currentOpenPlan(date)
        }
    }
    LaunchedEffect(draft?.id) { if (draft == null) editorHidden = false }
    LaunchedEffect(state.message) {
        if (state.message != null) { delay(2600); viewModel.clearNotice() }
    }

    val editorVisible = draft != null && !editorHidden
    val route = when {
        editorVisible && draft != null && draft.kind == LifeKind.REVIEW -> LifeRoute.FLOW
        editorVisible && draft != null && state.base == null -> LifeRoute.WIZARD
        editorVisible && mode in listOf("life-review", "review", "complete") -> LifeRoute.FLOW
        editorVisible -> LifeRoute.EDITOR
        selectedId != null -> LifeRoute.DETAIL
        kindFilter != null -> LifeRoute.LIST
        else -> LifeRoute.OVERVIEW
    }
    val routeKey = when (route) {
        LifeRoute.DETAIL -> "detail:$selectedId"
        LifeRoute.LIST -> "list:$kindFilter"
        LifeRoute.EDITOR, LifeRoute.WIZARD, LifeRoute.FLOW -> "${route.name}:${draft?.id}:$mode"
        LifeRoute.OVERVIEW -> "overview"
    }

    val currentRootChanged by rememberUpdatedState(onRootChanged)
    LaunchedEffect(route) { currentRootChanged(route == LifeRoute.OVERVIEW || route == LifeRoute.LIST) }

    fun draftHasContent(entry: LifeEntry) = entry.title.any(Char::isLetterOrDigit) || entry.fields.values.any { it.any(Char::isLetterOrDigit) }
    fun closeEditor() {
        val current = viewModel.uiState.value.draft
        // A wizard left before anything was typed is not a draft worth keeping.
        if (current != null && viewModel.uiState.value.base == null && !draftHasContent(current)) viewModel.discardDraft()
        else editorHidden = true
    }
    val back: () -> Unit = {
        when {
            state.busy -> Unit
            planSheetFor != null -> planSheetFor = null
            editorVisible -> closeEditor()
            selectedId != null -> {
                val previous = detailTrail.lastOrNull()
                if (previous != null) {
                    detailTrail = ArrayList(detailTrail.dropLast(1))
                    viewModel.open(previous)
                } else viewModel.closeDetail()
            }
            kindFilter != null -> viewModel.selectKind(null)
            else -> onBack()
        }
    }
    BackHandler(onBack = back)

    fun openEntry(entry: LifeEntry) {
        if (state.busy) return
        selectedId?.takeIf { it != entry.id }?.let { detailTrail = ArrayList(detailTrail + it) }
        viewModel.open(entry.id)
    }
    fun startEditing(purpose: String = "edit") {
        if (state.busy) return
        editorHidden = false
        if (viewModel.uiState.value.draft != null) return
        when (purpose) {
            "review" -> viewModel.editForDecisionReview()
            "life-review" -> viewModel.editForLifeReview()
            else -> viewModel.edit()
        }
        viewModel.setDraftPurpose(purpose)
    }
    fun create(kind: LifeKind, parent: LifeEntry?) {
        if (state.busy) return
        editorHidden = false
        if (viewModel.uiState.value.draft != null) return
        if (kind == LifeKind.REVIEW) viewModel.beginWeeklyReview() else viewModel.create(kind, parent)
    }
    fun review(entry: LifeEntry) {
        if (state.busy) return
        if (selectedId != entry.id) openEntry(entry)
        startEditing(if (entry.kind == LifeKind.DECISION) "review" else "life-review")
    }

    val headerTitle = when (route) {
        LifeRoute.OVERVIEW -> "Цели и проекты"
        LifeRoute.LIST -> kindFilter?.let(::kindPlural).orEmpty()
        LifeRoute.EDITOR -> if (mode == "activate") "Подготовка к старту" else "Правка"
        else -> ""
    }
    val holder = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (route != LifeRoute.WIZARD && route != LifeRoute.FLOW) {
                LifeTopBar(
                    title = headerTitle,
                    // As a tab the overview is a root: no arrow pointing nowhere.
                    onBack = back.takeUnless { asTab && route == LifeRoute.OVERVIEW },
                    busy = state.busy || state.syncing || state.snapshot.loading,
                    trailing = {
                        if (route == LifeRoute.OVERVIEW || route == LifeRoute.LIST) {
                            IconButton(onClick = { createParentId = null; createSheet = true },
                                enabled = state.snapshot.connected && !state.busy && draft == null) {
                                Icon(Icons.Rounded.Add, "Создать", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                )
            }
            val error = state.error ?: state.snapshot.error
            AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                if (error != null) ErrorNotice(error, state, viewModel, onOpenNote)
            }
            AnimatedVisibility(draft != null && !editorVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                if (draft != null) DraftResumeBar(draft, onResume = { editorHidden = false }, onDiscard = { confirmDiscard = true })
            }
            AnimatedContent(
                targetState = route to routeKey,
                // Routes fill the screen: no size transform, or the keyboard drags them up. See RootScreen.
                transitionSpec = { routeTransition(initialState.first, targetState.first) using null },
                contentKey = { it.second },
                label = "lifeRoute",
                modifier = Modifier.weight(1f),
            ) { (target, key) ->
                holder.SaveableStateProvider(key) {
                    val live = viewModel.uiState.collectAsState().value
                    when (target) {
                        LifeRoute.OVERVIEW -> LifeOverview(
                            state = live,
                            onOpen = ::openEntry,
                            onKind = { detailTrail = arrayListOf(); viewModel.selectKind(it) },
                            onCreate = { kind -> create(kind, null) },
                            onCreateSheet = { createParentId = null; createSheet = true },
                            onPlanNext = { entry, date -> viewModel.planNextStep(entry, date) },
                            onReview = ::review,
                            onWeekly = { previous ->
                                if (!state.busy && viewModel.uiState.value.draft == null) { editorHidden = false; viewModel.beginWeeklyReview(previous) }
                            },
                            onInitAreas = viewModel::initializeAreas,
                            onOpenVault = onOpenVault,
                        )
                        LifeRoute.LIST -> LifeList(
                            state = live, kind = live.kindFilter ?: LifeKind.PROJECT, onOpen = ::openEntry,
                            onShowArchived = viewModel::showArchived,
                            onWeekly = { create(LifeKind.REVIEW, null) },
                        )
                        LifeRoute.DETAIL -> {
                            val entry = live.selected
                            if (entry == null) MissingEntry(onRefresh = viewModel::refresh)
                            else LifeDetail(
                                state = live, entry = entry,
                                actions = DetailActions(
                                    recordMetric = { viewModel.recordMetric(entry, it) },
                                    open = ::openEntry,
                                    edit = { startEditing() },
                                    activate = { startEditing("activate") },
                                    review = { review(entry) },
                                    complete = {
                                        if (entry.kind == LifeKind.GOAL) startEditing("complete")
                                        else viewModel.changeStatus(LifeStatus.DONE)
                                    },
                                    status = viewModel::changeStatus,
                                    postpone = viewModel::postponeReview,
                                    createChild = { kind -> create(kind, entry) },
                                    planNext = { date -> viewModel.planNextStep(entry, date) },
                                    planSheet = { planSheetFor = entry.id },
                                    setNext = { text, date -> viewModel.setNextStep(entry, text, date) },
                                    toggleMilestone = { milestone, done -> viewModel.toggleMilestone(entry, milestone, done) },
                                    addMilestone = { viewModel.addMilestone(entry, it) },
                                    removeMilestone = { viewModel.removeMilestone(entry, it) },
                                    openNote = { onOpenNote(entry) },
                                    openPlan = { date -> onOpenPlan(date) },
                                    weekFacts = viewModel::loadWeekFacts,
                                ),
                            )
                        }
                        LifeRoute.WIZARD -> LifeWizard(state = live, viewModel = viewModel, onClose = ::closeEditor)
                        LifeRoute.EDITOR -> LifeEditor(state = live, viewModel = viewModel, mode = live.draftPurpose,
                            onDiscard = { confirmDiscard = true })
                        LifeRoute.FLOW -> LifeFlow(state = live, viewModel = viewModel, mode = live.draftPurpose,
                            onClose = ::closeEditor, onDiscard = { confirmDiscard = true })
                    }
                }
            }
        }

        NoticeToast(state.message, Modifier.align(Alignment.BottomCenter))
        CelebrationLayer(state.celebration, onDone = viewModel::consumeCelebration)
    }

    if (createSheet) CreateSheet(
        parent = state.snapshot.entry(createParentId),
        onDismiss = { createSheet = false },
        onPick = { kind -> createSheet = false; create(kind, state.snapshot.entry(createParentId)) },
    )
    planSheetFor?.let { id -> state.snapshot.entry(id) }?.let { entry ->
        PlanSheet(entry = entry, onDismiss = { planSheetFor = null }, onConfirm = { title, date, minutes, start ->
            planSheetFor = null
            viewModel.addActionToDay(title, date, minutes, entry, navigate = false, startMinute = start)
        })
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Удалить черновик?") },
        text = { Text("Несохранённый текст пропадёт. Заметка в Obsidian не изменится.") },
        confirmButton = { TextButton(onClick = { viewModel.discardDraft(); confirmDiscard = false }, enabled = !state.busy) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Оставить") } },
    )
}

private fun routeTransition(from: LifeRoute, to: LifeRoute): ContentTransform {
    val slide = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    return when {
        to == LifeRoute.WIZARD || to == LifeRoute.FLOW ->
            (slideInVertically(slide) { it / 6 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.98f)) togetherWith fadeOut(tween(120))
        from == LifeRoute.WIZARD || from == LifeRoute.FLOW ->
            fadeIn(tween(220)) togetherWith (slideOutVertically(slide) { it / 6 } + fadeOut(tween(160)))
        to.depth >= from.depth ->
            (slideInHorizontally(slide) { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(slide) { -it / 8 } + fadeOut(tween(140)))
        else ->
            (slideInHorizontally(slide) { -it / 8 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(slide) { it / 3 } + fadeOut(tween(140)))
    }
}

@Composable
internal fun LifeTopBar(title: String, onBack: (() -> Unit)?, busy: Boolean, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", Modifier.size(22.dp))
            } else Spacer(Modifier.width(4.dp))
            Text(title, Modifier.weight(1f), style = LifeType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
        // Saving is a 2 dp line, not a paragraph pushing the content down.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            androidx.compose.animation.AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
    }
}

@Composable
private fun DraftResumeBar(draft: LifeEntry, onResume: () -> Unit, onDiscard: () -> Unit) {
    val tone = kindTone(draft.kind)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CircleShape).background(tone.container.copy(alpha = 0.6f))
            .clickable(onClick = onResume).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.EditNote, null, tint = tone.onContainer, modifier = Modifier.size(18.dp))
        Text(draft.title.ifBlank { "${kindShort(draft.kind)} без названия" }, style = MaterialTheme.typography.labelLarge,
            color = tone.onContainer, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("продолжить", style = MaterialTheme.typography.labelMedium, color = tone.onContainer.copy(alpha = 0.8f))
        IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Close, "Удалить черновик", tint = tone.onContainer, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ErrorNotice(error: String, state: LifeUiState, viewModel: LifeViewModel, onOpenNote: (LifeEntry) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val deck = LocalFocusDeckColors.current
    val draft = state.draft
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp)).background(deck.attentionContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = deck.onAttentionContainer, modifier = Modifier.size(20.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = deck.onAttentionContainer, modifier = Modifier.weight(1f))
        }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft != null) {
                Pill("Копировать черновик", { clipboard.setText(AnnotatedString(LifeNoteCodec.create(draft))) },
                    container = deck.onAttentionContainer.copy(alpha = 0.16f), content = deck.onAttentionContainer)
                (state.snapshot.entry(draft.id) ?: state.base)?.let { note ->
                    Pill("Obsidian", { onOpenNote(note) }, container = deck.onAttentionContainer.copy(alpha = 0.16f), content = deck.onAttentionContainer)
                }
            }
            Pill("Обновить", viewModel::refresh, container = deck.onAttentionContainer.copy(alpha = 0.16f), content = deck.onAttentionContainer,
                enabled = !state.busy && !state.snapshot.loading)
            if (state.error != null) Pill("Скрыть", viewModel::clearMessage, container = deck.onAttentionContainer.copy(alpha = 0.16f), content = deck.onAttentionContainer)
        }
    }
}

@Composable
private fun NoticeToast(message: String?, modifier: Modifier) {
    AnimatedVisibility(message != null, modifier = modifier.padding(bottom = 24.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Text(message.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun MissingEntry(onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Запись не найдена", style = LifeType.title)
        Text("Возможно, её переименовали в Obsidian", style = LifeType.muted)
        Pill("Обновить", onRefresh)
    }
}

internal fun kindPlural(kind: LifeKind): String = when (kind) {
    LifeKind.AREA -> "Сферы"
    LifeKind.GOAL -> "Цели"
    LifeKind.PROJECT -> "Проекты"
    LifeKind.DECISION -> "Решения"
    LifeKind.REVIEW -> "Обзоры недели"
}
