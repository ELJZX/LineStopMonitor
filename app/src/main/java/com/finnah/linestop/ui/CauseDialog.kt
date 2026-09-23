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
import androidx.compose.ui.unit.sp
import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.data.CauseCatalog
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatTime

/**
 * Выбор причины остановки в три шага:
 *  1) категория — «Технологическое оборудование», «Автомат фасовки»,
 *     «Конечное оборудование», «Честный знак»;
 *  2) пункт внутри категории;
 *  3) конкретная причина пункта либо «Другая причина» с ручным вводом.
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

    fun goBack() {
        when {
            reason != null -> reason = null
            item != null -> item = null
            category != null -> category = null
        }
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

    val title = when {
        category == null -> "Категория причины"
        item == null -> category!!.title
        else -> "Уточните причину"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
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

                when {
                    // Шаг 1 — категория
                    category == null -> {
                        CauseCatalog.categories.forEach { cat ->
                            OutlinedButton(
                                onClick = { category = cat },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .height(80.dp)
                            ) {
                                Text(
                                    cat.title,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    // Шаг 2 — пункт внутри категории
                    item == null -> {
                        category!!.items.forEach { it2 ->
                            OutlinedButton(
                                onClick = { item = it2 },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .height(80.dp)
                            ) {
                                Text(
                                    it2.title,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    // Шаг 3 — причина
                    else -> {
                        item!!.reasons.forEach { r ->
                            val selected = reason == r
                            OutlinedButton(
                                onClick = { reason = r },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .height(80.dp),
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
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 18.sp
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
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (category != null) {
                    TextButton(onClick = { goBack() }) { Text("Назад") }
                }
                if (item != null) {
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
