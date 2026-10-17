package local.youtubetv.refresh

import local.youtubetv.data.StoredVideo
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build ticket 40: which videos a walk flags as **Newly listed**.
 *
 * One rule, in `mergeRefresh`: a video the walk added is flagged, and only when the Source
 * already held videos. See `StoredVideo.newlyListed` for what the flag then does.
 *
 * **The trap this suite exists for.** `SourceStore.replaceVideos` deletes every row of the
 * Source and inserts them again, so a flag that `mergeRefresh` does not carry forward is lost
 * on the very next walk, and the household would be offered a video for a few minutes and
 * never again.
 */
class NewlyListedMergeTest {

    private val nothingWatched: (String) -> Boolean = { false }

    private fun merge(
        stored: List<StoredVideo>,
        fresh: List<StoredVideo>,
        fullWalk: Boolean = true,
    ) = mergeRefresh(stored, fresh, fullWalk = fullWalk, hasWatch = nothingWatched)

    private fun flagged(outcome: RefreshOutcome): Set<String> =
        outcome.videos.filter { it.newlyListed }.mapTo(mutableSetOf()) { it.videoId }

    @Test
    fun `a video a walk adds to a Source that already had videos is newly listed`() {
        val outcome = merge(
            stored = listOf(testVideo("old", day = 1)),
            fresh = listOf(testVideo("old", day = 1), testVideo("new", day = 9)),
        )

        assertEquals(setOf("new"), flagged(outcome))
        // The copy in `added` carries the flag too, because it is the same video.
        assertTrue(outcome.added.single().newlyListed)
    }

    @Test
    fun `the first walk of a newly added Source flags nothing`() {
        val outcome = merge(
            stored = emptyList(),
            fresh = (1..3).map { testVideo("v$it", day = it) },
        )

        assertEquals(emptySet<String>(), flagged(outcome))
        assertEquals(3, outcome.added.size)
    }

    @Test
    fun `the first walk after the video tables are rebuilt flags nothing`() {
        // A rebuilt `videos` table looks exactly like a Source that was never walked, which
        // is why one test on `stored` covers both. Without it a rebuild would offer the
        // household all 6,904 of its videos at once.
        val everything = (1..6).map { testVideo("v$it", day = it) }

        assertEquals(emptySet<String>(), flagged(merge(stored = emptyList(), fresh = everything)))
    }

    @Test
    fun `a newly listed video survives a full walk that changes nothing else`() {
        val stored = listOf(
            testVideo("new", day = 9, newlyListed = true),
            testVideo("old", day = 1),
        )

        val outcome = merge(stored = stored, fresh = stored.map { it.copy(newlyListed = false) })

        // The fresh listing comes from YouTube, which knows nothing about the flag, so the
        // stored value is what must win.
        assertEquals(setOf("new"), flagged(outcome))
        assertTrue(outcome.isNoChange)
    }

    @Test
    fun `a newly listed video survives a partial change check`() {
        // A channel Source re-lists its newest page and stops at the first known id. The
        // video is in that page and comes back with no flag on it.
        val stored = listOf(
            testVideo("new", day = 9, newlyListed = true),
            testVideo("old", day = 1),
        )

        val outcome = merge(
            stored = stored,
            fresh = listOf(testVideo("new", day = 9)),
            fullWalk = false,
        )

        assertEquals(setOf("new"), flagged(outcome))
        assertEquals(2, outcome.videos.size)
    }

    @Test
    fun `a newly listed video a partial check did not even re-list keeps its flag`() {
        val stored = listOf(
            testVideo("newest", day = 20),
            testVideo("new", day = 9, newlyListed = true),
        )

        val outcome = merge(
            stored = stored,
            fresh = listOf(testVideo("newest", day = 20)),
            fullWalk = false,
        )

        assertEquals(setOf("new"), flagged(outcome))
    }

    @Test
    fun `a video that vanished and came back counts as newly listed`() {
        // Deliberate, and written down in the ticket: `mergeRefresh` cannot tell it from a
        // genuine first listing without a second kind of stored state. It is rare, the
        // household never saw the video, and the next play at or past it ends it.
        val outcome = merge(
            stored = listOf(testVideo("stays", day = 1)),
            fresh = listOf(testVideo("stays", day = 1), testVideo("back", day = 5)),
        )

        assertEquals(setOf("back"), flagged(outcome))
    }

    @Test
    fun `a vanished video that is kept keeps its own flag rather than gaining one`() {
        val stored = listOf(
            testVideo("gone", day = 5),
            testVideo("stays", day = 1),
        )

        val outcome = mergeRefresh(
            stored = stored,
            fresh = listOf(testVideo("stays", day = 1)),
            fullWalk = true,
            hasWatch = { it == "gone" },
        )

        assertFalse(outcome.videos.single { it.videoId == "gone" }.newlyListed)
    }
}
