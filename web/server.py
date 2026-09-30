"""
Line Stop Monitor — веб-мониторинг аварий.

Принимает события от Android-приложений по HTTP и складывает их в SQLite3.
Показывает живую панель мониторинга.

Только стандартная библиотека Python (http.server + sqlite3) — без Flask и pip.

Запуск:
    py -3 web/server.py --host 0.0.0.0 --port 8080 --db web/linestop_web.db

API:
    POST /api/events   — приём событий (один объект или батч)
    GET  /api/events   — список событий (?limit=&since_id=&type=&device_id=)
    GET  /api/alarms   — случаи аварий (?open=1)
    GET  /api/stats    — счётчики
    GET  /api/health   — проверка живости
    GET  /             — панель мониторинга (HTML)
"""

import argparse
import hashlib
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
import zipfile
from contextlib import contextmanager
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO

DEFAULT_PORT = 8080
SESSION_COOKIE = "lsm_session"
SESSION_TTL = 8 * 3600

# Пользователи веб-панели. Пароли хранятся SHA-256-хешем.
USERS = {
    "admin": {
        "hash": hashlib.sha256("admin".encode("utf-8")).hexdigest(),
        "role": "admin",
    }
}

# Активные сессии: token -> {"user", "role", "expires"}
SESSIONS = {}

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    received_at INTEGER NOT NULL,
    device_time INTEGER,
    device_id   TEXT,
    type        TEXT,
    level       TEXT,
    category    TEXT,
    message     TEXT,
    alarm_id    INTEGER,
    shift_id    INTEGER,
    cause_code  INTEGER,
    cause_text  TEXT,
    cause_path  TEXT,
    duration_ms INTEGER,
    operator    TEXT,
    mechanic    TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_received ON events(received_at);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
CREATE INDEX IF NOT EXISTS idx_events_device ON events(device_id);

CREATE TABLE IF NOT EXISTS alarms (
    device_id   TEXT NOT NULL,
    alarm_id    INTEGER NOT NULL,
    shift_id    INTEGER,
    stop_time   INTEGER,
    start_time  INTEGER,
    duration_ms INTEGER,
    cause_code  INTEGER,
    cause_text  TEXT,
    cause_path  TEXT,
    closed      INTEGER NOT NULL DEFAULT 0,
    operator    TEXT,
    mechanic    TEXT,
    updated_at  INTEGER,
    PRIMARY KEY (device_id, alarm_id)
);
"""

ALARM_START_LEVELS = ("WARN", "ERROR")


def now_ms():
    return int(time.time() * 1000)


class EventStore:
    """Хранилище событий и проекция «случаи аварий» в SQLite3."""

    def __init__(self, db_path):
        self.db_path = db_path
        self._lock = threading.Lock()
        if db_path != ":memory:":
            parent = os.path.dirname(os.path.abspath(db_path))
            if parent:
                os.makedirs(parent, exist_ok=True)
        with self._session() as con:
            con.executescript(SCHEMA)
            self._ensure_column(con, "events", "mechanic", "TEXT")
            self._ensure_column(con, "alarms", "operator", "TEXT")
            self._ensure_column(con, "alarms", "mechanic", "TEXT")
        self.repair_alarms()

    @staticmethod
    def _ensure_column(con, table, column, ddl):
        existing = [row["name"] for row in con.execute("PRAGMA table_info(%s)" % table)]
        if column not in existing:
            con.execute("ALTER TABLE %s ADD COLUMN %s %s" % (table, column, ddl))

    def _connect(self):
        con = sqlite3.connect(self.db_path, timeout=10)
        con.row_factory = sqlite3.Row
        return con

    @contextmanager
    def _session(self):
        """Транзакция с гарантированным закрытием соединения."""
        con = self._connect()
        try:
            yield con
            con.commit()
        except Exception:
            con.rollback()
            raise
        finally:
            con.close()

    # ---------------------------------------------------------- ingestion
    def add_events(self, events, received_at=None):
        """Сохраняет список событий, возвращает список их id."""
        if received_at is None:
            received_at = now_ms()
        ids = []
        with self._lock, self._session() as con:
            for event in events:
                ids.append(self._insert_event(con, event, received_at))
                self._project(con, event, received_at)
        return ids

    @staticmethod
    def _insert_event(con, event, received_at):
        cur = con.execute(
            """
            INSERT INTO events (
                received_at, device_time, device_id, type, level, category,
                message, alarm_id, shift_id, cause_code, cause_text,
                cause_path, duration_ms, operator, mechanic
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                received_at,
                event.get("time") or event.get("deviceTime"),
                str(event.get("deviceId") or "unknown"),
                event.get("type"),
                event.get("level"),
                event.get("category"),
                event.get("message"),
                event.get("alarmId"),
                event.get("shiftId"),
                event.get("causeCode"),
                event.get("causeText"),
                event.get("causePath"),
                event.get("durationMs"),
                event.get("operator"),
                event.get("mechanic"),
            ),
        )
        return cur.lastrowid

    @staticmethod
    def _project(con, event, received_at):
        """Обновляет таблицу `alarms` по событию (если есть alarmId)."""
        alarm_id = event.get("alarmId")
        if alarm_id is None:
            return
        device_id = str(event.get("deviceId") or "unknown")
        etype = event.get("type") or ""
        device_time = event.get("time") or event.get("deviceTime")
        shift_id = event.get("shiftId")
        stop_time = event.get("stopTime")
        start_time = event.get("startTime")
        duration = event.get("durationMs")
        operator = event.get("operator")
        mechanic = event.get("mechanic")

        if etype == "ALARM_START":
            stop = stop_time or device_time
            con.execute(
                """
                INSERT INTO alarms (device_id, alarm_id, shift_id, stop_time,
                                    operator, mechanic, start_time, duration_ms,
                                    closed, updated_at)
                VALUES (?,?,?,?,?,?,NULL,NULL,0,?)
                ON CONFLICT(device_id, alarm_id) DO UPDATE SET
                    stop_time = COALESCE(excluded.stop_time, alarms.stop_time),
                    shift_id = COALESCE(excluded.shift_id, alarms.shift_id),
                    operator = COALESCE(excluded.operator, alarms.operator),
                    mechanic = COALESCE(excluded.mechanic, alarms.mechanic),
                    closed = 0,
                    updated_at = excluded.updated_at
                """,
                (device_id, alarm_id, shift_id, stop, operator, mechanic, received_at),
            )
        elif etype == "ALARM_END":
            start = start_time or device_time
            stop = stop_time
            if duration is None and start is not None and stop is not None:
                duration = max(0, start - stop)
            con.execute(
                """
                INSERT INTO alarms (device_id, alarm_id, shift_id, stop_time,
                                    start_time, duration_ms, operator, mechanic,
                                    closed, updated_at)
                VALUES (?,?,?,?,?,?,?,?,0,?)
                ON CONFLICT(device_id, alarm_id) DO UPDATE SET
                    stop_time = COALESCE(excluded.stop_time, alarms.stop_time),
                    start_time = COALESCE(excluded.start_time, alarms.start_time),
                    duration_ms = COALESCE(excluded.duration_ms, alarms.duration_ms),
                    operator = COALESCE(excluded.operator, alarms.operator),
                    mechanic = COALESCE(excluded.mechanic, alarms.mechanic),
                    updated_at = excluded.updated_at
                """,
                (device_id, alarm_id, shift_id, stop, start, duration,
                 operator, mechanic, received_at),
            )
        elif etype == "CAUSE_SELECTED":
            con.execute(
                """
                INSERT INTO alarms (device_id, alarm_id, shift_id, stop_time,
                                    start_time, duration_ms, cause_code,
                                    cause_text, cause_path, operator, mechanic,
                                    closed, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?)
                ON CONFLICT(device_id, alarm_id) DO UPDATE SET
                    stop_time = COALESCE(excluded.stop_time, alarms.stop_time),
                    start_time = COALESCE(excluded.start_time, alarms.start_time),
                    duration_ms = COALESCE(excluded.duration_ms, alarms.duration_ms),
                    cause_code = excluded.cause_code,
                    cause_text = excluded.cause_text,
                    cause_path = excluded.cause_path,
                    operator = COALESCE(excluded.operator, alarms.operator),
                    mechanic = COALESCE(excluded.mechanic, alarms.mechanic),
                    closed = 1,
                    updated_at = excluded.updated_at
                """,
                (device_id, alarm_id, shift_id, stop_time, start_time, duration,
                 event.get("causeCode"), event.get("causeText"),
                 event.get("causePath"), operator, mechanic, received_at),
            )
        elif etype == "ACK_CONFIRMED":
            con.execute(
                """
                UPDATE alarms SET closed = 1, updated_at = ?
                WHERE device_id = ? AND alarm_id = ?
                """,
                (received_at, device_id, alarm_id),
            )

    def repair_alarms(self):
        """Достраивает недостающие начало/конец/длительность из журнала событий.

        Нужно для случаев, когда часть событий пришла неполной (например, причину
        выбрали у ещё идущей аварии): у каждой зарегистрированной аварии должны
        быть время начала, время конца и продолжительность.
        """
        with self._lock, self._session() as con:
            rows = con.execute(
                """
                SELECT device_id, alarm_id, stop_time, start_time, duration_ms, closed
                FROM alarms
                WHERE stop_time IS NULL OR start_time IS NULL OR duration_ms IS NULL
                """
            ).fetchall()
            for r in rows:
                events = con.execute(
                    """
                    SELECT type, device_time, duration_ms FROM events
                    WHERE device_id = ? AND alarm_id = ? ORDER BY id
                    """,
                    (r["device_id"], r["alarm_id"]),
                ).fetchall()
                if not events:
                    continue
                starts = [e for e in events if e["type"] == "ALARM_START"]
                ends = [e for e in events if e["type"] == "ALARM_END"]
                closes = [e for e in events
                          if e["type"] in ("ALARM_END", "CAUSE_SELECTED", "ACK_CONFIRMED")]

                stop = r["stop_time"]
                if stop is None and starts:
                    stop = starts[0]["device_time"]
                if stop is None and ends and ends[0]["duration_ms"] is not None:
                    stop = (ends[0]["device_time"] or 0) - ends[0]["duration_ms"]
                if stop is None:
                    stop = events[0]["device_time"]

                start = r["start_time"]
                if start is None and ends:
                    start = ends[-1]["device_time"]
                if start is None and r["closed"] and closes:
                    start = closes[-1]["device_time"]

                duration = r["duration_ms"]
                if duration is None and ends and ends[-1]["duration_ms"] is not None:
                    duration = ends[-1]["duration_ms"]
                if duration is None and start is not None and stop is not None:
                    duration = max(0, start - stop)

                con.execute(
                    """
                    UPDATE alarms SET stop_time = ?, start_time = ?, duration_ms = ?
                    WHERE device_id = ? AND alarm_id = ?
                    """,
                    (stop, start, duration, r["device_id"], r["alarm_id"]),
                )

    # ------------------------------------------------------------ deletion
    def delete_event(self, event_id):
        with self._lock, self._session() as con:
            events = con.execute(
                "DELETE FROM events WHERE id = ?", (event_id,)).rowcount
        return {"events": events, "alarms": 0}

    def delete_alarm(self, device_id, alarm_id):
        with self._lock, self._session() as con:
            alarms = con.execute(
                "DELETE FROM alarms WHERE device_id = ? AND alarm_id = ?",
                (device_id, alarm_id)).rowcount
            events = con.execute(
                "DELETE FROM events WHERE device_id = ? AND alarm_id = ?",
                (device_id, alarm_id)).rowcount
        return {"events": events, "alarms": alarms}

    def delete_all(self):
        with self._lock, self._session() as con:
            events = con.execute("DELETE FROM events").rowcount
            alarms = con.execute("DELETE FROM alarms").rowcount
        return {"events": events, "alarms": alarms}

    # ------------------------------------------------------------- queries
    def events(self, limit=200, since_id=0, event_type=None, device_id=None):
        query = "SELECT * FROM events WHERE id > ?"
        params = [since_id]
        if event_type:
            query += " AND type = ?"
            params.append(event_type)
        if device_id:
            query += " AND device_id = ?"
            params.append(device_id)
        query += " ORDER BY id DESC LIMIT ?"
        params.append(limit)
        with self._session() as con:
            rows = con.execute(query, params).fetchall()
        return [dict(r) for r in rows]

    def shift_events(self, date_from=None, date_to=None, operator=None,
                     mechanic=None, limit=100000):
        """События смен: принятие, завершение, автопредложение, отклонение."""
        query = ("SELECT * FROM events WHERE (category = 'SHIFT' OR type IN "
                 "('SHIFT_START','SHIFT_END','SHIFT_REQUIRED','SHIFT_DECLINED'))")
        params = []
        if date_from is not None:
            query += " AND COALESCE(device_time, received_at) >= ?"
            params.append(date_from)
        if date_to is not None:
            query += " AND COALESCE(device_time, received_at) <= ?"
            params.append(date_to)
        if operator:
            query += " AND operator = ?"
            params.append(operator)
        if mechanic:
            query += " AND mechanic = ?"
            params.append(mechanic)
        query += " ORDER BY COALESCE(device_time, received_at) DESC, id DESC LIMIT ?"
        params.append(limit)
        with self._session() as con:
            rows = con.execute(query, params).fetchall()
        return [dict(r) for r in rows]

    def current_shift(self):
        """Активная смена (последний SHIFT_START без SHIFT_END)."""
        with self._session() as con:
            rows = con.execute(
                "SELECT device_id, shift_id, type, operator, mechanic, "
                "COALESCE(device_time, received_at) AS t "
                "FROM events WHERE type IN ('SHIFT_START', 'SHIFT_END') "
                "ORDER BY t, id"
            ).fetchall()
        active = {}
        for r in rows:
            key = (r["device_id"], r["shift_id"])
            if r["type"] == "SHIFT_START":
                active[key] = {
                    "device_id": r["device_id"],
                    "shift_id": r["shift_id"],
                    "operator": r["operator"],
                    "mechanic": r["mechanic"],
                    "start": r["t"],
                }
            else:
                active.pop(key, None)
        if not active:
            return None
        return max(active.values(), key=lambda s: s["start"] or 0)

    def alarms(self, open_only=False, device_id=None, limit=500,
               date_from=None, date_to=None, operator=None, mechanic=None,
               shift_id=None):
        query = "SELECT * FROM alarms WHERE 1=1"
        params = []
        if open_only:
            query += " AND closed = 0"
        if device_id:
            query += " AND device_id = ?"
            params.append(device_id)
        if shift_id is not None:
            query += " AND shift_id = ?"
            params.append(shift_id)
        if operator:
            query += " AND operator = ?"
            params.append(operator)
        if mechanic:
            query += " AND mechanic = ?"
            params.append(mechanic)
        if date_from is not None:
            query += " AND stop_time >= ?"
            params.append(date_from)
        if date_to is not None:
            query += " AND stop_time <= ?"
            params.append(date_to)
        query += " ORDER BY stop_time DESC LIMIT ?"
        params.append(limit)
        with self._session() as con:
            rows = con.execute(query, params).fetchall()
        return [dict(r) for r in rows]

    def filters(self, date_from=None, date_to=None):
        """Списки операторов и механиков для фильтра (в пределах периода)."""
        where = " WHERE 1=1"
        params = []
        if date_from is not None:
            where += " AND stop_time >= ?"
            params.append(date_from)
        if date_to is not None:
            where += " AND stop_time <= ?"
            params.append(date_to)
        with self._session() as con:
            operators = [r[0] for r in con.execute(
                "SELECT DISTINCT operator FROM alarms%s "
                "AND operator IS NOT NULL AND operator != '' ORDER BY operator"
                % where, params)]
            mechanics = [r[0] for r in con.execute(
                "SELECT DISTINCT mechanic FROM alarms%s "
                "AND mechanic IS NOT NULL AND mechanic != '' ORDER BY mechanic"
                % where, params)]
        return {"operators": operators, "mechanics": mechanics}

    def summary(self, date_from=None, date_to=None, operator=None, mechanic=None):
        """Агрегированные данные по авариям за период."""
        where = " WHERE 1=1"
        params = []
        if operator:
            where += " AND operator = ?"
            params.append(operator)
        if mechanic:
            where += " AND mechanic = ?"
            params.append(mechanic)
        if date_from is not None:
            where += " AND stop_time >= ?"
            params.append(date_from)
        if date_to is not None:
            where += " AND stop_time <= ?"
            params.append(date_to)

        shift_where = " WHERE type IN ('SHIFT_START', 'SHIFT_END')"
        shift_params = []
        if operator:
            shift_where += " AND operator = ?"
            shift_params.append(operator)
        if mechanic:
            shift_where += " AND mechanic = ?"
            shift_params.append(mechanic)
        if date_from is not None:
            shift_where += " AND COALESCE(device_time, received_at) >= ?"
            shift_params.append(date_from)
        if date_to is not None:
            shift_where += " AND COALESCE(device_time, received_at) <= ?"
            shift_params.append(date_to)

        with self._session() as con:
            totals = con.execute(
                """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(closed), 0) AS closed,
                       COALESCE(SUM(CASE WHEN closed = 0 THEN 1 ELSE 0 END), 0) AS open,
                       COALESCE(SUM(duration_ms), 0) AS total_duration,
                       COALESCE(AVG(duration_ms), 0) AS avg_duration,
                       COALESCE(MAX(duration_ms), 0) AS max_duration,
                       COALESCE(MIN(duration_ms), 0) AS min_duration
                FROM alarms%s
                """ % where,
                params,
            ).fetchone()
            shift_rows = con.execute(
                "SELECT device_id, shift_id, type, "
                "COALESCE(device_time, received_at) AS t "
                "FROM events" + shift_where + " ORDER BY t",
                shift_params,
            ).fetchall()
            by_cause = con.execute(
                """
                SELECT CASE
                         WHEN cause_code IS NOT NULL AND (cause_code %% 10) = 9 THEN
                           CASE
                             WHEN cause_path IS NOT NULL AND cause_path != '' THEN
                               CASE
                                 WHEN cause_text IS NOT NULL AND cause_text != ''
                                      AND instr(cause_path, cause_text) = 0
                                 THEN cause_path || ':' || cause_text
                                 ELSE cause_path
                               END
                             ELSE COALESCE(cause_text, 'Причина не указана')
                           END
                         ELSE COALESCE(cause_path, cause_text, 'Причина не указана')
                       END AS cause,
                       COUNT(*) AS count,
                       COALESCE(SUM(duration_ms), 0) AS duration_ms
                FROM alarms%s
                GROUP BY cause
                ORDER BY count DESC, duration_ms DESC
                """ % where,
                params,
            ).fetchall()
            by_day = con.execute(
                """
                SELECT date(stop_time / 1000, 'unixepoch', 'localtime') AS day,
                       COUNT(*) AS count,
                       COALESCE(SUM(duration_ms), 0) AS duration_ms
                FROM alarms%s
                GROUP BY day
                ORDER BY day DESC
                """ % where,
                params,
            ).fetchall()

        def as_int(value):
            return int(value) if value is not None else 0

        now = now_ms()
        starts = {}
        ends = {}
        for r in shift_rows:
            key = (r["device_id"], r["shift_id"])
            if r["type"] == "SHIFT_START":
                starts[key] = r["t"]
            elif r["type"] == "SHIFT_END":
                ends[key] = r["t"]
        shift_total = 0
        for key, start in starts.items():
            if start:
                shift_total += max(0, (ends.get(key) or now) - start)
        work = max(0, shift_total - as_int(totals["total_duration"]))

        return {
            "total": as_int(totals["total"]),
            "closed": as_int(totals["closed"]),
            "open": as_int(totals["open"]),
            "total_duration_ms": as_int(totals["total_duration"]),
            "avg_duration_ms": as_int(totals["avg_duration"]),
            "max_duration_ms": as_int(totals["max_duration"]),
            "min_duration_ms": as_int(totals["min_duration"]),
            "shift_duration_ms": shift_total,
            "work_duration_ms": work,
            "by_cause": [dict(r) for r in by_cause],
            "by_day": [dict(r) for r in by_day],
        }

    def stats(self):
        with self._session() as con:
            total = con.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            open_alarms = con.execute(
                "SELECT COUNT(*) FROM alarms WHERE closed = 0"
            ).fetchone()[0]
            alarms = con.execute("SELECT COUNT(*) FROM alarms").fetchone()[0]
            alarm_events = con.execute(
                "SELECT COUNT(*) FROM events WHERE category = 'ALARM'"
            ).fetchone()[0]
            last = con.execute(
                "SELECT MAX(received_at) FROM events"
            ).fetchone()[0]
        return {
            "events": total,
            "alarms": alarms,
            "open_alarms": open_alarms,
            "alarm_events": alarm_events,
            "last_event_at": last,
        }


class _Handler(BaseHTTPRequestHandler):
    server_version = "LineStopMonitor/1.0"
    store = None  # устанавливается фабрикой
    apk_path = None  # путь к APK для скачивания
    require_auth = True

    # ------------------------------------------------------------- helpers
    def _json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, html, status=200):
        body = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _bytes(self, body, content_type, filename=None):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if filename:
            self.send_header(
                "Content-Disposition", 'attachment; filename="%s"' % filename)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status, message):
        self._json({"error": message}, status)

    # --------------------------------------------------------------- auth
    def _token(self):
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            key, _, value = part.strip().partition("=")
            if key == SESSION_COOKIE:
                return value
        return None

    def _user(self):
        if not type(self).require_auth:
            return {"user": "local", "role": "admin"}
        token = self._token()
        if not token:
            return None
        session = SESSIONS.get(token)
        if not session:
            return None
        if session["expires"] < time.time():
            SESSIONS.pop(token, None)
            return None
        return {"user": session["user"], "role": session["role"]}

    def _require_auth(self):
        user = self._user()
        if user is None:
            if self.path.startswith("/api/"):
                self._error(401, "unauthorized")
            else:
                self._redirect("/login")
            return None
        return user

    def _require_admin(self):
        user = self._require_auth()
        if user is None:
            return None
        if user.get("role") != "admin":
            self._error(403, "forbidden")
            return None
        return user

    def _redirect(self, location, cookie=None):
        self.send_response(303)
        self.send_header("Location", location)
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return None
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return False  # маркер «некорректный JSON»

    def log_message(self, fmt, *args):  # тише в консоли тестов
        if os.environ.get("LSM_WEB_VERBOSE"):
            super().log_message(fmt, *args)

    # --------------------------------------------------------------- verbs
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        path, _, query = self.path.partition("?")
        params = parse_query(query)
        date_from = parse_int(params.get("from"), None, 0, None)
        date_to = parse_int(params.get("to"), None, 0, None)
        operator = params.get("operator") or None
        mechanic = params.get("mechanic") or None

        if path == "/login":
            if self._user():
                return self._redirect("/")
            return self._html(LOGIN_PAGE)
        if path == "/logout":
            token = self._token()
            if token:
                SESSIONS.pop(token, None)
            return self._redirect(
                "/login", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly")
        if path == "/api/health":
            return self._json({"status": "ok", "time": now_ms()})
        if self._require_auth() is None:
            return
        if path == "/api/me":
            return self._json(self._user())
        if path == "/" or path == "/index.html":
            return self._html(DASHBOARD)
        if path == "/api/events":
            return self._json({
                "events": self.store.events(
                    limit=parse_int(params.get("limit"), 200, 1, 2000),
                    since_id=parse_int(params.get("since_id"), 0, 0, None),
                    event_type=params.get("type"),
                    device_id=params.get("device_id"),
                )
            })
        if path == "/api/alarms":
            return self._json({
                "alarms": self.store.alarms(
                    open_only=params.get("open") in ("1", "true", "yes"),
                    device_id=params.get("device_id"),
                    limit=parse_int(params.get("limit"), 1000, 1, 100000),
                    date_from=date_from,
                    date_to=date_to,
                    operator=operator,
                    mechanic=mechanic,
                    shift_id=parse_int(params.get("shift_id"), None, 0, None),
                )
            })
        if path == "/api/current-shift":
            return self._json({"shift": self.store.current_shift()})
        if path == "/api/filters":
            return self._json(self.store.filters(date_from, date_to))
        if path == "/api/shift-events":
            return self._json({"events": self.store.shift_events(
                date_from, date_to, operator, mechanic)})
        if path == "/api/export-shifts.xlsx":
            return self._export_shifts(date_from, date_to, operator, mechanic)
        if path in ("/download/app.apk", "/app.apk"):
            return self._download_apk()
        if path == "/api/summary":
            return self._json(
                self.store.summary(date_from, date_to, operator, mechanic))
        if path == "/api/export.xlsx":
            return self._export_xlsx(date_from, date_to, operator, mechanic)
        if path == "/api/stats":
            return self._json(self.store.stats())
        self._error(404, "not found")

    def _export_xlsx(self, date_from, date_to, operator=None, mechanic=None):
        alarms = self.store.alarms(
            limit=100000, date_from=date_from, date_to=date_to,
            operator=operator, mechanic=mechanic)
        summary = self.store.summary(date_from, date_to, operator, mechanic)

        header = ["№", "Смена", "Оператор", "Механик", "Начало", "Конец",
                  "Длительность", "Длительность (с)", "Причина",
                  "Код причины", "Статус"]
        rows = [header]
        for a in sorted(alarms, key=lambda x: x.get("stop_time") or 0):
            rows.append([
                a.get("alarm_id"),
                a.get("shift_id"),
                a.get("operator"),
                a.get("mechanic"),
                ms_to_text(a.get("stop_time")),
                ms_to_text(a.get("start_time")),
                ms_to_text_short(a.get("duration_ms")),
                round((a.get("duration_ms") or 0) / 1000.0, 1),
                effective_cause(a),
                a.get("cause_code"),
                "закрыта" if a.get("closed") else "открыта",
            ])

        cause_rows = [["Причина", "Количество", "Суммарная длительность"]]
        for c in summary["by_cause"]:
            cause_rows.append([c["cause"], c["count"], ms_to_text_short(c["duration_ms"])])

        day_rows = [["День", "Количество аварий", "Суммарная длительность"]]
        for d in summary["by_day"]:
            day_rows.append([d["day"], d["count"], ms_to_text_short(d["duration_ms"])])

        totals_rows = [
            ["Показатель", "Значение"],
            ["Всего аварий", summary["total"]],
            ["Открытых", summary["open"]],
            ["Закрытых", summary["closed"]],
            ["Суммарная длительность", ms_to_text_short(summary["total_duration_ms"])],
            ["Средняя длительность", ms_to_text_short(summary["avg_duration_ms"])],
            ["Максимальная длительность", ms_to_text_short(summary["max_duration_ms"])],
        ]

        data = build_xlsx([
            ("Аварии", rows),
            ("По причинам", cause_rows),
            ("По дням", day_rows),
            ("Сводка", totals_rows),
        ])
        stamp = datetime.now().strftime("%Y%m%d_%H%M")
        self._bytes(
            data,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "linestop_alarms_%s.xlsx" % stamp,
        )

    def _export_shifts(self, date_from, date_to, operator=None, mechanic=None):
        events = self.store.shift_events(date_from, date_to, operator, mechanic)
        type_names = {
            "SHIFT_START": "Начало смены",
            "SHIFT_END": "Конец смены",
            "SHIFT_REQUIRED": "Автопредложение принять смену",
            "SHIFT_DECLINED": "Отклонение автопредложения",
        }
        rows = [["Время", "Событие", "Оператор", "Механик", "Сообщение"]]
        for e in sorted(events, key=lambda x: x.get("device_time")
                        or x.get("received_at") or 0):
            rows.append([
                ms_to_text(e.get("device_time") or e.get("received_at")),
                type_names.get(e.get("type"), e.get("type") or ""),
                e.get("operator"),
                e.get("mechanic"),
                e.get("message"),
            ])
        data = build_xlsx([("Смены", rows)])
        stamp = datetime.now().strftime("%Y%m%d_%H%M")
        self._bytes(
            data,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "linestop_shifts_%s.xlsx" % stamp,
        )

    def _download_apk(self):
        path = type(self).apk_path
        if not path or not os.path.exists(path):
            return self._error(404, "APK not found")
        with open(path, "rb") as fh:
            data = fh.read()
        self._bytes(
            data,
            "application/vnd.android.package-archive",
            "linestop-monitor.apk",
        )

    def do_POST(self):
        path, _, _ = self.path.partition("?")
        if path == "/login":
            return self._do_login()
        if path == "/api/events":
            return self._ingest_events()
        if path == "/api/delete":
            if self._require_admin() is None:
                return
            return self._do_delete()
        self._error(404, "not found")

    def _ingest_events(self):
        payload = self._read_json()
        if payload is False:
            return self._error(400, "invalid json")
        if payload is None:
            return self._error(400, "empty body")

        events, device_id = normalize_payload(payload)
        if not events:
            return self._error(400, "no events")
        for event in events:
            if device_id and not event.get("deviceId"):
                event["deviceId"] = device_id
            if not event.get("type") and event.get("category"):
                event["type"] = event["category"]

        ids = self.store.add_events(events)
        self._json({"accepted": len(events), "ids": ids}, 201)

    def _do_login(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        form = parse_query(raw)
        username = (form.get("username") or "").strip()
        password = form.get("password") or ""
        account = USERS.get(username)
        if account is None or \
                hashlib.sha256(password.encode("utf-8")).hexdigest() != account["hash"]:
            html = LOGIN_PAGE.replace(
                "<!--ERROR-->",
                '<p class="err">Неверный логин или пароль</p>'
            )
            return self._html(html, 401)
        token = secrets.token_urlsafe(32)
        SESSIONS[token] = {
            "user": username,
            "role": account["role"],
            "expires": time.time() + SESSION_TTL,
        }
        cookie = "%s=%s; Path=/; HttpOnly; Max-Age=%d" % (
            SESSION_COOKIE, token, SESSION_TTL)
        self._redirect("/", cookie)

    def _do_delete(self):
        payload = self._read_json()
        if not isinstance(payload, dict):
            return self._error(400, "invalid json")
        scope = payload.get("scope")
        if scope == "all":
            counts = self.store.delete_all()
        elif scope == "event":
            event_id = payload.get("id")
            if event_id is None:
                return self._error(400, "id required")
            counts = self.store.delete_event(int(event_id))
        elif scope == "alarm":
            device_id = payload.get("device_id")
            alarm_id = payload.get("alarm_id")
            if not device_id or alarm_id is None:
                return self._error(400, "device_id and alarm_id required")
            counts = self.store.delete_alarm(str(device_id), int(alarm_id))
        else:
            return self._error(400, "unknown scope")
        self._json({"deleted": counts})


def parse_query(query):
    params = {}
    for part in query.split("&"):
        if not part:
            continue
        key, _, value = part.partition("=")
        params[unquote(key)] = unquote(value)
    return params


def unquote(value):
    from urllib.parse import unquote_plus
    return unquote_plus(value)


def parse_int(value, default, minimum, maximum):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    if number < minimum:
        return default
    if maximum is not None and number > maximum:
        return maximum
    return number


def ms_to_text(ms):
    if not ms:
        return ""
    return datetime.fromtimestamp(ms / 1000.0).strftime("%d.%m.%Y %H:%M:%S")


def effective_cause(alarm):
    """Причина для отображения.

    Для «Другой причины» — формат «Категория / Пункт / Другая причина:<текст>».
    """
    code = alarm.get("cause_code")
    text = alarm.get("cause_text")
    path = alarm.get("cause_path")
    if code is not None and (code % 10) == 9:
        if path:
            if text and text not in path:
                return path + ":" + text
            return path
        return text or "Другая причина"
    return path or text or "Причина не указана"


def ms_to_text_short(ms):
    total = int(round((ms or 0) / 1000.0))
    hours, rem = divmod(total, 3600)
    minutes, seconds = divmod(rem, 60)
    parts = []
    if hours:
        parts.append("%d ч" % hours)
    if minutes:
        parts.append("%d мин" % minutes)
    if seconds or not parts:
        parts.append("%d с" % seconds)
    return " ".join(parts)


# ------------------------------------------------------------- Excel (xlsx)

def _xml_escape(value):
    return (str(value)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def _col_name(index):
    name = ""
    index += 1
    while index:
        index, rem = divmod(index - 1, 26)
        name = chr(65 + rem) + name
    return name


def _cell(ref, value):
    if value is None or value == "":
        return ""
    if isinstance(value, bool):
        value = int(value)
    if isinstance(value, (int, float)):
        return '<c r="%s"><v>%s</v></c>' % (ref, value)
    return ('<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'
            % (ref, _xml_escape(value)))


def _sheet_xml(rows):
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
           "<sheetData>"]
    for r, row in enumerate(rows, start=1):
        cells = "".join(
            _cell("%s%d" % (_col_name(c), r), v) for c, v in enumerate(row))
        out.append('<row r="%d">%s</row>' % (r, cells))
    out.append("</sheetData></worksheet>")
    return "".join(out)


def _sheet_name(name, used):
    safe = "".join(ch for ch in str(name) if ch not in "[]:*?/\\")[:31] or "Sheet"
    candidate = safe
    counter = 2
    while candidate in used:
        suffix = "(%d)" % counter
        candidate = safe[:31 - len(suffix)] + suffix
        counter += 1
    used.add(candidate)
    return candidate


def build_xlsx(sheets):
    """Минимальный генератор .xlsx (OOXML) на стандартной библиотеке."""
    used = set()
    prepared = [(_sheet_name(name, used), rows) for name, rows in sheets]

    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        ct = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
              '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">',
              '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>',
              '<Default Extension="xml" ContentType="application/xml"/>',
              '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>']
        for i in range(1, len(prepared) + 1):
            ct.append('<Override PartName="/xl/worksheets/sheet%d.xml" '
                      'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' % i)
        ct.append("</Types>")
        z.writestr("[Content_Types].xml", "".join(ct))

        z.writestr(
            "_rels/.rels",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
            'Target="xl/workbook.xml"/></Relationships>')

        wb = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
              '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
              'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">',
              "<sheets>"]
        rels = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">']
        for i, (name, rows) in enumerate(prepared, start=1):
            wb.append('<sheet name="%s" sheetId="%d" r:id="rId%d"/>'
                      % (_xml_escape(name), i, i))
            rels.append('<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
                        'Target="worksheets/sheet%d.xml"/>' % (i, i))
            z.writestr("xl/worksheets/sheet%d.xml" % i, _sheet_xml(rows))
        wb.append("</sheets></workbook>")
        rels.append("</Relationships>")
        z.writestr("xl/workbook.xml", "".join(wb))
        z.writestr("xl/_rels/workbook.xml.rels", "".join(rels))

    return buf.getvalue()


def normalize_payload(payload):
    """Поддерживает одиночное событие, список и объект с полем events."""
    device_id = None
    if isinstance(payload, list):
        return [e for e in payload if isinstance(e, dict)], device_id
    if isinstance(payload, dict):
        device_id = payload.get("deviceId")
        if isinstance(payload.get("events"), list):
            return [e for e in payload["events"] if isinstance(e, dict)], device_id
        return [payload], device_id
    return [], device_id


def make_handler(store, apk_path=None, require_auth=True):
    class Handler(_Handler):
        pass
    Handler.store = store
    Handler.apk_path = apk_path
    Handler.require_auth = require_auth
    return Handler


def create_server(host, port, db_path, apk_path=None, require_auth=True):
    store = EventStore(db_path)
    return ThreadingHTTPServer(
        (host, port), make_handler(store, apk_path, require_auth))


LOGIN_PAGE = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Вход — Line Stop Monitor</title>
<style>
  body { margin:0; min-height:100vh; display:flex; align-items:center;
         justify-content:center; background:#0f141a; color:#e6edf3;
         font-family:"Segoe UI",Roboto,Arial,sans-serif; }
  form { background:#161d26; border:1px solid #232c38; border-radius:14px;
         padding:28px 28px 24px; width:320px; }
  h1 { font-size:18px; margin:0 0 4px; }
  .sub { color:#8b98a5; font-size:13px; margin-bottom:18px; }
  label { display:block; font-size:13px; color:#8b98a5; margin:12px 0 6px; }
  input { width:100%; box-sizing:border-box; background:#0f141a; color:#e6edf3;
          border:1px solid #2c3745; border-radius:8px; padding:10px 12px;
          font-size:14px; }
  button { width:100%; margin-top:18px; background:#4338ca; color:#fff;
           border:0; border-radius:8px; padding:11px; font-size:14px;
           font-weight:600; cursor:pointer; }
  .err { color:#ff6b6b; font-size:13px; margin:12px 0 0; }
</style></head><body>
<form method="post" action="/login">
  <h1>Line Stop Monitor</h1>
  <div class="sub">Вход в панель мониторинга</div>
  <label>Логин</label>
  <input name="username" autocomplete="username" autofocus>
  <label>Пароль</label>
  <input name="password" type="password" autocomplete="current-password">
  <button type="submit">Войти</button>
  <!--ERROR-->
</form>
</body></html>"""


DASHBOARD = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Line Stop Monitor — мониторинг</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: "Segoe UI", Roboto, Arial, sans-serif;
         background: #0f141a; color: #e6edf3; }
  header { padding: 16px 24px; background: #161d26;
           border-bottom: 1px solid #232c38; display: flex;
           align-items: center; gap: 16px; position: sticky; top: 0; z-index: 5; }
  header h1 { font-size: 18px; margin: 0; font-weight: 700; }
  header .live { margin-left: auto; font-size: 13px; color: #8b98a5; }
  header .live .dot { display:inline-block; width:8px; height:8px;
      border-radius:50%; background:#2ecc71; margin-right:6px; }
  main { padding: 24px; max-width: 1280px; margin: 0 auto; }
  .toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px;
             background: #161d26; border: 1px solid #232c38; border-radius: 12px;
             padding: 12px 16px; margin-bottom: 20px; }
  .toolbar label { font-size: 13px; color: #8b98a5; display: flex;
                   align-items: center; gap: 8px; }
  .toolbar input[type=date], .toolbar select { background: #0f141a; color: #e6edf3;
      border: 1px solid #2c3745; border-radius: 8px; padding: 7px 10px;
      font-size: 13px; color-scheme: dark; }
  .toolbar .spacer { flex: 1; }
  .btn { background: #2b3542; color: #e6edf3; border: 1px solid #38434f;
         border-radius: 8px; padding: 8px 14px; font-size: 13px;
         cursor: pointer; text-decoration: none; }
  .btn:hover { background: #38434f; }
  .btn.primary { background: #4338ca; border-color: #4338ca; }
  .btn.excel { background: #166534; border-color: #166534; }
  .who { font-size: 13px; color: #8b98a5; }
  .del { display: none; background: #7f1d1d; border-color: #7f1d1d;
         padding: 4px 10px; font-size: 12px; }
  body.is-admin .del { display: inline-block; }
  .tabs { display: flex; gap: 8px; margin-bottom: 16px; }
  .tab { background: #161d26; color: #8b98a5; border: 1px solid #232c38;
         border-radius: 10px; padding: 10px 18px; font-size: 14px;
         cursor: pointer; font-weight: 600; }
  .tab.active { background: #2b3542; color: #e6edf3; border-color: #38434f; }
  .section-head { display: flex; align-items: center; gap: 12px;
                  margin: 0 0 12px; }
  .section-head .spacer { flex: 1; }
  .cards { display: grid; gap: 16px;
           grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); }
  .card { background: #161d26; border: 1px solid #232c38; border-radius: 12px;
          padding: 16px; }
  .card .value { font-size: 30px; font-weight: 800; }
  .card .label { color: #8b98a5; font-size: 13px; margin-top: 4px; }
  .card.alarm { border-color: #7f1d1d; }
  .card.alarm .value { color: #ff5c5c; }
  h2 { font-size: 15px; margin: 28px 0 12px; color: #cfd8e3; }
  .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
  @media (max-width: 760px) { .grid2 { grid-template-columns: 1fr; } }
  .acc { background: #161d26; border: 1px solid #232c38; border-radius: 12px;
         padding: 0 16px; align-self: start; }
  .acc > summary { cursor: pointer; padding: 14px 0; font-size: 15px;
                   font-weight: 600; color: #cfd8e3; list-style: none;
                   display: flex; align-items: center; user-select: none; }
  .acc > summary::-webkit-details-marker { display: none; }
  .acc > summary::after { content: '▾'; margin-left: auto; color: #8b98a5;
                          font-size: 12px; }
  .acc[open] > summary::after { content: '▴'; }
  .acc > div { padding-bottom: 14px; }
  .chart-card { background: #161d26; border: 1px solid #232c38;
                border-radius: 12px; padding: 12px 16px 8px; }
  .chart-legend { display: flex; gap: 18px; font-size: 13px; color: #8b98a5;
                  margin-bottom: 6px; }
  .chart-legend .lg::before { content: ''; display: inline-block; width: 10px;
                height: 10px; border-radius: 2px; margin-right: 6px;
                vertical-align: middle; }
  .chart-legend .lg.work::before { background: #2e7d32; }
  .chart-legend .lg.alarm::before { background: #c62828; }
  #shiftChart { width: 100%; display: block; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #1e2631;
           vertical-align: top; }
  th { color: #8b98a5; font-weight: 600; position: sticky; top: 0;
       background: #0f141a; }
  tr.open td { background: #1e1315; }
  tr.open td:first-child { box-shadow: inset 3px 0 0 #ff5c5c; }
  tr.row-hit td { background: rgba(255, 193, 7, .22) !important;
                  box-shadow: inset 3px 0 0 #ffc107; }
  .badge { display:inline-block; padding: 2px 8px; border-radius: 999px;
           font-size: 11px; font-weight: 700; }
  .badge.open { background: #7f1d1d; color: #ffd7d7; }
  .badge.closed { background: #14532d; color: #c7f9d8; }
  .muted { color: #8b98a5; }
  #empty { color: #8b98a5; padding: 12px 0; }
</style>
</head>
<body>
<header>
  <h1>Line Stop Monitor — мониторинг аварий</h1>
  <div class="live"><span class="dot"></span><span id="updated">загрузка…</span></div>
  <span class="who" id="who"></span>
  <a class="btn excel" href="/download/app.apk">Скачать APK</a>
  <a class="btn" href="/logout">Выйти</a>
</header>
<main>
  <div class="tabs">
    <button class="tab active" id="tabBtn-monitor"
            onclick="showTab('monitor')">Мониторинг</button>
    <button class="tab" id="tabBtn-all"
            onclick="showTab('all')">Все аварии</button>
    <button class="tab" id="tabBtn-shifts"
            onclick="showTab('shifts')">Смены</button>
  </div>

  <div class="toolbar">
    <label>С <input type="date" id="from"></label>
    <label>По <input type="date" id="to"></label>
    <label>Оператор
      <select id="operator"><option value="">Все</option></select></label>
    <label>Механик
      <select id="mechanic"><option value="">Все</option></select></label>
    <button class="btn primary" onclick="applyFilter()">Применить</button>
    <button class="btn" onclick="resetFilter()">Сбросить</button>
  </div>

  <section id="tab-monitor">
    <div class="section-head">
      <h2 style="margin:0">Сводка</h2>
    </div>
    <div class="cards" id="cards"></div>

    <div class="grid2">
      <details class="acc">
        <summary>По причинам</summary>
        <div id="byCause"></div>
      </details>
      <details class="acc">
        <summary>По дням</summary>
        <div id="byDay"></div>
      </details>
    </div>

    <h2>Текущая смена</h2>
    <div id="shiftInfo"></div>

    <h2 id="chartHeading">График работы линии за смену</h2>
    <div class="chart-card">
      <div class="chart-legend">
        <span class="lg work">В работе (+1)</span>
        <span class="lg alarm">Авария (-1)</span>
      </div>
      <svg id="shiftChart" height="260"></svg>
      <div id="chartEmpty" class="muted" style="display:none">
        Нет данных
      </div>
    </div>

    <h2 id="alarmsHeading">Зарегистрированные аварии (текущая смена)</h2>
    <div id="currentAlarms"></div>
  </section>

  <section id="tab-all" style="display:none">
    <div class="section-head">
      <h2 style="margin:0">Все аварии</h2>
      <div class="spacer"></div>
      <button class="btn del" onclick="deleteAll()">Очистить всё</button>
      <a class="btn excel" id="export" href="/api/export.xlsx">Экспорт аварий в Excel</a>
    </div>
    <div id="allAlarms"></div>
  </section>

  <section id="tab-shifts" style="display:none">
    <div class="section-head">
      <h2 style="margin:0">Журнал смен</h2>
      <div class="spacer"></div>
      <a class="btn excel" id="exportShifts" href="/api/export-shifts.xlsx">Экспорт смен в Excel</a>
    </div>
    <div id="shiftLog"></div>
  </section>
</main>
<script>
const state = { from: null, to: null, operator: '', mechanic: '' };

const fmtTime = ms => ms ? new Date(ms).toLocaleString('ru-RU') : '—';
const fmtClock = ms => {
  const d = new Date(ms);
  return String(d.getHours()).padStart(2, '0') + ':' +
         String(d.getMinutes()).padStart(2, '0');
};
const fmtDur = ms => {
  if (ms == null) return '—';
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const parts = [];
  if (h) parts.push(h + ' ч');
  if (m) parts.push(m + ' мин');
  if (sec || !parts.length) parts.push(sec + ' с');
  return parts.join(' ');
};
const esc = t => (t == null ? '' : String(t));

const shiftTypeLabel = {
  SHIFT_START: 'Начало смены',
  SHIFT_END: 'Конец смены',
  SHIFT_REQUIRED: 'Автопредложение принять смену',
  SHIFT_DECLINED: 'Отклонение автопредложения'
};

function showTab(name) {
  for (const n of ['monitor', 'all', 'shifts']) {
    document.getElementById('tab-' + n).style.display = (n === name) ? '' : 'none';
    document.getElementById('tabBtn-' + n).classList.toggle('active', n === name);
  }
}

function dateQuery() {
  const p = [];
  if (state.from != null) p.push('from=' + state.from);
  if (state.to != null) p.push('to=' + state.to);
  return p.length ? '?' + p.join('&') : '';
}

function queryString() {
  const p = [];
  if (state.from != null) p.push('from=' + state.from);
  if (state.to != null) p.push('to=' + state.to);
  if (state.operator) p.push('operator=' + encodeURIComponent(state.operator));
  if (state.mechanic) p.push('mechanic=' + encodeURIComponent(state.mechanic));
  return p.length ? '?' + p.join('&') : '';
}

function filterActive() {
  return state.from != null || state.to != null ||
         !!state.operator || !!state.mechanic;
}

async function j(url) {
  const r = await fetch(url);
  if (r.status === 401) { location.href = '/login'; throw new Error('unauthorized'); }
  return r.json();
}

let isAdmin = false;
async function loadMe() {
  try {
    const me = await j('/api/me');
    document.getElementById('who').textContent = 'Пользователь: ' + me.user;
    isAdmin = (me.role === 'admin');
    if (isAdmin) document.body.classList.add('is-admin');
  } catch (e) { /* ignore */ }
}

async function postDelete(body) {
  const r = await fetch('/api/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (r.status === 401) { location.href = '/login'; return; }
  return r.json();
}
function deleteAlarm(deviceId, alarmId) {
  if (!confirm('Удалить аварию #' + alarmId + ' и её события?')) return;
  postDelete({ scope: 'alarm', device_id: deviceId, alarm_id: alarmId })
    .then(refresh);
}
function deleteEvent(id) {
  if (!confirm('Удалить запись #' + id + '?')) return;
  postDelete({ scope: 'event', id: id }).then(refresh);
}
function deleteAll() {
  if (!confirm('Удалить ВСЕ события и аварии? Действие необратимо.')) return;
  postDelete({ scope: 'all' }).then(refresh);
}

function fillSelect(id, values, selected) {
  const el = document.getElementById(id);
  el.innerHTML = '<option value="">Все</option>' +
    values.map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join('');
  if (selected && values.includes(selected)) el.value = selected;
}

async function loadFilters() {
  try {
    const data = await j('/api/filters' + dateQuery());
    fillSelect('operator', data.operators, state.operator);
    fillSelect('mechanic', data.mechanics, state.mechanic);
  } catch (e) { /* нет связи */ }
}

function applyFilter() {
  const from = document.getElementById('from').value;
  const to = document.getElementById('to').value;
  state.from = from ? new Date(from + 'T00:00:00').getTime() : null;
  state.to = to ? new Date(to + 'T23:59:59.999').getTime() : null;
  state.operator = document.getElementById('operator').value;
  state.mechanic = document.getElementById('mechanic').value;
  loadFilters();
  refresh();
}

function resetFilter() {
  document.getElementById('from').value = '';
  document.getElementById('to').value = '';
  state.from = null;
  state.to = null;
  state.operator = '';
  state.mechanic = '';
  loadFilters();
  refresh();
}

function renderCards(sum) {
  document.getElementById('cards').innerHTML = `
    <div class="card alarm"><div class="value">${sum.open}</div>
      <div class="label">Активные аварии</div></div>
    <div class="card"><div class="value">${sum.total}</div>
      <div class="label">Всего случаев</div></div>
    <div class="card"><div class="value">${fmtDur(sum.total_duration_ms)}</div>
      <div class="label">Суммарная длительность</div></div>
    <div class="card"><div class="value">${fmtDur(sum.work_duration_ms)}</div>
      <div class="label">Время в работе (за смены)</div></div>`;
}

function renderByCause(rows) {
  const el = document.getElementById('byCause');
  if (!rows.length) { el.innerHTML = '<div id="empty">Нет данных</div>'; return; }
  el.innerHTML = `<table><thead><tr>
      <th>Причина</th><th>Кол-во</th><th>Суммарно</th></tr></thead><tbody>` +
    rows.map(c => `<tr>
      <td>${esc(c.cause)}</td>
      <td>${c.count}</td>
      <td>${fmtDur(c.duration_ms)}</td>
    </tr>`).join('') + '</tbody></table>';
}

function renderByDay(rows) {
  const el = document.getElementById('byDay');
  if (!rows.length) { el.innerHTML = '<div id="empty">Нет данных</div>'; return; }
  el.innerHTML = `<table><thead><tr>
      <th>День</th><th>Аварий</th><th>Суммарно</th></tr></thead><tbody>` +
    rows.map(d => `<tr>
      <td>${esc(d.day)}</td>
      <td>${d.count}</td>
      <td>${fmtDur(d.duration_ms)}</td>
    </tr>`).join('') + '</tbody></table>';
}

/* Одна зарегистрированная авария = одна строка. Открытые — сверху. */
function renderAlarmTable(elId, alarms, emptyText) {
  const el = document.getElementById(elId);
  if (!alarms.length) {
    el.innerHTML = '<div id="empty">' + emptyText + '</div>';
    return;
  }
  const sorted = alarms.slice().sort((a, b) =>
    (a.closed - b.closed) || (b.stop_time - a.stop_time));
  el.innerHTML = `<table><thead><tr>
      <th>№</th><th>Смена</th><th>Оператор</th><th>Механик</th><th>Начало</th>
      <th>Конец</th><th>Длительность</th><th>Причина</th><th>Статус</th>
      <th></th></tr></thead><tbody>` +
    sorted.map(a => {
      let cause;
      if (a.cause_code != null && a.cause_code % 10 === 9) {
        if (a.cause_path) {
          cause = (a.cause_text && !a.cause_path.includes(a.cause_text))
            ? (a.cause_path + ':' + a.cause_text) : a.cause_path;
        } else {
          cause = a.cause_text || 'Другая причина';
        }
      } else {
        cause = a.cause_path || a.cause_text || (a.closed ? 'Без причины' : '—');
      }
      const end = a.start_time ? fmtTime(a.start_time) : (a.closed ? '—' : 'идёт');
      const dur = a.closed ? fmtDur(a.duration_ms) : 'идёт';
      return `<tr class="${a.closed ? '' : 'open'}" data-key="${esc(a.device_id)}|${a.alarm_id}">
        <td>#${esc(a.alarm_id)}</td>
        <td class="muted">${a.shift_id == null ? '—' : esc(a.shift_id)}</td>
        <td>${esc(a.operator || '—')}</td>
        <td>${esc(a.mechanic || '—')}</td>
        <td>${fmtTime(a.stop_time)}</td>
        <td>${end}</td>
        <td>${dur}</td>
        <td>${esc(cause)}</td>
        <td><span class="badge ${a.closed ? 'closed' : 'open'}">
          ${a.closed ? 'закрыта' : 'открыта'}</span></td>
        <td><button class="btn del"
            onclick="deleteAlarm('${esc(a.device_id)}',${a.alarm_id})">Удалить</button></td>
      </tr>`;
    }).join('') + '</tbody></table>';
}

async function refresh() {
  try {
    const qs = queryString();
    document.getElementById('export').href = '/api/export.xlsx' + qs;
    document.getElementById('exportShifts').href = '/api/export-shifts.xlsx' + qs;
    const [sum, alarms, shifts, cur] = await Promise.all([
      j('/api/summary' + qs), j('/api/alarms' + qs), j('/api/shift-events' + qs),
      j('/api/current-shift')]);
    renderCards(sum);
    renderByCause(sum.by_cause);
    renderByDay(sum.by_day);
    renderAlarmTable('allAlarms', alarms.alarms, 'Аварий пока нет');
    renderShiftEvents(shifts.events);

    const shift = cur.shift;
    let viewAlarms = [], day = null, heading = '';
    if (filterActive()) {
      viewAlarms = alarms.alarms;
      day = state.from != null ? state.from
        : (viewAlarms.length
            ? Math.max(...viewAlarms.map(a => a.stop_time || 0)) : Date.now());
      heading = 'Зарегистрированные аварии (по фильтру)';
    } else if (shift) {
      const curAlarms = await j('/api/alarms?shift_id=' + shift.shift_id +
        '&device_id=' + encodeURIComponent(shift.device_id));
      viewAlarms = curAlarms.alarms;
      day = shift.start;
      heading = 'Зарегистрированные аварии (текущая смена)';
    } else {
      heading = 'Зарегистрированные аварии (текущая смена)';
    }
    document.getElementById('alarmsHeading').textContent = heading;
    document.getElementById('chartHeading').textContent =
      filterActive() ? 'График работы линии (по фильтру)'
                     : 'График работы линии за смену';
    renderAlarmTable('currentAlarms', viewAlarms, 'Аварий нет');
    renderTimeline(viewAlarms, day);

    if (shift) {
      document.getElementById('shiftInfo').innerHTML =
        'Оператор: <b>' + esc(shift.operator || '—') + '</b> · Механик: <b>' +
        esc(shift.mechanic || '—') + '</b> · с ' + fmtTime(shift.start);
    } else {
      document.getElementById('shiftInfo').innerHTML =
        '<span class="muted">Смена не начата</span>';
    }
    document.getElementById('updated').textContent =
      'обновлено ' + new Date().toLocaleTimeString('ru-RU');
  } catch (e) {
    document.getElementById('updated').textContent = 'нет связи с сервером';
  }
}

/* Прокручивает таблицу к аварии и подсвечивает её. */
function focusAlarm(deviceId, alarmId) {
  const tr = document.querySelector('tr[data-key="' + deviceId + '|' + alarmId + '"]');
  if (!tr) return;
  tr.scrollIntoView({ behavior: 'smooth', block: 'center' });
  tr.classList.add('row-hit');
  setTimeout(() => tr.classList.remove('row-hit'), 2600);
}

/* График: X — 07:00–21:00, Y: +1 — работа (зелёный), -1 — авария (красный),
   ещё не наступившее время — 0. */
function renderTimeline(alarms, dayMs) {
  const svg = document.getElementById('shiftChart');
  const empty = document.getElementById('chartEmpty');
  if (dayMs == null) {
    svg.style.display = 'none';
    empty.style.display = '';
    return;
  }
  empty.style.display = 'none';
  svg.style.display = '';

  const d0 = new Date(dayMs);
  d0.setHours(0, 0, 0, 0);
  const winStart = d0.getTime() + 7 * 3600 * 1000;
  const winEnd = d0.getTime() + 21 * 3600 * 1000;
  const span = winEnd - winStart;
  const nowMs = Math.min(Date.now(), winEnd);

  const ints = (alarms || [])
    .map(a => ({ s: a.stop_time, e: a.start_time || nowMs,
                 dev: a.device_id, id: a.alarm_id }))
    .filter(x => x.s != null && x.e > x.s)
    .map(x => ({ ...x, s: Math.max(winStart, x.s), e: Math.min(nowMs, x.e) }))
    .filter(x => x.e > x.s)
    .sort((a, b) => a.s - b.s);

  const segs = [];
  let cur = winStart;
  for (const iv of ints) {
    if (iv.s > cur) segs.push({ work: true, s: cur, e: iv.s });
    segs.push({ work: false, s: Math.max(cur, iv.s), e: iv.e, dev: iv.dev, id: iv.id });
    cur = Math.max(cur, iv.e);
  }
  if (cur < nowMs) segs.push({ work: true, s: cur, e: nowMs });
  if (nowMs < winEnd) segs.push({ future: true, s: nowMs, e: winEnd });

  const W = Math.max(320, svg.parentElement.clientWidth);
  const H = 260, padL = 56, padR = 16, padT = 16, padB = 26;
  const mid = padT + (H - padT - padB) / 2;
  const half = (H - padT - padB) / 2 - 8;
  const plotW = W - padL - padR;
  const x = t => padL + (t - winStart) / span * plotW;
  const yv = v => mid - v * half;
  const valOf = g => g.future ? 0 : (g.work ? 1 : -1);
  const colorOf = g => g.future ? '#8b98a5' : (g.work ? '#2e7d32' : '#c62828');

  const p = [];
  p.push(`<line x1="${padL}" y1="${mid.toFixed(1)}" x2="${W - padR}" y2="${mid.toFixed(1)}" stroke="#2c3745"/>`);
  for (const v of [1, 0, -1]) {
    const yy = yv(v);
    p.push(`<text x="${padL - 8}" y="${(yy + 4).toFixed(1)}" fill="#8b98a5" font-size="11" text-anchor="end">${v > 0 ? '+' : ''}${v}</text>`);
  }
  for (let hh = 7; hh <= 21; hh += 2) {
    const t = d0.getTime() + hh * 3600 * 1000;
    p.push(`<text x="${x(t).toFixed(1)}" y="${H - 8}" fill="#8b98a5" font-size="11" text-anchor="middle">${String(hh).padStart(2, '0')}:00</text>`);
  }

  segs.forEach((g, i) => {
    const y = yv(valOf(g)).toFixed(1);
    const x0 = x(g.s).toFixed(1), x1 = x(g.e).toFixed(1);
    if (g.future) {
      p.push(`<line x1="${x0}" y1="${y}" x2="${x1}" y2="${y}" stroke="${colorOf(g)}" stroke-width="1.4" stroke-dasharray="4 3"/>`);
    } else if (g.work) {
      p.push(`<line x1="${x0}" y1="${y}" x2="${x1}" y2="${y}" stroke="${colorOf(g)}" stroke-width="2.2"/>`);
    } else {
      const tip = 'Авария ' + fmtClock(g.s) + '–' + fmtClock(g.e) +
        ' — нажмите, чтобы показать в таблице';
      p.push(`<line x1="${x0}" y1="${y}" x2="${x1}" y2="${y}" stroke="transparent" stroke-width="16" style="cursor:pointer" onclick="focusAlarm('${g.dev}','${g.id}')"><title>${tip}</title></line>`);
      p.push(`<line x1="${x0}" y1="${y}" x2="${x1}" y2="${y}" stroke="${colorOf(g)}" stroke-width="2.6" pointer-events="none"/>`);
    }
    if (i > 0) {
      const yA = yv(valOf(segs[i - 1])).toFixed(1);
      p.push(`<line x1="${x0}" y1="${yA}" x2="${x0}" y2="${y}" stroke="#5b6673" stroke-width="1.2"/>`);
    }
  });

  svg.setAttribute('width', W);
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.innerHTML = p.join('');
}

function renderShiftEvents(rows) {
  const el = document.getElementById('shiftLog');
  if (!rows.length) {
    el.innerHTML = '<div id="empty">Записей о сменах пока нет</div>';
    return;
  }
  el.innerHTML = `<table><thead><tr>
      <th>Время</th><th>Событие</th><th>Оператор</th><th>Механик</th>
      <th>Сообщение</th><th></th></tr></thead><tbody>` +
    rows.map(e => `<tr>
      <td>${fmtTime(e.device_time || e.received_at)}</td>
      <td>${esc(shiftTypeLabel[e.type] || e.type || e.category)}</td>
      <td>${esc(e.operator || '—')}</td>
      <td>${esc(e.mechanic || '—')}</td>
      <td class="muted">${esc(e.message)}</td>
      <td><button class="btn del" onclick="deleteEvent(${e.id})">Удалить</button></td>
    </tr>`).join('') + '</tbody></table>';
}
loadMe();
loadFilters();
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>"""


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description="Line Stop Monitor web server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--db", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "linestop_web.db"))
    default_apk = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    parser.add_argument("--apk", default=default_apk,
                        help="путь к APK для скачивания с сайта")
    args = parser.parse_args()

    server = create_server(args.host, args.port, args.db, args.apk)
    print("=" * 60)
    print("Line Stop Monitor — веб-мониторинг")
    print("База данных: %s" % os.path.abspath(args.db))
    print("Панель:      http://%s:%d/" % (
        "127.0.0.1" if args.host in ("0.0.0.0", "") else args.host, args.port))
    print("Приём:       POST http://<host>:%d/api/events" % args.port)
    print("APK:         http://<host>:%d/download/app.apk" % args.port)
    print("Доступ:      логин admin / пароль admin")
    print("В Android:   Настройки -> адрес сервера мониторинга")
    print("=" * 60)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
        print("Остановлено.")


if __name__ == "__main__":
    main()
