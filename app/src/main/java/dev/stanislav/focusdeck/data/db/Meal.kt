package dev.stanislav.focusdeck.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MealType { BREAKFAST, LUNCH, SNACK, DINNER }

@Entity(tableName = "meals")
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayEpochDay: Long,
    val type: MealType,
    val name: String,
    val calories: Int,
    val epochMillis: Long,
)
