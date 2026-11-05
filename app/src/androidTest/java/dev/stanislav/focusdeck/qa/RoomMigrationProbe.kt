package dev.stanislav.focusdeck.qa

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import dev.stanislav.focusdeck.data.db.FocusDeckDatabase
import java.util.UUID

/** A self-contained v7-to-v9 upgrade; never opens the normal QA or release database. */
object RoomMigrationProbe {
    fun run(context: Context): Bundle {
        check(context.packageName == "dev.stanislav.focusdeck.qa")
        val name = "qa-harness-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        var room: FocusDeckDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE focus_blocks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, plannedMinutes INTEGER NOT NULL, startEpochMillis INTEGER NOT NULL, endEpochMillis INTEGER, interrupted INTEGER NOT NULL, dayEpochDay INTEGER NOT NULL, taskStableId TEXT, taskVaultRoot TEXT, taskStartMinuteOfDay INTEGER)")
                db.execSQL("CREATE TABLE meals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayEpochDay INTEGER NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, calories INTEGER NOT NULL, epochMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE weight_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayEpochDay INTEGER NOT NULL, kg REAL NOT NULL, epochMillis INTEGER NOT NULL)")
                db.execSQL("INSERT INTO focus_blocks VALUES (17, 'synthetic', 25, 1000, 2000, 0, 1, 'qa-task', 'content://qa-vault', 600)")
                db.execSQL("INSERT INTO focus_blocks VALUES (18, 'synthetic-active', 25, 3000, NULL, 0, 1, NULL, NULL, NULL)")
                db.execSQL("INSERT INTO meals VALUES (41, 1, 'LUNCH', 'synthetic', 520, 1000)")
                db.execSQL("INSERT INTO weight_entries VALUES (9, 1, 73.4, 1000)")
                db.version = 7
            }
            room = Room.databaseBuilder(context, FocusDeckDatabase::class.java, name)
                .addMigrations(FocusDeckDatabase.MIGRATION_7_8, FocusDeckDatabase.MIGRATION_8_9).build()
            val db = room.openHelper.writableDatabase // Room runs its own full schema validation here.
            fun count(table: String): Int = db.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            check(count("focus_blocks") == 2 && count("meals") == 1 && count("weight_entries") == 1)
            db.query("SELECT taskStableId,taskVaultRoot,taskStartMinuteOfDay,category,sessionId,pausedMillis,breakCount FROM focus_blocks WHERE id=17").use {
                check(it.moveToFirst() && it.getString(0) == "qa-task" && it.getString(1) == "content://qa-vault" && it.getInt(2) == 600)
                check(it.isNull(3) && it.isNull(4) && it.getLong(5) == 0L && it.getInt(6) == 0)
            }
            db.query("SELECT endEpochMillis FROM focus_blocks WHERE id=18").use { check(it.moveToFirst() && it.isNull(0)) }
            db.query("SELECT calories FROM meals WHERE id=41").use { check(it.moveToFirst() && it.getInt(0) == 520) }
            db.query("SELECT kg FROM weight_entries WHERE id=9").use { check(it.moveToFirst() && kotlin.math.abs(it.getDouble(0) - 73.4) < 0.001) }
            db.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") }
            return Bundle().apply { putString("migration", "7_to_9"); putString("status", "ok"); putInt("focus_rows", 2); putInt("meal_rows", 1); putInt("weight_rows", 1) }
        } finally {
            room?.close()
            context.deleteDatabase(name) // Only the UUID-named synthetic database created above.
        }
    }
}
