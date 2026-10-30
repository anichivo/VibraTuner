package com.personal.vibratuner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R

/**
 * Контекст строки трека: от него зависит набор пунктов ЕДИНОГО меню «⋮».
 *
 * - [Library] — трек в «Моей медиатеке»: добавить в плейлист, удалить трек.
 * - [Catalog] — удалённый трек «От автора» (read-only плейлист): только
 *   добавить в локальный плейлист.
 * - [InPlaylist] — трек внутри локального плейлиста: добавить, переместить,
 *   удалить из плейлиста. «Переместить в…» существует ТОЛЬКО здесь.
 */
sealed interface TrackRowContext {
    data object Library : TrackRowContext
    data object Catalog : TrackRowContext
    data class InPlaylist(val playlistId: Long) : TrackRowContext
}

/** Действие строки трека, не зависящее от текста: подписи берутся из strings.xml. */
enum class TrackRowAction {
    ADD_TO_PLAYLIST,
    MOVE_TO_PLAYLIST,
    DOWNLOAD_TRACK,

    /**
     * Part 3, задача 1: удалить ТОЛЬКО скачанный приложением файл этого трека
     * (https-трек с локальной копией). Сам трек остаётся в плейлисте/библиотеке.
     * Для импортированных файлов пользователя пункт НИКОГДА не показывается.
     */
    DELETE_DOWNLOADED,
    REMOVE_FROM_PLAYLIST,
    DELETE_TRACK
}

private val CATALOG_ACTIONS = listOf(TrackRowAction.ADD_TO_PLAYLIST)

/**
 * Чистая функция состава меню: одна и та же для всех экранов и покрыта
 * JVM-тестом. «Переместить в…» НИКОГДА не появляется вне плейлиста.
 *
 * Stage 2: «Скачать» показывается только для трека, который вообще скачивается
 * (https-адрес каталога) и ещё не лежит на устройстве. Пункт остаётся в меню,
 * пока идёт загрузка и после неё, но недоступен (подписи «Скачивается…» и
 * «Скачано»): меню не меняет состав на глазах и не предлагает дубль файла.
 * В «Моей коллекции» загрузка не предлагается никогда — там импортированные треки.
 *
 * Part 3: у трека, который РЕАЛЬНО скачан приложением (файл на устройстве),
 * появляется «Удалить из памяти». У импортированных файлов пользователя этого
 * пункта нет: приложение НИКОГДА не удаляет их.
 */
fun trackRowActions(
    context: TrackRowContext,
    downloadStatus: TrackDownloadStatus = TrackDownloadStatus.Unavailable
): List<TrackRowAction> = buildList {
    when (context) {
        TrackRowContext.Library -> add(TrackRowAction.ADD_TO_PLAYLIST)
        TrackRowContext.Catalog -> addAll(CATALOG_ACTIONS)
        is TrackRowContext.InPlaylist -> {
            add(TrackRowAction.ADD_TO_PLAYLIST)
            add(TrackRowAction.MOVE_TO_PLAYLIST)
        }
    }
    // «Скачать» относится к каталогу/плейлисту; в «Моей коллекции» его нет.
    if (context != TrackRowContext.Library &&
        downloadStatus != TrackDownloadStatus.Unavailable
    ) {
        add(TrackRowAction.DOWNLOAD_TRACK)
    }
    when (context) {
        TrackRowContext.Library -> add(TrackRowAction.DELETE_TRACK)
        is TrackRowContext.InPlaylist -> add(TrackRowAction.REMOVE_FROM_PLAYLIST)
        TrackRowContext.Catalog -> Unit
    }
    // Part 3, задача 1: «Удалить из памяти» — ТОЛЬКО для реально скачанного
    // приложением файла (TrackDownloadStatus.Downloaded). У импортированных
    // content:// треков файла нет, поэтому пункт не появляется и файлы
    // пользователя никогда не удаляются.
    if (downloadStatus == TrackDownloadStatus.Downloaded) {
        add(TrackRowAction.DELETE_DOWNLOADED)
    }
}

/**
 * ЕДИНОЕ меню строки трека для всех списков («Моя медиатека», плейлист,
 * read-only «От автора»). Кнопка не запускает трек: нажатие потребляет сам
 * `IconButton`. Размер области нажатия и иконки — из `AppDimens`
 * (`AppOverflowMenuButton`), пункты — только из strings.xml.
 */
@Composable
fun TrackRowMenu(
    context: TrackRowContext,
    onAction: (TrackRowAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Stage 2: состояние отдельной загрузки; по нему — подпись и доступность. */
    downloadStatus: TrackDownloadStatus = TrackDownloadStatus.Unavailable
) {
    val items = trackRowActions(context, downloadStatus).map { action ->
        if (action == TrackRowAction.DOWNLOAD_TRACK) {
            AppMenuItem(
                label = stringResource(downloadStatus.menuLabelRes()),
                enabled = downloadStatus == TrackDownloadStatus.Available,
                onClick = { onAction(action) }
            )
        } else {
            AppMenuItem(
                label = stringResource(action.labelRes()),
                onClick = { onAction(action) }
            )
        }
    }
    AppOverflowMenuButton(
        items = items,
        modifier = modifier,
        contentDescription = stringResource(R.string.action_track_menu)
    )
}

private fun TrackDownloadStatus.menuLabelRes(): Int = when (this) {
    is TrackDownloadStatus.Running -> R.string.track_download_in_progress
    TrackDownloadStatus.Downloaded -> R.string.track_downloaded
    else -> R.string.track_download_action
}

private fun TrackRowAction.labelRes(): Int = when (this) {
    TrackRowAction.ADD_TO_PLAYLIST -> R.string.track_menu_add_to_playlist
    TrackRowAction.MOVE_TO_PLAYLIST -> R.string.track_menu_move_to_playlist
    TrackRowAction.DOWNLOAD_TRACK -> R.string.track_download_action
    TrackRowAction.DELETE_DOWNLOADED -> R.string.download_delete_action
    TrackRowAction.REMOVE_FROM_PLAYLIST -> R.string.track_menu_remove_from_playlist
    TrackRowAction.DELETE_TRACK -> R.string.action_delete_track
}
