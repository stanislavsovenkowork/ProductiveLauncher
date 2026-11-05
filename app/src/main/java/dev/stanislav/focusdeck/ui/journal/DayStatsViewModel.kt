package dev.stanislav.focusdeck.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import dev.stanislav.focusdeck.data.repo.focusMillis
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import dev.stanislav.focusdeck.data.repo.UsageStatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** What the day left behind besides words: food, weight, focus and screen time. */
data class DayStats(
    val date: LocalDate = LocalDate.now(),
    val calories: Int = 0,
    val meals: Int = 0,
    val weightKg: Float? = null,
    /** True when the weight was recorded on this very day, not carried from earlier. */
    val weightToday: Boolean = false,
    val focusMinutes: Int = 0,
    val focusBlocks: Int = 0,
    val screenMinutes: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DayStatsViewModel(
    selectedDate: StateFlow<LocalDate>,
    nutrition: NutritionRepository,
    focusBlocks: FocusBlockRepository,
    usage: UsageStatsRepository,
) : ViewModel() {
    private val refreshTick = MutableStateFlow(0)

    val stats: StateFlow<DayStats> = combine(selectedDate, refreshTick) { date, _ -> date }.flatMapLatest { date ->
        val day = date.toEpochDay()
        combine(
            nutrition.observeMealsForDay(day),
            nutrition.observeRecentWeights(60),
            focusBlocks.observeForDay(day),
            flow {
                emit(null)
                if (usage.hasPermission() && date <= LocalDate.now()) emit(usage.screenTimeMillisOn(date) / 60_000)
            },
        ) { meals, weights, blocks, screen ->
            val weight = weights.filter { it.dayEpochDay <= day }.maxByOrNull { it.epochMillis }
            val now = System.currentTimeMillis()
            DayStats(
                date = date,
                calories = meals.sumOf { it.calories },
                meals = meals.size,
                weightKg = weight?.kg,
                weightToday = weight?.dayEpochDay == day,
                focusMinutes = blocks.sumOf { it.focusMillis(now) / 60_000 }.toInt(),
                focusBlocks = blocks.size,
                screenMinutes = screen,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayStats())

    /** Screen time is read on demand; call when the tab becomes visible again. */
    fun refresh() { refreshTick.value++ }

    class Factory(
        private val selectedDate: StateFlow<LocalDate>,
        private val nutrition: NutritionRepository,
        private val focusBlocks: FocusBlockRepository,
        private val usage: UsageStatsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DayStatsViewModel(selectedDate, nutrition, focusBlocks, usage) as T
    }
}
