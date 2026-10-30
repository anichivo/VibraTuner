package com.personal.vibratuner.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrosoundScheduleModeTest {

    @Test
    fun exactAlarmsAvailable_selectsExactMode() {
        // Когда разрешение на точные будильники предоставлено
        val mode = PlatformCompat.determineMicrosoundScheduleMode(canScheduleExact = true)
        assertEquals(AlarmScheduleMode.EXACT, mode)
    }

    @Test
    fun exactAlarmsUnavailable_selectsInexactFallbackMode() {
        // Когда точные будильники недоступны (отозваны или ограничены системой)
        // выбирается запасной неточный режим (Inexact / setAndAllowWhileIdle)
        val mode = PlatformCompat.determineMicrosoundScheduleMode(canScheduleExact = false)
        assertEquals(AlarmScheduleMode.INEXACT, mode)
    }
}
