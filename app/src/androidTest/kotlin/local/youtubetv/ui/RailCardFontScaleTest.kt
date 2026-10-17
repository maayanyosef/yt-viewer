package local.youtubetv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The date at the bottom of a rail card survives the largest text size.
 *
 * This is build ticket 21's defect, measured rather than budgeted. That card has a **fixed**
 * height and a picture that does not grow with the system text size, so the text under the
 * picture has only what the picture leaves. The first height chosen for it failed at about font
 * scale 1.08, and the date at the bottom of the card was lost on every card of the rail at once.
 *
 * **`RailCardLayoutTest` adds the same numbers up on the JVM, and this is not a second copy of
 * it.** That test checks a **budget**: the styles' declared `lineHeight` values, times the
 * scale, plus the gaps. A budget is a model of the layout, and nothing on the JVM can tell
 * whether the model is the layout. This one lets Compose measure the real text with the real
 * font and then asks what became of the date.
 *
 * **What it found is that the model is wrong about how the card fails**, which is the whole
 * reason this layer exists. Put the height back to the 318dp that ticket 21 rejected and the
 * date does **not** spill past the card's background: a `Column` of a fixed height hands its
 * last child only the room that is left, so Compose squeezes the date's own line instead. At
 * 318dp and font scale 1.3 the date measures 19dp where an unsqueezed line of the same style
 * measures 20dp, and its bottom edge lands exactly on the card's padding, to the pixel. So the
 * assertion that catches it is
 * [theDateIsNotSqueezed], which compares the date against the same style measured with nothing
 * squeezing it. [theDateStaysInsideTheCard] is kept beside it because squeezing is Compose's
 * behaviour today and not a promise, and a spill is what the household would actually see.
 *
 * **Density 2 and 320dpi are the household television's**, and the `googletv1080p` emulator's,
 * which is why the suite runs on that device. It is pinned here anyway, so the test says the
 * same thing wherever it runs.
 */
@RunWith(AndroidJUnit4::class)
class RailCardFontScaleTest {

    @get:Rule
    val compose = createComposeRule()

    /** Two physical pixels to the dp, which is what 320dpi means. */
    private val householdDensity = 2f

    /** One line of `Fonts.Meta` with nothing constraining it. See [theDateIsNotSqueezed]. */
    private val unsqueezedMeta = "unsqueezedMeta"

    private val card = testSourceCard()

    private val date: String get() = nextUnwatchedSlot(card).date!!

    @Composable
    private fun RailAt(fontScale: Float) {
        CompositionLocalProvider(LocalDensity provides Density(householdDensity, fontScale)) {
            Box {
                HomeScreen(state = HomeScreenState.Ready(listOf(card)))
                // The same style the date is drawn in, in the same composition and at the same
                // font scale, with the whole screen's height to grow into. It is what one line
                // of this style really measures when nothing is taking room away from it.
                //
                // It has to be measured here and not worked out from `Fonts.Meta`, because what
                // a line measures is the set's own font metrics and not the declared
                // `lineHeight`. Compose trims a single line to the font's ascent and descent,
                // so the two are different numbers.
                Text(
                    text = "0",
                    style = Fonts.Meta,
                    maxLines = 1,
                    modifier = Modifier.testTag(unsqueezedMeta),
                )
            }
        }
    }

    /**
     * The date is still a whole line of text at the largest text size the card is built for.
     *
     * **This is the assertion that goes red when the card is made too short.** Proved on
     * 2026-09-21 by putting [RailCardLayout.HEIGHT] back to the 318dp ticket 21 rejected: the
     * date then measures 19dp against a 20dp reference and this fails, while
     * [theDateStaysInsideTheCard] and every JVM assertion about the card's real layout still
     * pass.
     */
    @Test
    fun theDateIsNotSqueezed() {
        compose.setContent { RailAt(RailCardLayout.MAX_FONT_SCALE) }

        val dateHeight = compose.onNodeWithText(date).getUnclippedBoundsInRoot().height
        val wanted = compose.onNodeWithTag(unsqueezedMeta).getUnclippedBoundsInRoot().height

        assertTrue(
            "At font scale ${RailCardLayout.MAX_FONT_SCALE} the card's date measures " +
                "$dateHeight, and one unsqueezed line of the same style measures $wanted. " +
                "The card is ${RailCardLayout.HEIGHT} tall and has no room left for it.",
            dateHeight >= wanted,
        )
    }

    /** And it has not been pushed past the card's own padding, which is what a spill looks like. */
    @Test
    fun theDateStaysInsideTheCard() {
        compose.setContent { RailAt(RailCardLayout.MAX_FONT_SCALE) }

        val cardBottom = compose.onNodeWithTag(TestTags.railCard(card.sourceId))
            .getUnclippedBoundsInRoot().bottom
        val dateBottom = compose.onNodeWithText(date).getUnclippedBoundsInRoot().bottom

        assertTrue(
            "At font scale ${RailCardLayout.MAX_FONT_SCALE} the date ends at $dateBottom and " +
                "the card's padding ends at ${cardBottom - RailCardLayout.PADDING_Y}.",
            dateBottom <= cardBottom - RailCardLayout.PADDING_Y,
        )
    }
}
