package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a store must follow, checked against [InMemorySourceStore].
 *
 * `SqliteSourceStore` implements the same rules in SQL and is checked on the device, because
 * a unit test cannot open an Android database. The ordering and the Shorts filter live in
 * both places, so the two are written to match and are compared by eye when either changes.
 */
class SourceStoreTest {

    private val store = InMemorySourceStore()

    @Test
    fun `an unknown Source has no videos and no record`() {
        assertTrue(store.listedVideos("UUx").isEmpty())
        assertNull(store.source("UUx"))
    }

    @Test
    fun `listed videos come back newest first`() {
        store.replaceVideos(
            "UUx",
            listOf(testVideo("old", day = 1), testVideo("new", day = 9), testVideo("mid", day = 5)),
        )
        assertEquals(listOf("new", "mid", "old"), store.listedVideos("UUx").map { it.videoId })
    }

    @Test
    fun `Shorts are stored but never listed`() {
        store.replaceVideos(
            "UUx",
            listOf(testVideo("normal", day = 1), testVideo("short", day = 2, isShort = true)),
        )
        assertEquals(listOf("normal"), store.listedVideos("UUx").map { it.videoId })
    }

    /**
     * **This test said the opposite until ticket 23**, and the rule really changed.
     *
     * It read "unavailable and upcoming videos are listed, because they are shown with a
     * marker", which is what spec section 4.4 asked for until 2026-09-20. The owner then chose to
     * hide both, exactly as a Short is hidden. See spec sections 4.3 and 4.4.
     */
    @Test
    fun `unavailable and upcoming videos are stored but never listed`() {
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
                testVideo("premiere", day = 2, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
                testVideo("normal", day = 1),
            ),
        )
        assertEquals(listOf("normal"), store.listedVideos("UUx").map { it.videoId })
        // Stored, not forgotten. A later walk is what brings either of them back.
        assertEquals(3, store.allVideos("UUx").size)
    }

    /** A live stream that is running now is playable, so it stays on the grid. */
    @Test
    fun `a live stream is still listed`() {
        store.replaceVideos(
            "UUx",
            listOf(testVideo("live", day = 2, liveBroadcastContent = LiveBroadcastContent.LIVE)),
        )
        assertEquals(listOf("live"), store.listedVideos("UUx").map { it.videoId })
    }

    @Test
    fun `a video with no date sorts last`() {
        store.replaceVideos("UUx", listOf(testVideo("undated", day = null), testVideo("dated", day = 1)))
        assertEquals(listOf("dated", "undated"), store.listedVideos("UUx").map { it.videoId })
    }

    @Test
    fun `a second walk replaces the list rather than adding to it`() {
        store.replaceVideos("UUx", listOf(testVideo("a", day = 1), testVideo("b", day = 2)))
        store.replaceVideos("UUx", listOf(testVideo("c", day = 3)))
        assertEquals(listOf("c"), store.listedVideos("UUx").map { it.videoId })
    }

    @Test
    fun `one Source's videos never leak into another`() {
        store.replaceVideos("UUa", listOf(testVideo("a", sourceId = "UUa")))
        store.replaceVideos("UUb", listOf(testVideo("b", sourceId = "UUb")))
        assertEquals(listOf("a"), store.listedVideos("UUa").map { it.videoId })
        assertEquals(listOf("b"), store.listedVideos("UUb").map { it.videoId })
    }

    @Test
    fun `a saved Source keeps the time it was walked`() {
        val at = Instant.parse("2026-09-19T12:00:00Z")
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = at))
        assertEquals(at, store.source("UUx")?.lastWalkedAt)
    }

    @Test
    fun `a Source that stores nothing is still remembered as walked`() {
        // An empty playlist, or a channel that posts nothing but Shorts. The record is what
        // says the walk happened, not the video count.
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = Instant.EPOCH))
        store.replaceVideos("UUx", emptyList())
        assertTrue(store.listedVideos("UUx").isEmpty())
        assertNull(store.source("UUx")!!.title)
    }
}

/**
 * Direction, as the Source store holds it.
 *
 * The SQLite store answers the same questions with an `ORDER BY` and an `UPDATE`, and every
 * rule that decides an answer is here rather than in the SQL. See `SqliteSourceStore`.
 */
class SourceDirectionStoreTest {

    private val store = InMemorySourceStore()

    private val videos = listOf(
        testVideo("newest", day = 9),
        testVideo("middle", day = 5),
        testVideo("oldest", day = 1),
    )

    /**
     * A walked Source holding [videos].
     *
     * The row comes first in every test here, because `setDirection` leaves a Source with no
     * row alone: a row holding only a Direction would look like a walked Source for ever.
     */
    private fun walked(sourceId: String = "UUx", videos: List<StoredVideo> = this.videos) {
        store.saveSource(StoredSource(sourceId, title = null, lastWalkedAt = Instant.EPOCH))
        store.replaceVideos(sourceId, videos)
    }

    @Test
    fun `a Source with no Direction stored lists newest first`() {
        walked()
        assertEquals(
            listOf("newest", "middle", "oldest"),
            store.listedVideos("UUx").map { it.videoId },
        )
    }

    @Test
    fun `setting the Direction turns the listing around`() {
        walked()
        store.setDirection("UUx", Direction.OLDEST_FIRST)
        assertEquals(
            listOf("oldest", "middle", "newest"),
            store.listedVideos("UUx").map { it.videoId },
        )
    }

    @Test
    fun `setting the Direction keeps the title and the walk time`() {
        val at = Instant.parse("2026-09-19T12:00:00Z")
        store.saveSource(StoredSource("UUx", title = "Example Channel", lastWalkedAt = at))

        store.setDirection("UUx", Direction.OLDEST_FIRST)

        val stored = store.source("UUx")!!
        assertEquals("Example Channel", stored.title)
        assertEquals(at, stored.lastWalkedAt)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
    }

    @Test
    fun `a Source with no row is left alone, rather than given one`() {
        // A row holding nothing but an id and a Direction would look to every later launch
        // like a Source that had already been walked, so the app would show an empty grid for
        // it for ever and never call YouTube. A `/code-review` finding.
        store.setDirection("UUx", Direction.OLDEST_FIRST)
        assertNull(store.source("UUx"))
    }

    @Test
    fun `an undated video stays last whichever way round the Source is`() {
        walked(videos = videos + testVideo("gone", day = null))

        assertEquals("gone", store.listedVideos("UUx").last().videoId)
        store.setDirection("UUx", Direction.OLDEST_FIRST)
        assertEquals("gone", store.listedVideos("UUx").last().videoId)
    }

    @Test
    fun `a Short is left out whichever way round the Source is`() {
        walked(videos = videos + testVideo("short", day = 3, isShort = true))
        store.setDirection("UUx", Direction.OLDEST_FIRST)

        assertEquals(
            listOf("oldest", "middle", "newest"),
            store.listedVideos("UUx").map { it.videoId },
        )
    }

    @Test
    fun `autoplay-next starts off and is remembered per Source`() {
        // The owner chose off on the household television on 2026-09-20. See ticket 18.
        val store = InMemorySourceStore()
        store.saveSource(StoredSource("UUx", null, null))
        store.saveSource(StoredSource("UUy", null, null))

        assertFalse(store.source("UUx")!!.autoplayNext)

        store.setAutoplayNext("UUx", true)

        // One Source's choice, and only that Source's. Spec section 8.6 makes it per Source.
        assertTrue(store.source("UUx")!!.autoplayNext)
        assertFalse(store.source("UUy")!!.autoplayNext)
    }

    @Test
    fun `a Source stored before the column existed still reads as on`() {
        // The default and the migration answer differently on purpose, and this is the test
        // that holds them apart. A stored null is a Source the household walked while the
        // default was on, so reading it as off would throw away a choice they were living
        // with. Ticket 18 changed the default and deliberately left this rule alone.
        assertTrue(StoredSource.autoplayNextOfStored(null))
        assertTrue(StoredSource.autoplayNextOfStored(1))
        assertFalse(StoredSource.autoplayNextOfStored(0))
    }

    @Test
    fun `autoplay-next leaves a Source with no row alone`() {
        // The presence of a row is what says a Source has been walked. A row holding nothing
        // but an id and a toggle would make the app show an empty grid for that Source for
        // ever and never walk it. The same rule as `setDirection`, found by `/code-review`.
        val store = InMemorySourceStore()
        store.setAutoplayNext("never-walked", false)
        assertNull(store.source("never-walked"))
    }
}

/**
 * The one writer of `sources.title` after a Source has been added. Ticket 20.
 *
 * All four of the household's own Sources were added by a build with no add screen, so every
 * one of them has a null title and every rail card is headed by its raw playlist id. The
 * change check of spec section 5.2 already reads a title it then drops, so filling it costs
 * nothing.
 *
 * **The "only when missing" rule lives in the store and not in the caller.** It is the
 * guarantee that a name the household types can never be written over, and a rule kept in one
 * caller is a rule the second caller does not know about.
 */
class SourceTitleStoreTest {

    private val store = InMemorySourceStore()

    private val walkedAt: Instant = Instant.parse("2026-09-19T12:00:00Z")

    @Test
    fun `a Source with no title takes the one it is given`() {
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = walkedAt))

        store.fillMissingTitle("UUx", "Example Channel")

        assertEquals("Example Channel", store.source("UUx")!!.title)
    }

    @Test
    fun `a Source that already has a title keeps it`() {
        store.saveSource(
            StoredSource("UUx", title = "The name the owner gave it", lastWalkedAt = walkedAt),
        )

        store.fillMissingTitle("UUx", "The name YouTube gave it")

        assertEquals("The name the owner gave it", store.source("UUx")!!.title)
    }

    @Test
    fun `a blank stored title counts as missing`() {
        // `railCardName` already reads a blank title as no title, so the two places agree.
        store.saveSource(StoredSource("UUx", title = "   ", lastWalkedAt = walkedAt))

        store.fillMissingTitle("UUx", "Example Channel")

        assertEquals("Example Channel", store.source("UUx")!!.title)
    }

    @Test
    fun `a blank name is never written`() {
        // A blank title stored over a null one changes nothing a household can see and takes
        // the Source out of reach of the next check, which would find it no longer missing.
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = walkedAt))

        store.fillMissingTitle("UUx", "   ")

        assertNull(store.source("UUx")!!.title)
    }

    @Test
    fun `it writes the one column and nothing else`() {
        // The same rule `markWalked` and `setRemoteCount` follow. Three of the household's
        // four Sources have never had `autoplay_next` written at all and live on the
        // migration rule that reads null as on, so a whole-row write here would turn
        // autoplay-next off on three Sources with nothing the household did.
        store.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = null,
                lastWalkedAt = walkedAt,
                direction = Direction.OLDEST_FIRST,
                autoplayNext = true,
                addedAt = Instant.parse("2026-09-01T00:00:00Z"),
                remoteCount = 588,
            ),
        )

        store.fillMissingTitle("UUx", "Example Channel")

        val stored = store.source("UUx")!!
        assertEquals("Example Channel", stored.title)
        assertEquals(walkedAt, stored.lastWalkedAt)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
        assertTrue(stored.autoplayNext)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), stored.addedAt)
        assertEquals(588, stored.remoteCount)
    }

    @Test
    fun `a Source with no row is left alone`() {
        // The presence of a row is what says the household added the Source. The same rule
        // `setDirection`, `setAutoplayNext`, `markWalked` and `setRemoteCount` all follow.
        store.fillMissingTitle("never-added", "Example Channel")

        assertNull(store.source("never-added"))
    }

    /**
     * The caller is told whether the write really happened. Found by `/code-review`.
     *
     * Without it a refresh would log `<id> had no name and is now "<title>"` for a Source it
     * had just been refused, which is a line saying a Source is called something it is not.
     */
    @Test
    fun `it says whether it wrote`() {
        store.saveSource(StoredSource("fresh", title = null, lastWalkedAt = walkedAt))
        store.saveSource(StoredSource("named", title = "Example Channel", lastWalkedAt = walkedAt))

        assertTrue(store.fillMissingTitle("fresh", "TED"))
        assertFalse(store.fillMissingTitle("fresh", "TED again"))
        assertFalse(store.fillMissingTitle("named", "TED"))
        assertFalse(store.fillMissingTitle("fresh", "   "))
        assertFalse(store.fillMissingTitle("never-added", "TED"))
    }
}

/**
 * The household's own name for a Source, and clearing it. Build ticket 27.
 *
 * **The two names are two columns and the point of this class is that they never meet.** A
 * rename writes `typed_name`; the title backfill of ticket 20 writes `title`. Clearing the
 * first is what brings the second back, which is the owner's decision of 2026-09-20, and it works
 * only because nothing ever wrote over it.
 */
class SourceTypedNameStoreTest {

    private val store = InMemorySourceStore()

    private val walkedAt: Instant = Instant.parse("2026-09-19T12:00:00Z")

    private fun named(title: String?) =
        StoredSource("UUx", title = title, lastWalkedAt = walkedAt)

    @Test
    fun `a typed name is stored beside the fetched one and not over it`() {
        store.saveSource(named("TED"))

        store.setTypedName("UUx", "Talks")

        val stored = store.source("UUx")!!
        assertEquals("Talks", stored.typedName)
        assertEquals("TED", stored.title)
    }

    @Test
    fun `clearing the typed name leaves the fetched one exactly where it was`() {
        store.saveSource(named("TED"))
        store.setTypedName("UUx", "Talks")

        store.setTypedName("UUx", null)

        val stored = store.source("UUx")!!
        assertNull(stored.typedName)
        assertEquals("TED", stored.title)
    }

    @Test
    fun `an empty or blank name clears rather than storing nothing visible`() {
        store.saveSource(named("TED"))

        store.setTypedName("UUx", "Talks")
        store.setTypedName("UUx", "")
        assertNull(store.source("UUx")!!.typedName)

        store.setTypedName("UUx", "Talks")
        store.setTypedName("UUx", "   ")
        assertNull(store.source("UUx")!!.typedName)
    }

    @Test
    fun `a typed name is trimmed`() {
        // A household typing on a television remote lands on the space key by accident, and a
        // leading space would push the whole card's name along by one character for ever.
        store.saveSource(named("TED"))

        store.setTypedName("UUx", "  Talks  ")

        assertEquals("Talks", store.source("UUx")!!.typedName)
    }

    /**
     * The interaction that makes this ticket subtle, in both directions.
     *
     * The backfill must not undo a rename, and clearing a rename must put the Source back in
     * the state the backfill can still fill.
     */
    @Test
    fun `the title backfill can neither reach nor be blocked by a typed name`() {
        // A Source every change check has failed on: no fetched title at all, but renamed.
        store.saveSource(named(null))
        store.setTypedName("UUx", "The one about cooking")

        // A later change check finds it still has no `title` and fills it, as it should.
        assertTrue(store.fillMissingTitle("UUx", "Lofi Girl"))

        // The rename is untouched, and the fetched name is now waiting underneath it.
        assertEquals("The one about cooking", store.source("UUx")!!.typedName)
        assertEquals("Lofi Girl", store.source("UUx")!!.title)

        // Clearing gives back the name the backfill fetched, with no API call.
        store.setTypedName("UUx", null)
        assertEquals("Lofi Girl", store.source("UUx")!!.title)
        assertNull(store.source("UUx")!!.typedName)
    }

    @Test
    fun `it writes the one column and nothing else`() {
        // The same rule `markWalked`, `setRemoteCount` and `fillMissingTitle` follow. Three of
        // the household's four Sources have never had `autoplay_next` written at all and live
        // on the migration rule that reads null as on, so a whole-row write here would turn
        // autoplay-next off on three Sources with nothing the household did.
        store.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "TED",
                lastWalkedAt = walkedAt,
                direction = Direction.OLDEST_FIRST,
                autoplayNext = true,
                addedAt = Instant.parse("2026-09-01T00:00:00Z"),
                remoteCount = 588,
            ),
        )

        store.setTypedName("UUx", "Talks")

        val stored = store.source("UUx")!!
        assertEquals("Talks", stored.typedName)
        assertEquals("TED", stored.title)
        assertEquals(walkedAt, stored.lastWalkedAt)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
        assertTrue(stored.autoplayNext)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), stored.addedAt)
        assertEquals(588, stored.remoteCount)
    }

    @Test
    fun `a Source with no row is left alone and says so`() {
        assertFalse(store.setTypedName("never-added", "Talks"))

        assertNull(store.source("never-added"))
    }

    @Test
    fun `it says whether it wrote`() {
        store.saveSource(named("TED"))

        assertTrue(store.setTypedName("UUx", "Talks"))
        assertTrue(store.setTypedName("UUx", null))
        assertFalse(store.setTypedName("never-added", "Talks"))
    }
}

/**
 * What a typed name becomes on its way into storage. Build ticket 27.
 *
 * It is a rule of its own so that `InMemorySourceStore` and `SqliteSourceStore` cannot answer
 * differently, in the same way `autoplayNextOfStored` is.
 */
class TypedNameOfInputTest {

    @Test
    fun `nothing at all is no name`() {
        assertNull(StoredSource.typedNameOfInput(null))
        assertNull(StoredSource.typedNameOfInput(""))
        assertNull(StoredSource.typedNameOfInput("   "))
        assertNull(StoredSource.typedNameOfInput("\t\n"))
    }

    @Test
    fun `a name is trimmed and kept`() {
        assertEquals("Talks", StoredSource.typedNameOfInput("Talks"))
        assertEquals("Talks", StoredSource.typedNameOfInput("  Talks  "))
        assertEquals("Two words", StoredSource.typedNameOfInput(" Two words "))
    }
}
