package com.finnah.linestop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finnah.linestop.LineStopViewModel
import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.data.ShiftRecord
import com.finnah.linestop.plc.PlcSnapshot
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatShortTime
import com.finnah.linestop.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: LineStopViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val dialogAlarm by vm.dialogAlarm.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val ip by vm.ip.collectAsStateWithLifecycle()
    val port by vm.port.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showOperator by remember { mutableStateOf(false) }
    var showEndShift by remember { mutableStateOf(false) }

    val openAlarms = remember(alarms) { alarms.filter { !it.closed } }
    val history = remember(alarms) {
        alarms.filter { it.closed }.sortedByDescending { it.stopTime }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мониторинг аварий линии") },
                actions = {
                    Text(
                        text = if (snapshot.connected) "Связь: $ip:$port" else "Нет связи",
                        color = if (snapshot.connected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    TextButton(onClick = { vm.refreshLogs(); showLogs = true }) { Text("Журнал") }
                    TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            StatusCard(snapshot, openAlarms.size)
            Spacer(Modifier.height(12.dp))

            ShiftRow(
                shift = shift,
                now = snapshot.lastUpdate,
                onStart = { showOperator = true },
                onEnd = { showEndShift = true }
            )
            Spacer(Modifier.height(12.dp))

            if (openAlarms.isNotEmpty()) {
                Text(
                    "Активные аварии",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                openAlarms.forEach { alarm ->
                    ActiveAlarmCard(
                        alarm = alarm,
                        liveDurationMs = if (alarm.ongoing) {
                            (snapshot.lastUpdate - alarm.stopTime).coerceAtLeast(0L)
                        } else null,
                        onSetCause = { vm.showCauseDialog(alarm.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "История (${history.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (history.isEmpty()) {
                Text(
                    "Нет закрытых случаев",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(history, key = { it.id }) { alarm ->
                        HistoryRow(alarm)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    dialogAlarm?.let { alarm ->
        CauseDialog(
            alarm = alarm,
            onConfirm = { code, text -> vm.acknowledge(alarm.id, code, text) },
            onDismiss = { vm.dismissDialog() }
        )
    }

    if (showSettings) {
        SettingsDialog(
            currentIp = ip,
            currentPort = port,
            onSave = { newIp, newPort ->
                vm.updateIp(newIp)
                vm.updatePort(newPort)
                showSettings = false
            },
            onClearHistory = {
                vm.clearHistory()
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showLogs) {
        LogsDialog(logs = logs, onDismiss = { showLogs = false })
    }

    if (showOperator) {
        OperatorDialog(
            onConfirm = { name ->
                vm.startShift(name)
                showOperator = false
            },
            onDismiss = { showOperator = false }
        )
    }

    if (showEndShift) {
        EndShiftDialog(
            operator = shift?.operator ?: "",
            onConfirm = {
                vm.endShift()
                showEndShift = false
            },
            onDismiss = { showEndShift = false }
        )
    }
}

@Composable
private fun StatusCard(snapshot: PlcSnapshot, openCount: Int) {
    val infinite = rememberInfiniteTransition(label = "statusPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val label: String
    val color: Color
    val icon: String?
    when {
        !snapshot.connected -> {
            label = "НЕТ СВЯЗИ"
            color = Color(0xFF616161)
            icon = null
        }

        snapshot.alarmActive -> {
            label = "АВАРИЯ"
            // пульсация красного фона
            color = lerp(Color(0xFF8E0000), Color(0xFFE53935), pulse)
            icon = "!"
        }

        else -> {
            label = "ЛИНИЯ В РАБОТЕ"
            color = Color(0xFF2E7D32)
            icon = "✔"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (snapshot.connected) {
                        "запусков: ${snapshot.startCounter}"
                    } else {
                        "Ошибка: ${snapshot.lastError ?: "нет соединения"}"
                    },
                    color = Color.White
                )
                when {
                    snapshot.alarmActive -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Сигнал аварии активен (линия остановлена)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    openCount > 0 -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Незакрытых аварий: $openCount — укажите причину",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            icon?.let {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = it,
                    color = Color.White,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ShiftRow(
    shift: ShiftRecord?,
    now: Long,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (shift == null) {
                    Text("Смена не начата", fontWeight = FontWeight.Bold)
                    Text(
                        "Нажмите «Начать работу», чтобы открыть смену оператора",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("Оператор: ${shift.operator}", fontWeight = FontWeight.Bold)
                    Text(
                        "Смена с ${formatShortTime(shift.startTime)} · " +
                                "длительность ${formatDuration(shift.duration(now))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = if (shift == null) onStart else onEnd) {
                Text(if (shift == null) "Начать работу" else "Закончить работу")
            }
        }
    }
}

@Composable
private fun ActiveAlarmCard(
    alarm: AlarmRecord,
    liveDurationMs: Long?,
    onSetCause: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (alarm.ongoing) "Авария идёт" else "Авария завершена — укажите причину",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB71C1C)
            )
            Text("Начало аварии: ${formatTime(alarm.stopTime)}")
            if (alarm.ongoing) {
                Text("Длительность: ${formatDuration(liveDurationMs ?: alarm.duration())} (продолжается)")
            } else {
                Text("Конец аварии: ${formatTime(alarm.startTime)}")
                Text("Длительность аварии: ${formatDuration(alarm.duration())}")
            }
            if (alarm.ackPending) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Причина отправлена, ожидаем подтверждения ПЛК…",
                    color = Color(0xFF6A1B9A)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSetCause) {
                Text("Указать причину")
            }
        }
    }
}

@Composable
private fun HistoryRow(alarm: AlarmRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(alarm.causeLabel ?: "Без причины", fontWeight = FontWeight.Bold)
        Text(
            "Начало: ${formatTime(alarm.stopTime)}   " +
                    "Конец: ${formatTime(alarm.startTime)}   " +
                    "Длительность: ${formatDuration(alarm.duration())}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
