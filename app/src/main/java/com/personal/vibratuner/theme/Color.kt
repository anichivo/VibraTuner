package com.personal.vibratuner.theme

import androidx.compose.ui.graphics.Color

// Тёмная палитра Anthropic/Claude: тёплые нейтрали + коралловый акцент.
// НИКОГДА не использовать зелёный, синий и любые другие оттенки для статусов:
// статусы — только акцент и нейтрали. Все цвета интерфейса берутся ОТСЮДА
// через роли MaterialTheme.colorScheme (см. Theme.kt).

// Поверхности (от тёмной к светлой)
val Background = Color(0xFF141413)     // фон приложения
val CardSurface = Color(0xFF1F1E1D)    // карточки
val RaisedSurface = Color(0xFF262624)  // поля ввода, листы, диалоги, меню
val ActiveSurface = Color(0xFF30302E)  // выбранные/активные элементы, границы, разделители

// Текст
val TextPrimary = Color(0xFFFAF9F5)    // контраст 17.5:1 к фону, 12.6:1 к ActiveSurface
val TextSecondary = Color(0xFFB0AEA5)  // контраст 8.3:1 к фону, 7.4:1 к карточке

// Акцент
val Accent = Color(0xFFD97757)         // 5.9:1 к фону
val AccentPressed = Color(0xFFC6613F)  // нажатое состояние акцентных кнопок
val AccentSoft = Color(0xFFE8B49E)     // приглушённый акцент (роль tertiary)

// Текст и иконки на акценте. Контраст #141413: 5.9:1 на Accent и 4.55:1 на AccentPressed — оба >= 4.5:1.
val OnAccent: Color = Background

// Ошибка (остаётся в тёплой гамме, без зелёного/синего)
val ErrorRed = Color(0xFFBF4D43)
val ErrorContainer = Color(0xFF43201D)
val OnErrorContainer = Color(0xFFF3C6C1)

// Инверсные поверхности Material 3 (снекбар/бейджи на светлом)
val InverseSurface: Color = TextPrimary
val InverseOnSurface: Color = Background

// Затемнение под модальными слоями (тёплый чёрный, не холодный #000000)
val Scrim = Color(0xFF0A0A09)

// Незаполненная часть полос (seek, прогресс загрузки): светлее границ,
// чтобы дорожка читалась на верхних слоях (#262624) — контраст ~2:1.
val ControlTrack = Color(0xFF56554F)

// Прозрачность (не цвет палитры): контейнер переключателя вкладок без фона.
val Transparent = Color.Transparent
