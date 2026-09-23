package com.finnah.linestop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.finnah.linestop.plc.PlcSnapshot
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatShortTime
import com.finnah.linestop.util.formatTime

@Composable
fun MainScreen(vm: LineStopViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val dialogAlarm by vm.dialogAlarm.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()
    val shiftPrompt by vm.shiftPrompt.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val ip by vm.ip.collectAsStateWithLifecycle()
    val port by vm.port.collectAsStateWithLifecycle()
    val monitorUrl by vm.monitorUrl.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showAccess by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showOperator by remember { mutableStateOf(false) }
    var showEndShift by remember { mutableStateOf(false) }

    // Датчик сработал без начатой смены — автоматически предлагаем принять смену
    LaunchedEffect(shiftPrompt, shift) {
        if (shiftPrompt && shift == null) showOperator = true
    }

    val openAlarms = remember(alarms) { alarms.filter { !it.closed } }
    val history = remember(alarms) {
        alarms.filter { it.closed }.sortedByDescending { it.stopTime }
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (snapshot.connected) "Связь: $ip:$port" else "Нет связи",
                        color = if (snapshot.connected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.weight(1f))

                    val currentShift = shift
                    if (currentShift == null) {
                        Button(onClick = { showOperator = true }) { Text("Начать работу") }
                    } else {
                        Text(
                            text = "Оператор: ${currentShift.operator}" +
                                    (if (currentShift.mechanic.isNotBlank())
                                        " · Механик: ${currentShift.mechanic}" else "") +
                                    " · с ${formatShortTime(currentShift.startTime)} · " +
                                    formatDuration(currentShift.duration(snapshot.lastUpdate))
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { showEndShift = true }) { Text("Закончить работу") }
                    }

                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.refreshLogs(); showLogs = true }) { Text("Журнал") }
                    TextButton(onClick = { showAccess = true }) { Text("Настройки") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                StatusCard(snapshot, openAlarms.size, shiftActive = shift != null)
            }

            if (shift != null && openAlarms.isNotEmpty()) {
                item {
                    Text(
                        "Активные аварии",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(openAlarms, key = { "active-${it.id}" }) { alarm ->
                    ActiveAlarmCard(
                        alarm = alarm,
                        liveDurationMs = if (alarm.ongoing) {
                            (snapshot.lastUpdate - alarm.stopTime).coerceAtLeast(0L)
                        } else null,
                        onSetCause = { vm.showCauseDialog(alarm.id) }
                    )
                }
            }

            item {
                Text(
                    "История (${history.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (history.isEmpty()) {
                item {
                    Text(
                        "Нет закрытых случаев",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(history, key = { "history-${it.id}" }) { alarm ->
                    Column {
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
            onConfirm = { code, path, text -> vm.acknowledge(alarm.id, code, path, text) },
            onEndShift = { showEndShift = true },
            onDismiss = { vm.dismissDialog() }
        )
    }

    if (showAccess) {
        AccessDialog(
            onSuccess = {
                showAccess = false
                showSettings = true
            },
            onDismiss = { showAccess = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            currentIp = ip,
            currentPort = port,
            currentMonitorUrl = monitorUrl,
            onSave = { newIp, newPort, newMonitorUrl ->
                vm.updateIp(newIp)
                vm.updatePort(newPort)
                vm.updateMonitorUrl(newMonitorUrl)
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
            alert = shiftPrompt,
            onConfirm = { name, mechanic ->
                vm.startShift(name, mechanic)
                showOperator = false
            },
            onDismiss = {
                vm.dismissShiftPrompt()
                showOperator = false
            }
        )
    }

    if (showEndShift) {
        EndShiftDialog(
            operator = shift?.operator ?: "",
            mechanic = shift?.mechanic ?: "",
            onConfirm = {
                vm.endShift()
                showEndShift = false
            },
            onDismiss = { showEndShift = false }
        )
    }
}

@Composable
private fun StatusCard(snapshot: PlcSnapshot, openCount: Int, shiftActive: Boolean) {
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
        !shiftActive -> {
            label = "СМЕНА НЕ НАЧАТА"
            color = Color(0xFF546E7A)
            icon = null
        }

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

        openCount > 0 -> {
            // линия работает, но есть незаполненные причины аварий
            label = "ЛИНИЯ В РАБОТЕ"
            color = Color(0xFFF9A825)
            icon = "✔"
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
                if (!snapshot.connected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Ошибка: ${snapshot.lastError ?: "нет соединения"}",
                        color = Color.White
                    )
                }
                when {
                    shiftActive && snapshot.alarmActive -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Сигнал аварии активен (линия остановлена)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    shiftActive && openCount > 0 -> {
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
