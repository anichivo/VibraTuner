package com.personal.vibratuner.core.config

/**
 * Единственное место сетевой конфигурации приложения.
 *
 * [AUTHOR_MANIFEST_URL] — адрес манифеста плейлистов «От автора» (этап 4).
 * Каталог: `https://github.com/anichivo/playlists` (аудио — в GitHub Releases).
 * Берётся raw-путь GitHub: `blob`-адрес отдаёт HTML, а не JSON. Этот адрес
 * отвечает редиректом на raw-хост, поэтому загрузчик следует редиректам.
 * Относительные `icon` (`icons/…`) разрешаются от адреса манифеста
 * ([com.personal.vibratuner.data.remote.CatalogIconUrl]) — тоже в новом
 * репозитории. Образец формата — `docs/author-manifest-example.json`.
 *
 * Пустое значение или шаблон с `OWNER/REPO` считается «не задано»: приложение
 * показывает ошибку (или сохранённый кеш), но НИКОГДА не падает и не
 * повторяет запрос автоматически.
 */
object AppConfig {

    const val AUTHOR_MANIFEST_URL: String =
        "https://github.com/anichivo/playlists/raw/main/manifest.json"

    /**
     * Репозиторий приложения на GitHub — ЕДИНСТВЕННОЕ место владельца и имени
     * репозитория. Из них строятся страница релизов (ссылка в «Настройках» и
     * запасной адрес для UI) и адрес API последнего релиза (проверка
     * обновлений). НИКОГДА не дублировать owner/repo в экранах, ресурсах и
     * других классах: репозиторий должен быть ПУБЛИЧНЫМ, иначе
     * неаутентифицированный GitHub API отвечает 404.
     */
    const val GITHUB_REPOSITORY_OWNER: String = "anichivo"
    const val GITHUB_REPOSITORY_NAME: String = "VibraTuner"

    /** Страница релизов для человека (открывается браузером). */
    val GITHUB_RELEASES_PAGE_URL: String =
        "https://github.com/$GITHUB_REPOSITORY_OWNER/$GITHUB_REPOSITORY_NAME/releases/"

    /**
     * `GET /repos/{owner}/{repo}/releases/latest`: последний ОПУБЛИКОВАННЫЙ
     * релиз без черновиков и пререлизов (их отсекает сам GitHub). Для
     * анонимного запроса заголовки задаёт `UpdateChecker`.
     */
    val GITHUB_LATEST_RELEASE_API_URL: String =
        "https://api.github.com/repos/$GITHUB_REPOSITORY_OWNER/$GITHUB_REPOSITORY_NAME/releases/latest"
}
