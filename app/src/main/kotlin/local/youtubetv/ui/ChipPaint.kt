package local.youtubetv.ui

/** What a chip's fill says about the chip. */
enum class ChipFill {
    /** No state of its own. Every chip on the row but `Autoplay` is always this. */
    QUIET,

    /** On. Today only `Autoplay` with autoplay-next on. */
    ACCENT,
}

/** Which [Ink] colour fills a chip's body. */
enum class ChipBody { SURFACE_RAISED, ACCENT, FOCUS_FILL }

/**
 * Which [Ink] colour draws the ring around a chip, or none at all.
 *
 * [FOCUS_FILL] is the one case where the ring is the whole of the focus, so the chip draws it
 * thicker than the other. See [chipPaint].
 */
enum class ChipRing { NONE, ACCENT, FOCUS_FILL }

/** Which [Ink] colour a chip's word takes. */
enum class ChipLabel { ON_SURFACE, ON_SURFACE_FAINT, ON_FOCUS }

/**
 * The three colours one chip of the Source screen's chip row is drawn with.
 *
 * The rule that picks them is [chipPaint]. It is here, with no Compose and no Android in it,
 * so every case is a JVM unit test, and the chip itself only reads [Ink] for the colour each
 * role names. Spec section 7.2.
 *
 * **One chip carries a state, and its fill is one of the two things that say so.** `Autoplay`
 * names its state in words as well, which is `autoplayChipLabel` in `SourceScreenText.kt`.
 *
 * - Ticket 18 cut the label to the single word `Autoplay` and left the fill as the only
 *   signal. The owner chose that on the household television on 2026-09-20.
 * - The same day he was shown the two fills and asked whether he could tell them apart from
 *   the sofa. **His answer was no.** Ticket 26 put the words back and **kept this fill
 *   unchanged**, so the chip carries both signals at once.
 *
 * One boolean feeds the word and the fill, so the two can never disagree.
 *
 * **Focus and state are two different things and are drawn in two different places**, which is
 * what this file exists to keep true. A chip is pressed while it holds the highlight, so a
 * focus treatment that filled over the state would hide it at the one moment it is being
 * changed, and pressing the chip would look as though it did nothing.
 */
data class ChipPaint(val body: ChipBody, val ring: ChipRing, val label: ChipLabel)

/**
 * The `Autoplay` chip's fill, which is one of the two things on it that say which state it is
 * in. The other is its word. See [ChipPaint].
 *
 * Spec section 8.6 makes autoplay-next per Source, and section 7.2 puts the chip last on the
 * row.
 */
fun autoplayChipFill(autoplayNext: Boolean): ChipFill =
    if (autoplayNext) ChipFill.ACCENT else ChipFill.QUIET

/**
 * The three colours a chip is drawn with, given what it says and where the highlight is.
 *
 * Two rules meet here:
 *
 * - **A quiet chip is filled when it takes the highlight**, not merely outlined. An outline
 *   alone was hard to find from the sofa, which is why `focus_fill` exists at all.
 * - **An accent chip keeps its fill and takes a ring instead.** Its body is already spoken
 *   for by its state, so focus has to be said somewhere else. The ring is the focus fill, so
 *   the brightest thing on the row is still the chip the household is standing on, and the
 *   chip draws that one ring thicker than the ring a quiet chip takes, because here it is
 *   carrying the focus on its own. Found by `/code-review` and checked on the television.
 *
 * A disabled chip is never focused, because it is not focusable, and it is never accent:
 * `Jump to last played` is the only chip that is ever disabled and it carries no state.
 */
fun chipPaint(fill: ChipFill, focused: Boolean, enabled: Boolean): ChipPaint = when {
    !enabled -> ChipPaint(ChipBody.SURFACE_RAISED, ChipRing.NONE, ChipLabel.ON_SURFACE_FAINT)
    fill == ChipFill.ACCENT -> ChipPaint(
        body = ChipBody.ACCENT,
        ring = if (focused) ChipRing.FOCUS_FILL else ChipRing.NONE,
        // Dark, because the accent is a bright blue and white on it cannot be read across a
        // room. It is the same ink a focused chip's word takes, for the same reason.
        label = ChipLabel.ON_FOCUS,
    )
    focused -> ChipPaint(ChipBody.FOCUS_FILL, ChipRing.ACCENT, ChipLabel.ON_FOCUS)
    else -> ChipPaint(ChipBody.SURFACE_RAISED, ChipRing.NONE, ChipLabel.ON_SURFACE)
}
