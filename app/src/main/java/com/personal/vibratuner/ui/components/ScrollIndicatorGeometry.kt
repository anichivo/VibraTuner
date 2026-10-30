package com.personal.vibratuner.ui.components

/**
 * Положение и длина бегунка полосы прокрутки в долях длины дорожки (0..1).
 *
 * @param top верх бегунка от начала дорожки (0 — начало списка).
 * @param length длина бегунка: доля содержимого, видимая в окне.
 */
data class ScrollIndicatorThumb(
    val top: Float,
    val length: Float
)

/**
 * Чистая геометрия тонкой полосы прокрутки: по видимым элементам ленивой
 * сетки считает бегунок без обращения к Compose, поэтому проверяется
 * JVM-тестом (`ScrollIndicatorGeometryTest`).
 *
 * Полоса показывается ТОЛЬКО когда содержимое реально больше окна. Два случая:
 * 1. Не все элементы измерены ([visibleItems] < [totalItems]) — ниже/выше окна
 *    есть элементы, поэтому содержимое точно прокручивается. Доля видимого
 *    считается по числу элементов, позиция — по индексу первого видимого.
 * 2. Все элементы видны, но их общая высота больше окна — сетка ленивая и
 *    измеряет только видимые элементы, поэтому высота содержимого считается по
 *    нижнему краю последнего видимого элемента плюс уже прокрученное
 *    расстояние. Переполнение меньше [minOverflowPx] полосой не подсвечивается:
 *    иначе короткий список с чуть обрезанным последним рядом показывал бы
 *    почти неподвижную полосу.
 *
 * @param totalItems всего элементов в сетке.
 * @param visibleItems сколько элементов сейчас видно (включая частично видимые).
 * @param firstVisibleIndex индекс первого видимого элемента.
 * @param firstVisibleOffset смещение первого видимого элемента по оси прокрутки
 *   в px (обычно отрицательное — элемент прокручен вверх за край окна).
 * @param firstVisibleItemHeight высота первого видимого элемента в px; 0 —
 *   высота неизвестна, учитывается только индекс.
 * @param viewportHeight высота окна сетки в px.
 * @param contentEnd нижний край самого нижнего видимого элемента в px
 *   (координаты от начала окна).
 * @param minLengthFraction минимальная длина бегунка как доля дорожки
 *   (например, `scrollIndicatorMinLength / высота окна`).
 * @param minOverflowPx минимальное переполнение, при котором полоса появляется,
 *   когда все элементы уже измерены.
 */
fun scrollIndicatorThumb(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleIndex: Int,
    firstVisibleOffset: Float,
    firstVisibleItemHeight: Int,
    viewportHeight: Float,
    contentEnd: Float,
    minLengthFraction: Float,
    minOverflowPx: Float
): ScrollIndicatorThumb? {
    if (totalItems <= 0 || visibleItems <= 0 || viewportHeight <= 0f) return null

    if (visibleItems < totalItems) {
        // Часть элементов за пределами окна: содержимое больше окна.
        val length = (visibleItems.toFloat() / totalItems)
            .coerceAtLeast(minLengthFraction)
            .coerceAtMost(1f)
        val span = totalItems - visibleItems
        val intra = if (firstVisibleItemHeight > 0) {
            (-firstVisibleOffset / firstVisibleItemHeight).coerceIn(0f, 1f)
        } else {
            0f
        }
        val progress = ((firstVisibleIndex + intra) / span).coerceIn(0f, 1f)
        return ScrollIndicatorThumb(top = progress * (1f - length), length = length)
    }

    // Все элементы измерены. Высота содержимого = нижний край последнего
    // видимого элемента + уже прокрученное расстояние.
    val scrolled = (-firstVisibleOffset).coerceAtLeast(0f)
    val contentHeight = contentEnd + scrolled
    val overflow = contentHeight - viewportHeight
    if (contentHeight <= 0f || overflow < minOverflowPx) return null

    val length = (viewportHeight / contentHeight)
        .coerceAtLeast(minLengthFraction)
        .coerceAtMost(1f)
    val progress = (scrolled / overflow).coerceIn(0f, 1f)
    return ScrollIndicatorThumb(top = progress * (1f - length), length = length)
}
