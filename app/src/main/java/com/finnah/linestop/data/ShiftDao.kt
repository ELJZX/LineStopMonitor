package com.finnah.linestop.data

import android.content.ContentValues
import android.database.Cursor

/** Доступ к таблице смен операторов. */
class ShiftDao(private val db: AppDatabase) {

    /** Начать новую смену. Предыдущая активная смена закрывается. */
    fun start(operator: String, time: Long): ShiftRecord {
        val active = active()
        if (active != null) end(active.id, time)

        val values = ContentValues().apply {
            put("operator", operator)
            put("start_time", time)
            putNull("end_time")
        }
        val id = db.writableDatabase.insert("shifts", null, values)
        return ShiftRecord(id = id, operator = operator, startTime = time)
    }

    fun end(id: Long, time: Long) {
        val values = ContentValues().apply { put("end_time", time) }
        db.writableDatabase.update("shifts", values, "id = ?", arrayOf(id.toString()))
    }

    fun active(): ShiftRecord? =
        db.readableDatabase.rawQuery(
            "SELECT * FROM shifts WHERE end_time IS NULL ORDER BY id DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) fromCursor(c) else null }

    fun all(): List<ShiftRecord> {
        val result = mutableListOf<ShiftRecord>()
        db.readableDatabase.rawQuery("SELECT * FROM shifts ORDER BY start_time", null).use { c ->
            while (c.moveToNext()) result += fromCursor(c)
        }
        return result
    }

    fun clear() {
        db.writableDatabase.delete("shifts", null, null)
    }

    private fun fromCursor(c: Cursor) = ShiftRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        operator = c.getString(c.getColumnIndexOrThrow("operator")),
        startTime = c.getLong(c.getColumnIndexOrThrow("start_time")),
        endTime = c.getLongOrNull("end_time")
    )
}
