# Архитектура

## Общая схема

```
 ┌───────────────┐  24 В (уровень)   ┌─────────────────────────┐
 │ Линия         │ ───────────────►  │  DVP12SE11R (ПЛК)       │
 └───────────────┘  X0 = авария      │  X0 — вход сигнала      │
                    (есть, пока      │  M10/M11 — защёлки      │
                     линия в аварии) │  D0..D5, D100..D101     │
                                      └───────────┬─────────────┘
                                                  │ Modbus TCP :502
                                                  │ (Ethernet, IP 172.16.29.150)
                                                  ▼
                                      ┌─────────────────────────┐
                                      │  Android-планшет        │
                                      │  ModbusTcpClient        │
                                      │  LineStopViewModel      │
                                      │  AlarmEngine (домен)    │
                                      │  SQLite (alarms/shifts/ │
                                      │  logs) + Compose UI     │
                                      └─────────────────────────┘
```

## Слои приложения

Пакет приложения — `com.finnah.linestop`, один Gradle-модуль `:app`.

| Слой | Файлы | Ответственность |
|------|-------|-----------------|
| Вход | `MainActivity.kt` | `ComponentActivity`, ставит `LineStopTheme { MainScreen(vm) }` |
| UI | `ui/MainScreen.kt`, `ui/Dialogs.kt`, `ui/CauseDialog.kt`, `ui/SettingsDialog.kt` | Jetpack Compose: экран, статус, история и все диалоги |
| Состояние | `LineStopViewModel.kt` | Опрос ПЛК, оркестрация `AlarmEngine`, запись в БД, журнал |
| Домен | `domain/AlarmEngine.kt` | Чистый автомат аварий (без Android/ПЛК) |
| Данные | `data/AppDatabase.kt`, `data/*Dao.kt`, `data/*Record.kt`, `data/CauseCatalog.kt` | SQLite (`SQLiteOpenHelper`) и справочник причин |
| ПЛК | `plc/ModbusTcpClient.kt`, `plc/PlcRegisters.kt`, `plc/PlcSnapshot.kt` | Ручной Modbus TCP клиент (FC 3/6/16) |
| Утилиты | `util/TimeFormat.kt`, `util/AccessGuard.kt` | Форматирование времени, проверка кода доступа |

Внешних библиотек, кроме AndroidX и kotlinx.coroutines, нет: Modbus реализован
вручную, БД — на `SQLiteOpenHelper` (Room/DI/сторонние сетевые библиотеки не
используются намеренно).

## Поток данных

1. `LineStopViewModel` при старте поднимает корутину `startPolling()` в
   `Dispatchers.IO`.
2. Цикл опроса:
   - при отсутствии соединения — `client.connect()`, сбрасывается флаг
     `firstRead = true` и пишется событие в журнал;
   - читается 6 holding-регистров начиная с `D0` (`PlcRegisters.STATE`):
     `D0..D5` → `PlcSnapshot`;
   - `processEvents(prev, cur)` вызывает `AlarmEngine.poll(...)`;
   - перезапуск отложенных квитирований `retryPendingAckIfDue()`;
   - пауза `POLL_MS = 200` мс.
3. При ошибке чтения `client.close()`, `snapshot.connected = false`,
   `lastError`, запись в журнал (если связь была) и повтор через
   `RETRY_MS = 2000` мс.
4. Изменения списка аварий сохраняются в SQLite (`persistAlarms`), состояние
   отдаётся в UI через `StateFlow`.

## Автомат аварий (`AlarmEngine`)

`AlarmEngine` не хранит состояние сам — он функция над неизменяемым списком
`List<AlarmRecord>`. На каждый цикл опроса возвращает новый список, опциональный
диалог и событие для журнала (`Result`).

Правила:

- **Хаос первых данных (`firstRead`).** На первом чтении после подключения
  событие начала не генерируется. Но если `D0 = 1` и нет ни одной незакрытой
  аварии, создаётся активный случай — это восстановление после перезапуска
  приложения во время аварии.
- **Фронт `D0 0→1`** (`started`) — создаётся новый открытый `AlarmRecord` с
  `stopTime = now`, показывается как активная «АВАРИЯ».
- **Спад `D0 1→0`** (`ended`) — последний незакрытый случай получает
  `startTime`, `durationMs = now - stopTime` и `dialogShown = true`; этот случай
  возвращается в `dialogAlarm`, UI открывает диалог выбора причины.
- **Рост счётчика квитирований `D4`** (`ackDelta = (cur - prev) & 0xFFFF`) —
  подтверждаются последние `ackDelta` случаев с `ackPending = true`:
  `closed = true`, `ackPending = false`, при необходимости подставляется
  `cur.lastCause`.
- **`acknowledge`** — оператор выбрал причину: случай сразу помечается
  `closed = true`, `ackPending = true`, `causeCode/causePath/causeText`
  заполняются, диалог закрывается. Отправка в ПЛК идёт фоном и повторяется до
  подтверждения.
- **`dismiss`** — диалог закрыт без причины: `closed` остаётся `false`,
  `dialogShown = true`; случай виден в списке активных с кнопкой «Указать причину».
- **`nextId`** — `max(id) + 1`; `id` задаётся в памяти и переносится в БД, чтобы
  идентификаторы совпадали.

События (`AlarmEngine.Event`): `Started`, `Ended`, `AckConfirmed` — используются
только для записи в журнал.

Изменения логики `AlarmEngine` обязаны синхронно попадать в Python-порт
`simulator/app_logic.py` и в оба набора тестов.

## Потокобезопасность и жизненный цикл

- Все сетевые операции и работа с БД идут в `Dispatchers.IO`; UI читает
  `StateFlow` через `collectAsStateWithLifecycle()`.
- Ручная отправка квитирования в `acknowledge(...)` идёт отдельной корутиной.
- Повторная отправка квитирования ограничена `MAX_ACK_ATTEMPTS = 10` с
  интервалом `ACK_RETRY_MS = 3000` мс.
- `onCleared()` отменяет `pollJob`, закрывает клиент и пишет событие о закрытии
  приложения.
