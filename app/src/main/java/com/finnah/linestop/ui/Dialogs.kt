package com.finnah.linestop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.finnah.linestop.data.LogEntry
import com.finnah.linestop.data.MechanicCatalog
import com.finnah.linestop.util.AccessGuard
import com.finnah.linestop.util.formatTime

/** Запрос кода доступа перед открытием настроек. */
@Composable
fun AccessDialog(
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = false
                    },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text("Неверный код", color = Color(0xFFC62828))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (AccessGuard.check(code)) onSuccess() else error = true
            }) { Text("ОК") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Диалог начала смены: оператор и ответственный механик. */
@Composable
fun OperatorDialog(
    alert: Boolean = false,
    onConfirm: (operator: String, mechanic: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mechanic by remember { mutableStateOf("") }
    var mechanicMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alert) "Требуется начать смену" else "Начать работу") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (alert) {
                    Text(
                        "Сработал датчик остановки линии, а смена не начата. " +
                                "Примите смену, чтобы зафиксировать аварию.",
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text("Укажите оператора линии и ответственного механика:")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Фамилия оператора") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { mechanicMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = mechanic.ifBlank { "Выберите ответственного механика" },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (mechanic.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = mechanicMenu,
                        onDismissRequest = { mechanicMenu = false }
                    ) {
                        MechanicCatalog.mechanics.forEach { person ->
                            DropdownMenuItem(
                                text = { Text(person) },
                                onClick = {
                                    mechanic = person
                                    mechanicMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && mechanic.isNotBlank(),
                onClick = { onConfirm(name, mechanic) }
            ) { Text("Начать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Подтверждение завершения смены. */
@Composable
fun EndShiftDialog(
    operator: String,
    mechanic: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Завершение работы") },
        text = {
            Text(
                "Вы точно хотите завершить работу на линии?\n\n" +
                        "Оператор: $operator\n" +
                        "Механик: ${mechanic.ifBlank { "—" }}"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Да") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Нет") }
        }
    )
}

/** Просмотр подробного журнала событий. */
@Composable
fun LogsDialog(
    logs: List<LogEntry>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Журнал событий (${logs.size})") },
        text = {
            if (logs.isEmpty()) {
                Text("Журнал пуст")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(logs, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = entry.level,
                                color = levelColor(entry.level),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${formatTime(entry.time)} · ${entry.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

private fun levelColor(level: String): Color = when (level) {
    LogEntry.LEVEL_ERROR -> Color(0xFFC62828)
    LogEntry.LEVEL_WARN -> Color(0xFFE65100)
    else -> Color(0xFF2E7D32)
}
