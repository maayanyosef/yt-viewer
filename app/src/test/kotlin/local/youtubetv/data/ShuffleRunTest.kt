package local.youtubetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shuffle run, as plain functions. Build ticket 69, rule 4 and rule 8.
 *
 * Every test plays a run the way the player does: ask [ShuffleRun.next], then record the answer
 * with [ShuffleRun.played].
 */
class ShuffleRunTest {

    private val ten = (1..10).map { "v$it" }

    /** Plays [count] videos of the run by Shuffle, and returns the run and what played. */
    private fun play(run: ShuffleRun, playable: List<String>, count: Int): Pair<ShuffleRun, List<String>> {
        var at = run
        val order = mutableListOf<String>()
        repeat(count) {
            val next = at.next(playable) ?: return at to order
            order += next
            at = at.played(next, playable)
        }
        return at to order
    }

    @Test
    fun `a run plays every playable video once, in random order, before any plays again`() {
        val (_, order) = play(ShuffleRun(seed = 42), ten, 10)

        assertEquals(ten.toSet(), order.toSet())
        assertEquals(10, order.distinct().size)
        // Random, not Direction. One seed could land on the list's own order by chance, so the
        // claim is made over several.
        val orders = (1L..5L).map { seed -> play(ShuffleRun(seed), ten, 10).second }
        assertTrue(orders.any { it != ten })
        assertTrue(orders.distinct().size > 1)
    }

    @Test
    fun `the next video is stable until something plays`() {
        val run = ShuffleRun(seed = 7, played = listOf("v3"))

        assertEquals(run.next(ten), run.next(ten))
        assertEquals(run.next(ten), run.copy().next(ten.reversed()))
    }

    @Test
    fun `a video a walk lists during the run joins it among the videos not yet played`() {
        val (half, firstHalf) = play(ShuffleRun(seed = 3), ten, 5)
        val listedLater = ten + "new"

        val (_, secondHalf) = play(half, listedLater, 6)

        assertTrue("new" in secondHalf)
        assertEquals(listedLater.toSet(), (firstHalf + secondHalf).toSet())
        assertEquals(11, (firstHalf + secondHalf).distinct().size)
    }

    @Test
    fun `a newly listed video lands at a random place, not always first or last`() {
        val places = (1L..40L).map { seed ->
            val (half, _) = play(ShuffleRun(seed), ten, 5)
            play(half, ten + "new", 6).second.indexOf("new")
        }
        assertTrue(places.distinct().size > 2)
    }

    @Test
    fun `a video that stops being playable is skipped, and the run does not end for it`() {
        val (half, firstHalf) = play(ShuffleRun(seed = 11), ten, 4)
        val left = ten - firstHalf.toSet()
        val gone = left.first()

        val (_, rest) = play(half, ten - gone, 5)

        assertTrue(gone !in rest)
        assertEquals((left - gone).toSet(), rest.toSet())
    }

    @Test
    fun `a finished run starts a new one at once, whose first video is not the old last`() {
        for (seed in 1L..200L) {
            val (finished, order) = play(ShuffleRun(seed), ten, 10)
            val first = finished.next(ten)
            assertNotEquals("seed $seed", order.last(), first)
            // The new run is a whole new pass: ten more plays cover every video again.
            val (_, again) = play(finished, ten, 10)
            assertEquals(ten.toSet(), again.toSet())
            assertNotEquals(order.last(), again.first())
        }
    }

    @Test
    fun `a finished run counts as the new one, which has played nothing`() {
        val (finished, _) = play(ShuffleRun(seed = 5), ten, 10)

        assertEquals(0, finished.playedCount(ten))
        assertEquals(10, finished.played.size)
    }

    @Test
    fun `a Source with one playable video has nothing after it`() {
        val (finished, order) = play(ShuffleRun(seed = 1), listOf("only"), 1)

        assertEquals(listOf("only"), order)
        assertNull(finished.next(listOf("only")))
    }

    @Test
    fun `next beside a video on screen that is not yet played skips it, and renews past it`() {
        val (almost, order) = play(ShuffleRun(seed = 9), ten, 9)
        val lastOne = (ten - order.toSet()).single()

        val after = almost.next(ten, besides = lastOne)

        // The run renews past the video on screen, and the new run does not start with it.
        assertNotEquals(lastOne, after)
        assertTrue(after in ten)
    }

    @Test
    fun `a card pressed by hand counts as played, so Shuffle does not pick it again`() {
        val run = ShuffleRun(seed = 21)
        val shufflesFirst = run.next(ten)!!
        val byHand = ten.first { it != shufflesFirst }

        val (_, order) = play(run.played(byHand, ten), ten, 9)

        assertTrue(byHand !in order)
        assertEquals((ten - byHand).toSet(), order.toSet())
    }

    @Test
    fun `a video played again by hand moves to the end and is counted once`() {
        val run = ShuffleRun(seed = 1, played = listOf("v1", "v2", "v3"))

        val again = run.played("v1", ten)

        assertEquals(listOf("v2", "v3", "v1"), again.played)
        assertEquals(3, again.playedCount(ten))
    }

    @Test
    fun `previous walks the run back in the order the videos played`() {
        val run = ShuffleRun(seed = 1, played = listOf("a", "b", "c"))

        val back1 = run.previous(onScreen = "c", at = null)
        assertEquals(ShuffleBack.To("b", 1), back1)
        val back2 = run.previous(onScreen = "b", at = (back1 as ShuffleBack.To).at)
        assertEquals(ShuffleBack.To("a", 0), back2)
        assertEquals(ShuffleBack.FirstOfRun, run.previous(onScreen = "a", at = 0))
    }

    @Test
    fun `previous from a video not yet in the run goes to the newest play`() {
        val run = ShuffleRun(seed = 1, played = listOf("a", "b"))

        assertEquals(ShuffleBack.To("b", 1), run.previous(onScreen = "pending", at = null))
    }

    @Test
    fun `previous steps over a video that is no longer playable`() {
        val run = ShuffleRun(seed = 1, played = listOf("a", "gone", "c"))

        assertEquals(ShuffleBack.To("a", 0), run.previousPlayable("c", null, listOf("a", "c")))
        assertEquals(ShuffleBack.FirstOfRun, run.previousPlayable("c", null, listOf("c")))
    }

    @Test
    fun `previous on the run's first video says so`() {
        assertEquals(ShuffleBack.FirstOfRun, ShuffleRun(1, listOf("a")).previous("a", null))
        assertEquals(ShuffleBack.FirstOfRun, ShuffleRun(1).previous("x", null))
    }

    @Test
    fun `a new run started early forgets what the old run played, and avoids its last video`() {
        val old = ShuffleRun(seed = 1, played = listOf("v1", "v2", "v3"))

        val fresh = ShuffleRun.fresh(seed = 99, after = old, playable = ten)

        assertEquals(emptyList<String>(), fresh.played)
        assertEquals("v3", fresh.avoidFirst)
        assertNotEquals("v3", fresh.next(ten))
        assertEquals(0, fresh.playedCount(ten))
    }

    @Test
    fun `a run survives being written down and read back`() {
        val run = ShuffleRun(seed = Long.MIN_VALUE + 3, played = listOf("a", "b"), avoidFirst = "z")

        assertEquals(run, shuffleRunOfStored(run.stored()))
        assertEquals(ShuffleRun(5), shuffleRunOfStored(ShuffleRun(5).stored()))
    }

    @Test
    fun `a stored run this build cannot read is no run`() {
        assertNull(shuffleRunOfStored(null))
        assertNull(shuffleRunOfStored(""))
        assertNull(shuffleRunOfStored("not json"))
        assertNull(shuffleRunOfStored("""{"seed":"abc"}"""))
    }

    @Test
    fun `the key is fixed, so a stored seed means the same order in every build`() {
        // Written down once. A change to [shuffleKey] would silently reshuffle every
        // household's run, so this number must never be edited to make a test pass.
        // Worked out independently of the Kotlin, by the same FNV-1a and SplitMix64 steps.
        assertEquals(6376178516472316934L, shuffleKey(1, "dQw4w9WgXcQ"))
        assertNotEquals(shuffleKey(1, "a"), shuffleKey(2, "a"))
        assertNotEquals(shuffleKey(1, "a"), shuffleKey(1, "b"))
    }

    @Test
    fun `only playable videos are picked`() {
        val videos = listOf(
            testVideo("a", day = 1),
            testVideo("short", day = 2, isShort = true),
            testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
            testVideo("b", day = 4),
        )

        assertEquals(listOf("a", "b"), shufflePlayable(videos))
    }
}
