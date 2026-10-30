package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.coordinator.TrackQueueSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Этап 3: выбор следующего трека в режиме SHUFFLE. Чистая функция, поэтому
 * покрывает и «без немедленного повтора», и равномерность выбора.
 */
class TrackQueueSelectorTest {

    @Test
    fun emptySource_returnsNull() {
        assertNull(TrackQueueSelector.randomIndex(size = 0, currentIndex = 0))
        assertNull(TrackQueueSelector.randomIndex(size = -3, currentIndex = 0))
    }

    @Test
    fun singleTrack_alwaysReturnsZero() {
        repeat(20) { seed ->
            assertEquals(
                0,
                TrackQueueSelector.randomIndex(
                    size = 1,
                    currentIndex = 0,
                    random = Random(seed)
                )
            )
        }
    }

    @Test
    fun neverReturnsCurrentTrack_whenMoreThanOneExists() {
        val size = 5
        for (currentIndex in 0 until size) {
            repeat(200) { seed ->
                val picked = TrackQueueSelector.randomIndex(
                    size = size,
                    currentIndex = currentIndex,
                    random = Random(seed)
                )
                assertEquals(true, picked in 0 until size)
                assertNotEquals(currentIndex, picked)
            }
        }
    }

    @Test
    fun currentIndexOutsideSource_returnsAnyValidIndex() {
        repeat(100) { seed ->
            val picked = TrackQueueSelector.randomIndex(
                size = 4,
                currentIndex = -1,
                random = Random(seed)
            )
            assertEquals(true, picked in 0 until 4)
        }
    }

    @Test
    fun allOtherTracksAreReachable() {
        val size = 4
        val picked = (0 until 500).mapNotNull { seed ->
            TrackQueueSelector.randomIndex(
                size = size,
                currentIndex = 1,
                random = Random(seed)
            )
        }.toSet()

        assertTrue(picked.containsAll(listOf(0, 2, 3)))
        assertTrue(!picked.contains(1))
    }
}
