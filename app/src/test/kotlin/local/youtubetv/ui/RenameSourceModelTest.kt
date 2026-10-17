package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.StoredSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SOURCE = "UUx"
private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")

/**
 * Renaming one Source, and clearing the name again. Build ticket 27.
 *
 * Everything here runs on the JVM against [InMemorySourceStore], with no device and no quota,
 * which is the shape [AddSourceModel] and [SourceScreenModel] already have.
 */
class RenameSourceModelTest {

    private val store = InMemorySourceStore()
    private val lines = mutableListOf<String>()

    private fun addSource(title: String?, typedName: String? = null) {
        store.saveSource(StoredSource(SOURCE, title = title, lastWalkedAt = WALKED_AT))
        if (typedName != null) store.setTypedName(SOURCE, typedName)
    }

    private fun model(fetchedName: String?, typedName: String? = null) = RenameSourceModel(
        sourceId = SOURCE,
        fetchedName = fetchedName,
        typedName = typedName,
        store = store,
        log = { lines += it },
    )

    // ---- what the screen opens on -----------------------------------------------------------

    @Test
    fun `the field opens on the name the card is already showing`() {
        addSource(title = "TED")

        assertEquals("TED", model(fetchedName = "TED").state.value.typed)
    }

    @Test
    fun `the field opens on the typed name when there is one, not the fetched one`() {
        addSource(title = "TED", typedName = "Talks")

        val state = model(fetchedName = "TED", typedName = "Talks").state.value

        assertEquals("Talks", state.typed)
        assertEquals("TED", state.fetchedName)
        assertEquals("Talks", state.storedTypedName)
    }

    @Test
    fun `the field opens empty on a Source that has no name at all`() {
        // Its card is headed by its raw playlist id, and that is not a name to edit.
        addSource(title = null)

        assertEquals("", model(fetchedName = null).state.value.typed)
    }

    // ---- saving -----------------------------------------------------------------------------

    @Test
    fun `saving writes the typed name and leaves the fetched one alone`() = runBlocking {
        addSource(title = "TED")
        val model = model(fetchedName = "TED")

        model.typed("Talks")
        assertEquals("Talks", model.save()?.shown)

        val stored = store.source(SOURCE)!!
        assertEquals("Talks", stored.typedName)
        assertEquals("TED", stored.title)
    }

    @Test
    fun `saving an empty field clears the name and the fetched one comes back`() = runBlocking {
        addSource(title = "TED", typedName = "Talks")
        val model = model(fetchedName = "TED", typedName = "Talks")

        model.typed("")

        // The answer is what the card will be headed by, which is YouTube's name again.
        assertEquals("TED", model.save()?.shown)
        assertNull(store.source(SOURCE)!!.typedName)
        assertEquals("TED", store.source(SOURCE)!!.title)
    }

    @Test
    fun `clearing is the same write, reached in one press`() = runBlocking {
        addSource(title = "TED", typedName = "Talks")
        val model = model(fetchedName = "TED", typedName = "Talks")

        assertEquals("TED", model.clear()?.shown)

        assertNull(store.source(SOURCE)!!.typedName)
        // It does not touch the field, because the screen closes on it. What matters is the
        // write, and the write happened.
        assertEquals("Talks", model.state.value.typed)
    }

    @Test
    fun `clearing a Source YouTube never named gives its raw id back`() = runBlocking {
        // Honest rather than pretty. The next change check fills `title` and the card is named
        // without the household doing anything.
        addSource(title = null, typedName = "The one about cooking")
        val model = model(fetchedName = null, typedName = "The one about cooking")

        assertEquals(SOURCE, model.clear()?.shown)
    }

    @Test
    fun `a name of nothing but spaces clears rather than being stored`() = runBlocking {
        addSource(title = "TED", typedName = "Talks")
        val model = model(fetchedName = "TED", typedName = "Talks")

        model.typed("   ")

        assertEquals("TED", model.save()?.shown)
        assertNull(store.source(SOURCE)!!.typedName)
    }

    @Test
    fun `a typed name is trimmed on its way in`() = runBlocking {
        addSource(title = "TED")
        val model = model(fetchedName = "TED")

        model.typed("  Talks  ")

        assertEquals("Talks", model.save()?.shown)
        assertEquals("Talks", store.source(SOURCE)!!.typedName)
    }

    /**
     * The answer carries both facts, because the caller needs both.
     *
     * `MainActivity` patches the rail's one card with `typedName` and the log line says
     * `shown`. For a cleared name the two are different, which is the case that makes the pair
     * worth having. Found by `/code-review`.
     */
    @Test
    fun `the answer says what was written and what the card will show`() = runBlocking {
        addSource(title = "TED")
        val model = model(fetchedName = "TED")

        model.typed("Talks")
        assertEquals(RenamedSource(typedName = "Talks", shown = "Talks"), model.save())

        assertEquals(RenamedSource(typedName = null, shown = "TED"), model.clear())
    }

    // ---- what a rename must not touch -------------------------------------------------------

    @Test
    fun `a rename writes one column and no preference moves`() = runBlocking {
        // The trap ticket 20 named: two of the household's four Sources have never had
        // `autoplay_next` written at all, and a whole-row write would turn it off on both
        // with nothing the household did. Ticket 20 said three; `TED` and `Lofi Girl` hold
        // null and the two Hebrew ones hold 1 and 0, read off the set on 2026-09-21.
        store.saveSource(
            StoredSource(
                sourceId = SOURCE,
                title = "TED",
                lastWalkedAt = WALKED_AT,
                direction = local.youtubetv.data.Direction.OLDEST_FIRST,
                autoplayNext = true,
                addedAt = Instant.parse("2026-09-01T00:00:00Z"),
                remoteCount = 588,
            ),
        )
        val model = model(fetchedName = "TED")

        model.typed("Talks")
        model.save()

        val stored = store.source(SOURCE)!!
        assertEquals(WALKED_AT, stored.lastWalkedAt)
        assertEquals(local.youtubetv.data.Direction.OLDEST_FIRST, stored.direction)
        assertTrue(stored.autoplayNext)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), stored.addedAt)
        assertEquals(588, stored.remoteCount)
    }

    @Test
    fun `a later title backfill cannot undo a rename`() = runBlocking {
        addSource(title = null)
        val model = model(fetchedName = null)

        model.typed("The one about cooking")
        model.save()
        // The daily change check, landing afterwards. Its rule is over `title`, so it fills
        // the column the rename does not own and cannot reach the one it does.
        assertTrue(store.fillMissingTitle(SOURCE, "Lofi Girl"))

        assertEquals("The one about cooking", store.source(SOURCE)!!.typedName)
        assertEquals("Lofi Girl", store.source(SOURCE)!!.title)
    }

    // ---- a Source that went away -------------------------------------------------------------

    @Test
    fun `a Source removed between the menu and the save writes nothing and says so`() =
        runBlocking {
            val model = model(fetchedName = "TED")

            model.typed("Talks")

            assertNull(model.save())
            assertNull(store.source(SOURCE))
            assertTrue(lines.any { it.startsWith("Rename refused: $SOURCE") })
        }

    // ---- the logcat line ----------------------------------------------------------------------

    @Test
    fun `the log says what was written and what the card will show`() = runBlocking {
        addSource(title = "TED")
        val model = model(fetchedName = "TED")

        model.typed("Talks")
        model.save()
        model.clear()

        assertEquals(
            listOf(
                "Renamed UUx to Talks. Its card shows Talks.",
                "Renamed UUx: the typed name is cleared, so its card shows TED again.",
            ),
            lines,
        )
    }

    /**
     * The log reads the row back rather than reporting what it meant to write.
     *
     * It is the lesson `/code-review` wrote into `fillMissingTitle` on ticket 20: a caller that
     * logs its own intention will one day log a name the Source is not called. Here the two
     * differ whenever a name is cleared, which is half of what this screen does.
     */
    @Test
    fun `clearing logs the name the household will actually see, not the empty one`() =
        runBlocking {
            addSource(title = "TED", typedName = "Talks")

            model(fetchedName = "TED", typedName = "Talks").clear()

            assertTrue(lines.single().contains("shows TED again"))
            assertFalse(lines.single().contains("Talks"))
        }
}
