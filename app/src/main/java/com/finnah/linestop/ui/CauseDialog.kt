package com.finnah.linestop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.data.StopCauses
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatTime

/**
 * Диалог выбора причины остановки.
 *
 * Показывается автоматически после сигнала запуска линии. Пока оператор
 * не выбрал причину, случай считается незакрытым и "АВАРИЯ" остаётся
 * на главном экране (кнопка "Позже" не закрывает аварию).
 */
@Composable
fun CauseDialog(
    alarm: AlarmRecord,
    onConfirm: (code: Int, text: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCode by remember(alarm.id) { mutableStateOf<Int?>(alarm.causeCode) }
    var otherText by remember(alarm.id) { mutableStateOf(alarm.causeText.orEmpty()) }

    val isOther = selectedCode == StopCauses.OTHER_CODE
    val canConfirm = selectedCode != null && (!isOther || otherText.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Укажите причину остановки") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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

                StopCauses.list.forEach { cause ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedCode == cause.code,
                                onClick = { selectedCode = cause.code }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedCode == cause.code,
                            onClick = { selectedCode = cause.code }
                        )
                        Text(cause.title)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isOther,
                            onClick = { selectedCode = StopCauses.OTHER_CODE }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isOther,
                        onClick = { selectedCode = StopCauses.OTHER_CODE }
                    )
                    Text("Другая причина")
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
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    val code = selectedCode ?: return@TextButton
                    onConfirm(code, if (isOther) otherText else null)
                }
            ) { Text("Подтвердить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        }
    )
}
