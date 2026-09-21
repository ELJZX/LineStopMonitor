package com.finnah.linestop.data

import android.database.Cursor

internal fun Cursor.getLongOrNull(name: String): Long? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getLong(i)
}

internal fun Cursor.getIntOrNull(name: String): Int? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getInt(i)
}

internal fun Cursor.getStringOrNull(name: String): String? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getString(i)
}
