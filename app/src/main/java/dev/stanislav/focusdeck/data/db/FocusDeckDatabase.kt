package dev.stanislav.focusdeck.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FocusBlock::class,
        Meal::class,
        WeightEntry::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FocusDeckDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `startUptimeMillis` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `startBootCount` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `startProcessId` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `pauseUptimeMillis` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `pauseProcessId` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `resumeUptimeMillis` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `resumeBootCount` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `resumeProcessId` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `endUptimeMillis` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `endBootCount` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `endProcessId` TEXT")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `category` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `sessionId` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `breakStartedAt` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `firstBreakStartedAt` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `firstBreakEndedAt` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `breakStartedElapsedMillis` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `breakBootCount` INTEGER")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `pausedMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `breakCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `taskStableId` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `taskVaultRoot` TEXT")
                db.execSQL("ALTER TABLE `focus_blocks` ADD COLUMN `taskStartMinuteOfDay` INTEGER")
            }
        }
    }

    abstract fun focusBlockDao(): FocusBlockDao
    abstract fun mealDao(): MealDao
    abstract fun weightDao(): WeightDao
}
