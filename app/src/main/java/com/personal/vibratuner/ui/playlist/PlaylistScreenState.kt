package com.personal.vibratuner.ui.playlist

import androidx.compose.runtime.Immutable

/**
 * Простое UI-состояние экрана плейлиста: НЕ зависит от Room и не содержит
 * идентификаторов ресурсов. Экран рисуется только по этому состоянию, поэтому
 * этап 4 переиспользует его для read-only плейлистов «От автора».
 */
@Immutable
data class PlaylistScreenState(
    /** Имя из БД; null у системного «Любимое» — подпись берётся из strings.xml. */
    val title: String? = null,
    /**
     * Готовый к показу текст описания из манифеста «От автора»; null — блока нет.
     * У локальных плейлистов описания нет (в Room оно не хранится).
     */
    val description: String? = null,
    /**
     * Системный плейлист «Любимое»: подпись берётся из strings.xml, а строка
     * трека показывает ♥. Меню «⋮» есть и здесь (контекст `InPlaylist`).
     */
    val isSystem: Boolean = false,
    /** Только чтение: редактирование недоступно (плейлисты «От автора», этап 4). */
    val readOnly: Boolean = false,
    /** «От автора»: плейлист ещё не добавлен в библиотеку — показываем баннер. */
    val showAddReminder: Boolean = false,
    val isLoading: Boolean = true,
    /** Плейлист удалён, пока экран был открыт: экран закрывается. */
    val isMissing: Boolean = false,
    val tracks: List<PlaylistTrackUi> = emptyList()
)

/** Строка трека в плейлисте. [trackUri] — стабильный идентификатор (`tracks.uri`). */
@Immutable
data class PlaylistTrackUi(
    val trackUri: String,
    val title: String,
    val artist: String? = null,
    val isFavorite: Boolean = false,
    /**
     * Длительность: библиотека по URI (content:// или скачанный трек),
     * манифест автора либо длительность уже скачанного файла (Stage 3).
     * 0 — неизвестна; итоговая длительность плейлиста тогда не показывается,
     * чтобы не подписывать неполную сумму словом «всего».
     */
    val durationMs: Long = 0L
)
