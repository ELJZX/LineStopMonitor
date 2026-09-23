package com.finnah.linestop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite-база приложения: аварии, смены, подробный журнал событий. */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        const val NAME = "linestop.db"
        const val VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE shifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator TEXT NOT NULL,
                mechanic TEXT,
                start_time INTEGER NOT NULL,
                end_time INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE alarms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shift_id INTEGER,
                stop_time INTEGER NOT NULL,
                start_time INTEGER,
                duration_ms INTEGER,
                cause_code INTEGER,
                cause_text TEXT,
                cause_path TEXT,
                closed INTEGER NOT NULL DEFAULT 0,
                dialog_shown INTEGER NOT NULL DEFAULT 0,
                ack_pending INTEGER NOT NULL DEFAULT 0,
                start_counter INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                level TEXT NOT NULL,
                category TEXT NOT NULL,
                message TEXT NOT NULL,
                alarm_id INTEGER,
                shift_id INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_logs_time ON logs(time)")
        db.execSQL("CREATE INDEX idx_alarms_stop ON alarms(stop_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // добавлен полный путь причины «Категория / Пункт / Причина»
            db.execSQL("ALTER TABLE alarms ADD COLUMN cause_path TEXT")
        }
        if (oldVersion < 3) {
            // добавлен ответственный механик смены
            db.execSQL("ALTER TABLE shifts ADD COLUMN mechanic TEXT")
        }
    }
}
