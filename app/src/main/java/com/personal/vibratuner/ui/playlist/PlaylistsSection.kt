package com.personal.vibratuner.ui.playlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppConfirmDialog
import com.personal.vibratuner.ui.components.AppMenuItem
import com.personal.vibratuner.ui.components.AppOutlinedButton
import com.personal.vibratuner.ui.components.AppOverflowMenuButton
import com.personal.vibratuner.ui.components.EmptyStateView

/**
 * Блок «Мои плейлисты» (этап 2). С части 2 НЕ отображается на экране «Музыка»:
 * локальные плейлисты переехали в сетку `LibraryPlaylistsGrid`, а вкладка
 * «Каталог» содержит только «От автора». Компонент сохранён для возврата
 * переименования/удаления на экран плейлиста (часть 4), НИКОГДА не подключать
 * его вторым списком на «Музыку».
 *
 * «Любимое» всегда первое, без меню; пользовательские плейлисты — с меню
 * «Переименовать»/«Удалить». Тап по строке открывает экран плейлиста.
 */
@Composable
fun PlaylistsSection(
    playlists: List<PlaylistSummary>,
    alarmUsage: Map<Long, Int>,
    favoritesTitle: String,
    onOpenPlaylist: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    val favorites = playlists.firstOrNull {
        it.isSystem || it.id == PlaylistEntity.FAVORITES_PLAYLIST_ID
    }
    val userPlaylists = playlists.filterNot {
        it.isSystem || it.id == PlaylistEntity.FAVORITES_PLAYLIST_ID
    }

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameTargetName by rememberSaveable { mutableStateOf("") }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.playlists_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        if (favorites != null) {
            PlaylistSummaryRow(
                name = favoritesTitle,
                trackCount = favorites.trackCount,
                isSystem = true,
                onClick = { onOpenPlaylist(favorites.id) },
                menuItems = emptyList()
            )
            Spacer(modifier = Modifier.height(dimens.spacingSm))
        }

        AppOutlinedButton(
            text = stringResource(R.string.playlist_create_action),
            onClick = { showCreateDialog = true },
            icon = Icons.Default.Add,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        if (userPlaylists.isEmpty()) {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = stringResource(R.string.playlists_empty_title),
                description = stringResource(R.string.playlists_empty_desc)
            )
        } else {
            userPlaylists.forEach { playlist ->
                PlaylistSummaryRow(
                    name = playlist.name,
                    trackCount = playlist.trackCount,
                    isSystem = false,
                    onClick = { onOpenPlaylist(playlist.id) },
                    menuItems = listOf(
                        AppMenuItem(
                            label = stringResource(R.string.playlist_rename_action),
                            onClick = {
                                renameTargetId = playlist.id
                                renameTargetName = playlist.name
                            }
                        ),
                        AppMenuItem(
                            label = stringResource(R.string.playlist_delete_action),
                            onClick = { deleteTargetId = playlist.id }
                        )
                    )
                )
                Spacer(modifier = Modifier.height(dimens.spacingSm))
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_create_title),
            initialName = "",
            confirmLabel = stringResource(R.string.playlist_create_confirm),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreate(name)
            }
        )
    }

    val renameId = renameTargetId
    if (renameId != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            initialName = renameTargetName,
            confirmLabel = stringResource(R.string.playlist_rename_action),
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                renameTargetId = null
                onRename(renameId, name)
            }
        )
    }

    val deleteTarget = userPlaylists.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        // Будильники с этим источником после удаления будут пропускаться до выбора другого.
        val usedByAlarms = (alarmUsage[deleteTarget.id] ?: 0) > 0
        AppConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            message = if (usedByAlarms) {
                stringResource(R.string.playlist_delete_message_alarms, deleteTarget.name)
            } else {
                stringResource(R.string.playlist_delete_message, deleteTarget.name)
            },
            confirmLabel = stringResource(R.string.playlist_delete_action),
            onConfirm = {
                deleteTargetId = null
                onDelete(deleteTarget.id)
            },
            onDismiss = { deleteTargetId = null }
        )
    }
}

@Composable
private fun PlaylistSummaryRow(
    name: String,
    trackCount: Int,
    isSystem: Boolean,
    onClick: () -> Unit,
    menuItems: List<AppMenuItem>,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(dimens.trackIconContainerSize),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSystem) Icons.Default.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = if (isSystem) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(dimens.iconSizeMd)
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_track_count,
                        trackCount,
                        trackCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (menuItems.isNotEmpty()) {
                Spacer(modifier = Modifier.width(dimens.spacingXs))
                AppOverflowMenuButton(
                    items = menuItems,
                    contentDescription = stringResource(R.string.action_more)
                )
            }
        }
    }
}
