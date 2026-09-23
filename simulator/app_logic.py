"""
Python-порт конечного автомата Android-приложения (LineStopViewModel.kt).

Карта регистров — как в ладдер-программе заказчика:
    D0 = состояние (1 = авария, 0 = работа)
    D1 = счётчик запусков (по спаду X0)
    D4 = счётчик квитирований
    D5 = код последней причины
    D100 / D101 = причина и запрос квитирования от приложения

Используется в тестах, чтобы проверить протокол без сборки Android-приложения.
"""

import socket
import struct
import time

MODBUS_D_BASE = 0x1000


class ModbusTcpClient:
    """Аналог ModbusTcpClient.kt (FC 0x03 / 0x06)."""

    def __init__(self, host, port=502, unit_id=1, timeout=1.5):
        self.host = host
        self.port = port
        self.unit_id = unit_id
        self.timeout = timeout
        self.sock = None
        self.tx = 0

    def connect(self):
        self.close()
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None

    @property
    def connected(self):
        return self.sock is not None

    def read_holding_registers(self, address, quantity):
        pdu = struct.pack(">BHH", 0x03, address, quantity)
        resp = self._exchange(pdu)
        if resp[0] != 0x03:
            raise IOError("bad function")
        byte_count = resp[1]
        if byte_count != quantity * 2:
            raise IOError("bad byte count")
        return list(struct.unpack(">" + "H" * quantity, resp[2:2 + byte_count]))

    def write_single_register(self, address, value):
        pdu = struct.pack(">BHH", 0x06, address, value & 0xFFFF)
        self._exchange(pdu)

    def _exchange(self, pdu):
        if not self.sock:
            raise IOError("not connected")
        self.tx = (self.tx + 1) & 0xFFFF
        frame = struct.pack(">HHHB", self.tx, 0, len(pdu) + 1, self.unit_id) + pdu
        self.sock.sendall(frame)

        header = self._recv_exact(7)
        tx, proto, length, unit = struct.unpack(">HHHB", header)
        resp = self._recv_exact(length - 1)
        if tx != self.tx:
            raise IOError("tx mismatch")
        if resp[0] & 0x80:
            raise IOError("modbus exception 0x%02x" % resp[1])
        return resp

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise IOError("connection closed")
            buf += chunk
        return buf


class Alarm:
    _next_id = 1

    def __init__(self, stop_time, start_counter=0, shift_id=None):
        self.id = Alarm._next_id
        Alarm._next_id += 1
        self.stop_time = stop_time
        self.start_time = None
        self.duration_ms = None
        self.cause_code = None
        self.cause_text = None
        self.cause_path = None
        self.shift_id = shift_id
        self.closed = False
        self.dialog_shown = False
        self.ack_pending = False
        self.start_counter = start_counter

    @property
    def ongoing(self):
        return self.start_time is None

    def duration(self, now=None):
        if self.duration_ms is not None:
            return self.duration_ms
        if self.start_time is not None:
            return self.start_time - self.stop_time
        if now is None:
            now = int(time.time() * 1000)
        return max(0, now - self.stop_time)


class AppLogic:
    """Порт LineStopViewModel.kt.

    Если задан `on_event`, каждый значимый переход порождает событие
    (словарь в формате MonitorEvent Android-приложения) — используется для
    интеграционных тестов со сервером веб-мониторинга.
    """

    def __init__(self, client, now=None, on_event=None, device_id="simulator"):
        self.client = client
        self.alarms = []
        self.dialog = None
        self.snapshot = None
        self.first_read = True
        self.prev_alarm_active = False
        self._now = now or (lambda: int(time.time() * 1000))
        self.on_event = on_event
        self.device_id = device_id
        self.shift = None
        self._shift_seq = 0

    # ---- events ---------------------------------------------------------
    def _emit(self, event_type, level, category, message, alarm=None, **extra):
        if self.on_event is None:
            return
        event = {
            "time": self._now(),
            "type": event_type,
            "level": level,
            "category": category,
            "message": message,
            "deviceId": self.device_id,
        }
        if alarm is not None:
            event["alarmId"] = alarm.id
            if alarm.shift_id is not None:
                event["shiftId"] = alarm.shift_id
        if self.shift:
            event.setdefault("operator", self.shift.get("operator"))
            event.setdefault("mechanic", self.shift.get("mechanic"))
        event.update(extra)
        self.on_event(event)

    # ---- helpers --------------------------------------------------------
    def open_alarm(self):
        for a in reversed(self.alarms):
            if not a.closed:
                return a
        return None

    def _current_shift_id(self):
        return self.shift["id"] if self.shift else None

    def _add_alarm(self, stop_time, start_counter):
        alarm = Alarm(stop_time, start_counter, shift_id=self._current_shift_id())
        self.alarms.append(alarm)
        self._emit(
            "ALARM_START", "WARN", "ALARM",
            "АВАРИЯ: сигнал появился", alarm=alarm
        )
        return alarm

    def _finalize_open_alarm(self, now):
        open_alarm = self.open_alarm()
        if open_alarm is None:
            return
        open_alarm.start_time = now
        open_alarm.duration_ms = max(0, now - open_alarm.stop_time)
        open_alarm.dialog_shown = True
        self.dialog = open_alarm
        self._emit(
            "ALARM_END", "INFO", "ALARM",
            "Авария завершена, длительность %d мс" % open_alarm.duration_ms,
            alarm=open_alarm, durationMs=open_alarm.duration_ms
        )

    # ---- polling --------------------------------------------------------
    def poll(self):
        regs = self.client.read_holding_registers(MODBUS_D_BASE, 6)
        cur = {
            "alarm_active": regs[0] != 0,
            "start_counter": regs[1],
            "ack_counter": regs[4],
            "last_cause": regs[5],
        }
        self._process(cur)
        self.snapshot = cur
        return cur

    def _process(self, cur):
        now = self._now()

        if self.first_read:
            self.first_read = False
            if cur["alarm_active"] and self.open_alarm() is None:
                self._add_alarm(now, cur["start_counter"])
            self.prev_alarm_active = cur["alarm_active"]
            return

        prev = self.snapshot
        alarm_started = cur["alarm_active"] and not prev["alarm_active"]
        alarm_ended = (not cur["alarm_active"]) and prev["alarm_active"]

        if alarm_started:
            self._add_alarm(now, cur["start_counter"])
        elif alarm_ended:
            self._finalize_open_alarm(now)

        ack_delta = (cur["ack_counter"] - prev["ack_counter"]) & 0xFFFF
        if ack_delta > 0:
            pending = [a for a in self.alarms if a.ack_pending][-ack_delta:]
            for p in pending:
                p.closed = True
                p.ack_pending = False
                if p.cause_code is None and cur["last_cause"] > 0:
                    p.cause_code = cur["last_cause"]
            if pending:
                self._emit(
                    "ACK_CONFIRMED", "INFO", "ACK",
                    "ПЛК подтвердил квитирование: %d шт." % len(pending)
                )

        self.prev_alarm_active = cur["alarm_active"]

    # ---- shifts ---------------------------------------------------------
    def start_shift(self, operator, mechanic=None):
        self._shift_seq += 1
        self.shift = {"id": self._shift_seq, "operator": operator,
                      "mechanic": mechanic or "", "start": self._now(), "end": None}
        self._emit(
            "SHIFT_START", "INFO", "SHIFT",
            "Смена начата: %s" % operator,
            operator=operator, mechanic=mechanic or "", shiftId=self.shift["id"]
        )

    def end_shift(self):
        if not self.shift:
            return
        shift = self.shift
        shift["end"] = self._now()
        self.dialog = None
        self.shift = None
        self._emit(
            "SHIFT_END", "INFO", "SHIFT",
            "Смена завершена: %s" % shift["operator"],
            operator=shift["operator"], mechanic=shift.get("mechanic", ""),
            shiftId=shift["id"]
        )

    # ---- actions --------------------------------------------------------
    def dismiss_dialog(self):
        if self.dialog is not None:
            self.dialog.dialog_shown = True
            self._emit(
                "CAUSE_DISMISSED", "WARN", "ALARM",
                "Диалог причины закрыт без выбора", alarm=self.dialog
            )
        self.dialog = None

    def acknowledge(self, alarm_id, cause_code, cause_text=None, cause_path=None):
        alarm = next((a for a in self.alarms if a.id == alarm_id), None)
        if alarm is None:
            return
        alarm.cause_code = cause_code
        alarm.cause_text = cause_text
        alarm.cause_path = cause_path
        alarm.closed = True        # закрываем сразу, не ждём ответа ПЛК
        alarm.ack_pending = True   # квитирование ещё уходит в ПЛК (D100/D101)
        alarm.dialog_shown = True
        self.dialog = None
        self._emit(
            "CAUSE_SELECTED", "INFO", "ALARM",
            "Причина выбрана: код %s" % cause_code, alarm=alarm,
            causeCode=cause_code, causeText=cause_text, causePath=cause_path
        )
        self.client.write_single_register(MODBUS_D_BASE + 100, cause_code)
        self.client.write_single_register(MODBUS_D_BASE + 101, 1)
