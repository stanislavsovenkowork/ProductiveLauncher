package dev.stanislav.focusdeck.ui.root

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import dev.stanislav.focusdeck.ui.journal.DayStatsViewModel
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.home.NightPlan
import dev.stanislav.focusdeck.ui.life.Celebration
import dev.stanislav.focusdeck.ui.life.CelebrationLayer
import dev.stanislav.focusdeck.ui.ritual.RitualInsights
import dev.stanislav.focusdeck.ui.ritual.RitualPhase
import dev.stanislav.focusdeck.ui.ritual.RitualProjectStep
import dev.stanislav.focusdeck.ui.ritual.ritualWindow
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.ui.life.LifeViewModel
import dev.stanislav.focusdeck.ui.life.LifeScreen
import dev.stanislav.focusdeck.data.repo.InstalledApp
import java.time.LocalDate
import java.time.LocalTime
import dev.stanislav.focusdeck.ui.apps.AppsScreen
import dev.stanislav.focusdeck.ui.apps.AppsViewModel
import dev.stanislav.focusdeck.ui.home.HomeScreen
import dev.stanislav.focusdeck.ui.home.HomeViewModel
import dev.stanislav.focusdeck.ui.journal.JournalEntryScreen
import dev.stanislav.focusdeck.ui.journal.JournalField
import dev.stanislav.focusdeck.ui.journal.JournalScreen
import dev.stanislav.focusdeck.ui.journal.JournalViewModel
import dev.stanislav.focusdeck.ui.nutrition.NutritionScreen
import dev.stanislav.focusdeck.ui.nutrition.NutritionViewModel
import dev.stanislav.focusdeck.ui.planning.PlanningScreen
import dev.stanislav.focusdeck.ui.planning.PlanningViewModel
import dev.stanislav.focusdeck.ui.ritual.RitualScreen
import dev.stanislav.focusdeck.ui.ritual.RitualViewModel
import dev.stanislav.focusdeck.ui.settings.VaultSettingsScreen
import dev.stanislav.focusdeck.ui.settings.VaultSettingsViewModel

private enum class Tab { HOME, PLANNING, LIFE, JOURNAL }

private enum class Overlay { NONE, NUTRITION, JOURNAL_ENTRY, VAULT_SETTINGS }

class RootViewModels(
    val home: HomeViewModel,
    val planning: PlanningViewModel,
    val journal: JournalViewModel,
    val nutrition: NutritionViewModel,
    val apps: AppsViewModel,
    val vaultSettings: VaultSettingsViewModel,
    val ritual: RitualViewModel,
    val life: LifeViewModel,
    val dayStats: DayStatsViewModel? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RootScreen(
    viewModels: RootViewModels,
    usageAccessGranted: Boolean = true,
    onRequestUsageAccess: () -> Unit = {},
    onMandatoryGateChanged: (Boolean) -> Unit = {},
    canPinScreen: Boolean = true,
    restrictionStatus: String = "Ограничение действует только внутри лаунчера. Шторка и последние приложения доступны.",
    onPinScreen: () -> Unit = {},
    onOpenLifeNote: (LifeEntry) -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var planningFormOpen by remember { mutableStateOf(false) }
    // Goals keep the bottom bar only on their overview and lists; a detail or wizard owns the screen.
    var lifeAtRoot by remember { mutableStateOf(true) }
    // Apps are a panel over the home screen, not an overlay that replaces it: home stays laid out
    // underneath, so opening costs no relayout and closing reveals it instantly.
    var appsOpen by remember { mutableStateOf(false) }
    var appsSearch by remember { mutableStateOf(false) }
    val tabScreens = rememberSaveableStateHolder()
    fun openLife() { viewModels.life.refresh(); overlay = Overlay.NONE; tab = Tab.LIFE }
    val ritualScreens = rememberSaveableStateHolder()
    var ritualPlanning by remember { mutableStateOf(false) }
    val ritualState by viewModels.ritual.uiState.collectAsState()
    val planningState by viewModels.planning.uiState.collectAsState()
    val lifeState by viewModels.life.uiState.collectAsState()
    val homeState by viewModels.home.uiState.collectAsState()
    val appsState by viewModels.apps.uiState.collectAsState()
    val context = LocalContext.current
    val gateActive = ritualState.preview
    val ritualVisible = gateActive

    val finishedPhase by viewModels.ritual.finished.collectAsState()
    var ritualCelebration by remember { mutableStateOf<Celebration?>(null) }
    LaunchedEffect(finishedPhase) {
        finishedPhase?.let { phase ->
            ritualCelebration = if (phase == RitualPhase.EVENING) Celebration("День закрыт", "Спокойной ночи", big = true)
                else Celebration("День начат", ritualState.progress?.plan?.priority?.let { "Главное: $it" }.orEmpty(), big = true)
            viewModels.ritual.consumeFinished()
        }
    }
    val insights = run {
        val journalDate = ritualState.window.journalDate
        RitualInsights(
            focusMinutes = homeState.focusMinutesToday.takeIf { journalDate == LocalDate.now() },
            goalSteps = lifeState.activity?.linkedSteps.orEmpty().count { it.done && it.date == journalDate },
            projectSteps = lifeState.snapshot.entries
                .filter { it.kind == LifeKind.PROJECT && it.status == LifeStatus.ACTIVE && !it.archived }
                .mapNotNull { entry ->
                    entry.text(LifeField.NEXT_ACTION).trim().takeIf { it.count(Char::isLetterOrDigit) >= 3 }
                        ?.let { RitualProjectStep(entry.id, entry.title, it) }
                }.take(6),
        )
    }
    // Finishing reflection never hides the current task or the controls for starting work.
    val night: NightPlan? = null

    LaunchedEffect(Unit) { onMandatoryGateChanged(false) }

    LaunchedEffect(gateActive) {
        if (gateActive) {
            tab = Tab.HOME
            overlay = Overlay.NONE
            appsOpen = false
        } else if (ritualPlanning) {
            ritualPlanning = false
            viewModels.planning.selectDate(LocalDate.now())
        }
    }

    // С какого поля открывать дневник: с того, по которому нажали, а при заходе
    // с главного экрана — с подходящего по времени суток.
    var journalField by remember { mutableStateOf(JournalField.forTime(LocalTime.now())) }

    // Registered before any screen content so a screen's own sheets and forms still win.
    // Back from any tab but Сегодня returns to Сегодня; Цели handle it themselves level by level.
    BackHandler(enabled = overlay == Overlay.NONE && !ritualVisible && tab != Tab.HOME && tab != Tab.LIFE) { tab = Tab.HOME }
    // Системная «назад» закрывает оверлей, а не выходит из лаунчера.
    BackHandler(enabled = overlay != Overlay.NONE && !ritualVisible) { overlay = Overlay.NONE }
    BackHandler(enabled = ritualVisible) {
        if (overlay == Overlay.VAULT_SETTINGS || overlay == Overlay.NUTRITION) {
            overlay = Overlay.NONE
            viewModels.ritual.refresh()
        } else if (ritualPlanning) {
            ritualPlanning = false
            viewModels.ritual.refresh()
        } else if (ritualState.preview) {
            viewModels.ritual.closePreview()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (ritualVisible) {
                    if (ritualState.loading) {
                        Text(
                            "Проверяю план и дневник…",
                            modifier = Modifier.padding(top = 28.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (overlay == Overlay.NUTRITION) {
                        NutritionScreen(viewModel = viewModels.nutrition, onBack = {
                            overlay = Overlay.NONE
                            viewModels.ritual.refresh()
                        })
                    } else if (overlay == Overlay.VAULT_SETTINGS) {
                        VaultSettingsScreen(
                            viewModel = viewModels.vaultSettings,
                            onBack = {
                                overlay = Overlay.NONE
                                viewModels.ritual.refresh()
                            },
                        )
                    } else if (ritualPlanning) {
                        Column {
                            IconButton(
                                onClick = {
                                    ritualPlanning = false
                                    viewModels.ritual.refresh()
                                },
                                modifier = Modifier.padding(top = 4.dp).size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (planningState.selectedDate == ritualState.window.planDate) {
                                Box(modifier = Modifier.weight(1f)) {
                                    PlanningScreen(
                                        viewModel = viewModels.planning,
                                        onOpenVaultSettings = { overlay = Overlay.VAULT_SETTINGS },
                                        allowDateSelection = false,
                                    )
                                }
                            } else {
                                Text("Открываю план…", modifier = Modifier.padding(top = 24.dp))
                            }
                        }
                    } else {
                        ritualScreens.SaveableStateProvider(ritualState.window.toString()) {
                            RitualScreen(
                                state = ritualState,
                                onOpenPlan = {
                                    viewModels.planning.selectDate(ritualState.window.planDate, createIfMissing = true)
                                    ritualPlanning = true
                                },
                                onOpenVault = { overlay = Overlay.VAULT_SETTINGS },
                                onChoosePriority = viewModels.ritual::choosePriority,
                                onCarryTask = viewModels.ritual::carryToPlan,
                                onJournalChange = viewModels.ritual::updateJournalDraft,
                                onOpenDraftConflict = viewModels.ritual::openDraftConflict,
                                onResolveDraftConflict = viewModels.ritual::resolveDraftConflict,
                                onDismissDraftConflict = viewModels.ritual::dismissDraftConflict,
                                onIfThenChange = viewModels.ritual::updateIfThenDraft,
                                onOpenFood = {
                                    viewModels.nutrition.selectDate(ritualState.window.journalDate)
                                    overlay = Overlay.NUTRITION
                                },
                                onConfirmFood = viewModels.ritual::confirmFood,
                                restrictionStatus = restrictionStatus,
                                canPinScreen = canPinScreen,
                                onPinScreen = onPinScreen,
                                onFinish = viewModels.ritual::finish,
                                onEmergency = viewModels.ritual::emergencyAccess,
                                onExitRitual = viewModels.ritual::exitCurrentRitual,
                                onClosePreview = viewModels.ritual::closePreview,
                                insights = insights,
                                onLetGo = viewModels.ritual::letGo,
                                onAddProjectStep = { step ->
                                    lifeState.snapshot.entry(step.entryId)?.let { entry ->
                                        viewModels.life.addActionToDay(step.title, ritualState.window.planDate, 30,
                                            source = entry, navigate = false)
                                    }
                                },
                            )
                        }
                    }
                } else AnimatedContent(
                    targetState = overlay,
                    // No size animation on full-screen containers: the keyboard shrinks them every frame,
                    // a lagging size spring overflows the space, and the overflow is centred, so the whole
                    // screen rode up with the keyboard and sprang back after it.
                    transitionSpec = { fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(120)) using null },
                    label = "overlay",
                ) { shownOverlay -> when (shownOverlay) {
                    Overlay.NUTRITION -> NutritionScreen(
                        viewModel = viewModels.nutrition,
                        onBack = { overlay = Overlay.NONE },
                    )
                    Overlay.JOURNAL_ENTRY -> JournalEntryScreen(
                        viewModel = viewModels.journal,
                        initialField = journalField,
                        onDone = { overlay = Overlay.NONE },
                    )
                    Overlay.VAULT_SETTINGS -> VaultSettingsScreen(
                        viewModel = viewModels.vaultSettings,
                        onBack = { overlay = Overlay.NONE },
                    )
                    Overlay.NONE -> AnimatedContent(
                        targetState = tab,
                        transitionSpec = { fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(120)) using null },
                        label = "tab",
                    ) { shown -> tabScreens.SaveableStateProvider(shown.name) { when (shown) {
                        Tab.HOME -> HomeScreen(
                            viewModel = viewModels.home,
                            onOpenNutrition = {
                                viewModels.nutrition.selectDate(LocalDate.now())
                                overlay = Overlay.NUTRITION
                            },
                            onOpenJournal = {
                                journalField = JournalField.forTime(LocalTime.now())
                                overlay = Overlay.JOURNAL_ENTRY
                            },
                            onOpenPlanning = { tab = Tab.PLANNING },
                            onOpenRitual = viewModels.ritual::openEvening,
                            onOpenLife = ::openLife,
                            selectedWork = appsState.selectedWork.count { pkg -> appsState.apps.any { it.packageName == pkg } },
                            selectedStudy = appsState.selectedStudy.count { pkg -> appsState.apps.any { it.packageName == pkg } },
                            activeProjects = lifeState.snapshot.activeProjects.size,
                            reviewsDue = lifeState.snapshot.reviewsDue.size,
                            lifeCard = { dev.stanislav.focusdeck.ui.life.LifeHomeCard(lifeState, ::openLife) },
                            usageAccessGranted = usageAccessGranted,
                            onRequestUsageAccess = onRequestUsageAccess,
                            onOpenApps = { search -> appsSearch = search; appsOpen = true },
                            onOpenSettings = { overlay = Overlay.VAULT_SETTINGS },
                            night = night,
                        )
                        Tab.PLANNING -> PlanningScreen(
                            viewModel = viewModels.planning,
                            onOpenVaultSettings = { overlay = Overlay.VAULT_SETTINGS },
                            onCreationVisibilityChanged = { planningFormOpen = it },
                        )
                        Tab.LIFE -> LifeScreen(
                            viewModel = viewModels.life,
                            onBack = { tab = Tab.HOME },
                            onOpenPlan = { date ->
                                viewModels.planning.selectDate(date)
                                tab = Tab.PLANNING
                            },
                            onOpenVault = { overlay = Overlay.VAULT_SETTINGS },
                            onOpenNote = onOpenLifeNote,
                            asTab = true,
                            onRootChanged = { lifeAtRoot = it },
                        )
                        Tab.JOURNAL -> {
                            val stats = viewModels.dayStats?.stats?.collectAsState()?.value
                            LaunchedEffect(Unit) { viewModels.dayStats?.refresh() }
                            JournalScreen(
                                viewModel = viewModels.journal,
                                onWriteToday = { field ->
                                    journalField = field
                                    overlay = Overlay.JOURNAL_ENTRY
                                },
                                stats = stats,
                                onOpenNutrition = { date ->
                                    viewModels.nutrition.selectDate(date)
                                    overlay = Overlay.NUTRITION
                                },
                            )
                        }
                    } } }
                } }
            }
        }
        if (overlay == Overlay.NONE && !ritualVisible && !planningFormOpen && !WindowInsets.isImeVisible &&
            (tab != Tab.LIFE || lifeAtRoot)) {
            BottomNav(current = tab, onSelect = { if (it == Tab.LIFE && tab != Tab.LIFE) viewModels.life.refresh(); tab = it })
        }
    }
    AnimatedVisibility(
        visible = appsOpen && !ritualVisible && overlay == Overlay.NONE,
        enter = appsEnter,
        exit = appsExit,
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) { detectTapGestures() }
                .padding(horizontal = 16.dp),
        ) {
            AppsScreen(
                viewModel = viewModels.apps,
                onOpenApp = { app -> appsOpen = false; launchApp(context, app) },
                onClose = { appsOpen = false },
                autoFocus = appsSearch,
            )
        }
    }
    CelebrationLayer(ritualCelebration) { ritualCelebration = null }
    }
}

/** The drawer rises from the dock with a soft overshoot-free glide and sinks back into it. */
private val appsGlide = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val dockOrigin = TransformOrigin(0.5f, 1f)
private val appsEnter =
    fadeIn(tween(180)) +
        slideInVertically(tween(380, easing = appsGlide)) { it / 5 } +
        scaleIn(tween(380, easing = appsGlide), initialScale = 0.92f, transformOrigin = dockOrigin)
private val appsExit =
    fadeOut(tween(200, delayMillis = 40)) +
        slideOutVertically(tween(240, easing = FastOutLinearInEasing)) { it / 6 } +
        scaleOut(tween(240, easing = FastOutLinearInEasing), targetScale = 0.94f, transformOrigin = dockOrigin)

private fun launchApp(context: Context, app: InstalledApp) {
    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    context.startActivity(intent)
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        NavItem(
            filled = Icons.Filled.Home,
            outlined = Icons.Outlined.Home,
            label = "Сегодня",
            selected = current == Tab.HOME,
        ) { onSelect(Tab.HOME) }
        NavItem(
            filled = Icons.Filled.EditCalendar,
            outlined = Icons.Outlined.EditCalendar,
            label = "План",
            selected = current == Tab.PLANNING,
        ) { onSelect(Tab.PLANNING) }
        NavItem(
            filled = Icons.Filled.TrackChanges,
            outlined = Icons.Outlined.TrackChanges,
            label = "Цели",
            selected = current == Tab.LIFE,
        ) { onSelect(Tab.LIFE) }
        NavItem(
            filled = Icons.AutoMirrored.Filled.MenuBook,
            outlined = Icons.AutoMirrored.Outlined.MenuBook,
            label = "Дневник",
            selected = current == Tab.JOURNAL,
        ) { onSelect(Tab.JOURNAL) }
    }
}

@Composable
private fun RowScope.NavItem(
    filled: ImageVector,
    outlined: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            // Клип до нажатия — иначе подсветка растекается прямоугольником
            // вместо того, чтобы держаться в границах пилюли.
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) filled else outlined,
                contentDescription = label,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}
