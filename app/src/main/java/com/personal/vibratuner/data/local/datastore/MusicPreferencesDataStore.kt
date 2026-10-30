package com.personal.vibratuner.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.MicrosoundIntervalRule
import com.personal.vibratuner.audio.microsound.MicrosoundWindow
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vibra_tuner_preferences")

/**
 * Последняя музыкальная сессия для восстановления мини-плеера при запуске
 * (Part 2). Хранится в существующем DataStore: id трека в библиотеке и позиция.
 */
data class LastPlaybackSession(
    val trackId: Long,
    val positionMs: Long
)

/**
 * Состояние автопроверки обновлений (часть 1): переключатель, время последней
 * УСПЕШНОЙ проверки и данные последнего найденного релиза. Хранится в
 * существующем `DataStore<Preferences>` — Room и миграции не нужны.
 *
 * [lastCheckTimeMs] = 0 — проверки ещё не было; при ошибке сети значение НЕ
 * обновляется (при следующем старте приложение попробует снова).
 */
data class UpdateCheckSettings(
    val enabled: Boolean = false,
    val lastCheckTimeMs: Long = 0L,
    val latestVersionName: String? = null,
    val latestReleaseUrl: String? = null
)

class MusicPreferencesDataStore(private val context: Context) {

    companion object {
        // Режим перемешивания треков (shuffle)
        val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        // Текущее желаемое усиление музыки (0.0f - 1.0f, по умолчанию 1.0f)
        val KEY_CURRENT_GAIN = floatPreferencesKey("current_music_gain")

        // Плавное нарастание громкости (в секундах, по умолчанию 10 сек)
        val KEY_VOLUME_RAMP_DURATION_SEC = intPreferencesKey("volume_ramp_duration_sec")

        // Прерывистая музыка (вкл/выкл, интервал музыки X, длительность паузы Y)
        val KEY_CHOPPY_ENABLED = booleanPreferencesKey("choppy_enabled")
        val KEY_CHOPPY_PLAY_MINUTES = intPreferencesKey("choppy_play_minutes")
        val KEY_CHOPPY_PAUSE_MINUTES = intPreferencesKey("choppy_pause_minutes")

        // Глобальные «Часы тишины»: вкл/выкл, начало и конец в минутах от полуночи
        val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val KEY_QUIET_HOURS_START_MINUTE = intPreferencesKey("quiet_hours_start_minute")
        val KEY_QUIET_HOURS_END_MINUTE = intPreferencesKey("quiet_hours_end_minute")

        // Подсказка про долгое нажатие на трек: показывается, пока будильник так не создавали
        val KEY_ALARM_LONG_PRESS_HINT_SHOWN = booleanPreferencesKey("alarm_long_press_hint_shown")

        // Диалог запроса POST_NOTIFICATIONS показывается один раз (без повторных напоминаний)
        val KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN =
            booleanPreferencesKey("notification_permission_prompt_shown")

        // Приветственный диалог первого запуска: показывается один раз за всё время,
        // флаг ставится при закрытии ЛЮБЫМ способом (кнопка, Back, тап вне)
        val KEY_WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")

        // Правила первого запуска: окно после приветствия, закрыть можно ТОЛЬКО
        // кнопкой «Принять», после чего флаг больше не сбрасывается
        val KEY_TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")

        // Stage 3: подсказка в фильтре «Скачанные» показывается один раз за всё время
        val KEY_DOWNLOADED_FILTER_HINT_SHOWN =
            booleanPreferencesKey("downloaded_filter_hint_shown")

        /**
         * SAF tree-URI папки, выбранной для импорта «Выбрать папку целиком».
         * Хранится, чтобы при старте/возврате в приложение и открытии библиотеки
         * автоматически дочитывать НОВЫЕ файлы из этой же папки (без повторного
         * выбора). Сам доступ к папке живёт в persistable URI permission.
         */
        val KEY_IMPORT_FOLDER_URI = stringPreferencesKey("import_folder_uri")

        // Закреплённый микрозвук «Включение колонки»: файл и усиление.
        val KEY_PINNED_MICROSOUND_ENABLED = booleanPreferencesKey("pinned_microsound_enabled")
        val KEY_PINNED_MICROSOUND_URI = stringPreferencesKey("pinned_microsound_uri")
        val KEY_PINNED_MICROSOUND_FILE_NAME = stringPreferencesKey("pinned_microsound_file_name")
        val KEY_PINNED_MICROSOUND_GAIN = floatPreferencesKey("pinned_microsound_gain")
        // Stage 3: собственное окно работы «Включения колонки». Отсутствие обоих
        // ключей = окно выключено («весь день») — как у старых версий.
        val KEY_PINNED_MICROSOUND_WINDOW_START_MINUTE =
            intPreferencesKey("pinned_microsound_window_start_minute")
        val KEY_PINNED_MICROSOUND_WINDOW_END_MINUTE =
            intPreferencesKey("pinned_microsound_window_end_minute")

        // Фейк-включение колонки: только интервалы min..max. СВОЕГО ЗВУКА НЕТ:
        // фейк всегда играет звук «Включения колонки» и его усиление.
        val KEY_FAKE_PINNED_MICROSOUND_ENABLED =
            booleanPreferencesKey("fake_pinned_microsound_enabled")
        /**
         * Устаревший ключ отдельного звука фейка (Part 4 / v1). Фейк больше не
         * имеет собственного звука, но ключ НИКОГДА не читается и НЕ удаляется:
         * сохранение пользовательских данных важнее.
         */
        @Deprecated("Фейк играет звук «Включения колонки»; ключ не используется")
        val KEY_FAKE_PINNED_MICROSOUND_URI = stringPreferencesKey("fake_pinned_microsound_uri")
        val KEY_FAKE_PINNED_MICROSOUND_MIN_INTERVAL =
            intPreferencesKey("fake_pinned_microsound_min_interval_minutes")
        val KEY_FAKE_PINNED_MICROSOUND_MAX_INTERVAL =
            intPreferencesKey("fake_pinned_microsound_max_interval_minutes")
        // Stage 3: собственное окно работы фейк-включения колонки. Отсутствие
        // обоих ключей = окно выключено («весь день»), поэтому старые данные
        // и чистый первый запуск работают как раньше.
        val KEY_FAKE_PINNED_MICROSOUND_WINDOW_START_MINUTE =
            intPreferencesKey("fake_pinned_microsound_window_start_minute")
        val KEY_FAKE_PINNED_MICROSOUND_WINDOW_END_MINUTE =
            intPreferencesKey("fake_pinned_microsound_window_end_minute")

        // Последняя сыгранная музыкальная сессия (Part 2): только id трека и позиция.
        val KEY_LAST_PLAYBACK_TRACK_ID = longPreferencesKey("last_playback_track_id")
        val KEY_LAST_PLAYBACK_POSITION_MS = longPreferencesKey("last_playback_position_ms")

        // Автопроверка обновлений (часть 1): переключатель (по умолчанию ВЫКЛ),
        // время последней успешной проверки и данные последнего релиза GitHub.
        val KEY_AUTO_UPDATE_CHECK_ENABLED = booleanPreferencesKey("auto_update_check_enabled")
        val KEY_LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val KEY_LATEST_VERSION_NAME = stringPreferencesKey("latest_version_name")
        val KEY_LATEST_RELEASE_URL = stringPreferencesKey("latest_release_url")

        /**
         * Устаревший ключ настройки «за N секунд». Не читается и не изменяется
         * с Part 2/3; сохранён, чтобы не терять пользовательские данные.
         */
        @Deprecated("Тайминг вычисляется по длительности звука + 10 с; ключ больше не используется")
        val KEY_PINNED_MICROSOUND_SECONDS_BEFORE_ALARM =
            intPreferencesKey("pinned_microsound_seconds_before_alarm")

        const val DEFAULT_CURRENT_GAIN = 1.0f
        const val DEFAULT_RAMP_DURATION_SEC = 10
        const val DEFAULT_CHOPPY_PLAY_MINUTES = 6
        const val DEFAULT_CHOPPY_PAUSE_MINUTES = 1
    }

    val shuffleEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SHUFFLE_ENABLED] ?: false
    }

    val currentGainFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_CURRENT_GAIN] ?: DEFAULT_CURRENT_GAIN
    }

    /**
     * Последняя музыкальная сессия или `null` (чистый первый запуск).
     * Невалидный (неположительный) id трактуется как отсутствие сессии.
     */
    val lastPlaybackSessionFlow: Flow<LastPlaybackSession?> = context.dataStore.data.map { preferences ->
        val trackId = preferences[KEY_LAST_PLAYBACK_TRACK_ID] ?: return@map null
        if (trackId <= 0L) {
            null
        } else {
            LastPlaybackSession(
                trackId = trackId,
                positionMs = (preferences[KEY_LAST_PLAYBACK_POSITION_MS] ?: 0L)
                    .coerceAtLeast(0L)
            )
        }
    }

    val volumeRampDurationSecFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOLUME_RAMP_DURATION_SEC] ?: DEFAULT_RAMP_DURATION_SEC
    }

    val choppyEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHOPPY_ENABLED] ?: false
    }

    val choppyPlayMinutesFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHOPPY_PLAY_MINUTES] ?: DEFAULT_CHOPPY_PLAY_MINUTES
    }

    val choppyPauseMinutesFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHOPPY_PAUSE_MINUTES] ?: DEFAULT_CHOPPY_PAUSE_MINUTES
    }

    val alarmLongPressHintShownFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ALARM_LONG_PRESS_HINT_SHOWN] ?: false
    }

    /** Показывался ли уже диалог запроса разрешения на уведомления. */
    val notificationPermissionPromptShownFlow: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN] ?: false
        }

    /** Показывался ли уже приветственный диалог первого запуска. */
    val welcomeShownFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_WELCOME_SHOWN] ?: false
    }

    /** Приняты ли правила первого запуска (обязательное окно после приветствия). */
    val termsAcceptedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_TERMS_ACCEPTED] ?: false
    }

    /** Показывалась ли уже подсказка в фильтре «Скачанные» (только один раз). */
    val downloadedFilterHintShownFlow: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[KEY_DOWNLOADED_FILTER_HINT_SHOWN] ?: false
        }

    /**
     * Сохранённая папка импорта (tree-URI) или `null`, если пользователь ещё
     * не выбирал папку. Пустая строка трактуется как отсутствие папки.
     */
    val importFolderUriFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_IMPORT_FOLDER_URI]?.takeIf { it.isNotBlank() }
    }

    /**
     * Состояние автопроверки обновлений: переключатель (по умолчанию выключен),
     * время последней успешной проверки и данные последнего найденного релиза.
     * Пустые строки трактуются как отсутствие данных.
     */
    val updateCheckSettingsFlow: Flow<UpdateCheckSettings> = context.dataStore.data.map { preferences ->
        UpdateCheckSettings(
            enabled = preferences[KEY_AUTO_UPDATE_CHECK_ENABLED] ?: false,
            lastCheckTimeMs = (preferences[KEY_LAST_UPDATE_CHECK_TIME] ?: 0L).coerceAtLeast(0L),
            latestVersionName = preferences[KEY_LATEST_VERSION_NAME]?.takeIf { it.isNotBlank() },
            latestReleaseUrl = preferences[KEY_LATEST_RELEASE_URL]?.takeIf { it.isNotBlank() }
        )
    }

    val quietHoursFlow: Flow<QuietHoursSettings> = context.dataStore.data.map { preferences ->
        QuietHoursSettings(
            enabled = preferences[KEY_QUIET_HOURS_ENABLED] ?: false,
            startMinuteOfDay = (preferences[KEY_QUIET_HOURS_START_MINUTE]
                ?: QuietHoursSettings.DEFAULT_START_MINUTE_OF_DAY).coerceIn(0, 1439),
            endMinuteOfDay = (preferences[KEY_QUIET_HOURS_END_MINUTE]
                ?: QuietHoursSettings.DEFAULT_END_MINUTE_OF_DAY).coerceIn(0, 1439)
        )
    }

    /**
     * Закреплённый микрозвук «Включение колонки». Без выбранного звука включить
     * его нельзя: флаг принудительно сбрасывается в false. Фейк-включение
     * собственного звука не имеет и использует эти же [PinnedMicrosoundSettings.uri]
     * и [PinnedMicrosoundSettings.gain].
     */
    val pinnedMicrosoundFlow: Flow<PinnedMicrosoundSettings> = context.dataStore.data.map { preferences ->
        val uri = preferences[KEY_PINNED_MICROSOUND_URI] ?: ""
        val enabled = (preferences[KEY_PINNED_MICROSOUND_ENABLED] ?: false) && uri.isNotBlank()
        val fakeIntervals = MicrosoundIntervalRule.sanitize(
            minIntervalMinutes = preferences[KEY_FAKE_PINNED_MICROSOUND_MIN_INTERVAL]
                ?: MicrosoundIntervalRule.DEFAULT_MIN_INTERVAL_MINUTES,
            maxIntervalMinutes = preferences[KEY_FAKE_PINNED_MICROSOUND_MAX_INTERVAL]
                ?: MicrosoundIntervalRule.DEFAULT_MAX_INTERVAL_MINUTES
        )
        PinnedMicrosoundSettings(
            enabled = enabled,
            uri = uri,
            fileName = preferences[KEY_PINNED_MICROSOUND_FILE_NAME] ?: "",
            gain = (preferences[KEY_PINNED_MICROSOUND_GAIN]
                ?: PinnedMicrosoundSettings.DEFAULT_GAIN)
                .coerceIn(PinnedMicrosoundSettings.MIN_GAIN, PinnedMicrosoundSettings.MAX_GAIN),
            fakeEnabled = preferences[KEY_FAKE_PINNED_MICROSOUND_ENABLED] ?: false,
            fakeMinIntervalMinutes = fakeIntervals.first,
            fakeMaxIntervalMinutes = fakeIntervals.second,
            fakeWindow = MicrosoundWindow.sanitize(
                startMinuteOfDay = preferences[KEY_FAKE_PINNED_MICROSOUND_WINDOW_START_MINUTE],
                endMinuteOfDay = preferences[KEY_FAKE_PINNED_MICROSOUND_WINDOW_END_MINUTE]
            ),
            window = MicrosoundWindow.sanitize(
                startMinuteOfDay = preferences[KEY_PINNED_MICROSOUND_WINDOW_START_MINUTE],
                endMinuteOfDay = preferences[KEY_PINNED_MICROSOUND_WINDOW_END_MINUTE]
            )
        )
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHUFFLE_ENABLED] = enabled
        }
    }

    suspend fun setCurrentGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        context.dataStore.edit { preferences ->
            preferences[KEY_CURRENT_GAIN] = clamped
        }
    }

    /** Сохраняет последнюю музыкальную сессию; синтетические треки (id <= 0) не сохраняются. */
    suspend fun saveLastPlaybackSession(trackId: Long, positionMs: Long) {
        if (trackId <= 0L) return
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_PLAYBACK_TRACK_ID] = trackId
            preferences[KEY_LAST_PLAYBACK_POSITION_MS] = positionMs.coerceAtLeast(0L)
        }
    }

    /** Сессия больше не нужна (трек удалён/недоступен или пользователь остановил музыку). */
    suspend fun clearLastPlaybackSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_LAST_PLAYBACK_TRACK_ID)
            preferences.remove(KEY_LAST_PLAYBACK_POSITION_MS)
        }
    }

    suspend fun setVolumeRampDurationSec(seconds: Int) {
        val clamped = seconds.coerceIn(0, 120)
        context.dataStore.edit { preferences ->
            preferences[KEY_VOLUME_RAMP_DURATION_SEC] = clamped
        }
    }

    suspend fun setChoppyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CHOPPY_ENABLED] = enabled
        }
    }

    suspend fun setChoppyIntervals(playMinutes: Int, pauseMinutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CHOPPY_PLAY_MINUTES] = playMinutes.coerceAtLeast(1)
            preferences[KEY_CHOPPY_PAUSE_MINUTES] = pauseMinutes.coerceAtLeast(1)
        }
    }

    /** Подсказка про долгое нажатие больше не нужна после первого создания будильника. */
    suspend fun setAlarmLongPressHintShown() {
        context.dataStore.edit { preferences ->
            preferences[KEY_ALARM_LONG_PRESS_HINT_SHOWN] = true
        }
    }

    /**
     * Диалог запроса POST_NOTIFICATIONS уже показан. Повторно не напоминаем:
     * включить разрешение можно вручную на экране «Надёжность».
     */
    suspend fun setNotificationPermissionPromptShown() {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN] = true
        }
    }

    /**
     * Приветственный диалог первого запуска закрыт кнопкой, системным Back или
     * тапом вне диалога: повторно НИКОГДА не показываем.
     */
    suspend fun setWelcomeShown() {
        context.dataStore.edit { preferences ->
            preferences[KEY_WELCOME_SHOWN] = true
        }
    }

    /**
     * Правила первого запуска приняты кнопкой «Принять»: окно больше НИКОГДА
     * не показываем (другого способа закрыть его нет).
     */
    suspend fun setTermsAccepted() {
        context.dataStore.edit { preferences ->
            preferences[KEY_TERMS_ACCEPTED] = true
        }
    }

    /** Подсказка в фильтре «Скачанные» показана: повторно не показываем. */
    suspend fun setDownloadedFilterHintShown() {
        context.dataStore.edit { preferences ->
            preferences[KEY_DOWNLOADED_FILTER_HINT_SHOWN] = true
        }
    }

    /**
     * Переключатель «Проверять обновления». Выключен по умолчанию: сеть
     * расходуется только после явного согласия пользователя.
     */
    suspend fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_UPDATE_CHECK_ENABLED] = enabled
        }
    }

    /**
     * Результат УСПЕШНОЙ проверки обновлений: имя версии релиза, ссылка на него
     * и время проверки записываются ОДНИМ изменением. Пустые значения не
     * сохраняются (тогда ничего не меняется и время не сдвигается).
     */
    suspend fun saveSuccessfulUpdateCheck(
        versionName: String,
        releaseUrl: String,
        checkedAtMs: Long
    ) {
        val name = versionName.trim()
        val url = releaseUrl.trim()
        if (name.isEmpty() || url.isEmpty()) return
        context.dataStore.edit { preferences ->
            preferences[KEY_LATEST_VERSION_NAME] = name
            preferences[KEY_LATEST_RELEASE_URL] = url
            preferences[KEY_LAST_UPDATE_CHECK_TIME] = checkedAtMs.coerceAtLeast(0L)
        }
    }

    /**
     * Запоминает папку импорта (tree-URI) для автоматического дочитывания
     * новых файлов; `null` — забыть папку (например, доступ к ней потерян).
     */
    suspend fun setImportFolderUri(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(KEY_IMPORT_FOLDER_URI)
            } else {
                preferences[KEY_IMPORT_FOLDER_URI] = uri
            }
        }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_QUIET_HOURS_ENABLED] = enabled
        }
    }

    /**
     * Сохраняет границы часов тишины. Начало и конец не могут совпадать:
     * в этом случае значения не сохраняются, возвращается false.
     */
    suspend fun setQuietHours(startMinuteOfDay: Int, endMinuteOfDay: Int): Boolean {
        val start = startMinuteOfDay.coerceIn(0, 1439)
        val end = endMinuteOfDay.coerceIn(0, 1439)
        if (start == end) return false
        context.dataStore.edit { preferences ->
            preferences[KEY_QUIET_HOURS_START_MINUTE] = start
            preferences[KEY_QUIET_HOURS_END_MINUTE] = end
        }
        return true
    }

    /**
     * Включение закреплённого микрозвука. Переключатель можно включить ДО выбора
     * звука: если звук ещё не выбран, сразу сохраняется звук по умолчанию
     * (первый встроенный), поэтому сохранённое состояние всегда валидно.
     */
    suspend fun setPinnedMicrosoundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            if (enabled) {
                val current = (preferences[KEY_PINNED_MICROSOUND_URI] ?: "").trim()
                if (current.isEmpty()) {
                    preferences[KEY_PINNED_MICROSOUND_URI] =
                        BuiltinMicrosoundCatalog.defaultStoredUri()
                    preferences[KEY_PINNED_MICROSOUND_FILE_NAME] = ""
                }
            }
            preferences[KEY_PINNED_MICROSOUND_ENABLED] = enabled &&
                (preferences[KEY_PINNED_MICROSOUND_URI] ?: "").isNotBlank()
        }
    }

    /** Свой файл «Включения колонки»: заменяет встроенный звук, если он был выбран. */
    suspend fun setPinnedMicrosoundFile(uri: String, fileName: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PINNED_MICROSOUND_URI] = uri
            preferences[KEY_PINNED_MICROSOUND_FILE_NAME] = fileName
        }
    }

    /**
     * Встроенный звук «Включения колонки»: только существующий в каталоге
     * `builtin:<id>`. Свой файл выбирается прежним [setPinnedMicrosoundFile].
     * Имя файла сбрасывается: название встроенного звука берётся из каталога.
     * Неизвестный id не сохраняется (возвращается false).
     */
    suspend fun setPinnedMicrosoundBuiltin(uri: String): Boolean {
        val builtin = BuiltinMicrosoundCatalog.findByStored(uri.trim()) ?: return false
        context.dataStore.edit { preferences ->
            preferences[KEY_PINNED_MICROSOUND_URI] = BuiltinMicrosoundCatalog.encode(builtin.id)
            preferences[KEY_PINNED_MICROSOUND_FILE_NAME] = ""
        }
        return true
    }

    suspend fun setPinnedMicrosoundGain(gain: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PINNED_MICROSOUND_GAIN] = gain.coerceIn(
                PinnedMicrosoundSettings.MIN_GAIN,
                PinnedMicrosoundSettings.MAX_GAIN
            )
        }
    }

    /**
     * Собственное окно работы «Включения колонки» (Stage 3). `null` — окно
     * выключено («весь день»): оба ключа удаляются. Окно с совпадающими началом
     * и концом НЕ сохраняется (возвращается false); переход через полночь допустим.
     */
    suspend fun setPinnedMicrosoundWindow(window: MicrosoundWindow?): Boolean {
        if (window != null && !window.isValid) return false
        context.dataStore.edit { preferences ->
            if (window == null) {
                preferences.remove(KEY_PINNED_MICROSOUND_WINDOW_START_MINUTE)
                preferences.remove(KEY_PINNED_MICROSOUND_WINDOW_END_MINUTE)
            } else {
                preferences[KEY_PINNED_MICROSOUND_WINDOW_START_MINUTE] = window.startMinuteOfDay
                preferences[KEY_PINNED_MICROSOUND_WINDOW_END_MINUTE] = window.endMinuteOfDay
            }
        }
        return true
    }

    /**
     * Включение фейк-включения колонки. Флаг хранится независимо от включения
     * самого звука: при выключенном «Включении колонки» значения сохраняются,
     * но фейк не играет (см. [PinnedMicrosoundSettings.fakeCanPlay]). Отдельного
     * звука у фейка нет: он всегда играет звук «Включения колонки».
     */
    suspend fun setFakePinnedMicrosoundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FAKE_PINNED_MICROSOUND_ENABLED] = enabled
        }
    }

    /**
     * Сохраняет интервалы фейка. Формат и проверка те же, что у обычного
     * микрозвука: 1..10080 минут и мин не больше макс. Некорректная пара
     * не сохраняется, возвращается false.
     */
    suspend fun setFakePinnedMicrosoundIntervals(
        minIntervalMinutes: Int,
        maxIntervalMinutes: Int
    ): Boolean {
        if (!MicrosoundIntervalRule.isValid(minIntervalMinutes, maxIntervalMinutes)) {
            return false
        }
        context.dataStore.edit { preferences ->
            preferences[KEY_FAKE_PINNED_MICROSOUND_MIN_INTERVAL] = minIntervalMinutes
            preferences[KEY_FAKE_PINNED_MICROSOUND_MAX_INTERVAL] = maxIntervalMinutes
        }
        return true
    }

    /**
     * Собственное окно работы фейк-включения колонки. `null` — окно выключено
     * («весь день»): оба ключа удаляются, поэтому отсутствие ключей и выключенное
     * окно — одно и то же состояние. Окно с совпадающими началом и концом
     * НЕ сохраняется (возвращается false); переход через полночь допустим.
     */
    suspend fun setFakePinnedMicrosoundWindow(window: MicrosoundWindow?): Boolean {
        if (window != null && !window.isValid) return false
        context.dataStore.edit { preferences ->
            if (window == null) {
                preferences.remove(KEY_FAKE_PINNED_MICROSOUND_WINDOW_START_MINUTE)
                preferences.remove(KEY_FAKE_PINNED_MICROSOUND_WINDOW_END_MINUTE)
            } else {
                preferences[KEY_FAKE_PINNED_MICROSOUND_WINDOW_START_MINUTE] =
                    window.startMinuteOfDay
                preferences[KEY_FAKE_PINNED_MICROSOUND_WINDOW_END_MINUTE] = window.endMinuteOfDay
            }
        }
        return true
    }
}
