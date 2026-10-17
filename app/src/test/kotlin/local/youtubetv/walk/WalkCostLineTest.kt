package local.youtubetv.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkCostLineTest {

    @Test
    fun `reports a matching cost for a channel Source`() {
        // 5,813 videos is 117 pages. 117 + 117 + 11 UUSH pages = 245. Read from a real walk
        // on the emulator on 2026-09-19.
        val line = costLine(
            playlistEntries = 5_813,
            distinctVideos = 5_813,
            calls = ApiCallCount(playlistPages = 117, videoPages = 117, shortsPages = 11),
        )
        assertTrue(line, line.contains("245 API calls"))
        assertTrue(line, line.contains("Expected 245, matches."))
    }

    @Test
    fun `reports a matching cost for a curated playlist, which reads no UUSH`() {
        val line = costLine(100, 100, ApiCallCount(playlistPages = 2, videoPages = 2, shortsPages = 0))
        assertTrue(line, line.contains("4 API calls"))
        assertTrue(line, line.contains("matches"))
    }

    @Test
    fun `says plainly when the cost does not match, rather than hiding it`() {
        val line = costLine(100, 100, ApiCallCount(playlistPages = 4, videoPages = 2, shortsPages = 0))
        assertTrue(line, line.contains("DOES NOT MATCH"))
    }

    @Test
    fun `an empty Source costs the one page it took to find out, and that matches`() {
        // The formula alone would expect zero pages. YouTube still has to be asked once.
        val line = costLine(0, 0, ApiCallCount(playlistPages = 1, videoPages = 0, shortsPages = 0))
        assertTrue(line, line.contains("0 videos"))
        assertTrue(line, line.contains("1 API calls"))
        assertTrue(line, line.contains("matches"))
        assertEquals(1, expectedApiCalls(playlistEntries = 0, distinctVideos = 0, shortsPages = 0))
    }

    @Test
    fun `a playlist that lists the same video twice still matches`() {
        // 60 entries is 2 playlist pages, but only 50 distinct videos, so 1 videos page.
        val line = costLine(
            playlistEntries = 60,
            distinctVideos = 50,
            calls = ApiCallCount(playlistPages = 2, videoPages = 1, shortsPages = 0),
        )
        assertTrue(line, line.contains("matches"))
        assertTrue(line, line.contains("50 videos (60 playlist entries)"))
    }

    @Test
    fun `the entry count is left out when nothing was deduplicated`() {
        val line = costLine(50, 50, ApiCallCount(playlistPages = 1, videoPages = 1, shortsPages = 0))
        assertTrue(line, line.contains("50 videos,"))
        assertTrue(line, !line.contains("playlist entries"))
    }

    @Test
    fun `the expected count rounds each half up on its own`() {
        // 51 entries is 2 pages, not 1. The same for the videos half.
        assertEquals(4, expectedApiCalls(playlistEntries = 51, distinctVideos = 51, shortsPages = 0))
        assertEquals(2, expectedApiCalls(playlistEntries = 50, distinctVideos = 50, shortsPages = 0))
    }

    private fun costLine(playlistEntries: Int, distinctVideos: Int, calls: ApiCallCount) =
        walkCostLine("UUx", playlistEntries, distinctVideos, calls)
}
