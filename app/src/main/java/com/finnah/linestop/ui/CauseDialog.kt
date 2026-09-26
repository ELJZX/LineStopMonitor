package com.finnah.linestop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * Окно занимает почти весь экран (широкие кнопки), кнопки действий закреплены
 * снизу и не «съезжают» при прокрутке списка причин.
 */
@Composable
fun CauseDialog(
    alarm: AlarmRecord,
    quickPick: AlarmRecord? = null,
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
        val text = otherText.trim()
        if (selected.other) {
            // Формат: «Категория / Пункт / Другая причина:<текст оператора>»
            val parts = listOfNotNull(
                category?.title, item?.title, "Другая причина:$text"
            )
            onConfirm(selected.code, parts.joinToString(" / "), text)
        } else {
            onConfirm(selected.code, CauseCatalog.path(selected.code), null)
        }
    }

    val title = when {
        category == null -> "Категория причины"
        item == null -> category!!.title
        else -> "Уточните причину"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    val quickCode = quickPick?.causeCode
                    if (quickPick != null && quickCode != null) {
                        OutlinedButton(
                            onClick = {
                                onConfirm(quickCode, quickPick.causePath, quickPick.causeText)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        ) {
                            Text(
                                "Повторить последнюю причину:\n${quickPick.causeLabel}",
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "— или выберите причину заново —",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }

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

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (category != null) {
                        TextButton(onClick = { goBack() }) { Text("Назад") }
                    }
                    if (item != null) {
                        TextButton(enabled = canConfirm, onClick = { confirm() }) {
                            Text("Подтвердить")
                        }
                    }
                    TextButton(onClick = onEndShift) { Text("Закончить работу") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Позже") }
                }
            }
        }
    }
}
