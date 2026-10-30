package com.personal.vibratuner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.personal.vibratuner.R
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.download.SingleTrackDownloadState
import com.personal.vibratuner.data.download.TrackDownloadManager
import com.personal.vibratuner.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Состояние отдельной загрузки трека для строки списка и единого меню «⋮»
 * (Stage 2). Вычисляется ТОЛЬКО из состояния менеджера и наличия файла:
 * - [Unavailable] — трек не скачивается (content:// с устройства) или меню
 *   вообще не предлагает загрузку;
 * - [Available] — https-трек, файла ещё нет, можно скачать;
 * - [Running] — загрузка идёт, меню показывает недоступное «Скачивается…»;
 * - [Downloaded] — файл уже на устройстве, повторно не предлагается.
 */
sealed interface TrackDownloadStatus {
    data object Unavailable : TrackDownloadStatus
    data object Available : TrackDownloadStatus
    data class Running(
        val progressFraction: Float,
        val progressPercent: Int
    ) : TrackDownloadStatus
    data object Downloaded : TrackDownloadStatus
}

/**
 * Чистая функция статуса строки: учитывает состояние загрузки из менеджера,
 * уже скачанный файл и то, скачивается ли трек вообще. Покрыта JVM-тестом.
 */
fun singleTrackDownloadStatus(
    trackUri: String,
    downloaded: Boolean,
    singleStates: Map<String, SingleTrackDownloadState>,
    canDownload: Boolean
): TrackDownloadStatus {
    val state = singleStates[trackUri]
    return when {
        state?.isRunning == true ->
            TrackDownloadStatus.Running(state.progressFraction, state.progressPercent)

        state?.isDone == true || downloaded -> TrackDownloadStatus.Downloaded
        canDownload && PlaylistDownloadKeys.isDownloadable(trackUri) ->
            TrackDownloadStatus.Available

        else -> TrackDownloadStatus.Unavailable
    }
}

/**
 * Компактный индикатор загрузки в строке трека: те же компоненты и цвета, что
 * у индикатора загрузки плейлиста (тонкая полоса + «Скачивается… N%»).
 * Показывается только пока трек качается; после успеха его сменяет
 * [TrackDownloadedMark].
 */
@Composable
fun TrackDownloadProgress(
    fraction: Float,
    percent: Int,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.playlistDownloadProgressHeight)
                .clip(RoundedCornerShape(dimens.radiusPill)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = AppTheme.controlTrack
        )
        Spacer(modifier = Modifier.height(dimens.spacingXs))
        Text(
            text = stringResource(R.string.playlist_downloading_progress, percent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Ненавязчивая отметка «скачано» в строке трека: маленькая иконка в цвете
 * `onSurfaceVariant`, без второго текста и без изменения высоты строки.
 */
@Composable
fun TrackDownloadedMark(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    Icon(
        imageVector = Icons.Default.DownloadDone,
        contentDescription = stringResource(R.string.track_downloaded),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(dimens.iconSizeSm)
    )
}

/**
 * URL-ы треков, файлы которых уже лежат на устройстве. Пересчитывается при
 * появлении/удалении скачанных файлов ([TrackDownloadManager.fileGeneration]),
 * сама загрузка читает MediaStore на IO — главный поток не блокируется.
 */
@Composable
fun rememberDownloadedTrackUris(
    downloads: TrackDownloadManager,
    urls: List<String>
): Set<String> {
    val fileGeneration by downloads.fileGeneration.collectAsState()
    return produceState(
        initialValue = emptySet<String>(),
        key1 = urls,
        key2 = fileGeneration
    ) {
        value = if (urls.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) { downloads.downloadedUris(urls) }
        }
    }.value
}
