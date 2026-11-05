package dev.stanislav.focusdeck.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_blocks")
data class FocusBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val plannedMinutes: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null,
    val interrupted: Boolean = false,
    val dayEpochDay: Long,
    /** Null root means an unbound/free or pre-v7 timer; never infer a task from its title. */
    val taskStableId: String? = null,
    val taskVaultRoot: String? = null,
    /** Original planned task time, used only for uniquely resolving tasks without stable IDs. */
    val taskStartMinuteOfDay: Int? = null,
    /** Null for timers created before sessions were classified. */
    val category: String? = null,
    val sessionId: String? = null,
    val breakStartedAt: Long? = null,
    val firstBreakStartedAt: Long? = null,
    val firstBreakEndedAt: Long? = null,
    val breakStartedElapsedMillis: Long? = null,
    val breakBootCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val pausedMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val breakCount: Int = 0,
    val startUptimeMillis: Long? = null,
    val startBootCount: Int? = null,
    val startProcessId: String? = null,
    val pauseUptimeMillis: Long? = null,
    val pauseProcessId: String? = null,
    val resumeUptimeMillis: Long? = null,
    val resumeBootCount: Int? = null,
    val resumeProcessId: String? = null,
    val endUptimeMillis: Long? = null,
    val endBootCount: Int? = null,
    val endProcessId: String? = null,
)
