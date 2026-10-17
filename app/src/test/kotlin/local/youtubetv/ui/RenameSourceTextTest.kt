package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every word and every rule of the rename screen. Build ticket 27.
 *
 * The Hebrew here is built from code points rather than typed, because the editing tools on
 * this machine decode a \uXXXX escape into the character and the character itself does not
 * survive them. Ticket 24 hit the same wall and ticket 27's own notes repeat it.
 */
private fun hebrew(vararg codePoints: Int): String =
    codePoints.fold(StringBuilder()) { builder, code -> builder.appendCodePoint(code) }.toString()

/** `ערוץ לדוגמה`, Hebrew for "example channel", a made-up Source name. */
private val HEBREW_NAME = hebrew(0x05E2, 0x05E8, 0x05D5, 0x05E5, 0x0020, 0x05DC, 0x05D3, 0x05D5, 0x05D2, 0x05DE, 0x05D4)

private const val SOURCE = "UUx"

class RenameFieldStartTest {

    @Test
    fun `the typed name wins, because it is what the card is showing`() {
        assertEquals(
            "Talks",
            renameFieldStart(typedName = "Talks", fetchedName = "TED", sourceId = SOURCE),
        )
    }

    @Test
    fun `the fetched name is next, so correcting one character costs one character`() {
        assertEquals("TED", renameFieldStart(null, fetchedName = "TED", sourceId = SOURCE))
        assertEquals("TED", renameFieldStart("  ", fetchedName = "TED", sourceId = SOURCE))
    }

    /**
     * The raw playlist id is never put in the field, although the card is headed by it. It is
     * not a name, and putting it there would make the household delete twenty-four characters
     * with a remote before they could type.
     *
     * The fetched title really can be the playlist id, and then the field must still be empty.
     *
     * `AddSourceModel.add` stores `candidate.title.ifBlank { candidate.playlistId }`, so a
     * Source YouTube answered about with a blank title holds its own id in `sources.title`.
     * Found by `/code-review`.
     */
    @Test
    fun `a fetched title that is really the playlist id opens on an empty field`() {
        val playlist = "PLmadeUpPlaylistForTestsA000000002"

        assertEquals(
            "",
            renameFieldStart(typedName = null, fetchedName = playlist, sourceId = playlist),
        )
        // It is only the Source's own id that is refused. A Source honestly named after some
        // other playlist's id is a name, however odd, and the household typed nothing.
        assertEquals(
            playlist,
            renameFieldStart(typedName = null, fetchedName = playlist, sourceId = "UUother"),
        )
    }

    @Test
    fun `a Source with no name at all opens on an empty field`() {
        assertEquals("", renameFieldStart(null, fetchedName = null, sourceId = SOURCE))
        assertEquals("", renameFieldStart("", fetchedName = "", sourceId = SOURCE))
    }

    @Test
    fun `a Hebrew name reaches the field whole`() {
        assertEquals(
            HEBREW_NAME,
            renameFieldStart(HEBREW_NAME, fetchedName = "TED", sourceId = SOURCE),
        )
    }
}

/**
 * The quiet line that says what the card will end up showing.
 *
 * **The empty field is the case it exists for.** An empty field does not leave the card blank;
 * it gives back the name YouTube has. The owner chose that on 2026-09-20 and nothing else on the
 * screen says so.
 */
class RenamePreviewNameTest {

    @Test
    fun `what is typed is what the card will show`() {
        assertEquals("Talks", renamePreviewName("Talks", fetchedName = "TED", sourceId = "UUx"))
    }

    @Test
    fun `an empty field previews the name YouTube has`() {
        assertEquals("TED", renamePreviewName("", fetchedName = "TED", sourceId = "UUx"))
        assertEquals("TED", renamePreviewName("   ", fetchedName = "TED", sourceId = "UUx"))
    }

    @Test
    fun `an empty field on a Source YouTube never named previews the raw id`() {
        assertEquals("UUx", renamePreviewName("", fetchedName = null, sourceId = "UUx"))
    }

    @Test
    fun `the preview is trimmed the same way the store trims`() {
        // Otherwise the household would be promised a name with a space in front of it.
        assertEquals("Talks", renamePreviewName("  Talks  ", fetchedName = "TED", sourceId = "UUx"))
    }

    /** It goes through `sourceNameOrId`, so it cannot disagree with what the rail draws. */
    @Test
    fun `the preview is the rail's own rule and not a second copy of it`() {
        for (typed in listOf("Talks", "", "   ", HEBREW_NAME)) {
            for (fetched in listOf("TED", null)) {
                assertEquals(
                    sourceNameOrId(
                        local.youtubetv.data.StoredSource.typedNameOfInput(typed),
                        fetched,
                        "UUx",
                    ),
                    renamePreviewName(typed, fetched, "UUx"),
                )
            }
        }
    }
}

class RenameClearEnabledTest {

    @Test
    fun `it is live only when there is a stored name to take away`() {
        assertTrue(renameClearEnabled("Talks"))
        assertFalse(renameClearEnabled(null))
        assertFalse(renameClearEnabled(""))
        assertFalse(renameClearEnabled("   "))
    }
}

class RenameLogLineTest {

    @Test
    fun `a rename names what was written and what the card shows`() {
        assertEquals(
            "Renamed UUx to Talks. Its card shows Talks.",
            renameLogLine("UUx", written = "Talks", shown = "Talks"),
        )
    }

    @Test
    fun `a clearing names the name that came back, which is a different fact`() {
        assertEquals(
            "Renamed UUx: the typed name is cleared, so its card shows TED again.",
            renameLogLine("UUx", written = null, shown = "TED"),
        )
    }

    @Test
    fun `a refusal says why`() {
        assertEquals(
            "Rename refused: UUx has no stored row, so there is no name to write.",
            renameRefusedLine("UUx"),
        )
    }
}
