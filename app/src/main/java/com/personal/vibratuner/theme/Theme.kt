package com.personal.vibratuner.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Единственная тёмная схема приложения (палитра Anthropic/Claude, тёплые нейтрали).
// Иерархия поверхностей: background (#141413) -> карточки surfaceContainer (#1F1E1D)
// -> поля ввода/листы/диалоги/меню surfaceContainerHigh (#262624) -> активные
// поверхности surfaceContainerHighest (#30302E). surface == background, поэтому
// верхние слои обязаны явно брать surfaceContainerHigh (см. project-rules §3).
private val AppDarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = ActiveSurface,
    onPrimaryContainer = TextPrimary,
    inversePrimary = AccentPressed,

    secondary = TextSecondary,
    onSecondary = OnAccent,
    secondaryContainer = ActiveSurface,
    onSecondaryContainer = TextPrimary,

    tertiary = AccentSoft,
    onTertiary = OnAccent,
    tertiaryContainer = RaisedSurface,
    onTertiaryContainer = TextPrimary,

    background = Background,
    onBackground = TextPrimary,

    surface = Background,
    onSurface = TextPrimary,
    // Без подмешивания акцента к поверхностям: цвета остаются ровно палитровыми.
    surfaceTint = Transparent,
    surfaceVariant = ActiveSurface,
    onSurfaceVariant = TextSecondary,

    surfaceDim = Background,
    surfaceBright = RaisedSurface,
    surfaceContainerLowest = Background,
    surfaceContainerLow = CardSurface,
    surfaceContainer = CardSurface,
    surfaceContainerHigh = RaisedSurface,
    surfaceContainerHighest = ActiveSurface,

    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,

    // Границы и разделители — один тёплый нейтраль.
    outline = ActiveSurface,
    outlineVariant = ActiveSurface,

    error = ErrorRed,
    onError = TextPrimary,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,

    scrim = Scrim
)

@Composable
fun VibraTunerTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Иконки системных панелей всегда светлые для тёмной темы (false = light icons)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    val dimens = AppDimens()

    CompositionLocalProvider(
        LocalAppDimens provides dimens
    ) {
        MaterialTheme(
            colorScheme = AppDarkColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}

object AppTheme {
    val dimens: AppDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalAppDimens.current

    /** Нажатое состояние акцентных кнопок и переключателей (#C6613F). */
    val accentPressed: Color
        @Composable
        @ReadOnlyComposable
        get() = AccentPressed

    /** Незаполненная часть полос seek/прогресса — светлее границ, чтобы дорожка читалась. */
    val controlTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = ControlTrack
}
