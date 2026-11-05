package dev.stanislav.focusdeck.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromMealType(value: MealType): String = value.name

    @TypeConverter
    fun toMealType(value: String): MealType = MealType.valueOf(value)
}
