package local.youtubetv.youtube

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An `HttpURLConnection` that answers from a string.
 *
 * Subclassing is enough here: the class is abstract, and this test only needs the status, the
 * body and the request headers.
 */
private class FakeConnection(
    private val status: Int,
    private val body: String,
    private val failOnRead: Boolean = false,
) : HttpURLConnection(URL("https://youtube.googleapis.com/youtube/v3/videos")) {

    val requestHeaders = mutableMapOf<String, String>()
    var disconnected = false
        private set

    override fun connect() = Unit
    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = status

    override fun getInputStream(): InputStream {
        if (failOnRead) throw IOException("connection reset")
        return body.byteInputStream()
    }

    override fun getErrorStream(): InputStream? = if (status >= 400) body.byteInputStream() else null

    override fun setRequestProperty(key: String, value: String) {
        requestHeaders[key] = value
    }
}

class HttpYouTubeApiTest {

    private fun api(
        connection: HttpURLConnection,
        androidPackage: String? = null,
        androidCertSha1: String? = null,
        seenUrls: MutableList<String> = mutableListOf(),
    ) = HttpYouTubeApi(
        apiKey = { "AIzaSyHouseholdKey" },
        androidPackage = androidPackage,
        androidCertSha1 = androidCertSha1,
        // Unconfined keeps the test on one thread. The real app uses Dispatchers.IO.
        dispatcher = Dispatchers.Unconfined,
        openConnection = { url ->
            seenUrls += url
            connection
        },
    )

    @Test
    fun `a good reply is parsed`() {
        val body = """{"items":[{"contentDetails":{"videoId":"abc","videoPublishedAt":"2026-01-01T00:00:00Z"}}]}"""
        val page = runBlocking { api(FakeConnection(200, body)).playlistItems("UUx") }
        assertEquals("abc", page.items.single().videoId)
    }

    @Test
    fun `sends the two headers that an Android key restriction is enforced from`() {
        val connection = FakeConnection(200, """{"items":[]}""")
        runBlocking {
            api(connection, androidPackage = "local.youtubetv", androidCertSha1 = "5EC3196719").playlistItems("UUx")
        }
        assertEquals("local.youtubetv", connection.requestHeaders["X-Android-Package"])
        assertEquals("5EC3196719", connection.requestHeaders["X-Android-Cert"])
    }

    @Test
    fun `leaves both headers off when the app could not read its own certificate`() {
        val connection = FakeConnection(200, """{"items":[]}""")
        runBlocking { api(connection).playlistItems("UUx") }
        assertNull(connection.requestHeaders["X-Android-Package"])
        assertNull(connection.requestHeaders["X-Android-Cert"])
    }

    @Test
    fun `maps a 404 playlistNotFound onto its reason`() {
        val body = """{"error":{"code":404,"errors":[{"reason":"playlistNotFound"}]}}"""
        val failure = failureFrom(FakeConnection(404, body)) { playlistItems("UUSHx") }
        assertEquals(YouTubeApiException.Reason.PLAYLIST_NOT_FOUND, failure.reason)
    }

    @Test
    fun `maps a 403 quotaExceeded onto its reason`() {
        val body = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}],"message":"quota"}}"""
        val failure = failureFrom(FakeConnection(403, body)) { playlistItems("UUx") }
        assertEquals(YouTubeApiException.Reason.QUOTA_EXCEEDED, failure.reason)
    }

    @Test
    fun `a reply that is cut off is a bad response, because YouTube served it and charged it`() {
        // It was `NETWORK` until ticket 12. YouTube answered 2xx before the read failed, so
        // the request was served and it came out of the quota. Calling it a network failure
        // made `SourceSearch` report one of the day's hundred `search.list` calls as unspent
        // when it had gone, and let the household spend a second one on the same query.
        // Found by `/code-review`.
        val failure = failureFrom(FakeConnection(200, "", failOnRead = true)) { playlistItems("UUx") }
        assertEquals(YouTubeApiException.Reason.BAD_RESPONSE, failure.reason)
    }

    @Test
    fun `always disconnects, even when the reply was an error`() {
        val connection = FakeConnection(403, """{"error":{"code":403}}""")
        failureFrom(connection) { playlistItems("UUx") }
        assertTrue(connection.disconnected)
    }

    @Test
    fun `an empty id list spends no call at all`() {
        val seen = mutableListOf<String>()
        val videos = runBlocking {
            api(FakeConnection(200, "{}"), seenUrls = seen).videos(emptyList())
        }
        assertTrue(videos.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `no error message ever carries the API key`() {
        val body = """{"error":{"code":403,"message":"forbidden"}}"""
        val failure = failureFrom(FakeConnection(403, body)) { playlistItems("UUx") }
        assertTrue(failure.message!!.isNotEmpty())
        assertTrue(!failure.message!!.contains("AIzaSy"))
    }

    private fun failureFrom(
        connection: HttpURLConnection,
        call: suspend YouTubeApi.() -> Unit,
    ): YouTubeApiException {
        val failure = runCatching { runBlocking { api(connection).call() } }.exceptionOrNull()
        assertTrue("expected a YouTubeApiException, got $failure", failure is YouTubeApiException)
        return failure as YouTubeApiException
    }
}
