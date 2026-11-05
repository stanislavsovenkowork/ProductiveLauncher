package dev.stanislav.focusdeck.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weight_entries")
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayEpochDay: Long,
    val kg: Float,
    val epochMillis: Long,
)
