package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Геометрия тонкой полосы прокрутки окна выбора звука: полоса видна ТОЛЬКО
 * когда содержимое больше окна, бегунок не выходит за дорожку и не становится
 * короче минимума.
 */
class ScrollIndicatorGeometryTest {

    private fun thumb(
        total: Int,
        visible: Int,
        firstIndex: Int = 0,
        offset: Float = 0f,
        itemHeight: Int = 100,
        viewport: Float = 300f,
        contentEnd: Float = 300f,
        minLength: Float = 0.05f,
        minOverflow: Float = 24f
    ) = scrollIndicatorThumb(
        totalItems = total,
        visibleItems = visible,
        firstVisibleIndex = firstIndex,
        firstVisibleOffset = offset,
        firstVisibleItemHeight = itemHeight,
        viewportHeight = viewport,
        contentEnd = contentEnd,
        minLengthFraction = minLength,
        minOverflowPx = minOverflow
    )

    @Test
    fun shortListHasNoIndicator() {
        assertNull("пустой список — полосы нет", thumb(total = 0, visible = 0))
        assertNull("всё помещается — полосы нет", thumb(total = 4, visible = 4))
        assertNull("элементов меньше, чем видно", thumb(total = 3, visible = 5))
        assertNull("нет окна — полосы нет", thumb(total = 10, visible = 4, viewport = 0f))
    }

    @Test
    fun tinyClipOfLastRowHasNoIndicator() {
        // Короткий список, последний ряд обрезан на 8 px (< minOverflow):
        // почти неподвижный бегунок не показываем.
        assertNull(thumb(total = 4, visible = 4, contentEnd = 308f, minOverflow = 24f))
    }

    @Test
    fun countPathAtTopStartsAtZero() {
        val thumb = thumb(total = 12, visible = 4)!!
        assertEquals(0f, thumb.top, 0.0001f)
        assertEquals(1f / 3f, thumb.length, 0.0001f)
    }

    @Test
    fun countPathAtEndStopsAtTrackEnd() {
        // Последний экран: первый видимый элемент — 8 из 12, видно 4.
        val thumb = thumb(total = 12, visible = 4, firstIndex = 8)!!
        assertEquals(1f - 1f / 3f, thumb.top, 0.0001f)
    }

    @Test
    fun countPathPartialItemOffsetMovesThumbSmoothly() {
        val start = thumb(total = 12, visible = 4, firstIndex = 4)!!
        // Первый видимый элемент прокручен ровно наполовину.
        val half = thumb(total = 12, visible = 4, firstIndex = 4, offset = -50f)!!
        assertEquals(4f / 8f, start.top / (1f - start.length), 0.0001f)
        assertEquals(4.5f / 8f, half.top / (1f - half.length), 0.0001f)
    }

    @Test
    fun allItemsVisibleButOverflowingUsesPixels() {
        // 8 элементов (4 ряда) видны частично, но окно ниже содержимого:
        // высота содержимого = нижний край + прокрученное расстояние.
        val thumb = thumb(total = 8, visible = 8, viewport = 416f, contentEnd = 520f)!!
        assertEquals(416f / 520f, thumb.length, 0.0001f)
        assertEquals(0f, thumb.top, 0.0001f)
    }

    @Test
    fun allItemsVisibleScrolledMovesThumb() {
        val thumb = thumb(
            total = 8,
            visible = 8,
            offset = -52f,
            viewport = 416f,
            contentEnd = 468f
        )!!
        // Прокручено 52 из 104 возможных — бегунок ровно посередине.
        val length = 416f / 520f
        assertEquals(0.5f * (1f - length), thumb.top, 0.0001f)
    }

    @Test
    fun thumbNeverShorterThanMinimum() {
        val thumb = thumb(total = 1000, visible = 2, minLength = 0.1f)!!
        assertEquals(0.1f, thumb.length, 0.0001f)
        assertEquals(0f, thumb.top, 0.0001f)
    }

    @Test
    fun hugeMinimumLengthDegradesToFullTrack() {
        val thumb = thumb(total = 1000, visible = 2, firstIndex = 500, minLength = 2f)!!
        assertEquals(1f, thumb.length, 0.0001f)
        assertEquals(0f, thumb.top, 0.0001f)
    }

    @Test
    fun invalidInputsDoNotThrow() {
        // Нулевая высота элемента и отрицательное смещение — не падаем.
        val thumb = thumb(total = 10, visible = 3, firstIndex = 7, itemHeight = 0)!!
        assertEquals(7f / 7f, thumb.top / (1f - thumb.length), 0.0001f)
        // Положительное смещение (первый элемент выше начала окна) зажимается.
        val clamped = thumb(total = 10, visible = 3, firstIndex = 0, offset = 40f)!!
        assertEquals(0f, clamped.top, 0.0001f)
        // Смещение больше высоты элемента зажимается долей 0..1 и не выводит
        // бегунок за дорожку.
        val overScrolled = thumb(
            total = 10,
            visible = 3,
            firstIndex = 0,
            offset = -500f,
            itemHeight = 100
        )!!
        assertEquals((1f / 7f) * (1f - overScrolled.length), overScrolled.top, 0.0001f)
    }
}
