package local.youtubetv.apikey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build ticket 68: the Key screen does not limit the Key page to a phone.
 *
 * The Key page itself says a computer is easier for its steps, so the Key screen must not say
 * that only a phone can open it.
 */
class ApiKeyTextTest {

    /** Every sentence the Key screen can show about where the Key page opens, or its answers. */
    private val keyScreenSentences =
        listOf(
            KEY_SCREEN_WHY,
            KEY_SCREEN_REPLACE_WHY,
            KEY_SCREEN_NO_NETWORK,
            KEY_SCREEN_ADDRESS_INTRO,
        ) + KeyCheckResult.entries.map(::keyCheckMessage)

    @Test
    fun `the address line names a computer and a phone`() {
        assertTrue(KEY_SCREEN_ADDRESS_INTRO.contains("computer"))
        assertTrue(KEY_SCREEN_ADDRESS_INTRO.contains("phone"))
        assertEquals(
            "Or type this address into a browser on a computer or phone on the same network:",
            KEY_SCREEN_ADDRESS_INTRO,
        )
    }

    @Test
    fun `no Key screen sentence calls the Key page a phone page`() {
        for (sentence in keyScreenSentences) {
            assertFalse(sentence, sentence.contains("phone page", ignoreCase = true))
            assertFalse(sentence, sentence.contains("on your phone", ignoreCase = true))
        }
    }

    @Test
    fun `scanning stays a phone's job`() {
        assertEquals("Scan the code with your phone.", KEY_SCREEN_SCAN)
    }

    @Test
    fun `the log names the Key page by its glossary name`() {
        assertTrue(keyCheckLogLine(KeyFrom.KEY_PAGE, KeyCheckResult.WORKS).contains("the Key page"))
    }
}
