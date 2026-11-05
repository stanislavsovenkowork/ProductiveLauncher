"""Execute the production 6->7 SQL against SQLite; no Gradle/device/dependencies needed.

Run: python3 -m unittest discover -s app/src/test/python -p test_focus_block_migration.py
Room's generated-schema validation still requires main's Android upgrade test.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
DATABASE = ROOT / "app/src/main/java/dev/stanislav/focusdeck/data/db/FocusDeckDatabase.kt"
APP = ROOT / "app/src/main/java/dev/stanislav/focusdeck/FocusDeckApp.kt"

# Exact v6 table definitions from the pre-upgrade generated Room implementation.
V6 = (
    "CREATE TABLE focus_blocks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, plannedMinutes INTEGER NOT NULL, startEpochMillis INTEGER NOT NULL, endEpochMillis INTEGER, interrupted INTEGER NOT NULL, dayEpochDay INTEGER NOT NULL)",
    "CREATE TABLE meals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayEpochDay INTEGER NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, calories INTEGER NOT NULL, epochMillis INTEGER NOT NULL)",
    "CREATE TABLE weight_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayEpochDay INTEGER NOT NULL, kg REAL NOT NULL, epochMillis INTEGER NOT NULL)",
)


class FocusBlockMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "focusdeck.db"
        self.db = sqlite3.connect(self.path)
        self.addCleanup(lambda: self.db.close())
        for sql in V6:
            self.db.execute(sql)
        self.db.execute("PRAGMA user_version=6")
        self.db.execute("INSERT INTO focus_blocks VALUES (17, 'completed free', 25, 1000, 2000, 0, 20725)")
        self.db.execute("INSERT INTO focus_blocks VALUES (18, 'active old task title', 30, 3000, NULL, 0, 20725)")
        self.db.execute("INSERT INTO meals VALUES (41, 20725, 'LUNCH', 'retained meal', 520, 1000)")
        self.db.execute("INSERT INTO weight_entries VALUES (9, 20725, 73.4, 1000)")
        self.db.commit()

    def migrate(self):
        source = DATABASE.read_text()
        self.assertRegex(source, r"version\s*=\s*9")
        self.assertIn("object : Migration(6, 7)", source)
        section = source.split("val MIGRATION_6_7:", 1)[1]
        sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', section)
        self.assertEqual(3, len(sql))
        with self.db:
            for statement in sql:
                self.assertTrue(statement.startswith("ALTER TABLE `focus_blocks` ADD COLUMN "))
                self.db.execute(statement)
            # Room controls user_version outside Migration.migrate.
            self.db.execute("PRAGMA user_version=7")

    def test_preserves_all_v6_rows_food_weight_and_completed_and_active_timers(self):
        original = {table: self.db.execute(f"SELECT * FROM {table} ORDER BY id").fetchall()
                    for table in ("focus_blocks", "meals", "weight_entries")}
        schemas = {table: self.db.execute(f"PRAGMA table_info({table})").fetchall()
                   for table in ("meals", "weight_entries")}
        self.migrate()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        for table in ("meals", "weight_entries"):
            self.assertEqual(original[table], self.db.execute(f"SELECT * FROM {table} ORDER BY id").fetchall())
            self.assertEqual(schemas[table], self.db.execute(f"PRAGMA table_info({table})").fetchall())
        rows = self.db.execute("SELECT * FROM focus_blocks ORDER BY id").fetchall()
        self.assertEqual(original["focus_blocks"], [row[:7] for row in rows])
        self.assertTrue(all(row[7:] == (None, None, None) for row in rows))
        self.assertEqual(520, self.db.execute("SELECT SUM(calories) FROM meals").fetchone()[0])

    def test_new_columns_nullable_and_have_expected_sqlite_affinities(self):
        self.migrate()
        columns = {row[1]: row for row in self.db.execute("PRAGMA table_info(focus_blocks)")}
        for name, affinity in (("taskStableId", "TEXT"), ("taskVaultRoot", "TEXT"), ("taskStartMinuteOfDay", "INTEGER")):
            self.assertEqual(affinity, columns[name][2])
            self.assertEqual(0, columns[name][3])
            self.assertIsNone(columns[name][4])

    def test_preserves_autoincrement_and_roundtrips_explicit_binding(self):
        self.migrate()
        cursor = self.db.execute("""INSERT INTO focus_blocks
            (title, plannedMinutes, startEpochMillis, interrupted, dayEpochDay, taskStableId, taskVaultRoot, taskStartMinuteOfDay)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", ("same title", 30, 4000, 0, 20725, "task-id", "content://original", 600))
        self.assertGreater(cursor.lastrowid, 18)
        self.assertEqual(("task-id", "content://original", 600), self.db.execute(
            "SELECT taskStableId,taskVaultRoot,taskStartMinuteOfDay FROM focus_blocks WHERE id=?", (cursor.lastrowid,)).fetchone())

    def test_v7_to_v8_preserves_existing_rows_and_adds_session_defaults(self):
        self.migrate()
        before = self.db.execute("SELECT * FROM focus_blocks ORDER BY id").fetchall()
        source = DATABASE.read_text()
        section = source.split("val MIGRATION_7_8:", 1)[1].split("val MIGRATION_6_7:", 1)[0]
        sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', section)
        self.assertEqual(9, len(sql))
        with self.db:
            for statement in sql:
                self.db.execute(statement)
        after = self.db.execute("SELECT * FROM focus_blocks ORDER BY id").fetchall()
        self.assertEqual(before, [row[:10] for row in after])
        self.assertTrue(all(row[10:] == (None, None, None, None, None, None, None, 0, 0) for row in after))
        self.assertEqual(520, self.db.execute("SELECT SUM(calories) FROM meals").fetchone()[0])
        self.assertEqual(73.4, self.db.execute("SELECT kg FROM weight_entries").fetchone()[0])

    def test_v8_to_v9_preserves_rows_and_does_not_invent_anchors(self):
        self.migrate()
        source = DATABASE.read_text()
        section78 = source.split("val MIGRATION_7_8:", 1)[1].split("val MIGRATION_6_7:", 1)[0]
        for statement in re.findall(r'db\.execSQL\("([^"\n]+)"\)', section78):
            self.db.execute(statement)
        before = self.db.execute("SELECT * FROM focus_blocks ORDER BY id").fetchall()
        section89 = source.split("val MIGRATION_8_9:", 1)[1].split("val MIGRATION_7_8:", 1)[0]
        sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', section89)
        self.assertEqual(11, len(sql))
        for statement in sql:
            self.db.execute(statement)
        after = self.db.execute("SELECT * FROM focus_blocks ORDER BY id").fetchall()
        self.assertEqual(before, [row[:19] for row in after])
        self.assertTrue(all(row[19:] == (None,) * 11 for row in after))
        self.assertEqual(520, self.db.execute("SELECT SUM(calories) FROM meals").fetchone()[0])

    def test_registers_migration_and_has_no_destructive_fallback(self):
        source = APP.read_text()
        self.assertIn(".addMigrations(FocusDeckDatabase.MIGRATION_6_7, FocusDeckDatabase.MIGRATION_7_8, FocusDeckDatabase.MIGRATION_8_9)", source)
        self.assertNotIn("fallbackToDestructiveMigration", source)


if __name__ == "__main__":
    unittest.main()
