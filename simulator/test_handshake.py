"""
Интеграционный тест протокола "Android <-> DVP12SE11R".

Сигнал аварии уровневый: X0=1 -> авария, X0=0 -> линию запустили.
Карта регистров — как в ладдер-программе заказчика (D0, D1, D4, D5, D100, D101).

Запуск:
    py -3 -m unittest discover -s simulator -v
"""

import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app_logic import AppLogic, ModbusTcpClient  # noqa: E402
from plc_sim import ModbusServer, PlcModel  # noqa: E402


class Clock:
    """Детерминированные "часы" приложения."""

    def __init__(self):
        self.t = 1_700_000_000_000

    def __call__(self):
        return self.t

    def tick(self, ms=1000):
        self.t += ms


class HandshakeTest(unittest.TestCase):

    def setUp(self):
        self.plc = PlcModel(scan_interval=0.002)
        self.plc.start()
        self.server = ModbusServer("127.0.0.1", 0, self.plc)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

        self.clock = Clock()
        self.client = ModbusTcpClient("127.0.0.1", self.port)
        self.client.connect()
        self.app = AppLogic(self.client, now=self.clock)

    def tearDown(self):
        self.client.close()
        self.server.shutdown()
        self.server.server_close()
        self.plc.stop()

    # ---- helpers --------------------------------------------------------
    def poll(self, times=3, delay=0.02):
        last = None
        for _ in range(times):
            last = self.app.poll()
            time.sleep(delay)
        return last

    def poll_until(self, predicate, timeout=2.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            snap = self.app.poll()
            if predicate(snap):
                return snap
            time.sleep(0.01)
        self.fail("condition not reached within %.1fs (snapshot=%s)" % (timeout, self.app.snapshot))

    def alarm_on(self):
        self.plc.set_alarm(True)

    def alarm_off(self):
        self.plc.set_alarm(False)

    # ---- tests ----------------------------------------------------------
    def test_01_initial_state_is_ok(self):
        snap = self.poll()
        self.assertEqual(snap["alarm_active"], False)
        self.assertEqual(len(self.app.alarms), 0)

    def test_02_alarm_on_creates_active_alarm(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])

        self.assertEqual(len(self.app.alarms), 1)
        alarm = self.app.alarms[0]
        self.assertFalse(alarm.closed)
        self.assertTrue(alarm.ongoing)
        self.assertEqual(alarm.stop_time, self.clock.t)
        self.assertIsNone(self.app.dialog)

    def test_03_alarm_off_shows_dialog_with_period(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])

        self.clock.tick(5000)
        self.alarm_off()
        self.poll_until(lambda s: self.app.dialog is not None)

        alarm = self.app.alarms[0]
        self.assertFalse(alarm.ongoing)
        self.assertEqual(alarm.start_time, self.clock.t)
        self.assertEqual(alarm.duration_ms, 5000)
        self.assertEqual(self.plc.D[1], 1)          # счётчик запусков
        self.assertFalse(alarm.closed)
        self.assertEqual(self.app.snapshot["alarm_active"], False)

    def test_04_acknowledge_closes_case(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])
        self.alarm_off()
        self.poll_until(lambda s: self.app.dialog is not None)

        alarm = self.app.alarms[0]
        self.app.acknowledge(alarm.id, cause_code=3)
        self.assertTrue(alarm.closed)          # закрывается сразу
        self.assertEqual(alarm.cause_code, 3)
        self.poll_until(lambda s: s["ack_counter"] == 1)
        self.assertFalse(alarm.ack_pending)    # ПЛК подтвердил

        self.assertEqual(self.plc.D[4], 1)
        self.assertEqual(self.plc.D[5], 3)
        self.assertEqual(self.plc.D[101], 0)
        self.assertEqual(self.plc.D[100], 0)

    def test_05_dismissed_dialog_keeps_alarm_active(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])
        self.alarm_off()
        self.poll_until(lambda s: self.app.dialog is not None)

        self.app.dismiss_dialog()
        self.poll(times=3)

        alarm = self.app.alarms[0]
        self.assertFalse(alarm.closed)
        self.assertEqual(len([a for a in self.app.alarms if not a.closed]), 1)

        self.app.acknowledge(alarm.id, cause_code=7)
        self.assertTrue(alarm.closed)
        self.assertEqual(alarm.cause_code, 7)
        self.poll_until(lambda s: s["ack_counter"] == 1)

    def test_06_other_cause_with_text(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])
        self.alarm_off()
        self.poll_until(lambda s: self.app.dialog is not None)

        alarm = self.app.alarms[0]
        self.app.acknowledge(alarm.id, cause_code=99, cause_text="Порвался ремень")
        self.assertTrue(alarm.closed)
        self.assertEqual(alarm.cause_code, 99)
        self.assertEqual(alarm.cause_text, "Порвался ремень")
        self.poll_until(lambda s: s["ack_counter"] == 1)
        self.assertEqual(self.plc.D[5], 99)

    def test_07_second_alarm_before_ack(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])
        self.alarm_off()
        self.poll_until(lambda s: self.app.dialog is not None)

        # оператор не ответил, линия снова упала и снова запустилась
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])
        self.alarm_off()
        self.poll_until(lambda s: self.app.snapshot["start_counter"] == 2)

        self.assertEqual(len(self.app.alarms), 2)
        open_cases = [a for a in self.app.alarms if not a.closed]
        self.assertEqual(len(open_cases), 2)

        self.app.acknowledge(open_cases[0].id, cause_code=1)
        self.assertTrue(open_cases[0].closed)
        self.poll_until(lambda s: s["ack_counter"] == 1)
        self.app.acknowledge(open_cases[1].id, cause_code=4)
        self.assertTrue(open_cases[1].closed)
        self.poll_until(lambda s: s["ack_counter"] == 2)
        self.assertEqual(self.plc.D[5], 4)
        self.assertEqual(len([a for a in self.app.alarms if not a.closed]), 0)

    def test_08_reconnect_while_alarm_active_restores_alarm(self):
        self.poll()
        self.alarm_on()
        self.poll_until(lambda s: s["alarm_active"])

        # приложение "перезапустилось" во время аварии
        self.client.close()
        self.client = ModbusTcpClient("127.0.0.1", self.port)
        self.client.connect()
        self.app = AppLogic(self.client, now=self.clock)
        self.poll()

        self.assertEqual(len(self.app.alarms), 1)
        self.assertTrue(self.app.alarms[0].ongoing)
        self.assertEqual(self.app.snapshot["alarm_active"], True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
