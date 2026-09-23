package com.finnah.linestop

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finnah.linestop.data.AlarmDao
import com.finnah.linestop.data.AlarmRecord
import com.finnah.linestop.data.AppDatabase
import com.finnah.linestop.data.LogDao
import com.finnah.linestop.data.LogEntry
import com.finnah.linestop.data.ShiftDao
import com.finnah.linestop.data.ShiftRecord
import com.finnah.linestop.domain.AlarmEngine
import com.finnah.linestop.net.EventReporter
import com.finnah.linestop.net.MonitorEvent
import com.finnah.linestop.plc.ModbusTcpClient
import com.finnah.linestop.plc.PlcRegisters
import com.finnah.linestop.plc.PlcSnapshot
import com.finnah.linestop.util.formatDuration
import com.finnah.linestop.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Ядро приложения.
 *
 * Данные: SQLite (аварии, смены, подробный журнал).
 * Логика аварий вынесена в [AlarmEngine] (покрыта unit-тестами).
 */
class LineStopViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val DEFAULT_IP = "172.16.29.150"
        const val DEFAULT_PORT = 502
        private const val KEY_IP = "plc_ip"
        private const val KEY_PORT = "plc_port"
        private const val KEY_MONITOR_URL = "monitor_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val POLL_MS = 200L
        private const val RETRY_MS = 2000L
        private const val ACK_RETRY_MS = 3000L
        private const val REPORT_MS = 500L
        private const val MAX_ACK_ATTEMPTS = 10
        private const val LOG_LIMIT = 300
    }

    private val db = AppDatabase(app)
    private val alarmDao = AlarmDao(db)
    private val shiftDao = ShiftDao(db)
    private val logDao = LogDao(db)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(PlcSnapshot())
    val snapshot: StateFlow<PlcSnapshot> = _snapshot.asStateFlow()

    private val _alarms = MutableStateFlow<List<AlarmRecord>>(emptyList())
    val alarms: StateFlow<List<AlarmRecord>> = _alarms.asStateFlow()

    private val _shift = MutableStateFlow<ShiftRecord?>(null)
    val shift: StateFlow<ShiftRecord?> = _shift.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _dialogAlarm = MutableStateFlow<AlarmRecord?>(null)
    val dialogAlarm: StateFlow<AlarmRecord?> = _dialogAlarm.asStateFlow()

    private val _shiftPrompt = MutableStateFlow(false)
    val shiftPrompt: StateFlow<Boolean> = _shiftPrompt.asStateFlow()

    private var pendingAlarmStart: Long? = null
    private var pendingAlarmEnd: Long? = null

    private val _ip = MutableStateFlow(prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP)
    val ip: StateFlow<String> = _ip.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _monitorUrl = MutableStateFlow(prefs.getString(KEY_MONITOR_URL, "") ?: "")
    val monitorUrl: StateFlow<String> = _monitorUrl.asStateFlow()

    private val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    private val reporter = EventReporter(deviceId)

    private var client = ModbusTcpClient(_ip.value, _port.value)
    private var pollJob: Job? = null
    private var reportJob: Job? = null

    private var firstRead = true
    private var prevAlarmActive = false
    private var lastAckAttemptAt = 0L
    private var ackAttempts = 0

    init {
        _alarms.value = alarmDao.all()
        _shift.value = shiftDao.active()
        reporter.setUrl(_monitorUrl.value)
        writeLog(
            LogEntry.LEVEL_INFO, LogEntry.CAT_APP, "Приложение запущено",
            type = "APP_START"
        )
        refreshLogs()
        startPolling()
        startReporting()
    }

    // ------------------------------------------------------- monitoring

    private fun startReporting() {
        reportJob?.cancel()
        reportJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    reporter.flush()
                } catch (_: Throwable) {
                    // сеть недоступна — события останутся в очереди
                }
                delay(REPORT_MS)
            }
        }
    }

    /** Адрес сервера мониторинга (пусто — отправка выключена). */
    fun updateMonitorUrl(newUrl: String) {
        val clean = newUrl.trim()
        prefs.edit().putString(KEY_MONITOR_URL, clean).apply()
        _monitorUrl.value = clean
        reporter.setUrl(clean)
        if (clean.isNotEmpty()) {
            writeLog(
                LogEntry.LEVEL_INFO, LogEntry.CAT_APP,
                "Адрес мониторинга: $clean", type = "MONITOR_URL"
            )
            viewModelScope.launch(Dispatchers.IO) { reporter.flush() }
        }
    }

    // ---------------------------------------------------------------- polling

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!client.isConnected) {
                        client.host = _ip.value
                        client.port = _port.value
                        client.connect()
                        firstRead = true
                        writeLog(
                            LogEntry.LEVEL_INFO, LogEntry.CAT_PLC,
                            "Связь с ПЛК установлена: ${_ip.value}:${_port.value}",
                            type = "PLC_CONNECTED"
                        )
                    }

                    val regs = client.readHoldingRegisters(PlcRegisters.d(PlcRegisters.STATE), 6)
                    val cur = PlcSnapshot(
                        connected = true,
                        alarmActive = regs[0] != 0,
                        startCounter = regs[1],
                        ackCounter = regs[4],
                        lastCause = regs[5],
                        lastUpdate = System.currentTimeMillis()
                    )

                    val prev = _snapshot.value
                    _snapshot.value = cur
                    detectUnshiftedAlarm(prev, cur)
                    processEvents(prev, cur)
                    retryPendingAckIfDue()

                    delay(POLL_MS)
                } catch (t: Throwable) {
                    val wasConnected = _snapshot.value.connected
                    _snapshot.value = _snapshot.value.copy(
                        connected = false,
                        lastError = t.message ?: t.javaClass.simpleName,
                        lastUpdate = System.currentTimeMillis()
                    )
                    client.close()
                    if (wasConnected) {
                        writeLog(
                            LogEntry.LEVEL_WARN, LogEntry.CAT_PLC,
                            "Связь с ПЛК потеряна: ${t.message ?: t.javaClass.simpleName}",
                            type = "PLC_LOST"
                        )
                    }
                    delay(RETRY_MS)
                }
            }
        }
    }

    /**
     * Смена не начата, но датчик сработал — запоминаем фронт и предлагаем
     * оператору принять смену.
     */
    private fun detectUnshiftedAlarm(prev: PlcSnapshot, cur: PlcSnapshot) {
        if (_shift.value != null || !cur.connected) return

        val started = cur.alarmActive && !prev.alarmActive
        val alreadyActive = firstRead && cur.alarmActive
        if (started || alreadyActive) {
            if (pendingAlarmStart == null) {
                pendingAlarmStart = System.currentTimeMillis()
                pendingAlarmEnd = null
                writeLog(
                    LogEntry.LEVEL_WARN, LogEntry.CAT_SHIFT,
                    "Автопредложение принять смену (сработал датчик без смены)",
                    type = "SHIFT_REQUIRED", stopTime = pendingAlarmStart
                )
            }
            _shiftPrompt.value = true
        }

        if (cur.alarmActive) {
            pendingAlarmEnd = null
        } else if (prev.alarmActive && pendingAlarmStart != null) {
            pendingAlarmEnd = System.currentTimeMillis()
        }
    }

    /** Принятие смены во время уже начавшейся аварии — фиксируем случай. */
    private fun capturePendingAlarm(shift: ShiftRecord) {
        val snap = _snapshot.value
        val pendingStart = pendingAlarmStart

        if (snap.alarmActive) {
            if (_alarms.value.any { !it.closed }) return
            val stop = pendingStart ?: System.currentTimeMillis()
            val id = AlarmEngine.nextId(_alarms.value)
            val after = AlarmEngine.create(
                _alarms.value, id, stop, shift.id, snap.startCounter
            )
            persistAlarms(_alarms.value, after)
            _alarms.value = after
            writeLog(
                LogEntry.LEVEL_WARN, LogEntry.CAT_ALARM,
                "АВАРИЯ: сигнал появился в ${formatTime(stop)}",
                id, shift.id, type = "ALARM_START", stopTime = stop,
                operator = shift.operator, mechanic = shift.mechanic
            )
        } else if (pendingStart != null && pendingAlarmEnd != null) {
            if (_alarms.value.any { !it.closed }) return
            val end = pendingAlarmEnd!!
            val id = AlarmEngine.nextId(_alarms.value)
            val duration = (end - pendingStart).coerceAtLeast(0L)
            val after = AlarmEngine.create(
                _alarms.value, id, pendingStart, shift.id, snap.startCounter,
                startTime = end, durationMs = duration, dialogShown = true
            )
            persistAlarms(_alarms.value, after)
            _alarms.value = after
            _dialogAlarm.value = after.lastOrNull()
            writeLog(
                LogEntry.LEVEL_INFO, LogEntry.CAT_ALARM,
                "Авария завершена (линия запущена), длительность ${formatDuration(duration)}",
                id, shift.id, type = "ALARM_END", durationMs = duration,
                stopTime = pendingStart, startTime = end,
                operator = shift.operator, mechanic = shift.mechanic
            )
        }
        pendingAlarmStart = null
        pendingAlarmEnd = null
    }

    private fun processEvents(prev: PlcSnapshot, cur: PlcSnapshot) {
        val result = AlarmEngine.poll(
            alarms = _alarms.value,
            prev = prev,
            cur = cur,
            firstRead = firstRead,
            now = System.currentTimeMillis(),
            nextId = AlarmEngine.nextId(_alarms.value),
            shiftId = _shift.value?.id,
            shiftActive = _shift.value != null
        )
        firstRead = false

        if (result.alarms !== _alarms.value) {
            persistAlarms(_alarms.value, result.alarms)
            _alarms.value = result.alarms
        }
        result.dialogAlarm?.let { _dialogAlarm.value = it }

        when (val event = result.event) {
            is AlarmEngine.Event.Started -> writeLog(
                LogEntry.LEVEL_WARN, LogEntry.CAT_ALARM,
                "АВАРИЯ: сигнал появился в ${formatTime(event.alarm.stopTime)}",
                event.alarm.id, event.alarm.shiftId, type = "ALARM_START",
                stopTime = event.alarm.stopTime,
                operator = _shift.value?.operator, mechanic = _shift.value?.mechanic
            )

            is AlarmEngine.Event.Ended -> writeLog(
                LogEntry.LEVEL_INFO, LogEntry.CAT_ALARM,
                "Авария завершена (линия запущена), длительность ${formatDuration(event.alarm.durationMs)}",
                event.alarm.id, event.alarm.shiftId, type = "ALARM_END",
                durationMs = event.alarm.durationMs,
                stopTime = event.alarm.stopTime, startTime = event.alarm.startTime,
                operator = _shift.value?.operator, mechanic = _shift.value?.mechanic
            )

            is AlarmEngine.Event.AckConfirmed -> writeLog(
                LogEntry.LEVEL_INFO, LogEntry.CAT_ACK,
                "ПЛК подтвердил квитирование: ${event.alarms.size} шт.",
                type = "ACK_CONFIRMED"
            )

            null -> Unit
        }

        prevAlarmActive = cur.alarmActive
    }

    private fun retryPendingAckIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastAckAttemptAt < ACK_RETRY_MS) return
        val pending = _alarms.value.lastOrNull { it.ackPending } ?: return
        if (ackAttempts >= MAX_ACK_ATTEMPTS) return
        lastAckAttemptAt = now
        ackAttempts++
        writeAck(pending.causeCode ?: 0, pending.id, logIt = false)
    }

    private fun writeAck(causeCode: Int, alarmId: Long?, logIt: Boolean) {
        try {
            client.writeSingleRegister(PlcRegisters.d(PlcRegisters.CAUSE_CODE), causeCode)
            client.writeSingleRegister(PlcRegisters.d(PlcRegisters.ACK_REQUEST), 1)
            if (logIt) {
                writeLog(
                    LogEntry.LEVEL_INFO, LogEntry.CAT_ACK,
                    "Квитирование отправлено в ПЛК (код $causeCode)", alarmId,
                    type = "ACK_SENT", causeCode = causeCode
                )
            }
        } catch (t: Throwable) {
            if (logIt) {
                writeLog(
                    LogEntry.LEVEL_ERROR, LogEntry.CAT_ACK,
                    "Не удалось отправить квитирование: ${t.message}", alarmId,
                    type = "ACK_ERROR"
                )
            }
        }
    }

    // ---------------------------------------------------------------- alarms

    fun acknowledge(alarmId: Long, causeCode: Int, causePath: String?, causeText: String?) {
        val before = _alarms.value
        val after = AlarmEngine.acknowledge(before, alarmId, causeCode, causePath, causeText)
        if (after === before) return
        persistAlarms(before, after)
        _alarms.value = after
        _dialogAlarm.value = null

        val alarm = after.firstOrNull { it.id == alarmId }
        writeLog(
            LogEntry.LEVEL_INFO, LogEntry.CAT_ALARM,
            "Причина выбрана: ${alarm?.causeLabel ?: causeCode} (код $causeCode)",
            alarmId, alarm?.shiftId, type = "CAUSE_SELECTED",
            causeCode = causeCode, causeText = causeText, causePath = causePath,
            stopTime = alarm?.stopTime, startTime = alarm?.startTime,
            durationMs = alarm?.durationMs,
            operator = _shift.value?.operator, mechanic = _shift.value?.mechanic
        )

        lastAckAttemptAt = System.currentTimeMillis()
        ackAttempts = 1
        viewModelScope.launch(Dispatchers.IO) { writeAck(causeCode, alarmId, logIt = true) }
    }

    fun dismissDialog() {
        val alarm = _dialogAlarm.value ?: return
        val before = _alarms.value
        val after = AlarmEngine.dismiss(before, alarm.id)
        persistAlarms(before, after)
        _alarms.value = after
        writeLog(
            LogEntry.LEVEL_WARN, LogEntry.CAT_ALARM,
            "Диалог причины закрыт без выбора", alarm.id, alarm.shiftId,
            type = "CAUSE_DISMISSED"
        )
        _dialogAlarm.value = null
    }

    fun showCauseDialog(alarmId: Long) {
        _dialogAlarm.value = _alarms.value.firstOrNull { it.id == alarmId }
    }

    // ---------------------------------------------------------------- shifts

    /** Начать смену оператора. */
    fun startShift(operator: String, mechanic: String) {
        val name = operator.trim()
        if (name.isEmpty()) return
        val mech = mechanic.trim()
        val shift = shiftDao.start(name, mech, System.currentTimeMillis())
        _shift.value = shift
        _shiftPrompt.value = false
        writeLog(
            LogEntry.LEVEL_INFO, LogEntry.CAT_SHIFT,
            "Смена начата: $name${if (mech.isNotEmpty()) ", механик $mech" else ""}",
            shiftId = shift.id, type = "SHIFT_START",
            operator = name, mechanic = mech
        )
        capturePendingAlarm(shift)
    }

    /** Оператор отложил принятие смены. */
    fun dismissShiftPrompt() {
        if (_shiftPrompt.value) {
            writeLog(
                LogEntry.LEVEL_WARN, LogEntry.CAT_SHIFT,
                "Оператор отклонил автоматическое предложение принять смену",
                type = "SHIFT_DECLINED", stopTime = pendingAlarmStart
            )
        }
        _shiftPrompt.value = false
    }

    /** Завершить текущую смену. Закрывает и окно выбора причины. */
    fun endShift() {
        val shift = _shift.value ?: return
        val now = System.currentTimeMillis()
        shiftDao.end(shift.id, now)
        _shift.value = null
        // завершаем работу — окно выбора причины больше не показываем
        _dialogAlarm.value = null
        _shiftPrompt.value = false
        writeLog(
            LogEntry.LEVEL_INFO, LogEntry.CAT_SHIFT,
            "Смена завершена: ${shift.operator}, длительность ${formatDuration(now - shift.startTime)}",
            shiftId = shift.id, type = "SHIFT_END",
            operator = shift.operator, mechanic = shift.mechanic
        )
    }

    // ---------------------------------------------------------------- settings

    fun updateIp(newIp: String) {
        val clean = newIp.trim()
        if (clean.isEmpty() || clean == _ip.value) return
        prefs.edit().putString(KEY_IP, clean).apply()
        _ip.value = clean
        client.close()
        firstRead = true
        startPolling()
    }

    fun updatePort(newPort: Int) {
        if (newPort !in 1..65535 || newPort == _port.value) return
        prefs.edit().putInt(KEY_PORT, newPort).apply()
        _port.value = newPort
        client.close()
        firstRead = true
        startPolling()
    }

    fun clearHistory() {
        alarmDao.clear()
        logDao.clear()
        _alarms.value = emptyList()
        writeLog(
            LogEntry.LEVEL_WARN, LogEntry.CAT_APP, "История аварий и журнал очищены",
            type = "HISTORY_CLEARED"
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun persistAlarms(old: List<AlarmRecord>, new: List<AlarmRecord>) {
        val oldById = old.associateBy { it.id }
        new.forEach { alarm ->
            val previous = oldById[alarm.id]
            when {
                previous == null -> alarmDao.insert(alarm)
                previous != alarm -> alarmDao.update(alarm)
            }
        }
    }

    private fun writeLog(
        level: String,
        category: String,
        message: String,
        alarmId: Long? = null,
        shiftId: Long? = null,
        type: String? = null,
        causeCode: Int? = null,
        causeText: String? = null,
        causePath: String? = null,
        durationMs: Long? = null,
        stopTime: Long? = null,
        startTime: Long? = null,
        operator: String? = null,
        mechanic: String? = null
    ) {
        val time = System.currentTimeMillis()
        logDao.add(
            LogEntry(
                time = time,
                level = level,
                category = category,
                message = message,
                alarmId = alarmId,
                shiftId = shiftId
            )
        )
        reporter.enqueue(
            MonitorEvent(
                time = time,
                type = type ?: category,
                level = level,
                category = category,
                message = message,
                deviceId = deviceId,
                alarmId = alarmId,
                shiftId = shiftId,
                stopTime = stopTime,
                startTime = startTime,
                causeCode = causeCode,
                causeText = causeText,
                causePath = causePath,
                durationMs = durationMs,
                operator = operator,
                mechanic = mechanic
            )
        )
    }

    fun refreshLogs() {
        _logs.value = logDao.recent(LOG_LIMIT)
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        reportJob?.cancel()
        client.close()
        writeLog(
            LogEntry.LEVEL_INFO, LogEntry.CAT_APP, "Приложение закрыто",
            type = "APP_STOP"
        )
    }
}
