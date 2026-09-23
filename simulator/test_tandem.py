"""
Интеграционный тест связки «ПЛК -> приложение -> веб-мониторинг».

Поднимает эмулятор ПЛК, Python-порт логики приложения и веб-сервер,
прогоняет полный сценарий смены и проверяет, что все события дошли до
базы SQLite веб-приложения.

Запуск:
    py -3 -m unittest simulator.test_tandem -v
"""

import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from urllib.parse import quote

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "web"))

from app_logic import AppLogic, ModbusTcpClient  # noqa: E402
from plc_sim import ModbusServer, PlcModel  # noqa: E402
from server import create_server  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1_700_000_000_000

    def __call__(self):
        return self.t

    def tick(self, ms=1000):
        self.t += ms


class TandemTest(unittest.TestCase):

    def setUp(self):
        # --- веб-сервер мониторинга ---
        fd, self.db_path = tempfile.mkstemp(suffix=".db")
        os.close(fd)
        os.unlink(self.db_path)
        self.web = create_server("127.0.0.1", 0, self.db_path)
        self.web_port = self.web.server_address[1]
        threading.Thread(target=self.web.serve_forever, daemon=True).start()
        self.web_url = "http://127.0.0.1:%d" % self.web_port

        # --- эмулятор ПЛК ---
        self.plc = PlcModel(scan_interval=0.002)
        self.plc.start()
        self.modbus = ModbusServer("127.0.0.1", 0, self.plc)
        self.modbus_port = self.modbus.server_address[1]
        threading.Thread(target=self.modbus.serve_forever, daemon=True).start()

        # --- приложение с отправкой событий в веб ---
        self.clock = Clock()
        self.client = ModbusTcpClient("127.0.0.1", self.modbus_port)
        self.client.connect()
        self.app = AppLogic(
            self.client, now=self.clock, on_event=self._post_event,
            device_id="tandem-1"
        )

    def tearDown(self):
        self.client.close()
        self.modbus.shutdown()
        self.modbus.server_close()
        self.plc.stop()
        self.web.shutdown()
        self.web.server_close()
        if os.path.exists(self.db_path):
            try:
                os.unlink(self.db_path)
            except PermissionError:
                pass

    # ---- helpers --------------------------------------------------------
    def _post_event(self, event):
        data = json.dumps([event], ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self.web_url + "/api/events", data=data,
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 201, resp.status

    def get_json(self, path):
        with urllib.request.urlopen(self.web_url + path, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def poll_until(self, predicate, timeout=2.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            snapshot = self.app.poll()
            if predicate(snapshot):
                return snapshot
            time.sleep(0.01)
        self.fail("condition not reached (snapshot=%s)" % self.app.snapshot)

    def poll(self, times=3):
        for _ in range(times):
            self.app.poll()
            time.sleep(0.02)

    # ---- test -----------------------------------------------------------
    def test_full_shift_reaches_web_monitoring(self):
        # 1. оператор начал смену вместе с механиком
        self.app.start_shift("Иванов", "Петров")

        # 2. авария: сигнал появился
        self.plc.set_alarm(True)
        self.poll_until(lambda s: s["alarm_active"])
        self.assertEqual(1, len(self.app.alarms))

        # 3. линия запущена через 5 c
        self.clock.tick(5000)
        self.plc.set_alarm(False)
        self.poll_until(lambda s: self.app.dialog is not None)
        alarm = self.app.alarms[0]

        # 4. оператор выбрал причину -> квитирование уходит в ПЛК
        self.app.acknowledge(alarm.id, cause_code=5,
                             cause_path="Оборудование / 1 / 1.1")
        self.poll_until(lambda s: s["ack_counter"] == 1)

        # 5. смена закончена
        self.app.end_shift()

        # --- проверяем веб-мониторинг ---
        stats = self.get_json("/api/stats")
        self.assertEqual(1, stats["alarms"])
        self.assertEqual(0, stats["open_alarms"])
        self.assertGreaterEqual(stats["events"], 6)
        self.assertNotIn("devices", stats)

        events = self.get_json("/api/events?limit=100")["events"]
        types = {e["type"] for e in events}
        for expected in ("SHIFT_START", "ALARM_START", "ALARM_END",
                         "CAUSE_SELECTED", "ACK_CONFIRMED", "SHIFT_END"):
            self.assertIn(expected, types)

        alarms = self.get_json("/api/alarms")["alarms"]
        self.assertEqual(1, len(alarms))
        row = alarms[0]
        self.assertEqual("tandem-1", row["device_id"])
        self.assertEqual(1, row["closed"])
        self.assertEqual(5, row["cause_code"])
        self.assertEqual(5000, row["duration_ms"])
        self.assertEqual("Оборудование / 1 / 1.1", row["cause_path"])
        self.assertEqual("Иванов", row["operator"])
        self.assertEqual("Петров", row["mechanic"])

        # фильтр по оператору и механику
        by_operator = self.get_json(
            "/api/alarms?operator=" + quote("Иванов"))["alarms"]
        self.assertEqual(1, len(by_operator))
        by_mechanic = self.get_json(
            "/api/alarms?mechanic=" + quote("Петров"))["alarms"]
        self.assertEqual(1, len(by_mechanic))
        none_found = self.get_json(
            "/api/alarms?mechanic=" + quote("Сидоров"))["alarms"]
        self.assertEqual(0, len(none_found))

        filters = self.get_json("/api/filters")
        self.assertIn("Иванов", filters["operators"])
        self.assertIn("Петров", filters["mechanics"])

        summary = self.get_json(
            "/api/summary?operator=" + quote("Иванов")
            + "&mechanic=" + quote("Петров"))
        self.assertEqual(1, summary["total"])
        self.assertEqual(
            0, self.get_json("/api/summary?operator=" + quote("Сидоров"))["total"])

    def test_alarm_without_shift_is_recorded(self):
        """Порт логики не блокирует события вне смены — они всё равно уходят."""
        self.plc.set_alarm(True)
        self.poll_until(lambda s: s["alarm_active"])
        self.plc.set_alarm(False)
        self.poll_until(lambda s: self.app.dialog is not None)

        events = self.get_json("/api/events?type=ALARM_START")["events"]
        self.assertEqual(1, len(events))
        self.assertIsNone(events[0]["shift_id"])

    def test_health_and_dashboard_available(self):
        with urllib.request.urlopen(self.web_url + "/api/health", timeout=5) as r:
            self.assertEqual(200, r.status)
        with urllib.request.urlopen(self.web_url + "/", timeout=5) as r:
            body = r.read().decode("utf-8")
        self.assertIn("Line Stop Monitor", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
