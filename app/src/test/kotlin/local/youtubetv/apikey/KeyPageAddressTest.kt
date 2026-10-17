package local.youtubetv.apikey

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build ticket 66: the Key page prints its own address and offers to send it. */
class KeyPageAddressTest {

    private val token = "abcd23efgh"
    private val address = keyPageUrl("192.168.1.20", KEY_PAGE_PREFERRED_PORT, token)
    private val html = keyPageHtml(result = null, postPath = "/$token", address = address)

    private val computerSentence =
        "A computer is easier than a phone for these steps. You can open this page on either.</p>"

    @Test
    fun `the full address is printed right after the sentence that recommends a computer`() {
        val after = html.substringAfter(computerSentence)
        val beforeSteps = after.substringBefore("<ol>")
        assertTrue(beforeSteps.contains(""">$address</textarea>"""))
        // Nothing but the address block stands between the sentence and the address.
        assertEquals(
            "<p>The address of this page:</p>",
            after.trimStart().lineSequence().first(),
        )
    }

    @Test
    fun `the email link carries the address, encoded, with spaces as percent 20`() {
        val mailto = keyPageMailtoLink(address)
        assertTrue(mailto.startsWith("mailto:?subject="))
        val body = mailto.substringAfter("&body=")
        assertFalse(body.contains(' '))
        assertFalse(body.contains('+'))
        assertTrue(body.contains("http%3A%2F%2F192.168.1.20%3A8642%2F$token"))
        assertEquals(keyPageShareText(address), URLDecoder.decode(body, "UTF-8"))
        assertTrue(html.contains("""href="${escapeHtml(mailto)}""""))
    }

    @Test
    fun `the WhatsApp link is wa_me with the encoded address as its text`() {
        val whatsApp = keyPageWhatsAppLink(address)
        assertTrue(whatsApp.startsWith("https://wa.me/?text="))
        val text = whatsApp.substringAfter("?text=")
        assertFalse(text.contains(' '))
        assertFalse(text.contains('+'))
        assertTrue(text.contains("http%3A%2F%2F192.168.1.20%3A8642%2F$token"))
        assertEquals(keyPageShareText(address), URLDecoder.decode(text, "UTF-8"))
        assertTrue(html.contains("""href="${escapeHtml(whatsApp)}""""))
    }

    @Test
    fun `a plus sign or an ampersand in the text cannot break the link`() {
        assertEquals("a%2Bb%20%26%20c", encodeForLink("a+b & c"))
    }

    @Test
    fun `the network warning is on the page, and says the address stops with the key screen`() {
        assertTrue(html.contains(escapeHtml(KEY_PAGE_NETWORK_WARNING)))
        assertTrue(KEY_PAGE_NETWORK_WARNING.contains("same wifi or home network as the television"))
        assertTrue(KEY_PAGE_NETWORK_WARNING.contains("does not work over mobile data"))
        assertTrue(KEY_PAGE_NETWORK_WARNING.contains("stops working when the key screen on the television closes"))
    }

    @Test
    fun `Copy and Share start hidden, so a page without script shows no dead button`() {
        assertTrue(html.contains("""<button id="copy" type="button" hidden>"""))
        assertTrue(html.contains("""<button id="share" type="button" hidden data-text="""))
        assertTrue(html.contains("""<textarea id="address" class="address" readonly"""))
    }

    @Test
    fun `Copy uses execCommand, and Share is offered only where the browser has it`() {
        assertTrue(KEY_PAGE_SCRIPT.contains("""document.execCommand("copy")"""))
        assertTrue(KEY_PAGE_SCRIPT.contains("""typeof navigator.share === "function""""))
        assertFalse(KEY_PAGE_SCRIPT.contains("clipboard"))
        assertFalse(KEY_PAGE_SCRIPT.contains("getElementById(\"key\")"))
        assertTrue(KEY_PAGE_SCRIPT.contains("navigator.share({ text: share.dataset.text })"))
    }

    @Test
    fun `the script's sentences come from escaped attributes, so a quote in one cannot break it`() {
        assertTrue(html.contains("""data-done="${escapeHtml(KEY_PAGE_COPIED)}""""))
        assertTrue(html.contains("""data-failed="${escapeHtml(KEY_PAGE_COPY_FAILED)}""""))
        assertFalse(KEY_PAGE_SCRIPT.contains(KEY_PAGE_COPIED))
        // Share sends the same sentence as email and WhatsApp, network rule included.
        assertTrue(html.contains("""data-text="${escapeHtml(keyPageShareText(address))}""""))
    }

    @Test
    fun `the policy allows exactly the script on the page, by its hash`() {
        val inPage = html.substringAfter("<script>").substringBefore("</script>")
        assertEquals(KEY_PAGE_SCRIPT, inPage)
        val hash = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(inPage.toByteArray(Charsets.UTF_8)),
        )
        assertTrue(KEY_PAGE_CONTENT_SECURITY_POLICY.contains("script-src 'sha256-$hash';"))
        assertFalse(KEY_PAGE_CONTENT_SECURITY_POLICY.contains("unsafe-eval"))
        assertFalse(KEY_PAGE_CONTENT_SECURITY_POLICY.contains("script-src 'unsafe-inline'"))
        assertEquals(1, Regex("<script").findAll(html).count())
    }

    @Test
    fun `once a key is saved or the screen has closed, the address is no longer offered`() {
        val saved = keyPageHtml(KeyCheckResult.WORKS, "/$token", address = address)
        assertFalse(saved.contains(address))
        assertFalse(saved.contains("<script"))
        val closed = keyPageHtml(KeyCheckResult.SCREEN_CLOSED, "/$token", address = address)
        assertFalse(closed.contains(address))
        assertFalse(closed.contains("<script"))
        val wrong = keyPageHtml(KeyCheckResult.WRONG_KEY, "/$token", address = address)
        assertTrue(wrong.contains(""">$address</textarea>"""))
    }

    @Test
    fun `with no address to print, the page offers nothing to share`() {
        val none = keyPageHtml(result = null, postPath = "/$token", address = null)
        assertFalse(none.contains("wa.me"))
        assertFalse(none.contains("mailto:"))
        assertFalse(none.contains(KEY_PAGE_NETWORK_WARNING))
    }
}
