package local.youtubetv.ui

/**
 * Where the D-pad moves the highlight in the Source screen grid.
 *
 * This is the whole navigation rule, with no Compose and no Android in it, so every case is a
 * JVM unit test. The screen only translates key codes into a [GridMove] and applies the
 * answer.
 *
 * The grid shows **one flat chronological list** wrapped into rows, so the D-pad walks that
 * list and not the visual rows. Compose's own focus search stops at the row edge, which forced
 * the household to press Down and then Left three times to reach the next video in time order.
 * Found on the TCL, 2026-09-19. See spec section 7.2.
 */

/**
 * Four columns at 1080p.
 *
 * It was five until 2026-09-19. Five was chosen because four fitted only eight videos on
 * screen, and because the official YouTube app fits five in a channel. The owner then judged the
 * five-column grid on the television against a Source he actually watches and called it too
 * tight, so the count that fits is no longer the thing being optimised. See spec section 7.2.
 */
const val GRID_COLUMNS = 4

/** Rows fully on screen at 1080p. A third row peeks below them. */
const val SCREEN_ROWS = 2

/** One screen of videos, which is eight. This is what the channel rocker pages by. */
const val PAGE_STEP = GRID_COLUMNS * SCREEN_ROWS

/**
 * How far the household asked to move.
 *
 * [UP_HELD] is Up with the key held down, which jumps to the **first card in the list**.
 * Without it, returning to the top of a 126-video Source took about 25 presses.
 *
 * That card is the newest video in a newest-first Source and the **oldest** in an oldest-first
 * one, since ticket 06 gave each Source its own Direction. The jump is unchanged and still does
 * the right thing, because what it is for is reaching the top of the list and the chips above
 * it, not reaching any particular video. Spec section 7.2 was reworded to match.
 */
enum class GridMove {
    LEFT,
    RIGHT,
    UP,
    UP_HELD,
    DOWN,
    PAGE_UP,
    PAGE_DOWN,
}

/**
 * The index the highlight moves to, or null when this app does not take the key.
 *
 * Null matters as much as a number. Two keys are deliberately left to Compose:
 *
 * - `Left` and `Right` inside a row, where Compose already moves one card along.
 * - `Up` from the first row, which is how focus escapes upwards to the chips.
 *
 * @param from the index, in the listed videos, of the card the household is standing on.
 * @param count how many videos the grid lists.
 */
fun gridTarget(move: GridMove, from: Int, count: Int): Int? {
    if (count <= 0 || from < 0 || from >= count) return null

    /** Clamped, so a page key near either end still moves to the end rather than doing nothing. */
    fun step(delta: Int): Int? {
        val target = (from + delta).coerceIn(0, count - 1)
        return if (target == from) null else target
    }

    return when (move) {
        GridMove.LEFT -> if (from % GRID_COLUMNS == 0) step(-1) else null
        GridMove.RIGHT -> if (from % GRID_COLUMNS == GRID_COLUMNS - 1) step(1) else null
        GridMove.DOWN -> step(GRID_COLUMNS)
        GridMove.UP -> if (from >= GRID_COLUMNS) step(-GRID_COLUMNS) else null
        GridMove.UP_HELD -> if (from == 0) null else 0
        GridMove.PAGE_UP -> step(-PAGE_STEP)
        GridMove.PAGE_DOWN -> step(PAGE_STEP)
    }
}

/**
 * Whether a key delivery is the household holding the key, rather than tapping it.
 *
 * Android delivers the initial press with a repeat count of 0. If the key is still down after
 * the autorepeat delay of about 400 ms, it delivers the same key again with 1, then 2, and so
 * on. So **any** repeat count above zero means the key is being held.
 *
 * This used to ask for three repeats, and that was wrong in a way no rule below could fix.
 * The first three deliveries were then plain [GridMove.UP], one row each, which from the
 * third or fourth row is enough to climb out of the grid altogether. Focus reached the chips
 * before the jump ever fired. The owner found it on the television on 2026-09-19.
 */
fun isHeld(repeatCount: Int): Boolean = repeatCount > 0

/**
 * Whether the grid swallows a key that moved the highlight nowhere.
 *
 * Normally a key the grid cannot act on is passed on, and that is deliberate: it is how a
 * tapped `Up` on the first row reaches the chips above the grid.
 *
 * A **held** `Up` is different. Holding the key keeps delivering it long after the highlight
 * has arrived at the first card, and passing those on would carry focus straight out of the
 * grid and into the chips. So the grid keeps them, and the hold stops where it was aimed. One
 * further tap of `Up` still reaches the chips.
 *
 * Spec section 7.2 asks for both behaviours, and this is what lets them coexist:
 * "Holding `Up` jumps to the first card in the list" and "Pressing Up from the first row of
 * videos reaches the chips".
 */
fun gridKeepsKey(move: GridMove, from: Int, count: Int): Boolean =
    move == GridMove.UP_HELD && count > 0 && from in 0 until count

/**
 * The first card of the row holding [index].
 *
 * The grid scrolls to this, never to the card itself, so a row always lines up with the top of
 * the viewport. Leaving a row of context above looks better on paper, but Compose runs its own
 * scroll-into-view when the card then asks for focus, and the two scrolls fight: the result was
 * a top row with its thumbnails sliced off, seen on the TCL on 2026-09-19.
 */
fun rowStart(index: Int): Int = index - index % GRID_COLUMNS
