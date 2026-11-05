package dev.stanislav.focusdeck.data.repo

import android.content.Context
import dev.stanislav.focusdeck.data.db.DailyCalories
import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.db.MealDao
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.db.WeightDao
import dev.stanislav.focusdeck.data.db.WeightEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NutritionRepository(
    context: Context,
    private val mealDao: MealDao,
    private val weightDao: WeightDao,
) {
    // Цель по калориям — одно число на всё приложение, ради него незачем заводить таблицу.
    private val prefs = context.getSharedPreferences("nutrition", Context.MODE_PRIVATE)

    private val _calorieTarget = MutableStateFlow(prefs.getInt(KEY_TARGET, DEFAULT_TARGET))
    val calorieTarget: StateFlow<Int> = _calorieTarget.asStateFlow()

    fun setCalorieTarget(value: Int) {
        val clamped = value.coerceIn(800, 6000)
        prefs.edit().putInt(KEY_TARGET, clamped).apply()
        _calorieTarget.value = clamped
    }

    fun observeDailyTotals(fromDay: Long, toDay: Long): Flow<List<DailyCalories>> =
        mealDao.observeDailyTotals(fromDay, toDay)

    fun observeMealsForDay(dayEpochDay: Long): Flow<List<Meal>> = mealDao.observeForDay(dayEpochDay)

    fun observeRecentWeights(limit: Int = 30): Flow<List<WeightEntry>> = weightDao.observeRecent(limit)

    suspend fun addMeal(dayEpochDay: Long, type: MealType, name: String, calories: Int) =
        mealDao.insert(Meal(dayEpochDay = dayEpochDay, type = type, name = name, calories = calories, epochMillis = System.currentTimeMillis()))

    suspend fun addMealOnce(reserved: Meal): Meal = mealDao.insertReserved(reserved)

    suspend fun updateMeal(meal: Meal) = mealDao.update(meal)

    suspend fun deleteMeal(meal: Meal) = mealDao.delete(meal)

    suspend fun addWeight(dayEpochDay: Long, kg: Float) =
        weightDao.insert(WeightEntry(dayEpochDay = dayEpochDay, kg = kg, epochMillis = System.currentTimeMillis()))

    suspend fun addWeightOnce(reserved: WeightEntry): WeightEntry = weightDao.insertReserved(reserved)

    private companion object {
        const val KEY_TARGET = "calorie_target"
        const val DEFAULT_TARGET = 2200
    }
}
