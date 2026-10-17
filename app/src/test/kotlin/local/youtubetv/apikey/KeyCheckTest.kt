package local.youtubetv.apikey

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build ticket 64: what YouTube's answer to the check call means. */
class KeyCheckTest {

    /** The reply to a wrong key, as YouTube sent it on 2026-10-17. */
    private val wrongKeyReply = """
        {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
        "errors":[{"message":"API key not valid. Please pass a valid API key.",
        "domain":"global","reason":"badRequest"}],"status":"INVALID_ARGUMENT",
        "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID",
        "domain":"googleapis.com","metadata":{"service":"youtube.googleapis.com"}}]}}
    """.trimIndent()

    /** The documented shape for a project where YouTube Data API v3 is not enabled. */
    private val notEnabledReply = """
        {"error":{"code":403,"message":"YouTube Data API v3 has not been used in project 1 before or it is disabled.",
        "errors":[{"domain":"usageLimits","reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED",
        "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED"}]}}
    """.trimIndent()

    @Test
    fun `any 2xx answer means the key works`() {
        assertEquals(KeyCheckResult.WORKS, classifyKeyCheck(200, null))
    }

    @Test
    fun `a wrong key is its own result`() {
        assertEquals(KeyCheckResult.WRONG_KEY, classifyKeyCheck(400, wrongKeyReply))
    }

    @Test
    fun `a wrong key is recognised by its message alone when details are missing`() {
        val bare = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""
        assertEquals(KeyCheckResult.WRONG_KEY, classifyKeyCheck(400, bare))
    }

    @Test
    fun `a project without the API enabled is its own result, from either reason`() {
        assertEquals(KeyCheckResult.API_NOT_ENABLED, classifyKeyCheck(403, notEnabledReply))
        val oldShape = """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}"""
        assertEquals(KeyCheckResult.API_NOT_ENABLED, classifyKeyCheck(403, oldShape))
    }

    @Test
    fun `a key whose own restrictions refuse the call is its own result`() {
        for (reason in listOf("API_KEY_SERVICE_BLOCKED", "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED")) {
            val body = """{"error":{"details":[{"reason":"$reason"}]}}"""
            assertEquals(reason, KeyCheckResult.KEY_RESTRICTED, classifyKeyCheck(403, body))
        }
    }

    @Test
    fun `a spent quota is a working key and is saved`() {
        val body = """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""
        assertEquals(KeyCheckResult.WORKS_BUT_QUOTA_USED, classifyKeyCheck(403, body))
        assertTrue(KeyCheckResult.WORKS_BUT_QUOTA_USED.saves)
    }

    @Test
    fun `no answer at all is no network`() {
        assertEquals(KeyCheckResult.NO_NETWORK, classifyKeyCheck(null, null))
    }

    @Test
    fun `an answer nothing above names is unexpected, and an unreadable body does not throw`() {
        assertEquals(KeyCheckResult.UNEXPECTED, classifyKeyCheck(500, "<html>"))
        assertEquals(KeyCheckResult.UNEXPECTED, classifyKeyCheck(403, null))
    }

    @Test
    fun `only the two working results save`() {
        val saving = KeyCheckResult.entries.filter { it.saves }
        assertEquals(listOf(KeyCheckResult.WORKS, KeyCheckResult.WORKS_BUT_QUOTA_USED), saving)
    }

    @Test
    fun `every result has its own sentence`() {
        val sentences = KeyCheckResult.entries.map { keyCheckMessage(it) }
        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `a pasted key loses spaces, line breaks and quotes`() {
        assertEquals("AIzaSyABC", normalizeKey("  \"AIzaSy ABC\"\n"))
        assertEquals("AIzaSyABC", normalizeKey("'AIzaSyABC'"))
    }

    @Test
    fun `the log line names the result and never the key`() {
        val line = keyCheckLogLine(KeyFrom.KEY_PAGE, KeyCheckResult.WRONG_KEY)
        assertTrue(line.contains("WRONG_KEY"))
        assertTrue(line.contains("nothing is saved"))
        assertFalse(line.contains("AIza"))
    }

    @Test
    fun `the HTTP checker sends the key on one videos call and reads the reply`() = runBlocking {
        val seen = mutableListOf<String>()
        val checker = HttpKeyChecker(openConnection = { url ->
            seen += url
            FakeConnection(url, 400, wrongKeyReply)
        })
        assertEquals(KeyCheckResult.WRONG_KEY, checker.check("AIzaSyWrong"))
        assertEquals(1, seen.size)
        assertTrue(seen.single().startsWith("https://youtube.googleapis.com/youtube/v3/videos?part=id&"))
        assertTrue(seen.single().endsWith("&key=AIzaSyWrong"))
    }

    @Test
    fun `the HTTP checker makes no call for an empty key`() = runBlocking {
        val checker = HttpKeyChecker(openConnection = { error("no call expected") })
        assertEquals(KeyCheckResult.EMPTY, checker.check(""))
    }

    @Test
    fun `the HTTP checker reads a failed connection as no network`() = runBlocking {
        val checker = HttpKeyChecker(openConnection = { url -> FakeConnection(url, -1, null) })
        assertEquals(KeyCheckResult.NO_NETWORK, checker.check("AIzaSyAny"))
    }

    private class FakeConnection(url: String, private val status: Int, private val body: String?) :
        HttpURLConnection(URL(url)) {
        override fun getResponseCode(): Int = if (status < 0) throw IOException("offline") else status
        override fun getErrorStream() = body?.let { ByteArrayInputStream(it.toByteArray()) }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
