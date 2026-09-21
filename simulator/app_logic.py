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

    def __init__(self, stop_time, start_counter=0):
        self.id = Alarm._next_id
        Alarm._next_id += 1
        self.stop_time = stop_time
        self.start_time = None
        self.duration_ms = None
        self.cause_code = None
        self.cause_text = None
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
    """Порт LineStopViewModel.kt."""

    def __init__(self, client, now=None):
        self.client = client
        self.alarms = []
        self.dialog = None
        self.snapshot = None
        self.first_read = True
        self.prev_alarm_active = False
        self._now = now or (lambda: int(time.time() * 1000))

    # ---- helpers --------------------------------------------------------
    def open_alarm(self):
        for a in reversed(self.alarms):
            if not a.closed:
                return a
        return None

    def _add_alarm(self, stop_time, start_counter):
        alarm = Alarm(stop_time, start_counter)
        self.alarms.append(alarm)
        return alarm

    def _finalize_open_alarm(self, now):
        open_alarm = self.open_alarm()
        if open_alarm is None:
            return
        open_alarm.start_time = now
        open_alarm.duration_ms = max(0, now - open_alarm.stop_time)
        open_alarm.dialog_shown = True
        self.dialog = open_alarm

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

        self.prev_alarm_active = cur["alarm_active"]

    # ---- actions --------------------------------------------------------
    def dismiss_dialog(self):
        if self.dialog is not None:
            self.dialog.dialog_shown = True
        self.dialog = None

    def acknowledge(self, alarm_id, cause_code, cause_text=None):
        alarm = next((a for a in self.alarms if a.id == alarm_id), None)
        if alarm is None:
            return
        alarm.cause_code = cause_code
        alarm.cause_text = cause_text
        alarm.closed = True        # закрываем сразу, не ждём ответа ПЛК
        alarm.ack_pending = True   # квитирование ещё уходит в ПЛК (D100/D101)
        alarm.dialog_shown = True
        self.dialog = None
        self.client.write_single_register(MODBUS_D_BASE + 100, cause_code)
        self.client.write_single_register(MODBUS_D_BASE + 101, 1)
