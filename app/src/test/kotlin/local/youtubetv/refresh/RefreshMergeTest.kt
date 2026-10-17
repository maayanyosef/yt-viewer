package local.youtubetv.refresh

import local.youtubetv.data.Availability
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchState
import local.youtubetv.data.sourceCard
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a refresh does to one Source's cached videos. Spec sections 5.2 and 5.3.
 *
 * Every rule here is a pure function, so none of it needs a device, a network or a unit of
 * quota.
 */
class RefreshMergeTest {

    private val nothingWatched: (String) -> Boolean = { false }

    @Test
    fun `a video the listing has never shown before is new`() {
        val stored = listOf(testVideo("old", day = 1))
        val fresh = listOf(testVideo("old", day = 1), testVideo("new", day = 9))

        val outcome = mergeRefresh(stored, fresh, fullWalk = true, hasWatch = nothingWatched)

        assertEquals(listOf("new"), outcome.added.map { it.videoId })
        assertEquals(listOf("new", "old"), outcome.videos.map { it.videoId })
        assertTrue(outcome.dropped.isEmpty())
        assertTrue(outcome.keptUnavailable.isEmpty())
    }

    @Test
    fun `a video already known keeps the Shorts answer it was classified with`() {
        // Spec section 4.3: `isShort` is classified once and never changes. Spec section 5.2:
        // only newly seen video ids are classified at all. A live stream's `videos.list` reply
        // carries no embed size, so a refresh that re-classified would make a video the
        // household is watching vanish off the grid.
        val stored = listOf(testVideo("kept", day = 3, isShort = true))
        val fresh = listOf(testVideo("kept", day = 3, isShort = false))

        val outcome = mergeRefresh(stored, fresh, fullWalk = true, hasWatch = nothingWatched)

        assertEquals(true, outcome.videos.single().isShort)
    }

    @Test
    fun `a new video is classified as a Short by the listing, because it has never been seen`() {
        val outcome = mergeRefresh(
            stored = emptyList(),
            fresh = listOf(testVideo("fresh", day = 3, isShort = true)),
            fullWalk = true,
            hasWatch = nothingWatched,
        )
        assertEquals(true, outcome.videos.single().isShort)
    }

    @Test
    fun `everything but the Shorts answer is taken from the fresh listing`() {
        // A stream that was live an hour ago is a finished VOD now, and its title and length
        // both change with it.
        val stored = listOf(testVideo("v", day = 3, title = "LIVE: tonight", durationSeconds = 0))
        val fresh = listOf(testVideo("v", day = 3, title = "Tonight", durationSeconds = 3875))

        val outcome = mergeRefresh(stored, fresh, fullWalk = true, hasWatch = nothingWatched)

        assertEquals("Tonight", outcome.videos.single().title)
        assertEquals(3875, outcome.videos.single().durationSeconds)
    }

    // -------------------------------------------- a video that vanished, spec section 5.3

    @Test
    fun `a video that vanished with no Watch is dropped`() {
        val stored = listOf(testVideo("gone", day = 2), testVideo("stays", day = 5))
        val fresh = listOf(testVideo("stays", day = 5))

        val outcome = mergeRefresh(stored, fresh, fullWalk = true, hasWatch = nothingWatched)

        assertEquals(listOf("gone"), outcome.dropped.map { it.videoId })
        assertEquals(listOf("stays"), outcome.videos.map { it.videoId })
    }

    @Test
    fun `a video that vanished with a Watch is kept and marked unavailable`() {
        val stored = listOf(testVideo("watched", day = 2), testVideo("stays", day = 5))
        val fresh = listOf(testVideo("stays", day = 5))

        val outcome = mergeRefresh(stored, fresh, fullWalk = true) { it == "watched" }

        assertTrue(outcome.dropped.isEmpty())
        assertEquals(listOf("watched"), outcome.keptUnavailable.map { it.videoId })
        assertEquals(listOf("stays", "watched"), outcome.videos.map { it.videoId })
        val kept = outcome.videos.single { it.videoId == "watched" }
        assertEquals(Availability.UNAVAILABLE, kept.availability)
        // Its title and date are still there, so the grid can still draw the card.
        assertEquals("Video watched", kept.title)
    }

    /**
     * A video that vanished a month ago is absent from every later listing too.
     *
     * Counting it as newly kept each time made every full walk report the Source as
     * changed for ever: rewriting all its rows, dropping its kept `SourceScreenModel` and
     * rebuilding the home rail, with nothing having happened. Found by `/code-review`.
     */
    @Test
    fun `a video that vanished long ago is not counted as newly kept again`() {
        val alreadyGone = testVideo("gone", day = 2, availability = Availability.UNAVAILABLE)

        val outcome = mergeRefresh(
            stored = listOf(alreadyGone, testVideo("stays", day = 5)),
            fresh = listOf(testVideo("stays", day = 5)),
            fullWalk = true,
        ) { it == "gone" }

        assertTrue(outcome.keptUnavailable.isEmpty())
        assertTrue(outcome.isNoChange)
        // It is still on the grid, and still marked unavailable.
        assertEquals(listOf("stays", "gone"), outcome.videos.map { it.videoId })
    }

    @Test
    fun `keeping a vanished video is idempotent, so a second refresh does not keep it twice`() {
        val first = mergeRefresh(
            stored = listOf(testVideo("watched", day = 2)),
            fresh = emptyList(),
            fullWalk = true,
        ) { true }
        val second = mergeRefresh(
            stored = first.videos,
            fresh = emptyList(),
            fullWalk = true,
        ) { true }

        assertEquals(1, second.videos.size)
        assertEquals(Availability.UNAVAILABLE, second.videos.single().availability)
    }

    @Test
    fun `a partial re-list never makes a video vanish`() {
        // Spec section 5.2 stops a channel re-list at the first known id, so it has no opinion
        // about anything older. Reading it as the whole truth would throw the Source away.
        val stored = (1..5).map { testVideo("v$it", day = it) }
        val fresh = listOf(testVideo("v6", day = 6))

        val outcome = mergeRefresh(stored, fresh, fullWalk = false, hasWatch = nothingWatched)

        assertTrue(outcome.dropped.isEmpty())
        assertTrue(outcome.keptUnavailable.isEmpty())
        assertEquals(listOf("v6"), outcome.added.map { it.videoId })
        assertEquals(6, outcome.videos.size)
    }

    @Test
    fun `a refresh that changed nothing says so`() {
        val stored = listOf(testVideo("v", day = 1))
        val outcome = mergeRefresh(stored, stored, fullWalk = true, hasWatch = nothingWatched)
        assertTrue(outcome.isNoChange)
    }

    @Test
    fun `the stored list comes back in publish-date order, newest first`() {
        val outcome = mergeRefresh(
            stored = listOf(testVideo("b", day = 2)),
            // A curated playlist arrives in the owner's order, which is never displayed.
            fresh = listOf(testVideo("b", day = 2), testVideo("c", day = 9), testVideo("a", day = 1)),
            fullWalk = true,
            hasWatch = nothingWatched,
        )
        assertEquals(listOf("c", "b", "a"), outcome.videos.map { it.videoId })
    }

    // ---------------------------------------- the highlight stays put, spec section 5.3

    @Test
    fun `a video inserted above the highlight moves the highlight to the same video`() {
        val before = listOf(testVideo("b", day = 2), testVideo("a", day = 1))
        val after = listOf(testVideo("c", day = 3), testVideo("b", day = 2), testVideo("a", day = 1))

        assertEquals(1, refocusIndexAfterRefresh(before, after, focusedIndex = 0))
        assertEquals(2, refocusIndexAfterRefresh(before, after, focusedIndex = 1))
    }

    @Test
    fun `nothing moves when the focused video kept its place`() {
        val before = listOf(testVideo("b", day = 2), testVideo("a", day = 1))
        val after = listOf(testVideo("b", day = 2), testVideo("a", day = 1), testVideo("z", day = 0))

        assertNull(refocusIndexAfterRefresh(before, after, focusedIndex = 0))
    }

    @Test
    fun `nothing moves when nothing had the highlight`() {
        val before = listOf(testVideo("b", day = 2))
        val after = listOf(testVideo("c", day = 3))

        assertNull(refocusIndexAfterRefresh(before, after, focusedIndex = -1))
        assertNull(refocusIndexAfterRefresh(before, after, focusedIndex = 4))
        assertNull(refocusIndexAfterRefresh(before, emptyList(), focusedIndex = 0))
    }

    /**
     * The focused video is the one that vanished, so there is no same video to go to.
     *
     * The highlight stays where it is, clamped to the shorter list, which puts it on the card
     * that took the vanished one's place. Leaving the index alone let it name a video past the
     * end of the list, which both the focused-title line and the play action read. Found by
     * `/code-review`.
     */
    @Test
    fun `the focused video vanished, so the highlight stays on that place in the list`() {
        val before = listOf(testVideo("c", day = 3), testVideo("b", day = 2), testVideo("a", day = 1))
        val after = listOf(testVideo("c", day = 3), testVideo("a", day = 1))

        // The middle card went. The one below it takes its place and keeps the highlight.
        assertNull(refocusIndexAfterRefresh(before, after, focusedIndex = 1))
        // The last card went. The highlight comes back one place rather than off the end.
        assertEquals(1, refocusIndexAfterRefresh(before, listOf(testVideo("c", day = 3), testVideo("b", day = 2)), focusedIndex = 2))
    }

    // ------------------- the highlight follows a new landing before a key, build ticket 55

    /** The grid before: the screen landed on "b", its old Next unwatched, at index 1. */
    private val landedBefore = listOf(testVideo("c", day = 3), testVideo("b", day = 2), testVideo("a", day = 1))

    /** The grid after a refresh stored a newly listed video at the top. */
    private val withNewVideo = listOf(testVideo("new", day = 4, newlyListed = true)) + landedBefore

    @Test
    fun `with no key pressed the highlight moves to the new landing`() {
        // The new landing is the new video at index 0. The owner's report of 2026-10-03.
        assertEquals(
            HighlightAfterRefresh.Land(0),
            highlightAfterRefresh(landedBefore, withNewVideo, focusedIndex = 1, keyPressed = false, landingAfter = 0),
        )
    }

    @Test
    fun `with a key pressed the highlight stays on its video, exactly as before`() {
        // "b" moved from index 1 to index 2, and the highlight goes with it and nowhere else.
        assertEquals(
            HighlightAfterRefresh.Follow(2),
            highlightAfterRefresh(landedBefore, withNewVideo, focusedIndex = 1, keyPressed = true, landingAfter = 0),
        )
        // And a press that left the highlight where the list did not move is left alone.
        assertNull(
            highlightAfterRefresh(landedBefore, landedBefore, focusedIndex = 1, keyPressed = true, landingAfter = 0),
        )
    }

    @Test
    fun `with no key pressed and the same landing video, the highlight follows without a landing`() {
        // An older video arrived, so the landing is still "b", one place further on. Following
        // puts the highlight on the same card and does not scroll the grid.
        val withOlder = landedBefore + testVideo("older", day = 0)
        assertNull(highlightAfterRefresh(landedBefore, withOlder, focusedIndex = 1, keyPressed = false, landingAfter = 1))
        val withNewerNotNewlyListed = listOf(testVideo("new", day = 4)) + landedBefore
        assertEquals(
            HighlightAfterRefresh.Follow(2),
            highlightAfterRefresh(landedBefore, withNewerNotNewlyListed, focusedIndex = 1, keyPressed = false, landingAfter = 2),
        )
    }

    @Test
    fun `with no key pressed and no selection yet, the screen still lands on the new landing`() {
        // The opening landing had not reported focus yet when the refresh arrived.
        assertEquals(
            HighlightAfterRefresh.Land(0),
            highlightAfterRefresh(landedBefore, withNewVideo, focusedIndex = -1, keyPressed = false, landingAfter = 0),
        )
    }

    @Test
    fun `an unchanged list never lands, because the screen is composed with one`() {
        // The refresh effect runs once when the screen is first composed, with the same list on
        // both sides and no selection yet. The grid is landing by itself at that moment.
        assertNull(highlightAfterRefresh(landedBefore, landedBefore, focusedIndex = -1, keyPressed = false, landingAfter = 1))
        assertNull(highlightAfterRefresh(landedBefore, landedBefore.toList(), focusedIndex = 0, keyPressed = false, landingAfter = 1))
    }

    @Test
    fun `a list whose view counts alone moved never lands, even with no selection yet`() {
        // Build ticket 59, found by `/code-review`. The first read and a count-only full walk
        // reload an open screen so its cards show the new counts. With no selection reported
        // yet, the rule above would land, and the grid would scroll for nothing but counts.
        val counted = landedBefore.mapIndexed { i, video -> video.copy(viewCount = 100L + i) }
        assertNull(highlightAfterRefresh(landedBefore, counted, focusedIndex = -1, keyPressed = false, landingAfter = 1))
        assertNull(highlightAfterRefresh(landedBefore, counted, focusedIndex = 0, keyPressed = false, landingAfter = 1))
        assertNull(highlightAfterRefresh(landedBefore, counted, focusedIndex = 1, keyPressed = true, landingAfter = 1))
    }

    @Test
    fun `a moved count does not hide a new video from the landing rule`() {
        val counted = withNewVideo.map { it.copy(viewCount = 5L) }
        assertEquals(
            HighlightAfterRefresh.Land(0),
            highlightAfterRefresh(landedBefore, counted, focusedIndex = 1, keyPressed = false, landingAfter = 0),
        )
    }

    @Test
    fun `a screen that had not drawn a grid yet does not land, because the grid lands by itself`() {
        assertNull(highlightAfterRefresh(emptyList(), withNewVideo, focusedIndex = -1, keyPressed = false, landingAfter = 0))
    }

    @Test
    fun `a refresh that empties the Source asks for nothing, key or no key`() {
        assertNull(highlightAfterRefresh(landedBefore, emptyList(), focusedIndex = 1, keyPressed = false, landingAfter = 0))
        assertNull(highlightAfterRefresh(landedBefore, emptyList(), focusedIndex = 1, keyPressed = true, landingAfter = 0))
    }
}

/**
 * An unlisted video comes back on its own, with nothing the household has to do.
 *
 * **This is ticket 23's hardest criterion, and it can only be proved here.** A private video
 * becoming public again, or a premiere starting, is something YouTube does. There is no way to
 * stage either on a television, so no screenshot could ever show it. These tests are the proof.
 *
 * They are written across the two real seams together, because neither half says anything on
 * its own:
 *
 * - [mergeRefresh] takes everything but `isShort` from the fresh listing, so a video's
 *   `availability` and `liveBroadcastContent` are replaced by what YouTube said this time.
 * - `StoredVideo.isListed` is read fresh from those two fields every time, so it turns back to
 *   true the moment they change.
 *
 * **The shape is what makes it work, and the shape was chosen for this.** There is no stored
 * "hidden" flag anywhere, and `StoredVideo`'s own comment says why: a stored conclusion is the
 * one thing that could not undo itself. The only stored conclusion in the whole type is
 * `isShort`, which is right, because a Short never stops being one. See spec sections 4.3,
 * 4.4 and 5.2.
 */
class ComingBackTest {

    private val store = InMemorySourceStore()
    private val everythingWatched: (String) -> Boolean = { true }

    /** One full walk folded into what is stored, which is what a refresh does. */
    private fun walkSaw(fresh: List<StoredVideo>) {
        val outcome = mergeRefresh(
            stored = store.allVideos("UUx"),
            fresh = fresh,
            fullWalk = true,
            hasWatch = everythingWatched,
        )
        store.replaceVideos("UUx", outcome.videos)
    }

    private fun listedIds() = store.listedVideos("UUx").map { it.videoId }

    @Test
    fun `a video that stops being unavailable is listed again at the next walk`() {
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
                testVideo("normal", day = 1),
            ),
        )
        assertEquals(listOf("normal"), listedIds())

        // YouTube serves it again, so this walk's listing carries it like any other video.
        walkSaw(listOf(testVideo("gone", day = 3), testVideo("normal", day = 1)))

        assertEquals(listOf("gone", "normal"), listedIds())
    }

    @Test
    fun `a video that stops being upcoming is listed again at the next walk`() {
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("soon", day = 3, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
                testVideo("normal", day = 1),
            ),
        )
        assertEquals(listOf("normal"), listedIds())

        // The premiere has happened. It is an ordinary video now.
        walkSaw(listOf(testVideo("soon", day = 3), testVideo("normal", day = 1)))

        assertEquals(listOf("soon", "normal"), listedIds())
    }

    /** A walk that hears the same bad news again changes nothing. It must not come back. */
    @Test
    fun `a video that is still unavailable stays unlisted`() {
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
                testVideo("normal", day = 1),
            ),
        )

        // A full walk that no longer sees it at all. It has a Watch, so it is kept.
        walkSaw(listOf(testVideo("normal", day = 1)))

        assertEquals(listOf("normal"), listedIds())
        assertEquals(2, store.allVideos("UUx").size)
    }

    /**
     * What the household actually sees change: the badge on the rail card.
     *
     * The Source reads `ALL WATCHED` while the deleted video is hidden, and goes back to
     * `1 UNWATCHED` the moment YouTube serves it again. No household action in between.
     */
    @Test
    fun `the rail card picks the video back up with no household action`() {
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
                testVideo("normal", day = 1),
            ),
        )
        val watched = mapOf(
            "normal" to WatchState(
                videoId = "normal",
                watched = true,
                positionSeconds = null,
                completed = true,
                lastPlayedAt = null,
            ),
        )

        val before = sourceCard("UUx", null, store.listedVideos("UUx"), watched, null)
        assertEquals(0, before.unwatchedCount)
        assertNull(before.nextUnwatched)

        walkSaw(listOf(testVideo("gone", day = 3), testVideo("normal", day = 1)))

        val after = sourceCard("UUx", null, store.listedVideos("UUx"), watched, null)
        assertEquals(1, after.unwatchedCount)
        assertEquals("gone", after.nextUnwatched?.videoId)
    }

    // ------------------------------------- build ticket 56: storedVideosChanged

    /** The household Source's shape in small: dated videos, one tied pair, two undated. */
    private val sameRows = listOf(
        testVideo("newest", day = 5),
        testVideo("twinA", day = 3),
        testVideo("twinB", day = 3),
        testVideo("oldest", day = 1),
        testVideo("privateA", day = null, title = "", availability = Availability.UNAVAILABLE),
        testVideo("privateB", day = null, title = "", availability = Availability.UNAVAILABLE),
    )

    @Test
    fun `the same rows in the same order are unchanged`() {
        assertFalse(storedVideosChanged(stored = sameRows, merged = sameRows.toList()))
    }

    @Test
    fun `two videos with the same publish time in the other order are unchanged`() {
        val stored = sameRows.toMutableList().apply { add(1, removeAt(2)) }
        assertEquals(listOf("newest", "twinB", "twinA"), stored.take(3).map { it.videoId })
        assertFalse(storedVideosChanged(stored = stored, merged = sameRows))
    }

    @Test
    fun `two undated videos in the other order are unchanged`() {
        // The shape of two undated videos of a real Source on 2026-10-03, with made-up ids.
        val stored = sameRows.dropLast(2) + sameRows.takeLast(2).reversed()
        assertFalse(storedVideosChanged(stored = stored, merged = sameRows))
    }

    @Test
    fun `the same rows in any order at all are unchanged`() {
        assertFalse(storedVideosChanged(stored = sameRows.reversed(), merged = sameRows))
    }

    @Test
    fun `a finished premiere that stops being upcoming is a change, even with a tie in the other order`() {
        // The case `/code-review` found on an earlier ticket: nothing added, dropped or kept,
        // and the Source must still be rewritten, or `Next unwatched` skips the video for ever.
        val stored = sameRows.map {
            if (it.videoId == "twinA") it.copy(liveBroadcastContent = LiveBroadcastContent.UPCOMING) else it
        }.reversed()
        assertTrue(storedVideosChanged(stored = stored, merged = sameRows))
    }

    @Test
    fun `any one field of one row that differs is a change`() {
        val twinB = sameRows.single { it.videoId == "twinB" }
        val edits = listOf(
            twinB.copy(title = "An edited title"),
            twinB.copy(thumbnailUrl = "https://i.ytimg.com/vi/twinB/hqdefault.jpg"),
            twinB.copy(durationSeconds = 601),
            twinB.copy(videoPublishedAt = twinB.videoPublishedAt!!.plusSeconds(1)),
            twinB.copy(liveEndedAt = twinB.videoPublishedAt),
            twinB.copy(availability = Availability.UNAVAILABLE),
            twinB.copy(isShort = true),
            twinB.copy(newlyListed = true),
        )
        for (edited in edits) {
            val merged = sameRows.map { if (it.videoId == "twinB") edited else it }
            assertTrue(edited.toString(), storedVideosChanged(stored = sameRows.reversed(), merged = merged))
        }
    }

    @Test
    fun `a video added or dropped is a change`() {
        assertTrue(storedVideosChanged(stored = sameRows, merged = sameRows + testVideo("added", day = 9)))
        assertTrue(storedVideosChanged(stored = sameRows, merged = sameRows.drop(1)))
    }

    @Test
    fun `a repeated video id cannot hide a row that differs`() {
        // A Source never holds one id twice, but the answer must not depend on that. Found by
        // `/code-review`: a comparison by id map read these two lists as the same.
        val a1 = testVideo("a", day = 2)
        val a2 = testVideo("a", day = 2, title = "Another title")
        val b = testVideo("b", day = 1)
        assertTrue(storedVideosChanged(stored = listOf(a1, a2, b), merged = listOf(a2, b, b)))
    }

    @Test
    fun `a video swapped for another of the same count is a change`() {
        val merged = sameRows.dropLast(1) + testVideo("someoneElse", day = null, title = "")
        assertTrue(storedVideosChanged(stored = sameRows, merged = merged))
    }

    // ------------------------------------- build ticket 59: view counts

    @Test
    fun `view counts that moved, and nothing else, are not a change`() {
        // The counts move every day. Read as a change, every full walk would rewrite every
        // Source again, which is the work build ticket 56 took away.
        val stored = sameRows.mapIndexed { i, video -> video.copy(viewCount = 1_000L + i) }
        val merged = sameRows.mapIndexed { i, video -> video.copy(viewCount = 2_000L + i) }
        assertFalse(storedVideosChanged(stored = stored.reversed(), merged = merged))
    }

    @Test
    fun `a count that appeared or went away is not a change either`() {
        val counted = sameRows.map { it.copy(viewCount = 5L) }
        assertFalse(storedVideosChanged(stored = sameRows, merged = counted))
        assertFalse(storedVideosChanged(stored = counted, merged = sameRows))
    }

    @Test
    fun `a moved count does not hide another field that changed`() {
        val stored = sameRows.map { it.copy(viewCount = 1L) }
        val merged = sameRows.map {
            if (it.videoId == "twinB") it.copy(title = "An edited title", viewCount = 2L) else it.copy(viewCount = 2L)
        }
        assertTrue(storedVideosChanged(stored = stored, merged = merged))
    }

    @Test
    fun `the counts to write are the ones that differ, by video id`() {
        val stored = listOf(
            testVideo("same", day = 3).copy(viewCount = 10L),
            testVideo("moved", day = 2).copy(viewCount = 10L),
            testVideo("appeared", day = 1),
            testVideo("vanished", day = 4).copy(viewCount = 7L),
        )
        val merged = listOf(
            testVideo("same", day = 3).copy(viewCount = 10L),
            testVideo("moved", day = 2).copy(viewCount = 11L),
            testVideo("appeared", day = 1).copy(viewCount = 3L),
            // YouTube stopped sending a count: what it last said is no count, never zero.
            testVideo("vanished", day = 4),
        )
        assertEquals(
            mapOf("moved" to 11L, "appeared" to 3L, "vanished" to null),
            viewCountChanges(stored = stored, merged = merged),
        )
    }

    @Test
    fun `a video storage has no row for is not a count to write`() {
        // A count never adds a row. A video new to the Source makes the Source changed, and
        // `replaceVideos` writes it whole.
        val merged = listOf(testVideo("new", day = 9).copy(viewCount = 4L))
        assertTrue(viewCountChanges(stored = emptyList(), merged = merged).isEmpty())
    }

    @Test
    fun `unchanged counts write nothing`() {
        val rows = sameRows.map { it.copy(viewCount = 42L) }
        assertTrue(viewCountChanges(stored = rows.reversed(), merged = rows).isEmpty())
    }
}
