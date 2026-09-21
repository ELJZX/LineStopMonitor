package com.finnah.linestop.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
private val shortTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun formatTime(millis: Long?): String =
    if (millis == null) "—" else timeFormat.format(Date(millis))

fun formatShortTime(millis: Long?): String =
    if (millis == null) "—" else shortTimeFormat.format(Date(millis))

fun formatDuration(ms: Long?): String {
    if (ms == null || ms < 0) return "—"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format(Locale.getDefault(), "%d ч %02d мин %02d с", h, m, s)
        m > 0 -> String.format(Locale.getDefault(), "%d мин %02d с", m, s)
        else -> String.format(Locale.getDefault(), "%d с", s)
    }
}
