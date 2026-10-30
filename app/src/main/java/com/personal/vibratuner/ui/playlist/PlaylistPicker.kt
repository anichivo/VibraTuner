package com.personal.vibratuner.ui.playlist

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.theme.AppTheme
import kotlinx.coroutines.launch

private const val TAG = "PlaylistPicker"

/** Режим нижнего листа: добавить трек или переместить его между плейлистами. */
enum class PlaylistPickerMode { ADD, MOVE }

/**
 * Запрос на открытие нижнего листа. Состояние поднято в экран, поэтому лист
 * ОДИН на экран, а не на строку списка.
 *
 * [fromPlaylistId] обязателен только для [PlaylistPickerMode.MOVE].
 */
@Immutable
data class PlaylistPickerRequest(
    val mode: PlaylistPickerMode,
    val track: PlaylistTrackInput,
    val fromPlaylistId: Long? = null
)

/**
 * Единый хост нижнего листа выбора плейлиста (часть 3).
 *
 * Лист вызывается из меню «⋮» строки трека и работает в двух режимах:
 * добавить и переместить. Список — `observeLibraryPlaylists()` (через
 * [PlaylistsViewModel]); «Любимое» в выборе НЕ участвует (для него ♥),
 * в режиме перемещения текущий плейлист исключается.
 *
 * «+ Новый плейлист» открывает существующий [PlaylistNameDialog] (часть 2),
 * после создания трек сразу добавляется/переносится, лист закрывается.
 * Сообщение показывается только если экран передал свой `SnackbarHostState`.
 *
 * Хост нужно вызывать ОДИН раз на экран; при `request == null` он ничего
 * не рисует, но остаётся в композиции — поэтому корутина записи не отменяется
 * сразу после закрытия листа.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerHost(
    request: PlaylistPickerRequest?,
    snackbarHostState: SnackbarHostState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = viewModel()
) {
    val dimens = AppTheme.dimens
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Ключ — сам запрос: новый выбор трека всегда открывает лист заново.
    var showNameDialog by remember(request) { mutableStateOf(false) }

    val addedFormat = stringResource(R.string.playlist_added_snackbar)
    val duplicateFormat = stringResource(R.string.playlist_duplicate_snackbar)
    val addFailedMessage = stringResource(R.string.playlist_add_failed_snackbar)
    val movedFormat = stringResource(R.string.playlist_moved_snackbar)
    val moveFailedMessage = stringResource(R.string.playlist_move_failed_snackbar)
    val createFailedMessage = stringResource(R.string.playlist_create_failed_snackbar)

    val current = request ?: return
    // Подписка на `observeLibraryPlaylists()` живёт только пока лист открыт.
    val playlists by viewModel.playlistList.collectAsState()
    val isMove = current.mode == PlaylistPickerMode.MOVE
    val fromPlaylistId = current.fromPlaylistId
    // «Любимое» — системный плейлист, для него есть ♥; в переносе источник не показываем.
    val targets = playlists.filter { playlist ->
        !playlist.isSystem && (!isMove || playlist.id != fromPlaylistId)
    }

    if (!showNameDialog) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // contentWindowInsets у ModalBottomSheet по умолчанию — safeDrawing
                    // (system bars + IME), поэтому своих inset-отступов не добавляем.
                    .padding(horizontal = dimens.spacingMd)
                    .padding(bottom = dimens.spacingLg)
            ) {
                Text(
                    text = if (isMove) {
                        stringResource(R.string.playlist_picker_move_title)
                    } else {
                        stringResource(R.string.playlist_picker_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingMd))
                PlaylistPickerRow(
                    title = stringResource(R.string.playlist_picker_new),
                    leadingIcon = Icons.Default.Add,
                    onClick = { showNameDialog = true }
                )
                if (targets.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = dimens.playlistPickerMaxHeight)
                    ) {
                        items(items = targets, key = { it.id }) { playlist ->
                            PlaylistPickerRow(
                                title = playlist.name,
                                trailingText = pluralStringResource(
                                    R.plurals.playlist_track_count,
                                    playlist.trackCount,
                                    playlist.trackCount
                                ),
                                onClick = {
                                    onDismiss()
                                    scope.launch {
                                        // Ошибка БД не должна ронять экран: пишем в лог
                                        // и показываем то же сообщение, что при отказе.
                                        val message = try {
                                            if (isMove) {
                                                val moveFrom = fromPlaylistId ?: return@launch
                                                if (viewModel.moveTrack(
                                                        trackUri = current.track.trackUri,
                                                        fromPlaylistId = moveFrom,
                                                        toPlaylistId = playlist.id
                                                    )
                                                ) {
                                                    movedFormat.format(playlist.name)
                                                } else {
                                                    moveFailedMessage
                                                }
                                            } else {
                                                if (viewModel.addTrackToPlaylist(
                                                        playlistId = playlist.id,
                                                        track = current.track
                                                    )
                                                ) {
                                                    addedFormat.format(playlist.name)
                                                } else {
                                                    duplicateFormat.format(playlist.name)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w(
                                                TAG,
                                                "Не удалось изменить плейлист id=${playlist.id}: ${e.message}"
                                            )
                                            if (isMove) moveFailedMessage else addFailedMessage
                                        }
                                        snackbarHostState?.showSnackbar(
                                            message = message,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_create_title),
            initialName = "",
            confirmLabel = stringResource(R.string.playlist_create_confirm),
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                showNameDialog = false
                onDismiss()
                scope.launch {
                    val createdId = try {
                        if (isMove) {
                            val moveFrom = fromPlaylistId ?: return@launch
                            viewModel.createPlaylistWithMovedTrack(
                                name = name,
                                trackUri = current.track.trackUri,
                                fromPlaylistId = moveFrom
                            )
                        } else {
                            viewModel.createPlaylistWithTrack(name = name, track = current.track)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось создать плейлист «$name»: ${e.message}")
                        -1L
                    }
                    val message = when {
                        createdId <= 0L -> createFailedMessage
                        isMove -> movedFormat.format(name)
                        else -> addedFormat.format(name)
                    }
                    snackbarHostState?.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }
}

/** Трек библиотеки → снимок для `playlist_tracks` (URI, название, исполнитель). */
fun TrackEntity.toPlaylistInput(): PlaylistTrackInput = PlaylistTrackInput(
    trackUri = uri,
    title = title,
    artist = artist.takeIf { it.isNotBlank() }
)

/**
 * Строка плейлиста → снимок для `playlist_tracks`. Для треков «От автора»
 * `trackUri` — это https-адрес: то же представление, что решила часть 1.
 */
fun PlaylistTrackUi.toPlaylistInput(): PlaylistTrackInput = PlaylistTrackInput(
    trackUri = trackUri,
    title = title,
    artist = artist?.takeIf { it.isNotBlank() }
)

/** Строка листа: область нажатия не меньше `minTouchTarget`, цвета — из темы. */
@Composable
private fun PlaylistPickerRow(
    title: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusMd))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = dimens.minTouchTarget)
            .padding(horizontal = dimens.spacingSm, vertical = dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(dimens.iconSizeMd)
            )
            Spacer(modifier = Modifier.width(dimens.spacingSm))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Spacer(modifier = Modifier.width(dimens.spacingSm))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
