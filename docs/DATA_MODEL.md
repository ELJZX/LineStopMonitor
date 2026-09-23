# Модель данных

Локальное хранилище — SQLite-база `linestop.db` (файл `data/AppDatabase.kt`,
`SQLiteOpenHelper`). Версия схемы — **2**. Сторонний ORM не используется.

## Схема БД

### `shifts` — смены операторов

```sql
CREATE TABLE shifts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    operator   TEXT NOT NULL,
    start_time INTEGER NOT NULL,
    end_time   INTEGER
);
```

- `end_time IS NULL` — активная смена. Активную смену возвращает
  `ShiftDao.active()` (`ORDER BY id DESC LIMIT 1`).
- `ShiftDao.start()` перед вставкой закрывает предыдущую активную смену.

### `alarms` — случаи аварий

```sql
CREATE TABLE alarms (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    shift_id      INTEGER,
    stop_time     INTEGER NOT NULL,   -- появление сигнала (начало аварии)
    start_time    INTEGER,            -- исчезновение сигнала (запуск линии)
    duration_ms   INTEGER,
    cause_code    INTEGER,
    cause_text    TEXT,               -- свободный текст «Другой причины»
    cause_path    TEXT,               -- «Категория / Пункт / Причина»
    closed        INTEGER NOT NULL DEFAULT 0,
    dialog_shown  INTEGER NOT NULL DEFAULT 0,
    ack_pending   INTEGER NOT NULL DEFAULT 0,
    start_counter INTEGER NOT NULL DEFAULT 0
);
```

Индексы: `idx_alarms_stop` по `stop_time`, `idx_logs_time` по `logs(time)`.

### `logs` — подробный журнал событий

```sql
CREATE TABLE logs (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    time     INTEGER NOT NULL,
    level    TEXT NOT NULL,      -- INFO | WARN | ERROR
    category TEXT NOT NULL,      -- APP | PLC | ALARM | ACK | SHIFT
    message  TEXT NOT NULL,
    alarm_id INTEGER,
    shift_id INTEGER
);
```

## Миграции

`onUpgrade` с версии `< 2` добавляет `alarms.cause_path` (`ALTER TABLE`).
Других миграций нет; при изменении схемы нужно поднимать `VERSION` и добавлять
ветку в `onUpgrade`.

## Доменные модели

| Класс | Файл | Комментарий |
|-------|------|-------------|
| `AlarmRecord` | `data/AlarmRecord.kt` | Случай аварии; `ongoing = startTime == null`; `causeLabel` собирает текст причины |
| `ShiftRecord` | `data/ShiftRecord.kt` | Смена; `active = endTime == null` |
| `LogEntry` | `data/LogEntry.kt` | Строка журнала; константы уровней и категорий |
| `PlcSnapshot` | `plc/PlcSnapshot.kt` | Снимок опроса `D0..D5` (в БД не хранится) |

## DAO

`AlarmDao`, `ShiftDao`, `LogDao` — ручные обёртки над `SQLiteDatabase`
(чтение через `rawQuery` + `Cursor`, запись через `ContentValues`). Расширения
чтения `Cursor.getLongOrNull/getIntOrNull/getStringOrNull` — в `data/CursorExt.kt`.

- `AlarmDao.all()` — все аварии по возрастанию `stop_time`; используется при
  старте ViewModel и далее список живёт в памяти как `StateFlow`.
- `LogDao.recent(limit)` — последние записи (`ORDER BY id DESC LIMIT ?`);
  ViewModel держит `LOG_LIMIT = 300`.
- `ShiftDao.all()` и `LogDao.count()` в текущем UI не задействованы.

## Важные нюансы

- **Имя оператора хранится только в `shifts`.** В `alarms` есть лишь
  `shift_id`, поэтому любой отчёт/фильтр «кто был на смене» требует соединения
  `alarms.shift_id = shifts.id`.
- `clearHistory()` (кнопка «Очистить историю») удаляет **`alarms` и `logs`**, но
  **не `shifts`**. История смен сохраняется.
- Запись — write-through: после каждого изменения списка `persistAlarms`
  сравнивает записи по `id` и делает `INSERT`/`UPDATE`; `AlarmEngine` задаёт `id`
  заранее, поэтому id в памяти и БД совпадают.
- Время хранится в миллисекундах Unix (`System.currentTimeMillis()`), в часовом
  поясе и по часам планшета.
