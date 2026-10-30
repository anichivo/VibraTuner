package com.personal.vibratuner.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Иконка плейлиста на карточках «Звуков» (Stage 3).
 *
 * Источник зависит от вида плейлиста ([PlaylistCardIconKind]):
 * - «Любимое» — ВСЕГДА встроенный глиф [FavoritesPlaylistIcon] (сердечко),
 *   каталог на него не влияет;
 * - копия каталога — PNG из кеша Stage 2, иначе нейтральная запасная иконка;
 * - пользовательский плейлист — прежняя иконка очереди.
 *
 * PNG декодируется ТОЛЬКО в фоне (`produceState` + `Dispatchers.IO`), размер
 * уменьшается через `inJustDecodeBounds` + `inSampleSize` до ~128 px, результат
 * запоминается по пути файла. Ошибка декодирования НИКОГДА не роняет UI: просто
 * показывается запасная иконка. Сервис и координатор воспроизведения этот
 * компонент не используют.
 */
enum class PlaylistCardIconKind {
    /** Системный плейлист «Любимое»: только встроенное сердечко. */
    FAVORITES,

    /** Добавленная копия каталога «От автора». */
    CATALOG,

    /** Пользовательский плейлист: прежняя иконка, каталог не участвует. */
    USER
}

/**
 * ♥ «Любимого» — ЕДИНСТВЕННОЕ место глифа. Чтобы заменить сердечко, достаточно
 * поменять эту константу: оба места отрисовки берут иконку отсюда, а каталог
 * на неё НИКОГДА не влияет.
 */
val FavoritesPlaylistIcon: ImageVector = Icons.Default.Favorite

/**
 * Нейтральная запасная иконка каталога: нет поля `icon`, файл ещё не скачан
 * или PNG не декодировался. Не является ошибкой и не показывает предупреждений.
 */
val CatalogFallbackPlaylistIcon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic

/** Иконка пользовательского плейлиста: прежний вид карточки. */
val UserPlaylistIcon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic

/**
 * Рисует иконку плейлиста; размер задаёт вызывающий (слот карточки).
 * `contentDescription = null` — иконка декоративная, название озвучивается текстом.
 */
@Composable
fun PlaylistCardIcon(
    kind: PlaylistCardIconKind,
    iconFile: File?,
    modifier: Modifier = Modifier
) {
    // Хук вызывается всегда, чтобы смена вида плейлиста (например, после
    // восстановления catalogId) не путала состояние; для не-PNG файла
    // декодирование не запускается.
    val catalogBitmap = rememberCatalogIconBitmap(iconFile)
    val tint = if (kind == PlaylistCardIconKind.FAVORITES) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    when (kind) {
        PlaylistCardIconKind.FAVORITES -> Icon(
            imageVector = FavoritesPlaylistIcon,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        PlaylistCardIconKind.USER -> Icon(
            imageVector = UserPlaylistIcon,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        PlaylistCardIconKind.CATALOG -> if (catalogBitmap != null) {
            Image(
                bitmap = catalogBitmap,
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
                contentScale = ContentScale.Fit,
                modifier = modifier
            )
        } else {
            Icon(
                imageVector = CatalogFallbackPlaylistIcon,
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
    }
}

/** Максимальная сторона декодированной иконки: исходник 128×128 не теряет деталей. */
private const val MAX_ICON_DIMENSION_PX = 128

private const val TAG = "PlaylistCardIcon"

/**
 * Bitmap иконки из кеша: декодирование в фоне, результат запоминается по пути
 * файла. `null` — файла нет, он не PNG или декодирование не удалось; вызывающий
 * покажет запасную иконку.
 */
@Composable
private fun rememberCatalogIconBitmap(file: File?): ImageBitmap? {
    val path = file?.absolutePath
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = if (file == null || path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeCatalogIcon(file) }
        }
    }
    return bitmap
}

/**
 * Декодирование PNG с уменьшением через `inSampleSize` (степень двойки) так,
 * чтобы каждая сторона была не больше [MAX_ICON_DIMENSION_PX]: полноразмерный
 * Bitmap в память не попадает.
 */
private fun decodeCatalogIcon(file: File): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Иконка каталога не декодирована: ${file.name} (не изображение)")
            return null
        }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_ICON_DIMENSION_PX ||
            bounds.outHeight / sampleSize > MAX_ICON_DIMENSION_PX
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
            ?: run {
                Log.w(TAG, "Иконка каталога не декодирована: ${file.name}")
                null
            }
    } catch (e: Exception) {
        Log.w(TAG, "Иконка каталога не декодирована: ${file.name}: ${e.message}")
        null
    }
}
