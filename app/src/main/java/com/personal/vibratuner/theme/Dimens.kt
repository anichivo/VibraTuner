package com.personal.vibratuner.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AppDimens(
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,
    val spacingXxl: Dp = 48.dp,

    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val radiusXl: Dp = 24.dp,
    val radiusPill: Dp = 100.dp,

    val minTouchTarget: Dp = 48.dp,

    // Нижняя навигация с пятью разделами (часть 4: добавлен «Эквалайзер»):
    // подпись обязана помещаться в одну строку без обрезки на узком телефоне.
    // Размер шрифта — NavLabelStyle (Type.kt), масштаб ограничен здесь.
    val navLabelMaxFontScale: Float = 1.3f,

    // Индикатор выбранной вкладки «Музыки»: тонкая полоса со скруглёнными
    // концами (скругление — radiusPill), вписанная в ширину вкладки.
    val tabIndicatorHeight: Dp = 3.dp,

    val iconSizeSm: Dp = 18.dp,
    val iconSizeMd: Dp = 24.dp,
    val iconSizeLg: Dp = 32.dp,
    val iconSizeXl: Dp = 48.dp,

    // Иконка происхождения трека в «Моей коллекции» (Stage 3): маленькая и
    // приглушённая (доля непрозрачности к `onSurfaceVariant`), чтобы служить
    // подсказкой, а не конкурировать с названием трека и его действиями.
    val trackSourceIconSize: Dp = 16.dp,
    val trackSourceIconAlpha: Float = 0.7f,

    val cardBorderWidth: Dp = 1.dp,
    // Акцентная круглая play/pause плавающего плеера: 48 dp — минимальная
    // область нажатия; ряд управления плеера имеет ту же высоту.
    val playButtonSize: Dp = 48.dp,
    val trackIconContainerSize: Dp = 40.dp,
    val emptyStateBadgeSize: Dp = 72.dp,
    val fabListBottomPadding: Dp = 80.dp,
    val wideScreenBreakpoint: Dp = 600.dp,

    // Глобальный мини-плеер (Part 2/3): высота ряда управления, зона жеста
    // полосы прогресса (>= 48.dp — требование доступности), толщина самой линии
    // и точки-бегуна (обычная и увеличенная при нажатии).
    // Ряд управления равен кнопке play/pause (48 dp) — ниже минимальной области
    // нажатия не опускаемся, поэтому плеер стал на 8 dp ниже прежнего.
    val miniPlayerHeight: Dp = 48.dp,
    val miniPlayerSeekTouchHeight: Dp = 48.dp,
    val miniPlayerProgressHeight: Dp = 5.dp,
    val miniPlayerThumbSize: Dp = 8.dp,
    val miniPlayerThumbPressedSize: Dp = 14.dp,
    // Зазор между главной тройкой и КАЖДОЙ боковой кнопкой ряда управления
    // (режим слева, ♥ справа): тройка стоит ровно по центру, боковые —
    // зеркально у краёв.
    val miniPlayerControlsGap: Dp = 16.dp,
    // Вертикальный ритм плавающего плеера (правка по фидбеку): заголовок живёт
    // в компактной строке, сверху — минимальный отступ, снизу под рядом кнопок —
    // заметно больше воздуха. Суммарно карточка стала ниже, а крупная круглая
    // кнопка play/pause больше не прижата к нижнему краю. Полоса seek сохраняет
    // свою зону нажатия 48 dp, поэтому высота уменьшена именно у строки текста.
    val miniPlayerTopPadding: Dp = 2.dp,
    val miniPlayerBottomPadding: Dp = 12.dp,
    val miniPlayerTitleHeight: Dp = 32.dp,

    // Индикатор активного трека (Part 3): три полоски эквалайзера.
    val equalizerBarWidth: Dp = 3.dp,
    val equalizerBarMinHeight: Dp = 6.dp,
    val equalizerBarMaxHeight: Dp = 18.dp,

    // Экран «Эквалайзер» (Stage 1): слот значения регулятора — ФИКСИРОВАННОЙ
    // ширины, иначе подпись («0%» / «100%» / «+500%» / «x0,25») меняет ширину
    // колонки названия и подсказки, и карточка меняет высоту во время
    // протяжки. 72 dp вмещают самое длинное значение («+500%»), масштаб
    // шрифта сверху ограничен, чтобы крупный системный шрифт не обрезал текст.
    val equalizerValueSlotWidth: Dp = 72.dp,
    val equalizerValueMaxFontScale: Float = 1.3f,

    // Слайдер эквалайзера с рисками: тонкая дорожка, короткие риски по
    // круглым значениям и приглушённый цвет рисок. Высота области рисок
    // больше дорожки — риски выступают над ней и не утолщают полосу.
    val sliderTrackHeight: Dp = 4.dp,
    val sliderTickAreaHeight: Dp = 10.dp,
    val sliderTickWidth: Dp = 2.dp,
    val sliderTickHeight: Dp = 8.dp,
    val sliderTickAlpha: Float = 0.55f,

    // Встроенная справка (этап 4): визуальный размер иконки «i» внутри
    // области нажатия minTouchTarget, максимальная ширина диалога и доля
    // высоты экрана для прокручиваемого текста (~60%).
    val helpIconSize: Dp = 20.dp,
    val helpDialogMaxWidth: Dp = 560.dp,
    val helpDialogBodyMaxHeightFraction: Float = 0.6f,

    // Сетка плейлистов «Моей медиатеки» (Stage 1): карточки — визуальная
    // доминанта экрана, две колонки на всю ширину. Stage 3: из карточки убрано
    // лишнее вертикальное «воздушное» пространство (внутренние отступы и зазор
    // над названием), поэтому она стала заметно ниже. Контент, размеры шрифтов
    // и область нажатия всей карточки не изменились.
    val playlistGridCardMinHeight: Dp = 144.dp,
    // Вертикальные внутренние поля карточки плейлиста — только они меньше
    // горизонтальных (spacingMd): текст прижимается к краям без «воздуха».
    val playlistCardVerticalPadding: Dp = 8.dp,
    // Тонкая полоса прогресса скачивания добавленного плейлиста в карточке
    // сетки: обновляется в реальном времени, высота — только из Dimens.
    val playlistDownloadProgressHeight: Dp = 4.dp,
    // Область прогресса в карточке сетки зарезервирована ВСЕГДА (полоса +
    // проценты), поэтому высота карточки не меняется при старте и окончании
    // скачивания. Складывается из полосы (4 dp) + зазора (4 dp) + строки
    // bodySmall (16 dp); при простое область пустая.
    val playlistDownloadProgressAreaHeight: Dp = 24.dp,

    // Нижний лист выбора плейлиста (часть 3): максимальная высота списка
    // плейлистов; сам лист остаётся обёрткой по содержимому (heightIn, не height).
    val playlistPickerMaxHeight: Dp = 320.dp,

    // Поля «Прерывистой музыки» в «Настройках» (v1.1): подпись стоит НАД
    // рамкой, поле занимает всю ширину на телефоне, но не растягивается на
    // планшете/широком экране на всю карточку.
    val settingsFieldMaxWidth: Dp = 320.dp,

    // Плавающий плеер (часть 4): карточка над нижней навигацией — боковые поля,
    // зазор до NavigationBar, скругление и тень. Все значения — только отсюда.
    val floatingPlayerSideMargin: Dp = 16.dp,
    val floatingPlayerNavBarGap: Dp = 8.dp,
    val floatingPlayerCornerRadius: Dp = 20.dp,
    val floatingPlayerElevation: Dp = 4.dp,

    // Колёса выбора (Part 2): переиспользуемое колесо времени и длительности.
    // Видимых строк — нечётное число (5), высота строки фиксирована, поэтому
    // колесо занимает ровно 5 * 48 = 240 dp. Масштаб шрифта внутри колеса
    // ограничен — при очень крупном системном шрифте крупная центральная строка
    // не обрезается. Соседние строки приглушаются прозрачностью, выбранная
    // строка лежит на лёгкой акцентной подложке; все значения — из темы.
    val wheelItemHeight: Dp = 48.dp,
    val wheelPickerVisibleItems: Int = 5,
    val wheelPickerMaxFontScale: Float = 1.5f,
    val wheelPickerNeighborAlpha: Float = 0.45f,
    val wheelPickerBandAlpha: Float = 0.16f,
    val wheelPickerDisabledAlpha: Float = 0.38f,

    // Дни недели в редакторе будильника (правка по фидбеку): ровно семь
    // компактных переключателей в ОДНОМ ряду. Ширину каждому даёт weight(1f),
    // высота держится около 40 dp (палец попадает без промаха), внутренние
    // поля минимальны, чтобы двухбуквенная подпись не обрезалась даже на узком
    // телефоне. Масштаб шрифта подписи ограничен сверху, иначе при очень
    // крупном системном шрифте текст перестал бы помещаться в узкую ячейку.
    val weekdayToggleHeight: Dp = 40.dp,
    val weekdayToggleSpacing: Dp = 4.dp,
    val weekdayToggleHorizontalPadding: Dp = 2.dp,
    val weekdayLabelMaxFontScale: Float = 1.3f,

    // Описание и фон экрана плейлиста (часть 4): текст без карточки начинается
    // с отступом сверху; градиент идёт от лёгкого акцента к фону и заканчивается
    // долей высоты экрана (ниже — сплошной background).
    val playlistDescriptionTopPadding: Dp = 20.dp,
    // Отступ снизу отделяет конец описания от напоминания/начала списка треков
    // (к нему добавляется вертикальный интервал `LazyColumn`).
    val playlistDescriptionBottomPadding: Dp = 12.dp,
    // Межстрочный интервал абзацев меньше прежней пустой строки (22 sp): абзацы
    // остаются различимыми, но разрыв между ними стал меньше (правка по фидбеку).
    val playlistDescriptionParagraphSpacing: Dp = 12.dp,
    // Дополнительный пустой промежуток под описанием: конец последней строки
    // заметнее отделён от начала списка треков (прибавляется к нижнему отступу).
    val playlistDescriptionListGap: Dp = 8.dp,
    val playlistGradientTopAlpha: Float = 0.14f,
    val playlistGradientEndFraction: Float = 0.25f,
    // Баннер добавления плейлиста каталога (Stage 3): после успешного добавления
    // он плавно скрывается (затухание + уменьшение высоты) одной длительностью.
    val addBannerAnimationDurationMs: Int = 220,

    // ОБЩИЙ выбор звука/трека (Part 1): модальное окно с прокручиваемой сеткой
    // карточек, закреплённой нижней панелью и одним предпросмотром. Две колонки
    // на телефоне, одинаковая минимальная высота карточки (название — до двух
    // строк), выбранная карточка — рамкой 2 dp, смена состояния — за 150 мс.
    // Появление карточки окна — единственная анимация (окно не анимирует себя).
    val soundPickerColumns: Int = 2,
    val soundPickerMaxWidth: Dp = 480.dp,
    // Part 3: предел высоты сетки увеличен (320 -> 440 dp), поэтому в окне
    // видно ТРИ ряда карточек вместо двух. Сам предел задаёт только число
    // полных рядов; фактическая высота сетки дополнительно ограничивается
    // свободным местом окна (см. `SoundPickerDialog`), поэтому маленький экран
    // и горизонтальная ориентация по-прежнему помещаются.
    val soundPickerGridMaxHeight: Dp = 440.dp,
    // Частично видимый СЛЕДУЮЩИЙ ряд под последним полным: подсказка, что
    // список продолжается (плюс полоса прокрутки справа). Добавляется, только
    // если элементов больше, чем помещается.
    val soundPickerNextRowPeek: Dp = 40.dp,
    val soundPickerCardMinHeight: Dp = 120.dp,
    val soundPickerSelectedBorderWidth: Dp = 2.dp,
    val soundPickerSelectionAnimationMs: Int = 150,
    val soundPickerAppearDurationMs: Int = 150,
    val soundPickerAppearInitialScale: Float = 0.94f,

    // Тонкая полоса прокрутки списков окна выбора (Part 3): ширина, минимальная
    // длина бегунка, отступ от правого края, прозрачность в покое и при
    // прокрутке (цвет — `onSurface` темы) и длительность её анимации.
    // В покое полоса НИКОГДА не исчезает полностью: список остаётся
    // прокручиваемым, и подсказка должна быть видна.
    val scrollIndicatorWidth: Dp = 3.dp,
    val scrollIndicatorMinLength: Dp = 24.dp,
    // Полоса не появляется ради пары пикселей: у короткого списка последний
    // ряд иногда обрезан самим расчётом высоты сетки (карточка выше
    // минимальной), и почти неподвижный бегунок только мешал бы.
    val scrollIndicatorMinOverflow: Dp = 24.dp,
    val scrollIndicatorEndInset: Dp = 2.dp,
    val scrollIndicatorIdleAlpha: Float = 0.22f,
    val scrollIndicatorActiveAlpha: Float = 0.5f,
    val scrollIndicatorAlphaAnimationMs: Int = 220
)

val LocalAppDimens = staticCompositionLocalOf { AppDimens() }
