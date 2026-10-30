package com.personal.vibratuner.data.repository

import android.util.Log
import com.personal.vibratuner.core.config.AppConfig
import com.personal.vibratuner.data.local.datastore.MusicPreferencesDataStore
import com.personal.vibratuner.data.local.datastore.UpdateCheckSettings
import com.personal.vibratuner.data.remote.LatestRelease
import com.personal.vibratuner.data.remote.UpdateCheckInterval
import com.personal.vibratuner.data.remote.UpdateChecker
import com.personal.vibratuner.data.remote.UpdateVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Единственная точка проверки обновлений и её состояния для UI (часть 1).
 *
 * Данные — в существующем `DataStore<Preferences>` (см. `UpdateCheckSettings`),
 * Room и миграции не участвуют. Сеть — только `UpdateChecker`
 * (`HttpURLConnection`), одна проверка за раз (`Mutex`).
 *
 * Публичный контракт для UI (его использует часть 2):
 * - `updateAvailable: StateFlow<Boolean>` — переключатель включён И сохранённый
 *   релиз новее УСТАНОВЛЕННОЙ версии; пересчитывается на каждой эмиссии
 *   настроек (и при чтении из хранилища на старте), поэтому после установки
 *   новой версии флаг гаснет сам, без нового сетевого запроса;
 * - `latestVersionName: StateFlow<String?>` — `tag_name` последнего релиза;
 * - `latestReleaseUrl: StateFlow<String>` — `html_url` последнего релиза, а без
 *   него — страница релизов из `AppConfig.GITHUB_RELEASES_PAGE_URL`;
 * - `autoUpdateCheckEnabled: StateFlow<Boolean>` + `setAutoUpdateCheckEnabled`;
 * - `checkOnAppStartIfDue()` — проверка со старта приложения (раз в сутки).
 *
 * Ошибка сети/разбора НИКОГДА не меняет сохранённые данные и время последней
 * проверки и НИКОГДА не бросается в UI.
 */
class UpdateRepository(
    private val preferences: MusicPreferencesDataStore,
    private val checker: UpdateChecker,
    private val installedVersionName: () -> String?,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = { message -> Log.i(TAG, message) }
) {

    private val checkMutex = Mutex()

    /**
     * Установленная версия читается ОДИН раз за процесс: установка нового APK
     * перезапускает процесс, поэтому сравнение всегда идёт с актуальной
     * версией, а PackageManager не дёргается на каждую эмиссию.
     */
    private val installedVersion: String? by lazy { installedVersionName() }

    private val stored: StateFlow<UpdateCheckSettings> =
        preferences.updateCheckSettingsFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = UpdateCheckSettings()
        )

    val autoUpdateCheckEnabled: StateFlow<Boolean> = stored
        .map { it.enabled }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val latestVersionName: StateFlow<String?> = stored
        .map { it.latestVersionName }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val latestReleaseUrl: StateFlow<String> = stored
        .map { it.latestReleaseUrl ?: AppConfig.GITHUB_RELEASES_PAGE_URL }
        .stateIn(scope, SharingStarted.Eagerly, AppConfig.GITHUB_RELEASES_PAGE_URL)

    val updateAvailable: StateFlow<Boolean> = stored
        .map { settings ->
            if (!settings.enabled) return@map false
            val latest = settings.latestVersionName ?: return@map false
            if (!UpdateVersion.isParsable(latest) || !UpdateVersion.isParsable(installedVersion)) {
                logger(
                    "Версия не разобрана (релиз «$latest», установлено «$installedVersion») — " +
                        "обновления нет"
                )
                return@map false
            }
            UpdateVersion.isNewer(latest, installedVersion)
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Переключатель «Проверять обновления». Включение сразу запускает ОДНУ
     * проверку; выключение только снимает флаг (плановой работы в проекте нет,
     * отменять нечего), `updateAvailable` при выключенном переключателе всегда
     * `false` по формуле.
     */
    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setAutoUpdateCheckEnabled(enabled)
            if (enabled) {
                checkNow()
            } else {
                logger("Автопроверка обновлений выключена: сохранённые данные не удаляются")
            }
        }
    }

    /**
     * Проверка при старте ПРОЦЕССА. Вызывается ТОЛЬКО из
     * `VibraTunerApplication.onCreate` (один раз за процесс): WorkManager в проекте
     * нет, поэтому «раз в сутки» считается по времени последней УСПЕШНОЙ
     * проверки. Сервисы, AlarmManager и `BOOT_COMPLETED` не участвуют.
     */
    fun checkOnAppStartIfDue() {
        scope.launch {
            val current = preferences.updateCheckSettingsFlow.first()
            if (!current.enabled) {
                logger("Автопроверка обновлений выключена — стартовая проверка пропущена")
                return@launch
            }
            if (!UpdateCheckInterval.isDue(current.lastCheckTimeMs, System.currentTimeMillis())) {
                return@launch
            }
            checkNow()
        }
    }

    /**
     * Одна проверка. При ЛЮБОЙ ошибке сохранённые данные и время последней
     * проверки остаются прежними. Параллельные вызовы не выполняются.
     */
    suspend fun checkNow() {
        if (!checkMutex.tryLock()) {
            logger("Проверка обновлений уже идёт — повторная не запускается")
            return
        }
        try {
            val release: LatestRelease = checker.fetchLatestRelease() ?: return
            preferences.saveSuccessfulUpdateCheck(
                versionName = release.versionName,
                releaseUrl = release.releaseUrl,
                checkedAtMs = System.currentTimeMillis()
            )
            logger("Проверка обновлений: последний релиз ${release.versionName}")
        } finally {
            checkMutex.unlock()
        }
    }

    private companion object {
        const val TAG = "UpdateRepository"
    }
}
