package com.finnah.linestop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    currentIp: String,
    currentPort: Int,
    currentMonitorUrl: String,
    onSave: (ip: String, port: Int, monitorUrl: String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    var ipText by remember { mutableStateOf(currentIp) }
    var portText by remember { mutableStateOf(currentPort.toString()) }
    var monitorText by remember { mutableStateOf(currentMonitorUrl) }

    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP контроллера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Порт Modbus TCP") },
                    singleLine = true,
                    isError = !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = monitorText,
                    onValueChange = { monitorText = it },
                    label = { Text("Адрес сервера мониторинга") },
                    placeholder = { Text("http://172.16.29.1:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "ПЛК DVP12SE11R: порт 502. Сервер мониторинга — адрес ПК " +
                            "с web/server.py (пусто — отправка выключена)."
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ipText.isNotBlank() && portValid,
                onClick = { onSave(ipText.trim(), portValue ?: 502, monitorText.trim()) }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onClearHistory) { Text("Очистить историю") }
        }
    )
}
