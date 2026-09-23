# Разработка

## Android-приложение не собирается агентом

Сборку и запуск Android-приложения выполняет пользователь. Агент **не** запускает
Gradle-задачи, компилирующие приложение (`assembleDebug`, `installDebug`, `test*`,
`lint`). Проверка изменений — чтением кода и синхронизацией с эмулятором Python.

Справочные команды (только если пользователь явно попросил):

```powershell
.\gradlew.bat assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
# Требуется JDK 17+, Android SDK (local.properties / ANDROID_HOME),
# Gradle 9.4.1, AGP 9.2.1. Системный jdk-24 подходит,
# встроенный C:\Program Files\Android\Android Studio\jbr неполный.
```

## Эмулятор ПЛК и тесты (можно запускать)

```powershell
# Эмулятор ПЛК (Modbus TCP slave)
py -3 simulator/plc_sim.py --host 0.0.0.0 --port 502

# Read-only монитор реального ПЛК
py -3 simulator/plc_monitor.py --host 172.16.29.150

# Интеграционные тесты протокола — 8 тестов, без внешних зависимостей
py -3 -m unittest discover -s simulator -p "test_*.py" -v
```

Эмулятор поднимает Modbus-сервер на эфемерном порту внутри процесса теста.
Управление из консоли: `a` — поднять аварию, `r` — снять, `d` — дамп, `q` —
выход. Тест-хук `D200`: `1` — авария, `2` — запуск линии
(см. `simulator/README.md`).

## Тесты

- **51** unit-тест JUnit4 на JVM: `AlarmEngineTest`, `CauseCatalogTest`,
  `AlarmRecordTest`, `ShiftRecordTest`, `TimeFormatTest`, `PlcRegistersTest`,
  `PlcSnapshotTest`.
- **8** интеграционных Python-тестов `simulator/test_handshake.py`
  (протокол «авария → запуск → причина → квит»).
- Python-тесты запускаются и проверяются агентом; Android-тесты — пользователем
  (см. выше).

## Соглашения

- **Язык.** Документация, комментарии, UI-строки и названия тестов — русские.
  Методы JUnit — в бэктиках: ``fun `первое чтение без аварии ничего не создаёт`()``.
- **Зависимости.** Только AndroidX + kotlinx.coroutines. Modbus TCP и доступ к
  SQLite написаны вручную; не добавлять Room, DI, сторонние сетевые библиотеки.
- **Синхронизация логики.** Любое изменение `domain/AlarmEngine.kt` обязано быть
  перенесено в `simulator/app_logic.py` и сопровождаться правкой обоих наборов
  тестов.
- **Причины остановки.** Редактируются только в `data/CauseCatalog.kt`.
- **Код доступа.** Хранится как SHA-256-хеш в `util/AccessGuard.kt`; никогда не
  хранить и не выводить открытый код.
- **История изменений.** `CHANGELOG.md` — источник правды (Keep a Changelog +
  SemVer). Релиз повышает `versionCode`/`versionName` в `app/build.gradle.kts` и
  добавляет запись в Changelog (в истории — коммиты `release: Line Stop Monitor x.y.z`).
- **Устаревшие документы.** `README.md` и `plc/REGISTER_MAP.md` частично
  устарели: README ссылается на удалённые классы (`AlarmStore`, `StopCause`) и
  папку `CheckStopFinnah` (корень — `LineStopMonitor`). Ориентир — код и
  `CHANGELOG.md`.
