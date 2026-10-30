package com.personal.vibratuner.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackUriNormalizerTest {

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            "content://audio/track.mp3",
            TrackUriNormalizer.normalize("  content://audio/track.mp3  ")
        )
    }

    @Test
    fun lowercasesSchemeOnly() {
        assertEquals(
            "content://Audio/Track.MP3",
            TrackUriNormalizer.normalize("Content://Audio/Track.MP3")
        )
    }

    @Test
    fun keepsPlainFilePathWithoutScheme() {
        assertEquals("/storage/emulated/0/Music/a.mp3", TrackUriNormalizer.normalize("/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun blankUriBecomesEmpty() {
        assertEquals("", TrackUriNormalizer.normalize("   "))
    }
}
