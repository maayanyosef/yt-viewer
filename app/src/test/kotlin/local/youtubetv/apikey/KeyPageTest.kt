package local.youtubetv.apikey

import java.net.InetAddress
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build ticket 64: the token, the address, the routing and the page. */
class KeyPageTest {

    private val token = "abcd23efgh"

    @Test
    fun `a token is ten letters of the readable alphabet, and a new one each time`() {
        val random = SecureRandom()
        val tokens = List(50) { newKeyPageToken(random) }
        assertTrue(tokens.all { it.length == TOKEN_LENGTH && it.all { c -> c in TOKEN_ALPHABET } })
        assertEquals(50, tokens.toSet().size)
        assertFalse(TOKEN_ALPHABET.any { it in "ilo01" })
    }

    @Test
    fun `only the exact token matches`() {
        assertTrue(tokenMatches(token, token))
        assertFalse(tokenMatches("abcd23efgx", token))
        assertFalse(tokenMatches("abcd23efg", token))
        assertFalse(tokenMatches("", token))
        assertFalse(tokenMatches("", ""))
    }

    @Test
    fun `GET on the token's path shows the page, and a query or a slash changes nothing`() {
        assertEquals(KeyPageAction.ShowPage, routeKeyPageRequest("GET", "/$token", "", token))
        assertEquals(KeyPageAction.ShowPage, routeKeyPageRequest("GET", "/$token/?x=1", "", token))
        assertEquals(KeyPageAction.ShowPage, routeKeyPageRequest("HEAD", "/$token", "", token))
    }

    @Test
    fun `POST with the right token carries the key, decoded and normalized`() {
        val action = routeKeyPageRequest("POST", "/$token", "key=+AIzaSy%2DAbc%0A", token)
        assertEquals(KeyPageAction.Submit("AIzaSy-Abc"), action)
    }

    @Test
    fun `POST without the right token is not found, whatever it carries`() {
        for (target in listOf("/", "/wrongtoken", "/$token/extra", "/${token}x", "$token")) {
            assertEquals(target, KeyPageAction.NotFound, routeKeyPageRequest("POST", target, "key=AIzaSyX", token))
        }
    }

    @Test
    fun `any other method on the right path is refused`() {
        assertEquals(KeyPageAction.MethodNotAllowed, routeKeyPageRequest("PUT", "/$token", "", token))
    }

    @Test
    fun `a POST with no key field submits an empty key, which the check answers without a call`() {
        assertEquals(KeyPageAction.Submit(""), routeKeyPageRequest("POST", "/$token", "other=1", token))
    }

    @Test
    fun `only home network and loopback addresses may connect`() {
        for (local in listOf("192.168.1.10", "10.0.2.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "fe80::1", "fd12::1", "::1")) {
            assertTrue(local, isLocalNetworkAddress(InetAddress.getByName(local)))
        }
        for (public in listOf("8.8.8.8", "142.250.1.1", "2001:4860::8888")) {
            assertFalse(public, isLocalNetworkAddress(InetAddress.getByName(public)))
        }
    }

    @Test
    fun `the printed address is a private IPv4 address, on wifi or ethernet first`() {
        val chosen = chooseLocalAddress(
            listOf(
                InterfaceAddresses("tun0", up = true, addresses = listOf(InetAddress.getByName("10.8.0.2"))),
                InterfaceAddresses("wlan0", up = true, addresses = listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.1.20"))),
            ),
        )
        assertEquals("192.168.1.20", chosen?.hostAddress)
    }

    @Test
    fun `no interface that is up with a private address means no address`() {
        assertNull(
            chooseLocalAddress(
                listOf(
                    InterfaceAddresses("wlan0", up = false, addresses = listOf(InetAddress.getByName("192.168.1.5"))),
                    InterfaceAddresses("rmnet0", up = true, addresses = listOf(InetAddress.getByName("100.70.1.1"))),
                ),
            ),
        )
    }

    @Test
    fun `the page links every console step and posts to its own path`() {
        val html = keyPageHtml(result = null, postPath = "/$token", address = null)
        for (link in listOf(
            "https://console.cloud.google.com/projectcreate",
            "https://console.cloud.google.com/apis/library/youtube.googleapis.com",
            "https://console.cloud.google.com/apis/credentials",
        )) {
            assertTrue(link, html.contains(link))
        }
        assertTrue(html.contains("""action="/$token""""))
        // With no address to share there is no Copy button, so there is no script either.
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `the page shows the answer, and drops the form once a key is saved`() {
        val wrong = keyPageHtml(KeyCheckResult.WRONG_KEY, "/$token", address = null)
        assertTrue(wrong.contains(escapeHtml(keyCheckMessage(KeyCheckResult.WRONG_KEY))))
        assertTrue(wrong.contains("<form"))
        val saved = keyPageHtml(KeyCheckResult.WORKS, "/$token", address = null)
        assertFalse(saved.contains("<form"))
        assertNotEquals(wrong, saved)
    }

    @Test
    fun `text on the page is escaped`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", escapeHtml("<a href=\"x\">&'"))
    }

    @Test
    fun `the app opens on the key screen exactly when no key is saved`() {
        assertEquals(FirstScreen.KEY, firstScreen(null))
        assertEquals(FirstScreen.KEY, firstScreen("  "))
        assertEquals(FirstScreen.HOME, firstScreen("AIzaSyX"))
    }

    @Test
    fun `the launch line never shows the key or its length`() {
        val line = apiKeyStatusLine("AIzaSyVerySecret")
        assertFalse(line.contains("AIza"))
        assertFalse(line.any { it.isDigit() })
    }
}
