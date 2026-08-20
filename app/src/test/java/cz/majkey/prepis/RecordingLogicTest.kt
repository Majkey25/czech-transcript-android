package cz.majkey.prepis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecordingLogicTest {
    @Test
    fun `M4A filter ignores case and rejects other files`() {
        assertTrue(isM4a("Hovor.m4a"))
        assertTrue(isM4a("HOVOR.M4A"))
        assertFalse(isM4a("Hovor.mp3"))
        assertFalse(isM4a("m4a"))
    }

    @Test
    fun `recording key changes when source metadata changes`() {
        val original = recordingKey("content://recordings/1", 1_024, 100)

        assertEquals(original, recordingKey("content://recordings/1", 1_024, 100))
        assertNotEquals(original, recordingKey("content://recordings/1", 2_048, 100))
        assertNotEquals(original, recordingKey("content://recordings/1", 1_024, 101))
    }

    @Test
    fun `transcript formatter creates readable paragraphs`() {
        val result = formatTranscript(
            listOf(
                " První věta.   Druhá věta? ",
                "Třetí věta! Čtvrtá věta.",
            ),
        )

        assertEquals(
            "První věta. Druhá věta? Třetí věta!\n\nČtvrtá věta.",
            result,
        )
    }

    @Test
    fun `transcript formatter keeps blank input blank`() {
        assertEquals("", formatTranscript(listOf(" ", "\n")))
    }
}
