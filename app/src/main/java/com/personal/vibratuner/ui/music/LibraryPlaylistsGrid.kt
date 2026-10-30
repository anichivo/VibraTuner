package com.personal.vibratuner.ui.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.personal.vibratuner.R
import com.personal.vibratuner.data.download.PlaylistDownloadState
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.PlaylistCardIcon
import com.personal.vibratuner.ui.components.PlaylistCardIconKind
import java.io.File

/**
 * Карточка плейлиста в сетке «Моей медиатеки» (Stage 1): две колонки, карточка —
 * визуальная доминанта экрана. Сетку собирает `MusicScreen`: плейлисты раскладываются
 * по рядам из двух через `favoritesFirst(playlists).chunked(2)`, каждая карточка получает
 * `Modifier.weight(1f)` (равная ширина), а нечётный остаток закрывается пустым слотом.
 *
 * «Любимое» — ВСЕГДА первая карточка (см. [favoritesFirst]), его подпись берётся из
 * strings.xml по `isSystem`; дальше — пользовательские плейлисты и копии каталога.
 * Карточки «Новый плейлист» на главной нет: создание доступно из меню «⋮» трека ->
 * «Добавить в плейлист…» -> «+ Новый плейлист» (PlaylistPickerHost). Тап по карточке
 * открывает существующий экран плейлиста; своего списка и своей навигации карточка
 * не создаёт.
 *
 * Долгое нажатие на карточку открывает меню с единственным пунктом «Удалить»;
 * у «Любимого» меню НЕТ. Подтверждение и само удаление — на экране «Музыка»
 * ([com.personal.vibratuner.ui.components.AppConfirmDialog]).
 *
 * Маленький индикатор в углу карточки показывает состояние скачивания
 * ([PlaylistDownloadState]) добавленного плейлиста: идёт загрузка / скачано /
 * ошибка. Значок ошибки нажимаемый — повторяет загрузку (та же карточка, второго
 * экрана загрузок нет).
 *
 * Stage 3: рядом с названием стоит иконка плейлиста в ЗАРЕЗЕРВИРОВАННОМ слоте
 * (он есть у каждой карточки, поэтому загрузка PNG в фоне не сдвигает текст).
 * Вид иконки — [PlaylistCardIconKind]: «Любимое» — встроенное сердечко, копия
 * каталога — PNG из кеша или запасная иконка, пользовательский — прежняя.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistGridCard(
    title: String,
    trackCount: Int,
    isSystem: Boolean,
    isCatalogCopy: Boolean,
    catalogIconFile: File?,
    downloadState: PlaylistDownloadState?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onRetryDownload: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    var menuExpanded by remember { mutableStateOf(false) }
    val iconKind = when {
        // «Любимое» всегда сердечко: каталог и запасные иконки его не подменяют.
        isSystem -> PlaylistCardIconKind.FAVORITES
        isCatalogCopy -> PlaylistCardIconKind.CATALOG
        else -> PlaylistCardIconKind.USER
    }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.playlistGridCardMinHeight)
                // Долгое нажатие -> меню «Удалить». Роль и обработчик тапа прежние.
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onDelete?.let { { menuExpanded = true } }
                ),
            shape = RoundedCornerShape(dimens.radiusMd),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
        ) {
            val state = downloadState
            // Stage 3: вертикальные поля карточки уменьшены (значения — только
            // из AppDimens), поэтому карточки стали компактнее. Горизонтальные
            // поля прежние: название и число треков не прижимаются к краям.
            Column(
                modifier = Modifier.padding(
                    start = dimens.spacingMd,
                    end = dimens.spacingMd,
                    top = dimens.playlistCardVerticalPadding,
                    bottom = dimens.playlistCardVerticalPadding
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Ведущая иконка переехала к заголовку (Stage 3). Высота
                        // строки сохранена, чтобы индикатор загрузки не сдвигал
                        // текст и карточка не меняла вид при старте скачивания.
                        .heightIn(min = dimens.iconSizeMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    // Индикатор загрузки: идёт / скачано / ошибка.
                    // Ошибка нажимаемая: тап повторяет загрузку (состояние живёт
                    // в менеджере, второй экран загрузок не создаётся).
                    if (state != null) {
                        when {
                            state.isFailed && onRetryDownload != null -> IconButton(
                                onClick = onRetryDownload,
                                modifier = Modifier.size(dimens.minTouchTarget)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = stringResource(
                                        R.string.error_connection_common
                                    ),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(dimens.iconSizeMd)
                                )
                            }

                            state.isRunning -> Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(
                                    R.string.playlist_download_in_progress
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(dimens.iconSizeSm)
                            )

                            state.isComplete -> Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = stringResource(
                                    R.string.playlist_download_done
                                ),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(dimens.iconSizeSm)
                            )
                        }
                    }
                }
                // Stage 3: зазор между строкой статуса и названием убран —
                // это была основная «воздушная» полоса внутри карточки.
                // Заголовок с иконкой в ОДНОМ Row (Stage 3). Слот иконки
                // зарезервирован ВСЕГДА: заголовки всех карточек начинаются на
                // одной линии, а PNG, скачанный в фоне, не сдвигает текст.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.size(dimens.iconSizeMd)) {
                        PlaylistCardIcon(
                            kind = iconKind,
                            iconFile = catalogIconFile,
                            modifier = Modifier.size(dimens.iconSizeMd)
                        )
                    }
                    Spacer(modifier = Modifier.width(dimens.spacingXs))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_track_count,
                        trackCount,
                        trackCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Прогресс скачивания в реальном времени: полоса и проценты
                // обновляются по мере записи байтов текущего трека и после
                // каждого завершённого трека (PlaylistDownloadState).
                //
                // Область прогресса зарезервирована ВСЕГДА и имеет фиксированную
                // высоту из AppDimens: при старте и окончании скачивания высота
                // карточки НЕ меняется, а в покое область просто пустая.
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimens.playlistDownloadProgressAreaHeight)
                ) {
                    if (state != null && state.isRunning) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dimens.playlistDownloadProgressHeight)
                                    .clip(RoundedCornerShape(dimens.radiusPill)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = AppTheme.controlTrack
                            )
                            Spacer(modifier = Modifier.height(dimens.spacingXs))
                            Text(
                                text = stringResource(
                                    R.string.playlist_download_progress,
                                    state.progressPercent
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Один пункт меню — «Удалить». Цвета и типографика из темы.
        if (onDelete != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.playlist_delete_action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}
