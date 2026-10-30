package com.personal.vibratuner.ui.playlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.personal.vibratuner.R
import com.personal.vibratuner.data.remote.AuthorManifestPlaylist
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppOutlinedButton
import com.personal.vibratuner.ui.components.PlaylistCardIcon
import com.personal.vibratuner.ui.components.PlaylistCardIconKind
import java.io.File

/**
 * Блок «От автора» вкладки «Каталог» (этап 4): сетевые плейлисты только для
 * чтения. Карточка открывает существующий экран плейлиста в режиме readOnly
 * (маршрут задаёт `MainScreenLayout`), кнопка добавляет копию в библиотеку —
 * ровно один раз: признак «Добавлено» приходит из БД (`playlists.catalogId`).
 *
 * Stage 3: на карточке рисуется PNG-иконка каталога из кеша
 * (`PlaylistIconCache.files`), а без скачанного файла — нейтральная запасная.
 */
@Composable
fun AuthorPlaylistsSection(
    state: AuthorPlaylistsUiState,
    addedPlaylistIds: Set<String>,
    iconFiles: Map<String, File>,
    onRefresh: () -> Unit,
    onOpenPlaylist: (AuthorManifestPlaylist) -> Unit,
    onAddPlaylist: (AuthorManifestPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    val loadingDescription = stringResource(R.string.author_loading)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.author_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.size(dimens.minTouchTarget)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.author_refresh_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(dimens.iconSizeMd)
                )
            }
        }

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        if (state.isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(dimens.iconSizeLg)
                        .semantics { contentDescription = loadingDescription }
                )
            }
            Spacer(modifier = Modifier.height(dimens.spacingSm))
        }

        if (state.loadFailed && !state.isLoading) {
            // Part 3: ЕДИНЫЙ текст ошибки обновления/скачивания (технические
            // детали — только в логе). Отдельное описание убрано: оно повторяло
            // ту же мысль вторым сообщением.
            Text(
                text = stringResource(R.string.error_connection_common),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(dimens.spacingSm))
            AppOutlinedButton(
                text = stringResource(R.string.author_retry_action),
                onClick = onRefresh,
                icon = Icons.Default.Refresh,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (state.hasLoaded) {
            if (state.cacheHint) {
                // Обновление не удалось, показаны сохранённые данные.
                Text(
                    text = stringResource(R.string.error_connection_common),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(dimens.spacingSm))
            }

            if (state.playlists.isEmpty()) {
                Text(
                    text = stringResource(R.string.author_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = stringResource(R.string.author_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.playlists.forEach { playlist ->
                    AuthorPlaylistCard(
                        playlist = playlist,
                        isAdded = playlist.id in addedPlaylistIds,
                        iconFile = playlist.icon?.let(iconFiles::get),
                        onOpen = { onOpenPlaylist(playlist) },
                        onAdd = { onAddPlaylist(playlist) }
                    )
                    Spacer(modifier = Modifier.height(dimens.spacingSm))
                }
            }
        }

        Spacer(modifier = Modifier.height(dimens.spacingMd))
    }
}

@Composable
private fun AuthorPlaylistCard(
    playlist: AuthorManifestPlaylist,
    isAdded: Boolean,
    iconFile: File?,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spacingMd)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(dimens.trackIconContainerSize),
                    contentAlignment = Alignment.Center
                ) {
                    // Stage 3: PNG из кеша или нейтральная запасная иконка;
                    // контейнер того же размера, поэтому раскладка не меняется.
                    PlaylistCardIcon(
                        kind = PlaylistCardIconKind.CATALOG,
                        iconFile = iconFile,
                        modifier = Modifier.size(dimens.iconSizeMd)
                    )
                }

                Spacer(modifier = Modifier.width(dimens.spacingSm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.playlist_track_count,
                            playlist.tracks.size,
                            playlist.tracks.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacingSm))

            if (isAdded) {
                // Признак из БД: добавлять нечего, кнопка убрана. Чип ненажимаемый.
                AddedChip()
            } else {
                AppOutlinedButton(
                    text = stringResource(R.string.catalog_add_action),
                    onClick = onAdd,
                    icon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Ненажимаемый чип «Добавлено»: Material 3 [AssistChip] выключен, поэтому не
 * принимает нажатия и не имеет действия. Цвета — только из темы.
 */
@Composable
private fun AddedChip(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = modifier,
        label = {
            Text(
                text = stringResource(R.string.catalog_added_chip),
                style = MaterialTheme.typography.labelLarge
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(dimens.iconSizeSm)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
