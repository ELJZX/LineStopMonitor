package com.finnah.linestop.data

import android.content.ContentValues
import android.database.Cursor

/** Доступ к подробному журналу событий. */
class LogDao(private val db: AppDatabase) {

    fun add(entry: LogEntry): Long {
        val values = ContentValues().apply {
            put("time", entry.time)
            put("level", entry.level)
            put("category", entry.category)
            put("message", entry.message)
            put("alarm_id", entry.alarmId)
            put("shift_id", entry.shiftId)
        }
        return db.writableDatabase.insert("logs", null, values)
    }

    fun recent(limit: Int = 500): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM logs ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) result += fromCursor(c)
        }
        return result
    }

    fun count(): Long =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM logs", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    fun clear() {
        db.writableDatabase.delete("logs", null, null)
    }

    private fun fromCursor(c: Cursor) = LogEntry(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        time = c.getLong(c.getColumnIndexOrThrow("time")),
        level = c.getString(c.getColumnIndexOrThrow("level")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        message = c.getString(c.getColumnIndexOrThrow("message")),
        alarmId = c.getLongOrNull("alarm_id"),
        shiftId = c.getLongOrNull("shift_id")
    )
}
