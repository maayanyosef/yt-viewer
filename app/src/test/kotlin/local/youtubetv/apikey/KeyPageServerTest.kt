package local.youtubetv.apikey

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Build ticket 64: the Key page's server, over a real socket on this machine's loopback.
 *
 * Port 0 is asked for, so a test never collides with anything already listening.
 */
class KeyPageServerTest {

    private val token = "abcd23efgh"
    private val keys = mutableListOf<String>()
    private val lines = mutableListOf<String>()
    private var server: KeyPageServer? = null

    private fun start(answer: KeyCheckResult = KeyCheckResult.WORKS): Int {
        val started = KeyPageServer(
            token = token,
            onKey = { key -> synchronized(keys) { keys += key }; answer },
            log = { synchronized(lines) { lines += it } },
            preferredPort = 0,
        )
        server = started
        return started.start()
    }

    @After
    fun stop() {
        server?.stop()
    }

    private fun request(port: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.use { it.bufferedReader().readText() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test
    fun `the page is served on the token's path`() {
        val port = start()
        val (status, html) = request(port, "GET", "/$token")
        assertEquals(200, status)
        assertTrue(html.contains("Create credentials"))
    }

    @Test
    fun `the page prints the address it is given, read again for every page`() {
        var printed: String? = "http://192.168.1.20:8642/$token"
        val started = KeyPageServer(
            token = token,
            onKey = { KeyCheckResult.WORKS },
            preferredPort = 0,
            pageAddress = { printed },
        )
        server = started
        val port = started.start()
        // Asked for over loopback, and still the address the television prints, not 127.0.0.1.
        assertTrue(request(port, "GET", "/$token").second.contains(""">http://192.168.1.20:8642/$token</textarea>"""))
        printed = "http://10.0.0.7:8642/$token"
        assertTrue(request(port, "GET", "/$token").second.contains(""">http://10.0.0.7:8642/$token</textarea>"""))
    }

    @Test
    fun `a key posted with the right token is handed on, and the answer is on the page`() {
        val port = start(KeyCheckResult.WRONG_KEY)
        val (status, html) = request(port, "POST", "/$token", "key=AIzaSyFromPhone")
        assertEquals(200, status)
        assertEquals(listOf("AIzaSyFromPhone"), keys)
        assertTrue(html.contains(escapeHtml(keyCheckMessage(KeyCheckResult.WRONG_KEY))))
    }

    @Test
    fun `a POST without the right token is refused and hands nothing on`() {
        val port = start()
        for (path in listOf("/", "/wrongtoken", "/$token/more")) {
            val (status, _) = request(port, "POST", path, "key=AIzaSyIntruder")
            assertEquals(path, 404, status)
        }
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `nothing the server logs carries the key`() {
        val port = start(KeyCheckResult.WORKS)
        request(port, "POST", "/$token", "key=AIzaSySecretKey")
        request(port, "POST", "/nope", "key=AIzaSySecretKey")
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("AIzaSySecretKey") })
    }

    @Test
    fun `a body larger than a key could ever be is refused`() {
        val port = start()
        val (status, _) = request(port, "POST", "/$token", "key=" + "A".repeat(KEY_PAGE_MAX_BYTES + 1))
        assertEquals(413, status)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `a connection from outside the home network is closed unanswered`() {
        val refusing = KeyPageServer(
            token = token,
            onKey = { KeyCheckResult.WORKS },
            preferredPort = 0,
            acceptFrom = { false },
        )
        server = refusing
        val port = refusing.start()
        try {
            request(port, "GET", "/$token")
            fail("expected the connection to be closed")
        } catch (expected: IOException) {
            // Closed before any answer.
        }
    }

    @Test
    fun `after stop nothing is listening on the port`() {
        val port = start()
        server?.stop()
        server = null
        try {
            Socket(InetAddress.getLoopbackAddress(), port).close()
            fail("port $port is still listening")
        } catch (expected: ConnectException) {
            // Nothing listening.
        }
    }

    @Test
    fun `the answer tells the browser not to keep the page and not to send the token on`() {
        val port = start()
        val connection = URL("http://127.0.0.1:$port/$token").openConnection() as HttpURLConnection
        assertEquals(200, connection.responseCode)
        assertEquals("no-store", connection.getHeaderField("Cache-Control"))
        assertEquals("no-referrer", connection.getHeaderField("Referrer-Policy"))
        assertEquals(KEY_PAGE_CONTENT_SECURITY_POLICY, connection.getHeaderField("Content-Security-Policy"))
        connection.disconnect()
    }
}
