package com.finnah.linestop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.data.CauseCatalog
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatTime

/**
 * Выбор причины остановки в два шага:
 *  1) пункт внутри категории («Нет продукта», «Формовка + протяжка», ...);
 *  2) конкретная причина пункта («1.1 Мойка», ...) либо «Другая причина» с ручным вводом.
 *
 * Окно компактное (не на весь экран), справа внизу — кнопка «Закончить работу».
 */
@Composable
fun CauseDialog(
    alarm: AlarmRecord,
    onConfirm: (code: Int, path: String?, text: String?) -> Unit,
    onEndShift: () -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(alarm.id) { mutableStateOf<CauseCatalog.Category?>(null) }
    var item by remember(alarm.id) { mutableStateOf<CauseCatalog.Item?>(null) }
    var reason by remember(alarm.id) { mutableStateOf<CauseCatalog.Reason?>(null) }
    var otherText by remember(alarm.id) { mutableStateOf("") }

    val isOther = reason?.other == true
    val canConfirm = reason != null && (!isOther || otherText.isNotBlank())

    fun reset() {
        category = null
        item = null
        reason = null
        otherText = ""
    }

    fun confirm() {
        val selected = reason ?: return
        onConfirm(
            selected.code,
            CauseCatalog.path(selected.code),
            if (selected.other) otherText else null
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (item == null) "Укажите причину остановки" else "Уточните причину")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Начало аварии: ${formatTime(alarm.stopTime)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (alarm.startTime != null) {
                    Text(
                        "Конец аварии: ${formatTime(alarm.startTime)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Длительность аварии: ${formatDuration(alarm.duration())}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                val currentItem = item
                if (currentItem == null) {
                    CauseCatalog.categories.forEach { cat ->
                        Text(
                            cat.title,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            cat.items.forEach { it2 ->
                                OutlinedButton(
                                    onClick = {
                                        category = cat
                                        item = it2
                                        reason = null
                                        otherText = ""
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(2.dp)
                                ) {
                                    Text(it2.title, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    Text(
                        "${category?.title ?: ""} → ${currentItem.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF616161)
                    )
                    Spacer(Modifier.height(8.dp))

                    currentItem.reasons.forEach { r ->
                        val selected = reason == r
                        OutlinedButton(
                            onClick = { reason = r },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            colors = if (selected) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFE8F5E9)
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text(
                                r.title,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    if (isOther) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = otherText,
                            onValueChange = { otherText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Опишите причину вручную") },
                            minLines = 2
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item != null) {
                    TextButton(onClick = { reset() }) { Text("Назад") }
                    TextButton(enabled = canConfirm, onClick = { confirm() }) {
                        Text("Подтвердить")
                    }
                }
                TextButton(onClick = onEndShift) { Text("Закончить работу") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        }
    )
}
