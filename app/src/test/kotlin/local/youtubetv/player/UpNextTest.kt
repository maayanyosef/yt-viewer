package local.youtubetv.player

import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextTest {

    private val videos = (1..10).map { testVideo("v$it", day = 20 - it) }

    @Test
    fun `the row holds the next six videos after the one playing, nearest first`() {
        val row = upNextVideos(videos, fromVideoId = "v2")
        assertEquals(listOf("v3", "v4", "v5", "v6", "v7", "v8"), row.map { it.videoId })
    }

    @Test
    fun `the row is shorter near the end and empty on the last video`() {
        assertEquals(listOf("v9", "v10"), upNextVideos(videos, "v8").map { it.videoId })
        assertEquals(emptyList<String>(), upNextVideos(videos, "v10").map { it.videoId })
    }

    @Test
    fun `Left and Right move the highlight and stop at both ends`() {
        val row = UpNextUi(upNextVideos(videos, "v1"), selected = 0)
        assertEquals(0, row.moved(right = false).selected)
        assertEquals(1, row.moved(right = true).selected)
        assertEquals(5, row.copy(selected = 5).moved(right = true).selected)
        assertEquals("v2", row.current?.videoId)
    }
}
