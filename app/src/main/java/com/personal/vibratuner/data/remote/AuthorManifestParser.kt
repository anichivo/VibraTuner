package com.personal.vibratuner.data.remote

/** Версия формата манифеста, которую понимает приложение. */
const val AUTHOR_MANIFEST_VERSION = 1

/**
 * Предел длины описания плейлиста в символах. Автору рекомендуется 100–500
 * символов, но всё, что длиннее, безопасно обрезается при разборе — экран
 * не должен страдать от гигантского текста.
 */
const val AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH = 2000

/**
 * Верхняя граница необязательной длительности трека (`duration`, секунды):
 * сутки. Больше — считаем значение некорректным и игнорируем, чтобы итог
 * длительности не превращался в бессмысленное число.
 */
private const val MAX_TRACK_DURATION_SECONDS = 24.0 * 60.0 * 60.0

/**
 * Плейлист «От автора» из манифеста. [id] уникален в пределах манифеста и
 * используется как аргумент read-only экрана плейлиста.
 */
data class AuthorManifestPlaylist(
    val id: String,
    val name: String,
    val tracks: List<AuthorTrack>,
    /**
     * Необязательное описание для шапки экрана плейлиста. `null` — поля нет,
     * оно пустое, не является строкой или содержит только пробелы.
     * Переводы строк сохранены (`\n` — строка, `\n\n` — абзац).
     */
    val description: String? = null,
    /**
     * Необязательная иконка плейлиста: РАЗРЕШЁННЫЙ абсолютный https-адрес PNG
     * ([CatalogIconUrl]). В манифесте допускается и относительный путь от
     * адреса каталога — он разрешается при разборе. `null` — иконки нет или
     * значение некорректно; тогда UI рисует запасную иконку.
     */
    val icon: String? = null
)

/** Трек из манифеста: только https-адрес потока, без скачивания. */
data class AuthorTrack(
    val title: String,
    val artist: String?,
    val url: String,
    /**
     * Необязательная длительность трека в миллисекундах (поле `duration` —
     * секунды). Нужна для итоговой длительности плейлиста на read-only экране.
     * 0 — поле отсутствует/некорректно; итог тогда не показывается.
     */
    val durationMs: Long = 0L
)

/**
 * Результат разбора манифеста.
 *
 * [Success.warnings] и [Failure.warnings] — готовые строки для существующего
 * лога: разборщик САМ не пишет в лог, чтобы оставаться чистой функцией
 * (юнит-тесты на JVM).
 */
sealed interface AuthorManifestParseResult {
    data class Success(
        val playlists: List<AuthorManifestPlaylist>,
        val warnings: List<String> = emptyList()
    ) : AuthorManifestParseResult

    data class Failure(
        val reason: String,
        val warnings: List<String> = emptyList()
    ) : AuthorManifestParseResult
}

/**
 * Разбор манифеста версии 1 (см. `docs/author-manifest-example.json`).
 *
 * Правила:
 * - неизвестные поля игнорируются;
 * - `artist` необязателен;
 * - `duration` (секунды) необязательна: используется только для итоговой
 *   длительности плейлиста; отрицательные, нечисловые и >24 ч значения
 *   трактуются как неизвестные;
 * - `description` необязателен: только строка, trim, CRLF -> LF, не длиннее
 *   [AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH] (длиннее — обрезается с предупреждением);
 * - `icon` необязателен: абсолютный https-адрес PNG либо относительный путь от
 *   адреса манифеста ([manifestUrl]); после разрешения обязан быть https,
 *   иначе `null` с предупреждением (старый каталог без `icon` работает как раньше);
 * - плейлист без непустых `id`/`name` пропускается с предупреждением;
 * - трек без непустых `title`/`url` или с не-https `url` пропускается
 *   с предупреждением;
 * - повтор `id` плейлиста и повтор `url` внутри плейлиста пропускаются
 *   с предупреждением (id уникален, а ключ строки в UI — URL);
 * - `version` обязателен и должен быть равен [AUTHOR_MANIFEST_VERSION];
 *   версия больше поддерживаемой — ошибка (вызывающий код уходит в кеш).
 *
 * Ограничение размера ответа (1 МБ) проверяется в репозитории до вызова.
 */
object AuthorManifestParser {

    /**
     * @param manifestUrl адрес самого манифеста: от него разрешаются
     *   относительные пути `icon` ([CatalogIconUrl]). `null` — относительные
     *   иконки проигнорируются (уже загруженный JSON без базы).
     */
    fun parse(raw: String, manifestUrl: String? = null): AuthorManifestParseResult {
        val warnings = mutableListOf<String>()
        if (raw.isBlank()) {
            return AuthorManifestParseResult.Failure("пустой ответ манифеста")
        }
        val root = try {
            MiniJson.parse(raw)
        } catch (e: JsonParseException) {
            return AuthorManifestParseResult.Failure("некорректный JSON: ${e.message}")
        }
        val rootObject = root as? JsonValue.Obj
            ?: return AuthorManifestParseResult.Failure("корень манифеста — не объект")

        val version = (rootObject.fields["version"] as? JsonValue.Num)?.value
            ?: return AuthorManifestParseResult.Failure("поле version отсутствует или не число")
        if (version != AUTHOR_MANIFEST_VERSION.toDouble()) {
            val reason = if (version > AUTHOR_MANIFEST_VERSION) {
                "версия манифеста $version новее поддерживаемой $AUTHOR_MANIFEST_VERSION"
            } else {
                "неподдерживаемая версия манифеста $version"
            }
            return AuthorManifestParseResult.Failure(reason)
        }

        val rawPlaylists = rootObject.fields["playlists"] as? JsonValue.Arr
            ?: return AuthorManifestParseResult.Failure("поле playlists отсутствует или не массив")

        val playlists = mutableListOf<AuthorManifestPlaylist>()
        val usedIds = mutableSetOf<String>()
        rawPlaylists.items.forEachIndexed { index, item ->
            val position = index + 1
            val objectItem = item as? JsonValue.Obj
            if (objectItem == null) {
                warnings += "плейлист №$position не объект — пропущен"
                return@forEachIndexed
            }
            val id = objectItem.nonBlankString("id")
            if (id == null) {
                warnings += "плейлист №$position без id — пропущен"
                return@forEachIndexed
            }
            if (!usedIds.add(id)) {
                warnings += "плейлист №$position с повторным id «$id» — пропущен"
                return@forEachIndexed
            }
            val name = objectItem.nonBlankString("name")
            if (name == null) {
                warnings += "плейлист «$id» без name — пропущен"
                return@forEachIndexed
            }
            val tracks = parseTracks(objectItem.fields["tracks"], id, warnings)
            val description = parseDescription(objectItem, id, warnings)
            val icon = parseIcon(objectItem, id, manifestUrl, warnings)
            playlists += AuthorManifestPlaylist(
                id = id,
                name = name,
                tracks = tracks,
                description = description,
                icon = icon
            )
        }
        return AuthorManifestParseResult.Success(playlists = playlists, warnings = warnings)
    }

    /**
     * Необязательное описание: только строка, trim, CRLF/CR -> LF, не длиннее
     * [AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH]. Нестроковое и пустое значение
     * молча игнорируется (как необязательный `artist`); обрезка длинного текста
     * попадает в лог, потому что меняет авторский контент.
     */
    private fun parseDescription(
        playlist: JsonValue.Obj,
        playlistId: String,
        warnings: MutableList<String>
    ): String? {
        val raw = (playlist.fields["description"] as? JsonValue.Str)?.value ?: return null
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return null
        if (normalized.length > AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH) {
            warnings += "плейлист «$playlistId»: описание длиннее " +
                "$AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH символов — обрезано"
            return normalized.take(AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH).trimEnd()
        }
        return normalized
    }

    /**
     * Необязательная иконка: только строка, trim. Отсутствие, `null`, пустая
     * строка и нестроковое значение молча дают `null` (как необязательный
     * `artist`), а неразрешимая ссылка (не https, битый URI, относительный путь
     * без адреса манифеста) попадает в лог предупреждением — это ошибка автора
     * каталога, но плейлист из-за неё НИКОГДА не пропускается.
     */
    private fun parseIcon(
        playlist: JsonValue.Obj,
        playlistId: String,
        manifestUrl: String?,
        warnings: MutableList<String>
    ): String? {
        val raw = (playlist.fields["icon"] as? JsonValue.Str)?.value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val resolved = CatalogIconUrl.resolve(raw, manifestUrl)
        if (resolved == null) {
            warnings += "плейлист «$playlistId»: icon «$raw» не https-адрес " +
                "и не относительный путь — проигнорирован"
        }
        return resolved
    }

    private fun parseTracks(
        rawTracks: JsonValue?,
        playlistId: String,
        warnings: MutableList<String>
    ): List<AuthorTrack> {
        val array = rawTracks as? JsonValue.Arr
        if (rawTracks != null && array == null) {
            warnings += "плейлист «$playlistId»: поле tracks не массив — плейлист оставлен пустым"
        }
        if (array == null) return emptyList()

        val tracks = mutableListOf<AuthorTrack>()
        val usedUrls = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val position = index + 1
            val objectItem = item as? JsonValue.Obj
            if (objectItem == null) {
                warnings += "плейлист «$playlistId», трек №$position не объект — пропущен"
                return@forEachIndexed
            }
            val title = objectItem.nonBlankString("title")
            if (title == null) {
                warnings += "плейлист «$playlistId», трек №$position без title — пропущен"
                return@forEachIndexed
            }
            val url = objectItem.nonBlankString("url")
            if (url == null) {
                warnings += "плейлист «$playlistId», трек «$title» без url — пропущен"
                return@forEachIndexed
            }
            if (!url.isHttpsUrl()) {
                warnings += "плейлист «$playlistId», трек «$title»: url не https — пропущен"
                return@forEachIndexed
            }
            if (!usedUrls.add(url)) {
                warnings += "плейлист «$playlistId», трек «$title»: повторный url — пропущен"
                return@forEachIndexed
            }
            val artist = objectItem.nonBlankString("artist")
            val durationMs = objectItem.durationMs()
            tracks += AuthorTrack(
                title = title,
                artist = artist,
                url = url,
                durationMs = durationMs
            )
        }
        return tracks
    }

    /**
     * Необязательная `duration` в секундах (число). Отрицательные, нулевые,
     * нечисловые и превышающие сутки значения молча трактуются как неизвестные:
     * поле необязательное и не должно ломать плейлист.
     */
    private fun JsonValue.Obj.durationMs(): Long {
        val seconds = (fields["duration"] as? JsonValue.Num)?.value ?: return 0L
        if (!seconds.isFinite() || seconds <= 0.0 || seconds > MAX_TRACK_DURATION_SECONDS) {
            return 0L
        }
        return (seconds * 1000.0).toLong()
    }

    private fun JsonValue.Obj.nonBlankString(field: String): String? =
        (fields[field] as? JsonValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() }

    /** Только https: http, content:// и файловые пути манифестом не принимаются. */
    private fun String.isHttpsUrl(): Boolean =
        startsWith("https://", ignoreCase = true) && length > "https://".length
}
