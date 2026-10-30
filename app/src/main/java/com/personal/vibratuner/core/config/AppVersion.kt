package com.personal.vibratuner.core.config

import android.content.Context

/**
 * Установленная версия приложения из `PackageManager` (`versionName` из
 * `app/build.gradle.kts`).
 *
 * `buildConfig = false`, поэтому `BuildConfig.VERSION_NAME` недоступен, а
 * версия НИКОГДА не хардкодится в коде, ресурсах и экранах. Единственная
 * точка чтения: блок «О приложении» в «Настройках» и сравнение версий в
 * проверке обновлений.
 *
 * `null` — версия не прочитана (например, пакет переустанавливается). Такое
 * значение трактуется вызывающим как «сравнивать не с чем»: обновления нет.
 */
object AppVersion {

    @Suppress("DEPRECATION")
    fun currentName(context: Context): String? =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
}
