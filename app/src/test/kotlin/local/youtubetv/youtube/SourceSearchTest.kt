package local.youtubetv.youtube

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.channelHit
import local.youtubetv.walk.fakeChannel
import local.youtubetv.walk.fakePlaylist
import local.youtubetv.walk.playlistHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule that protects the day's hundred `search.list` calls.
 *
 * **These tests are why this ticket could be built without spending the bucket.** Each one
 * runs against a fake, counts the calls the real API would have seen, and costs nothing. The
 * number `api.searchCalls` reports is the number the household's television would have spent.
 *
 * Spec sections 4.1, 6.1 and 6.2.
 */
class SourceSearchTest {

    private val tedId = "UCAuUUnT6oDeKwE6v1NGQxug"
    private val tedUploads = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val curated = "PLmadeUpPlaylistForTestsZ000000001"

    private val calls = mutableListOf<String>()
    private val lines = mutableListOf<String>()

    private fun search(api: YouTubeApi) =
        SourceSearch(api, onCall = { calls += it }, log = { lines += it })

    /** A search for `ted` that finds the channel first and one of its playlists second. */
    private fun tedApi() = FakeYouTubeApi(
        playlists = emptyMap(),
        channels = mapOf(
            tedId to fakeChannel(tedId, title = "TED", handle = "@TED", videoCount = 5813),
        ),
        playlistDetails = mapOf(
            curated to fakePlaylist(
                curated,
                title = "Best of TED",
                channelId = tedId,
                channelTitle = "TED",
                itemCount = 40,
            ),
        ),
        searchResults = mapOf(
            "ted" to listOf(channelHit(tedId, title = "TED"), playlistHit(curated, title = "Best of TED")),
        ),
    )

    // ---------------------------------------------------------------- what one search costs

    @Test
    fun `one search costs one search call and two units, and nothing more`() = runBlocking {
        val api = tedApi()
        val outcome = search(api).find("ted") as SearchOutcome.Results

        assertTrue(outcome.spentACall)
        assertEquals("one call of the day's hundred", 1, api.searchCalls)
        // Spec section 6.2: the counts cost 2 extra units for up to 50 results. One
        // `channels.list` over every channel hit and one `playlists.list` over every playlist
        // hit, both batched into a single call apiece.
        assertEquals(1, api.channelCalls)
        assertEquals(1, api.playlistCalls)
        assertEquals(listOf("search.list", "channels.list", "playlists.list"), calls)
    }

    @Test
    fun `a search with no playlist among its hits skips the playlist pricing call`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channels = mapOf(tedId to fakeChannel(tedId)),
            searchResults = mapOf("ted" to listOf(channelHit(tedId))),
        )
        search(api).find("ted")

        assertEquals(1, api.searchCalls)
        assertEquals(1, api.channelCalls)
        assertEquals("nothing to price, so nothing was asked", 0, api.playlistCalls)
    }

    @Test
    fun `an empty query never reaches YouTube at all`() = runBlocking {
        val api = tedApi()
        val outcome = search(api).find("   ") as SearchOutcome.Results

        assertTrue(outcome.rows.isEmpty())
        assertFalse(outcome.spentACall)
        assertEquals(0, api.searchCalls)
    }

    // ---------------------------------------------------------------- the per-session cache

    @Test
    fun `the same query twice in one session makes one search call`() = runBlocking {
        val api = tedApi()
        val search = search(api)

        val first = search.find("ted") as SearchOutcome.Results
        val second = search.find("ted") as SearchOutcome.Results

        assertEquals("the same rows come back", first.rows, second.rows)
        assertTrue(first.spentACall)
        assertFalse("the second was answered from this session's cache", second.spentACall)
        assertEquals(1, api.searchCalls)
        assertEquals("nor was anything priced a second time", 1, api.channelCalls)
        assertEquals(1, search.callsSpent)
    }

    @Test
    fun `case and spacing do not make a second query`() = runBlocking {
        val api = tedApi()
        val search = search(api)
        search.find("ted")
        search.find("  TED  ")
        search.find("Ted")

        assertEquals(1, api.searchCalls)
        assertEquals(listOf("ted"), api.searchQueries)
    }

    @Test
    fun `a query that found nothing is cached too`() = runBlocking {
        val api = tedApi()
        val search = search(api)

        // Nothing matches, which is the query a household is most likely to submit twice.
        assertTrue((search.find("nobody at all") as SearchOutcome.Results).rows.isEmpty())
        assertTrue((search.find("nobody at all") as SearchOutcome.Results).rows.isEmpty())

        assertEquals(1, api.searchCalls)
    }

    @Test
    fun `two different queries cost two calls`() = runBlocking {
        val api = tedApi()
        val search = search(api)
        search.find("ted")
        search.find("ted talks")

        assertEquals(2, api.searchCalls)
        assertEquals(2, search.callsSpent)
    }

    // ---------------------------------------------------------------- the day's hundred

    @Test
    fun `an exhausted bucket is latched, so nothing is asked again this session`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingSearches = mapOf(
                "ted" to YouTubeApiException.Reason.QUOTA_EXCEEDED,
                "anything else" to YouTubeApiException.Reason.QUOTA_EXCEEDED,
            ),
        )
        val search = search(api)

        assertEquals(SearchOutcome.QuotaExhausted, search.find("ted"))
        assertTrue(search.quotaExhausted)

        // Spec section 5.4 takes the same line for refresh: no retry loop. A second query is
        // refused without a request, because YouTube has already said there are none left.
        assertEquals(SearchOutcome.QuotaExhausted, search.find("anything else"))
        assertEquals("only the first one reached YouTube", 1, api.searchCalls)
    }

    /** Build ticket 64: the hundred belong to each key, so a new key lets the latch go. */
    @Test
    fun `a new API key lets the latch go`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingSearches = mapOf("ted" to YouTubeApiException.Reason.QUOTA_EXCEEDED),
        )
        val search = search(api)
        search.find("ted")
        assertTrue(search.quotaExhausted)

        search.forgetQuotaRefusal()

        assertFalse(search.quotaExhausted)
        assertEquals(0, search.callsSpent)
        search.find("ted")
        assertEquals("the search is asked again with the new key", 2, api.searchCalls)
    }

    @Test
    fun `a request YouTube refused still counted against the day's hundred`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingSearches = mapOf("ted" to YouTubeApiException.Reason.HTTP),
        )
        val search = search(api)
        assertEquals(SearchOutcome.Failed(YouTubeApiException.Reason.HTTP), search.find("ted"))

        // Spec section 4.1: invalid requests cost too. YouTube saw this one.
        assertEquals(1, search.callsSpent)
        assertFalse("but the bucket is not known to be empty", search.quotaExhausted)
    }

    @Test
    fun `a reply YouTube served but cut off counted, because YouTube charged for it`() =
        runBlocking {
            val api = FakeYouTubeApi(
                playlists = emptyMap(),
                failingSearches = mapOf("ted" to YouTubeApiException.Reason.BAD_RESPONSE),
            )
            val search = search(api)
            search.find("ted")
            // `HttpYouTubeApi` reports a cut-off reply as BAD_RESPONSE, not NETWORK, for
            // exactly this: YouTube answered, so one of the hundred is gone.
            assertEquals(1, search.callsSpent)
        }

    @Test
    fun `a request that never left the television spent nothing`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingSearches = mapOf("ted" to YouTubeApiException.Reason.NETWORK),
        )
        val search = search(api)
        search.find("ted")

        assertEquals("YouTube never saw it, so it cost nothing", 0, search.callsSpent)
        // And it is not cached, so the household can try again once the network is back.
        search.find("ted")
        assertEquals(2, api.searchCalls)
    }

    // ---------------------------------------------------------------- the rows

    @Test
    fun `a channel row carries its uploads playlist, its handle and its count`() = runBlocking {
        val rows = (search(tedApi()).find("ted") as SearchOutcome.Results).rows
        val channel = rows.first()

        assertEquals(CandidateKind.CHANNEL, channel.kind)
        // What would be stored. A Source is a playlist id, and `search.list` does not report
        // one for a channel, which is why the pricing call is not optional.
        assertEquals(tedUploads, channel.playlistId)
        assertEquals(tedId, channel.channelId)
        assertEquals("TED", channel.title)
        assertEquals("@TED", channel.handle)
        assertEquals(5813, channel.videoCount)
        assertTrue("picking it opens the second step", channel.secondStep)
    }

    @Test
    fun `a playlist row names the channel that owns it and its item count`() = runBlocking {
        val rows = (search(tedApi()).find("ted") as SearchOutcome.Results).rows
        val playlist = rows.last()

        assertEquals(CandidateKind.PLAYLIST, playlist.kind)
        assertEquals(curated, playlist.playlistId)
        assertEquals("Best of TED", playlist.title)
        assertEquals("a playlist has no handle, so the row names its owner", "TED", playlist.handle)
        assertEquals(40, playlist.videoCount)
        assertFalse("picking it goes straight to the add screen", playlist.secondStep)
    }

    @Test
    fun `the rows keep YouTube's own relevance order`() = runBlocking {
        val rows = (search(tedApi()).find("ted") as SearchOutcome.Results).rows
        assertEquals(listOf(tedUploads, curated), rows.map { it.playlistId })
    }

    @Test
    fun `every row has a thumbnail, a title and a badge`() = runBlocking {
        val rows = (search(tedApi()).find("ted") as SearchOutcome.Results).rows
        for (row in rows) {
            assertTrue(row.title.isNotBlank())
            assertTrue("spec section 6.2 puts a picture on every row", !row.thumbnailUrl.isNullOrBlank())
        }
    }

    @Test
    fun `the same hit twice becomes one row`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channels = mapOf(tedId to fakeChannel(tedId)),
            searchResults = mapOf("ted" to listOf(channelHit(tedId), channelHit(tedId))),
        )
        val rows = (search(api).find("ted") as SearchOutcome.Results).rows
        // Two rows of one channel would also be two rows of one key in the result list, and a
        // `LazyColumn` throws on a repeated key.
        assertEquals(1, rows.size)
    }

    // ---------------------------------------------------------------- pricing that failed

    @Test
    fun `a pricing failure never throws away the search call that was already spent`() =
        runBlocking {
            val api = FakeYouTubeApi(
                playlists = emptyMap(),
                channels = mapOf(tedId to fakeChannel(tedId, title = "TED")),
                searchResults = mapOf("ted" to listOf(channelHit(tedId, title = "TED from search"))),
                pricingFails = true,
            )
            val outcome = search(api).find("ted") as SearchOutcome.Results

            assertEquals(1, outcome.rows.size)
            // The search's own title stands in, because the priced one never arrived.
            assertEquals("TED from search", outcome.rows.single().title)
            assertNull("and it says nothing rather than a wrong count", outcome.rows.single().videoCount)
        }

    @Test
    fun `a degraded result is shown once and never cached`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channels = mapOf(tedId to fakeChannel(tedId)),
            searchResults = mapOf("ted" to listOf(channelHit(tedId))),
            pricingFails = true,
        )
        val search = search(api)
        search.find("ted")
        search.find("ted")

        // Caching the degraded rows would leave the household a channel it cannot add, with
        // no way back but a different query, and a different query costs one of the hundred.
        // Caching nothing at all would be worse: the second press would re-run the search
        // itself. So the hits are kept and only the 1-unit pricing is tried again.
        assertEquals("the expensive call was not spent twice", 1, api.searchCalls)
        assertEquals("but the cheap one was tried again", 2, api.channelCalls)
        assertTrue(lines.any { it.contains("no search.list call") })
    }

    @Test
    fun `a good result is still cached, so only failure retries`() = runBlocking {
        val api = tedApi()
        val search = search(api)
        search.find("ted")
        search.find("ted")
        assertEquals(1, api.channelCalls)
    }

    // ---------------------------------------------------------------- midnight Pacific Time

    @Test
    fun `the bucket, the cache and the count all start again at midnight Pacific Time`() =
        runBlocking {
            val api = tedApi()
            var clock = Instant.parse("2026-09-20T23:00:00Z") // 16:00 Pacific
            val search = SourceSearch(api, onCall = { calls += it }, log = { lines += it }, now = { clock })

            search.find("ted")
            assertEquals(1, search.callsSpent)

            // Still the same Pacific day, six hours later: the cache still answers.
            clock = Instant.parse("2026-09-21T05:00:00Z") // 22:00 Pacific, same day
            search.find("ted")
            assertEquals(1, api.searchCalls)

            // Past midnight Pacific. The hundred have come back, so nothing of yesterday's
            // should still be speaking for them.
            clock = Instant.parse("2026-09-21T08:00:00Z") // 01:00 Pacific, the next day
            search.find("ted")
            assertEquals("yesterday's rows are not served today", 2, api.searchCalls)
            assertEquals("and the count says 1 of 100 again", 1, search.callsSpent)
        }

    @Test
    fun `an exhausted bucket lets go once the Pacific day has turned`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channels = mapOf(tedId to fakeChannel(tedId)),
            searchResults = mapOf("ted" to listOf(channelHit(tedId))),
            failingSearches = mapOf("gone" to YouTubeApiException.Reason.QUOTA_EXCEEDED),
        )
        var clock = Instant.parse("2026-09-21T04:00:00Z") // 21:00 Pacific
        val search = SourceSearch(api, now = { clock })

        assertEquals(SearchOutcome.QuotaExhausted, search.find("gone"))
        assertTrue(search.quotaExhausted)

        // An app left running overnight, which is what a television does. The bucket refilled
        // at midnight and the latch must not go on speaking for it.
        clock = Instant.parse("2026-09-21T08:00:00Z") // 01:00 Pacific, the next day
        assertTrue(search.find("ted") is SearchOutcome.Results)
        assertFalse(search.quotaExhausted)
    }

    @Test
    fun `a channel the pricing call did not answer for still leaves a row to pick`() {
        val rows = searchRows(
            hits = listOf(channelHit(tedId, title = "TED")),
            channels = emptyMap(),
            playlists = emptyMap(),
        )
        val row = rows.single()
        assertEquals("", row.playlistId)
        assertEquals(tedId, row.channelId)
        // It still opens the second step, where `playlists.list?channelId=` answers whether or
        // not the uploads playlist id is known.
        assertTrue(row.secondStep)
    }

    @Test
    fun `the priced title wins over the searched one, because only search escapes its HTML`() {
        val rows = searchRows(
            hits = listOf(playlistHit(curated, title = "Tom &amp; Jerry")),
            channels = emptyMap(),
            playlists = mapOf(curated to fakePlaylist(curated, title = "Tom & Jerry")),
        )
        assertEquals("Tom & Jerry", rows.single().title)
    }

    // ---------------------------------------------------------------- the log

    @Test
    fun `every call and every cache hit is written down, counted against the hundred`() =
        runBlocking {
            val api = tedApi()
            val search = search(api)
            search.find("ted")
            search.find("ted")

            assertEquals(searchCallLine("ted", 1), lines.first())
            assertTrue("the count is in the line", lines.first().contains("1 of 100"))
            assertTrue("a cache hit says so too", lines.last().contains("No search.list call"))
        }

    @Test
    fun `the exhausted line names the reset and the paths that still work`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingSearches = mapOf("ted" to YouTubeApiException.Reason.QUOTA_EXCEEDED),
        )
        search(api).find("ted")

        val line = lines.single()
        assertTrue(line.contains("midnight Pacific Time"))
        assertTrue(line.contains("@handle"))
    }

    // ---------------------------------------------------------------- the size of a page

    @Test
    fun `a page bigger than one pricing call can cover is refused at construction`() {
        // Spec section 6.2 prices up to 50 results in 2 units. Asking for more would silently
        // leave the rest with no count, so it fails loudly and at once instead.
        val failure = runCatching { SourceSearch(tedApi(), maxResults = 51) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("spec section 6.1 asks for 25", 25, SEARCH_MAX_RESULTS)
    }
}
