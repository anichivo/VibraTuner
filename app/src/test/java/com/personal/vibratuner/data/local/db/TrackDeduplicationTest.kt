package com.personal.vibratuner.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDeduplicationTest {

    @Test
    fun keepsEarliestRowPerNormalizedUriAndRemovesTheRest() {
        val rows = listOf(
            TrackDedupeRow(id = 1, uri = "content://audio/a", dateAdded = 100L),
            TrackDedupeRow(id = 2, uri = "content://audio/a", dateAdded = 50L),
            TrackDedupeRow(id = 3, uri = "content://audio/b", dateAdded = 100L),
            TrackDedupeRow(id = 4, uri = "content://audio/a", dateAdded = 50L),
            TrackDedupeRow(id = 5, uri = "content://audio/b", dateAdded = 90L)
        )

        val duplicateIds = TrackDeduplication.selectDuplicateIds(rows)

        // Остаются id=2 (a, самый ранний dateAdded, при равенстве меньший id) и id=5 (b, dateAdded=90)
        assertEquals(listOf(1L, 3L, 4L), duplicateIds)
        val keptRows = rows.filter { it.id !in duplicateIds }
        assertEquals(listOf(2L, 5L), keptRows.map { it.id })
        // Порядок оставшихся строк не меняется, URI уникальны
        assertEquals(keptRows.size, keptRows.map { it.uri }.distinct().size)
    }

    @Test
    fun equalDateAddedKeepsLowerId() {
        val rows = listOf(
            TrackDedupeRow(id = 9, uri = "content://audio/a", dateAdded = 10L),
            TrackDedupeRow(id = 7, uri = "content://audio/a", dateAdded = 10L)
        )

        assertEquals(listOf(9L), TrackDeduplication.selectDuplicateIds(rows))
    }

    @Test
    fun noDuplicatesMeansNothingToDelete() {
        val rows = listOf(
            TrackDedupeRow(id = 1, uri = "content://audio/a", dateAdded = 10L),
            TrackDedupeRow(id = 2, uri = "content://audio/b", dateAdded = 20L)
        )

        assertTrue(TrackDeduplication.selectDuplicateIds(rows).isEmpty())
    }

    @Test
    fun emptyTableIsSafe() {
        assertTrue(TrackDeduplication.selectDuplicateIds(emptyList()).isEmpty())
    }
}
