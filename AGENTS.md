# VibraTuner — руководство для AI-агентов

VibraTuner — личное Android-приложение для музыки и коротких «микрозвуков» по расписанию: сеансы, каталог «От автора», микрозвуки, эквалайзер, «Включение колонки» и фейк-включение. Kotlin + Jetpack Compose + Media3. Версия: `versionName 1.0`, `versionCode 1`, `applicationId com.personal.vibratuner`.

## Стек и версии
- Kotlin 2.3.20, AGP 9.0.1, Gradle 9.1.0, Java toolchain 17 (`source/target 17`). Для запуска Gradle нужен JDK 17+; сборка проверена на JDK 25.
- Compose BOM 2026.03.01, Material 3, `material-icons-extended`.
- Media3 1.5.1 (ExoPlayer + MediaSession), Room 2.6.1 (только аннотации; DAO и БД — рукописный SQLite), DataStore 1.1.2, DocumentFile 1.0.1.
- `minSdk 31`, `compileSdk/targetSdk 36` (Android 12+).

## Команды
Windows (PowerShell):
```powershell
.\gradlew.bat --quiet assembleDebug
.\gradlew.bat --quiet testDebugUnitTest
```
Linux/macOS:
```bash
./gradlew --quiet assembleDebug
./gradlew --quiet testDebugUnitTest
```
Обе команды должны завершаться с кодом 0. Release: `.\gradlew.bat --quiet assembleRelease`. Подпись настраивается только при наличии `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); без него release собирается неподписанным. APK: `app/build/outputs/apk/release/app-release.apk`.

## Карта проекта
- `app/src/main/java/com/personal/vibratuner/` — исходники.
  - `MainActivity.kt` — единственная Activity; edge-to-edge и `MainScreenLayout`.
  - `VibraTunerApplication.kt` — создание БД, DataStore, репозиториев, загрузок, фоновых сканов и проверки обновлений.
  - `service/SoundPlaybackService.kt` — единственный foreground-сервис; в нём оба ExoPlayer (музыка и микрозвук) и `PlaybackCoordinator`.
  - `service/PlaybackNotification.kt` — единственное уведомление воспроизведения.
  - `audio/coordinator/` — приоритеты, состояние, очередь, режимы, история.
  - `audio/volume/` — `SystemVolumeController` и резерв системной громкости.
  - `audio/eq/`, `audio/choppy/`, `audio/microsound/` — эквалайзер, прерывистая музыка, окна и тишина.
  - `data/local/db/` — Room-сущности, версия БД и миграции, рукописный SQLite.
  - `data/local/datastore/`, `data/local/alarm/`, `data/local/microsound/`, `data/local/saf/` — настройки, планировщики AlarmManager, импорт файлов.
  - `data/remote/`, `data/download/` — каталог «От автора», иконки, скачивание, проверка обновлений.
  - `receiver/` — `AlarmReceiver`, `MicrosoundReceiver`, `PlaybackNotificationReceiver`, `BootCompletedReceiver`.
  - `ui/` — Compose-экраны и общие компоненты; `ui/navigation/MainScreenLayout.kt` — пять разделов, мини-плеер и единственный Snackbar.
  - `theme/` — `Color.kt`, `Dimens.kt`, `Theme.kt`, `Type.kt` (только тёмная тема).
- `app/src/main/res/values/strings.xml` — все строки интерфейса; `help_texts.xml` — справка, приветствие и внешние ссылки.
- `app/src/test/java/...` — 74 набора JVM-тестов (655 тестов).
- `docs/` — `STATE.md`, `CATALOG_ICONS.md`, `author-manifest-example.json`.

## Критические инварианты
Полный список — `.agent/rules/project-rules.md`. Кратко:
- Приоритет звука: `MICROSOUND > CHOPPY_PAUSE > MUSIC`; конфликт решает только `PlaybackCoordinator` в `SoundPlaybackService`.
- Системная громкость меняется ТОЛЬКО через `SystemVolumeController`, только при `enabled && boostSystemVolume && appIsAudible`; прежний уровень возвращается.
- `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` только перепланируют сигналы; сервис не запускается (исключение — `SystemVolumeRecovery.recoverIfPending` на загрузке).
- Room: рукописный `SQLiteAppDatabase`, версия 13, только явные миграции; `fallbackToDestructiveMigration` запрещён.
- Только тёмная тема; цвета, размеры и типографика — из `theme/`; строки — из `strings.xml` (справка — `help_texts.xml`).
- Проверки `Build.VERSION.SDK_INT` — только в `PlatformCompat`.
- Один путь воспроизведения: `SoundPlaybackService` + координатор; вторых плееров, сервисов, уведомлений и Snackbar-механизмов нет.
- `Toast` в UI не используется; ошибки сети/загрузки/воспроизведения — одним текстом `error_connection_common` через корневой Snackbar.
- Настройки — в одном `DataStore` (`vibra_tuner_preferences`); схема Room ради настроек не меняется.

## Протокол сессии
1. В начале прочитайте только `AGENTS.md`, `.agent/rules/project-rules.md` и `docs/STATE.md`; исходники открывайте точечно по задаче.
2. Не запускайте эмулятор и не меняйте `.gitignore`, Gradle-файлы и манифест без отдельной задачи.
3. В конце: `assembleDebug` и `testDebugUnitTest` с кодом 0; обновите `docs/STATE.md` (короче 100 строк) и правила, если изменились инварианты; сделайте один коммит.

## Чего НЕ делать
- Не добавлять библиотеки без крайней необходимости.
- Не реализовывать функции вне задачи и не переписывать файлы целиком.
- Не создавать параллельные пути воспроизведения, второе уведомление, второй Snackbar/Toast.
- Не хардкодить цвета, `.dp`, строки и версии в экранах.
- Не менять схему Room без явной миграции и теста.

## Документация
- `README.md` — для людей: что это, установка APK, сборка, разрешения.
- `docs/STATE.md` — текущее состояние, карта файлов, решения, известные проблемы.
- `.agent/rules/project-rules.md` — полный список инвариантов (файл опубликован в репозитории; остальные файлы `.agent/` — нет).
- `docs/CATALOG_ICONS.md` — формат иконок каталога; `docs/author-manifest-example.json` — пример манифеста.
