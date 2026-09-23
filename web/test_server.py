"""
Тесты веб-мониторинга (web/server.py).

Запуск:
    py -3 -m unittest web.test_server -v
или
    py -3 web/test_server.py
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from io import BytesIO

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from server import EventStore, create_server  # noqa: E402


class WebServerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        fd, cls.db_path = tempfile.mkstemp(suffix=".db")
        os.close(fd)
        os.unlink(cls.db_path)
        cls.server = create_server("127.0.0.1", 0, cls.db_path)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        if os.path.exists(cls.db_path):
            os.unlink(cls.db_path)

    # ---- helpers --------------------------------------------------------
    def request(self, method, path, payload=None):
        url = "http://127.0.0.1:%d%s" % (self.port, path)
        data = None
        headers = {}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as exc:
            body = exc.read()
            status = exc.code
            headers = dict(exc.headers)
            exc.close()
            return status, headers, body

    def get_json(self, path):
        status, _, body = self.request("GET", path)
        self.assertEqual(200, status)
        return json.loads(body.decode("utf-8"))

    def post_json(self, path, payload):
        status, _, body = self.request("POST", path, payload)
        return status, (json.loads(body.decode("utf-8")) if body else None)

    def event(self, **kwargs):
        base = {
            "deviceId": "tablet-1",
            "time": 1_700_000_000_000,
            "type": "ALARM_START",
            "level": "WARN",
            "category": "ALARM",
            "message": "АВАРИЯ: сигнал появился",
            "alarmId": 1,
            "shiftId": 10,
        }
        base.update(kwargs)
        return base

    # ---- tests ----------------------------------------------------------
    def test_01_health(self):
        data = self.get_json("/api/health")
        self.assertEqual("ok", data["status"])

    def test_02_dashboard_html(self):
        status, headers, body = self.request("GET", "/")
        self.assertEqual(200, status)
        self.assertIn("text/html", headers["Content-Type"])
        self.assertIn("Line Stop Monitor", body.decode("utf-8"))

    def test_03_empty_stats(self):
        data = self.get_json("/api/stats")
        self.assertEqual(0, data["events"])
        self.assertEqual(0, data["open_alarms"])
        self.assertNotIn("devices", data)

    def test_03b_dashboard_has_single_alarm_table(self):
        status, _, body = self.request("GET", "/")
        html = body.decode("utf-8")
        self.assertIn("Зарегистрированные аварии", html)
        self.assertNotIn("Устройств", html)

    def test_04_post_single_event(self):
        status, body = self.post_json("/api/events", self.event(message="single"))
        self.assertEqual(201, status)
        self.assertEqual(1, body["accepted"])
        events = self.get_json("/api/events?limit=5")["events"]
        self.assertTrue(any(e["message"] == "single" for e in events))

    def test_05_post_batch_with_device(self):
        status, body = self.post_json("/api/events", {
            "deviceId": "tablet-batch",
            "events": [
                self.event(type="SHIFT_START", category="SHIFT", alarmId=None,
                           message="Смена начата"),
                self.event(type="ALARM_START", alarmId=77, message="batch alarm"),
            ],
        })
        self.assertEqual(201, status)
        self.assertEqual(2, body["accepted"])

    def test_06_invalid_json_returns_400(self):
        url = "http://127.0.0.1:%d/api/events" % self.port
        req = urllib.request.Request(
            url, data=b"{not json", headers={"Content-Type": "application/json"},
            method="POST")
        try:
            urllib.request.urlopen(req, timeout=5)
            self.fail("expected 400")
        except urllib.error.HTTPError as exc:
            self.assertEqual(400, exc.code)
            exc.close()

    def test_07_empty_body_returns_400(self):
        status, _ = self.post_json("/api/events", None)
        self.assertEqual(400, status)

    def test_08_unknown_path_404(self):
        status, _, _ = self.request("GET", "/nope")
        self.assertEqual(404, status)

    def test_09_alarm_start_projection(self):
        self.post_json("/api/events", self.event(
            deviceId="proj-1", alarmId=5, type="ALARM_START",
            time=2_000_000))
        alarms = self.get_json("/api/alarms?open=1")["alarms"]
        row = next(a for a in alarms if a["device_id"] == "proj-1" and a["alarm_id"] == 5)
        self.assertEqual(0, row["closed"])
        self.assertEqual(2_000_000, row["stop_time"])

    def test_10_alarm_end_sets_duration(self):
        self.post_json("/api/events", self.event(
            deviceId="proj-2", alarmId=6, type="ALARM_START", time=3_000_000))
        self.post_json("/api/events", self.event(
            deviceId="proj-2", alarmId=6, type="ALARM_END", time=3_005_000,
            durationMs=5_000))
        row = next(a for a in self.get_json("/api/alarms")["alarms"]
                   if a["device_id"] == "proj-2" and a["alarm_id"] == 6)
        self.assertEqual(3_005_000, row["start_time"])
        self.assertEqual(5_000, row["duration_ms"])

    def test_11_cause_selected_closes_alarm(self):
        self.post_json("/api/events", self.event(
            deviceId="proj-3", alarmId=7, type="ALARM_START", time=4_000_000))
        self.post_json("/api/events", self.event(
            deviceId="proj-3", alarmId=7, type="CAUSE_SELECTED",
            causeCode=12, causePath="Оборудование / 1 / 1.2", time=4_001_000))
        row = next(a for a in self.get_json("/api/alarms")["alarms"]
                   if a["device_id"] == "proj-3" and a["alarm_id"] == 7)
        self.assertEqual(1, row["closed"])
        self.assertEqual(12, row["cause_code"])

    def test_12_ack_confirmed_closes_alarm(self):
        self.post_json("/api/events", self.event(
            deviceId="proj-4", alarmId=8, type="ALARM_START", time=5_000_000))
        self.post_json("/api/events", self.event(
            deviceId="proj-4", alarmId=8, type="ACK_CONFIRMED", time=5_002_000))
        row = next(a for a in self.get_json("/api/alarms")["alarms"]
                   if a["device_id"] == "proj-4" and a["alarm_id"] == 8)
        self.assertEqual(1, row["closed"])

    def test_13_events_filter_by_type(self):
        self.post_json("/api/events", self.event(
            deviceId="filt", type="PLC_LOST", category="PLC", alarmId=None,
            message="lost"))
        data = self.get_json("/api/events?type=PLC_LOST&device_id=filt")
        self.assertTrue(data["events"])
        self.assertTrue(all(e["type"] == "PLC_LOST" for e in data["events"]))

    def test_14_since_id(self):
        status, body = self.post_json("/api/events", self.event(
            type="APP_START", category="APP", alarmId=None, message="s1"))
        first_id = body["ids"][0]
        self.post_json("/api/events", self.event(
            type="APP_START", category="APP", alarmId=None, message="s2"))
        data = self.get_json("/api/events?since_id=%d" % first_id)
        self.assertTrue(all(e["id"] > first_id for e in data["events"]))

    def test_15_cors_header_present(self):
        status, headers, _ = self.request("GET", "/api/health")
        self.assertEqual("*", headers.get("Access-Control-Allow-Origin"))

    def test_16_type_falls_back_to_category(self):
        self.post_json("/api/events", {
            "deviceId": "fb", "time": 1, "category": "ALARM",
            "level": "WARN", "message": "fallback", "alarmId": None})
        data = self.get_json("/api/events?device_id=fb&limit=1")
        self.assertEqual("ALARM", data["events"][0]["type"])

    # ---- период / агрегаты / экспорт -----------------------------------
    T0 = 1_000_000_000_000
    T1 = 1_000_000_100_000

    def seed_period_alarms(self):
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=101, type="ALARM_START", time=self.T0,
            message="a101"))
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=101, type="ALARM_END",
            time=self.T0 + 7_000, durationMs=7_000, message="e101"))
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=101, type="CAUSE_SELECTED",
            time=self.T0 + 8_000, causeCode=3, causePath="A / B", message="c101"))
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=102, type="ALARM_START", time=self.T1,
            message="a102"))
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=102, type="ALARM_END",
            time=self.T1 + 3_000, durationMs=3_000, message="e102"))
        self.post_json("/api/events", self.event(
            deviceId="period", alarmId=102, type="ACK_CONFIRMED",
            time=self.T1 + 4_000, message="k102"))

    def period_query(self):
        return "?from=%d&to=%d" % (self.T0 - 1, self.T1 + 10_000)

    def test_17_summary_aggregates(self):
        self.seed_period_alarms()
        data = self.get_json("/api/summary" + self.period_query())
        self.assertEqual(2, data["total"])
        self.assertEqual(2, data["closed"])
        self.assertEqual(0, data["open"])
        self.assertEqual(10_000, data["total_duration_ms"])
        self.assertEqual(5_000, data["avg_duration_ms"])
        self.assertEqual(7_000, data["max_duration_ms"])
        self.assertEqual(3_000, data["min_duration_ms"])
        causes = {c["cause"]: c for c in data["by_cause"]}
        self.assertEqual(1, causes["A / B"]["count"])
        self.assertEqual(7_000, causes["A / B"]["duration_ms"])
        self.assertEqual(1, causes["Причина не указана"]["count"])
        self.assertTrue(data["by_day"])

    def test_18_alarms_date_filter(self):
        self.seed_period_alarms()
        data = self.get_json("/api/alarms" + self.period_query())
        ids = {a["alarm_id"] for a in data["alarms"]}
        self.assertEqual({101, 102}, ids)

        only_first = self.get_json(
            "/api/alarms?from=%d&to=%d" % (self.T0 - 1, self.T0 + 1))
        self.assertEqual([101], [a["alarm_id"] for a in only_first["alarms"]])

    def test_20_projection_uses_explicit_times(self):
        t = 1_000_000_200_000
        self.post_json("/api/events", {
            "deviceId": "tm", "alarmId": 201, "time": t, "stopTime": t,
            "type": "ALARM_START", "category": "ALARM", "message": "s"})
        self.post_json("/api/events", {
            "deviceId": "tm", "alarmId": 201, "time": t + 5_000,
            "stopTime": t, "startTime": t + 5_000, "durationMs": 5_000,
            "type": "ALARM_END", "category": "ALARM", "message": "e"})
        row = next(a for a in self.get_json("/api/alarms?device_id=tm")["alarms"]
                   if a["alarm_id"] == 201)
        self.assertEqual(t, row["stop_time"])
        self.assertEqual(t + 5_000, row["start_time"])
        self.assertEqual(5_000, row["duration_ms"])

    def test_21_repair_fills_missing_end_and_duration(self):
        """Авария закрыта выбором причины без ALARM_END — времена достраиваются."""
        import tempfile as _tf
        fd, path = _tf.mkstemp(suffix=".db")
        os.close(fd)
        os.unlink(path)
        try:
            store = EventStore(path)
            store.add_events([
                {"deviceId": "rep", "alarmId": 1, "time": 1_000,
                 "type": "ALARM_START", "category": "ALARM", "message": "s"},
                {"deviceId": "rep", "alarmId": 1, "time": 9_000,
                 "type": "CAUSE_SELECTED", "category": "ALARM", "message": "c",
                 "causeCode": 4},
            ])
            # повторное открытие запускает repair_alarms()
            store2 = EventStore(path)
            row = store2.alarms(device_id="rep")[0]
            self.assertEqual(1_000, row["stop_time"])
            self.assertEqual(9_000, row["start_time"])
            self.assertEqual(8_000, row["duration_ms"])
            self.assertEqual(1, row["closed"])
        finally:
            if os.path.exists(path):
                try:
                    os.unlink(path)
                except PermissionError:
                    pass

    def test_22_open_alarm_keeps_start_from_event(self):
        t = 1_000_000_300_000
        self.post_json("/api/events", {
            "deviceId": "op", "alarmId": 301, "time": t, "stopTime": t,
            "type": "ALARM_START", "category": "ALARM", "message": "s"})
        row = next(a for a in self.get_json("/api/alarms?device_id=op")["alarms"]
                   if a["alarm_id"] == 301)
        self.assertEqual(t, row["stop_time"])
        self.assertEqual(0, row["closed"])

    def test_23_operator_mechanic_filters(self):
        from urllib.parse import quote
        t = 1_000_000_400_000
        for aid, op, mech in [(401, "Иванов", "Петров"),
                              (402, "Сидоров", "Петров"),
                              (403, "Иванов", "Кузнецов")]:
            self.post_json("/api/events", {
                "deviceId": "oms", "alarmId": aid, "time": t, "stopTime": t,
                "type": "ALARM_START", "category": "ALARM", "message": "s",
                "operator": op, "mechanic": mech})
            self.post_json("/api/events", {
                "deviceId": "oms", "alarmId": aid, "time": t + 1_000,
                "stopTime": t, "startTime": t + 1_000, "durationMs": 1_000,
                "type": "ALARM_END", "category": "ALARM", "message": "e",
                "operator": op, "mechanic": mech})
        q = "?from=%d&to=%d" % (t - 1, t + 5_000)

        filters = self.get_json("/api/filters" + q)
        self.assertEqual(["Иванов", "Сидоров"], sorted(filters["operators"]))
        self.assertEqual(["Кузнецов", "Петров"], sorted(filters["mechanics"]))

        ivan = self.get_json("/api/alarms" + q + "&operator=" + quote("Иванов"))
        self.assertEqual({401, 403}, {a["alarm_id"] for a in ivan["alarms"]})
        petrov = self.get_json("/api/alarms" + q + "&mechanic=" + quote("Петров"))
        self.assertEqual({401, 402}, {a["alarm_id"] for a in petrov["alarms"]})
        both = self.get_json(
            "/api/alarms" + q + "&operator=" + quote("Иванов")
            + "&mechanic=" + quote("Петров"))
        self.assertEqual([401], [a["alarm_id"] for a in both["alarms"]])

        summary = self.get_json("/api/summary" + q + "&operator=" + quote("Иванов"))
        self.assertEqual(2, summary["total"])

        # экспорт учитывает фильтр и содержит оператора/механика
        status, _, body = self.request(
            "GET", "/api/export.xlsx" + q + "&operator=" + quote("Иванов"))
        self.assertEqual(200, status)
        with zipfile.ZipFile(BytesIO(body)) as z:
            sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
            self.assertIn("Оператор", sheet)
            self.assertIn("Механик", sheet)
            self.assertIn("Иванов", sheet)
            self.assertNotIn("Сидоров", sheet)

    def test_19_export_xlsx(self):
        self.seed_period_alarms()
        status, headers, body = self.request(
            "GET", "/api/export.xlsx" + self.period_query())
        self.assertEqual(200, status)
        self.assertIn("spreadsheetml.sheet", headers["Content-Type"])
        self.assertIn(".xlsx", headers.get("Content-Disposition", ""))

        with zipfile.ZipFile(BytesIO(body)) as z:
            names = set(z.namelist())
            self.assertIn("[Content_Types].xml", names)
            self.assertIn("xl/workbook.xml", names)
            self.assertIn("xl/worksheets/sheet1.xml", names)
            workbook = z.read("xl/workbook.xml").decode("utf-8")
            self.assertIn("Аварии", workbook)
            sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
            self.assertIn("Причина", sheet)
            self.assertIn("101", sheet)
            self.assertIn("102", sheet)
            summary = z.read("xl/worksheets/sheet4.xml").decode("utf-8")
            self.assertIn("Суммарная", summary)


    def test_24_shift_log_and_export(self):
        from urllib.parse import quote
        t = 1_000_000_500_000
        for typ in ["SHIFT_REQUIRED", "SHIFT_DECLINED", "SHIFT_START", "SHIFT_END"]:
            self.post_json("/api/events", {
                "deviceId": "shiftlog", "alarmId": None, "time": t, "type": typ,
                "category": "SHIFT", "message": typ,
                "operator": "Иванов", "mechanic": "Петров"})
            t += 1_000
        q = "?from=%d&to=%d" % (1_000_000_500_000 - 1, t + 5_000)

        events = self.get_json("/api/shift-events" + q)["events"]
        types = {e["type"] for e in events}
        self.assertEqual(
            {"SHIFT_REQUIRED", "SHIFT_DECLINED", "SHIFT_START", "SHIFT_END"},
            types)
        for e in events:
            self.assertNotIn(e.get("category"), ("ALARM",))

        filtered = self.get_json(
            "/api/shift-events" + q + "&operator=" + quote("Иванов"))["events"]
        self.assertEqual(4, len(filtered))

        status, headers, body = self.request(
            "GET", "/api/export-shifts.xlsx" + q)
        self.assertEqual(200, status)
        self.assertIn("spreadsheetml.sheet", headers["Content-Type"])
        self.assertIn(".xlsx", headers.get("Content-Disposition", ""))
        with zipfile.ZipFile(BytesIO(body)) as z:
            workbook = z.read("xl/workbook.xml").decode("utf-8")
            self.assertIn("Смены", workbook)
            sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
            self.assertIn("Начало смены", sheet)
            self.assertIn("Отклонение автопредложения", sheet)
            self.assertIn("Иванов", sheet)


class StorePersistenceTest(unittest.TestCase):
    def test_events_persist_across_reopen(self):
        fd, path = tempfile.mkstemp(suffix=".db")
        os.close(fd)
        os.unlink(path)
        try:
            store1 = EventStore(path)
            store1.add_events([{
                "deviceId": "d1", "time": 1, "type": "ALARM_START",
                "category": "ALARM", "message": "persist", "alarmId": 1}])
            store2 = EventStore(path)
            self.assertEqual(1, store2.stats()["events"])
            self.assertEqual(1, store2.stats()["open_alarms"])
        finally:
            if os.path.exists(path):
                os.unlink(path)

    def test_normalize_single_and_list(self):
        from server import normalize_payload
        events, device = normalize_payload({"deviceId": "x", "message": "a"})
        self.assertEqual(1, len(events))
        self.assertEqual("x", device)
        events, _ = normalize_payload([{"message": "a"}, {"message": "b"}, "junk"])
        self.assertEqual(2, len(events))


if __name__ == "__main__":
    unittest.main(verbosity=2)
