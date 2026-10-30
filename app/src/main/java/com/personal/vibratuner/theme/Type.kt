package com.personal.vibratuner.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Описание плейлиста «От автора»: акцентный текст шапки, крупнее обычного
 * текста списка (`bodyMedium` 13 sp Normal) и чуть крупнее `bodyLarge` (15 sp),
 * но НЕ совпадает с названием трека (`titleMedium` 16 sp Medium) по начертанию.
 *
 * Основа — межстрочный интервал `bodyLarge` (22 sp), размер увеличен до 16 sp,
 * вес SemiBold, БЕЗ курсива (правка по фидбеку). Абзацы разделяются не пустой
 * строкой, а небольшим отступом: `PlaylistDescription` разбивает текст на
 * абзацы и укладывает их в `Column` с `playlistDescriptionParagraphSpacing`.
 * Отдельный стиль нужен потому, что подходящего слота в Material 3 `Typography` нет.
 */
val PlaylistDescriptionStyle = AppTypography.bodyLarge.copy(
    fontSize = 16.sp,
    fontWeight = FontWeight.SemiBold
)

/**
 * Подпись нижней навигации (часть 4 эквалайзера). С пятым разделом
 * («Эквалайзер») прежний `labelSmall` 11 sp с межбуквенным интервалом 0.5 sp
 * перестаёт помещаться в ячейку на узком телефоне: пять подписей в одну строку
 * требуют чуть более узкого шрифта. Масштаб шрифта дополнительно ограничен
 * `AppDimens.navLabelMaxFontScale`, перенос запрещён в разметке бара.
 */
val NavLabelStyle = AppTypography.labelSmall.copy(
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.2.sp
)
