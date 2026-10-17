package local.youtubetv.ui

import local.youtubetv.data.Direction
import local.youtubetv.youtube.CandidateKind
import local.youtubetv.youtube.SourceCandidate
import local.youtubetv.youtube.YouTubeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every wording rule spec sections 6.1 to 6.6 fix, as a test rather than as a screenshot. */
class AddSourceTextTest {

    /**
     * Galgalatz, a real Israeli radio channel, written as escapes on purpose.
     *
     * It is the channel from a screenshot of the Source screen in ticket 12,
     * and it is Hebrew end to end, which
     * is what makes the meta line reorder. Escapes rather than the letters themselves, because
     * Hebrew inside a Kotlin line makes an editor draw the rest of that line out of order.
     */
    private val HEBREW_CHANNEL = "\u05D2\u05DC\u05D2\u05DC\u05E5"

    /**
     * `Channel 12`, an Israeli television channel, written as escapes for the same reason.
     *
     * It is here because a Hebrew name is allowed to carry digits of its own. A test that
     * forbade letters and digits in one part would fail on this name while nothing at all was
     * broken. Found by `/code-review`.
     */
    private val HEBREW_CHANNEL_WITH_A_NUMBER = "\u05E2\u05E8\u05D5\u05E5 12"

    private fun candidate(kind: CandidateKind) = SourceCandidate(
        kind = kind,
        playlistId = "PLx",
        channelId = null,
        title = "Something",
        handle = null,
        thumbnailUrl = null,
        videoCount = null,
    )

    @Test
    fun `the first-run page names all three add paths`() {
        assertEquals("spec section 6.1 names three", 3, FIRST_RUN_PATHS.size)
        assertTrue("the link path", FIRST_RUN_PATHS.any { it.contains("link") })
        assertTrue("the handle path", FIRST_RUN_PATHS.any { it.contains("@handle") })
        // Ticket 12 builds this one. The page names it anyway, because the page is about what
        // the household can do and a path left off would have to be untaught later.
        assertTrue("the search path", FIRST_RUN_PATHS.any { it.contains("name of a channel") })
    }

    @Test
    fun `nothing on the add screen mentions Shorts`() {
        val everything = listOf(
            FIRST_RUN_TITLE,
            FIRST_RUN_LEAD,
            ADD_A_SOURCE,
            ADD_A_SOURCE_META,
            ADD_TITLE,
            ADD_FIELD_HINT,
            ADD_FIND,
            ADD_LOOKING,
            ADD_PICK_ONE,
            ALL_UPLOADS,
            ADD_CONFIRM_TITLE,
            ADD_DIRECTION_LABEL,
            ADD_CONFIRM,
            ADD_CONFIRM_DETAIL,
            ALREADY_ADDED_TITLE,
            ADD_TRY_SOMETHING_ELSE,
            ADD_NOT_FOUND,
            ADD_UNREADABLE,
            SEARCH_QUOTA_EXHAUSTED,
            searchResultsTitle("anything"),
            searchFoundNothing("anything"),
        ) + FIRST_RUN_PATHS

        // Spec section 4.3: Shorts are always hidden, never counted, and there is no toggle to
        // put on this screen.
        for (line in everything) {
            assertTrue("\"$line\" names Shorts", !line.contains("Short", ignoreCase = true))
        }
    }

    @Test
    fun `a channel row and a playlist row carry different badges`() {
        assertEquals("CHANNEL", candidateBadge(candidate(CandidateKind.CHANNEL)))
        assertEquals("PLAYLIST", candidateBadge(candidate(CandidateKind.PLAYLIST)))
    }

    @Test
    fun `a count carries no thousands separator`() {
        assertEquals("5813 videos", candidateCount(5813))
        assertEquals("1 video", candidateCount(1))
        assertEquals("0 videos", candidateCount(0))
    }

    @Test
    fun `a count YouTube did not give says nothing at all`() {
        assertNull(candidateCount(null))
    }

    @Test
    fun `the count is one whole part, whatever the channel is called`() {
        // The defect ticket 24 fixes. One `Text` holding
        // `PLAYLIST   <Hebrew name>   57 videos` runs one bidirectional layout over all of it,
        // and the Hebrew run drags the neutral spaces and the digits with it, so the household
        // reads `PLAYLIST 57 <Hebrew name> videos`. The count is torn in half.
        //
        // The rule is **not** "no part holds both letters and digits". A real Hebrew channel
        // name carries digits of its own, `Channel 12` and `Kan 11` among them. The rule is
        // that the count's number and its word are one part, and that no other part holds any
        // of it. `AddSourceScreen.kt` draws one `Text` per part, so a part is a bidirectional
        // run and a run cannot reorder another.
        for (name in listOf(HEBREW_CHANNEL, HEBREW_CHANNEL_WITH_A_NUMBER, "Comedy Bar T.V")) {
            val meta = candidateMeta(
                candidate(CandidateKind.PLAYLIST).copy(handle = name, videoCount = 57),
            )
            assertEquals(listOf("PLAYLIST", name, "57 videos"), meta.parts)
            assertEquals("the count is one unbroken part", "57 videos", meta.count)
        }
    }

    @Test
    fun `only the channel name may shrink, so the count is never pushed off the row`() {
        val meta = candidateMeta(
            candidate(CandidateKind.PLAYLIST).copy(handle = HEBREW_CHANNEL, videoCount = 57),
        )
        val shrinking = meta.shrinkingPart
        assertEquals("the channel name is the part that gives way", 1, shrinking)
        assertEquals(meta.handle, meta.parts[shrinking!!])
    }

    @Test
    fun `a candidate YouTube told us least about still has a badge and nothing else`() {
        val meta = candidateMeta(candidate(CandidateKind.CHANNEL))
        assertEquals(listOf("CHANNEL"), meta.parts)
        assertNull("nothing on the line may give way when there is no name", meta.shrinkingPart)
    }

    @Test
    fun `newest first is offered first, and so is preselected`() {
        assertEquals(Direction.NEWEST_FIRST, ADD_DIRECTIONS.first())
        assertEquals(listOf(Direction.NEWEST_FIRST, Direction.OLDEST_FIRST), ADD_DIRECTIONS)
    }

    @Test
    fun `the duplicate refusal names the Source and says why`() {
        val said = alreadyAddedDetail("The curated one")
        assertTrue(said.contains("The curated one"))
        assertTrue("it says what would be lost", said.contains("split"))
        assertEquals("Open The curated one", openExisting("The curated one"))
    }

    @Test
    fun `a search that found nothing repeats the query and names the free path`() {
        val said = searchFoundNothing("example channel")
        assertTrue("the household has to see what the microphone heard", said.contains("example channel"))
        assertTrue("and be told what costs nothing to try", said.contains("link"))
    }

    @Test
    fun `the exhausted search bucket names the reset and the two paths that still work`() {
        assertTrue("it says how many there were", SEARCH_QUOTA_EXHAUSTED.contains("100"))
        assertTrue(
            "spec section 4.1 fixes the reset at midnight Pacific Time",
            SEARCH_QUOTA_EXHAUSTED.contains("midnight Pacific Time"),
        )
        // The whole point of the sentence: the other two add paths draw on a different pool
        // and are untouched, so the household must not read this as the app being broken.
        assertTrue(SEARCH_QUOTA_EXHAUSTED.contains("link"))
        assertTrue(SEARCH_QUOTA_EXHAUSTED.contains("@handle"))
    }

    @Test
    fun `the results heading repeats what was searched for`() {
        assertEquals("Results for \"ted talks\"", searchResultsTitle("ted talks"))
    }

    @Test
    fun `the field hint names all three ways in`() {
        assertTrue("a name", ADD_FIELD_HINT.contains("name"))
        assertTrue("a link", ADD_FIELD_HINT.contains("link"))
        assertTrue("a handle", ADD_FIELD_HINT.contains("@handle"))
    }

    @Test
    fun `every API failure gets one sentence the household can act on`() {
        for (reason in YouTubeApiException.Reason.entries) {
            val said = addFailureLine(reason)
            assertTrue("$reason said nothing", said.isNotBlank())
            assertTrue("$reason ended mid-sentence", said.endsWith("."))
        }
    }

    @Test
    fun `the remove confirmation names the Source and promises the history is kept`() {
        val said = removeSourceDetail("The curated one")
        assertTrue("it names the Source", said.contains("The curated one"))
        assertTrue("it promises the history", said.contains("Everything you have watched"))
        assertTrue("and that it comes back", said.contains("adding it again brings all of it back"))
    }

    @Test
    fun `the refusing line of the remove confirmation is not the acting one`() {
        assertTrue(REMOVE_NO.startsWith("No,"))
        assertTrue(REMOVE_YES.startsWith("Yes,"))
    }

    @Test
    fun `the cost of every add call is written down by endpoint`() {
        val said = addApiCallLine("channels.list", "@TED")
        assertTrue(said.contains("channels.list"))
        assertTrue("the unit is named, because quota is the project's tightest limit", said.contains("1 unit"))
    }

    @Test
    fun `the removal log line says what was kept as well as what went`() {
        val said = sourceRemovedLine("PLx", "The curated one")
        assertTrue(said.contains("cached videos are gone"))
        assertTrue(said.contains("Watch and every Bulk mark range it earned is kept"))
    }
}
