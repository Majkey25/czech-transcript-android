package cz.majkey.prepis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudLogicTest {
    @Test
    fun `provider IDs accept only the fixed allowlist`() {
        assertEquals(CloudProvider.OPENAI, CloudProvider.fromId("openai"))
        assertEquals(CloudProvider.GEMINI, CloudProvider.fromId("gemini"))
        assertEquals(CloudProvider.XAI, CloudProvider.fromId("xai"))
        assertEquals(CloudProvider.GROQ, CloudProvider.fromId("groq"))
        assertFailsWith<IllegalArgumentException> { CloudProvider.fromId("custom") }
    }

    @Test
    fun `API key validation trims but rejects line breaks`() {
        assertEquals("sk-example123", validateApiKey("  sk-example123  "))
        assertFailsWith<IllegalArgumentException> { validateApiKey("short") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key\nAuthorization: bad") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key\u0000bad") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key-ž") }
    }

    @Test
    fun `multipart filename cannot inject headers`() {
        assertEquals("evil___.m4a", safeUploadName("evil\"\r\n.m4a"))
        assertEquals("na_me-_.m4a", safeUploadName("na\\me-ž.m4a"))
        assertEquals("recording.m4a", safeUploadName(""))
        val longName = safeUploadName("a".repeat(200) + ".m4a")
        assertTrue(longName.length <= 120)
        assertTrue(longName.endsWith(".m4a"))
    }

    @Test
    fun `cloud JSON response returns nonblank text only`() {
        assertEquals("Dobrý den.", parseCloudText("""{"text":"  Dobrý den.  "}"""))
        assertFailsWith<IllegalArgumentException> { parseCloudText("""{"text":" "}""") }
        assertFailsWith<IllegalArgumentException> { parseCloudText("{}") }
    }
}
