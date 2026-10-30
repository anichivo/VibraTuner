package com.personal.vibratuner.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTrackReferenceTest {

    @Test
    fun deletingTrackClearsOnlyAlarmsThatUseIt() {
        val sql = AlarmTrackReference.CLEAR_BY_TRACK_ID_SQL
        assertTrue(sql.startsWith("UPDATE alarms SET trackId = NULL"))
        assertTrue(sql.contains("WHERE trackId = ?"))
    }

    @Test
    fun clearingLibraryClearsAllTrackReferences() {
        val sql = AlarmTrackReference.CLEAR_ALL_SQL
        assertTrue(sql.startsWith("UPDATE alarms SET trackId = NULL"))
        assertTrue(sql.contains("WHERE trackId IS NOT NULL"))
    }

    @Test
    fun resetReferenceMeansWholeLibrary() {
        // После сброса ссылки будильник снова играет всю библиотеку (trackId == null).
        val alarm = AlarmEntity(id = 3, hour = 6, minute = 30, trackId = 42L)
            .copy(trackId = null)
        assertNull(alarm.trackId)
        assertEquals(42L, AlarmEntity(id = 3, hour = 6, minute = 30, trackId = 42L).trackId)
    }
}
