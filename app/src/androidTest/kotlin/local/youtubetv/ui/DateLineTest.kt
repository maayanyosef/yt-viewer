package local.youtubetv.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The view count on a card's date line, measured. Build ticket 59.
 *
 * Two rules of the ticket are about measurement, which `docs/agents/testing.md` says no JVM test
 * can settle:
 *
 * - **A card with a count is exactly as tall as a card without one**, so no grid row grows
 *   taller. `CardOverlaysTest` holds the eye's fraction below 1 on the JVM, which is the budget.
 *   This measures the real card at the real density with the real font.
 * - **At a large font scale the count gives way before the date does, and the date is never
 *   cut.**
 *
 * **Density 2 is the household television's and the `googletv1080p` emulator's.** It is pinned
 * here so the test says the same thing wherever it runs.
 */
@RunWith(AndroidJUnit4::class)
class DateLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val householdDensity = 2f

    /** One column of the grid, at four columns on a 1920-pixel screen, is about this wide. */
    private val gridCardWidth = 420.dp

    private val counted = testVideo("counted", day = 30).copy(viewCount = 116_945L)
    private val uncounted = testVideo("uncounted", day = 30)

    /** The date both cards show. The two videos share a publish day. */
    private val date: String get() = exactDate(counted.videoPublishedAt)

    /** One line of the date with nothing taking room from it, to compare a card's date against. */
    private val wholeDate = "wholeDate"

    @Composable
    private fun Cards(fontScale: Float, width: Dp, watched: Boolean) {
        CompositionLocalProvider(LocalDensity provides Density(householdDensity, fontScale)) {
            val ink = screenInk()
            // Side by side, and not in a Column: two cards of about 286dp do not fit the 540dp
            // the screen is tall, and a Column squeezes the second one. That made the first
            // draft of this test compare a squeezed card against a whole one.
            Row {
                for (video in listOf(counted, uncounted)) {
                    VideoCard(
                        video = video,
                        watched = watched,
                        inProgressFraction = null,
                        thumbnails = NoThumbnails,
                        ink = ink,
                        register = { {} },
                        onHighlightChanged = {},
                        onSelect = {},
                        onHold = {},
                        modifier = Modifier.width(width),
                    )
                }
                Text(text = date, style = Fonts.Meta, maxLines = 1, modifier = Modifier.testTag(wholeDate))
            }
        }
    }

    private fun cardHeight(videoId: String): Dp =
        compose.onNodeWithTag(TestTags.videoCard(videoId)).getUnclippedBoundsInRoot().height

    /** The counted card's date. It is composed first, so it is the first node with that text. */
    private fun countedCardDate(): DpRect =
        compose.onAllNodes(hasText(date), useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

    /**
     * Whether a card's view count is laid out on its line at all.
     *
     * `WholeOrNothing` still composes a count that does not fit and simply never places it, so
     * the node can exist and be nowhere. Placed is what the household would see.
     */
    private fun countIsPlaced(videoId: String): Boolean =
        compose.onAllNodes(hasTestTag(TestTags.viewCount(videoId)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced }

    @Test
    fun theCountIsShownAfterTheDate() {
        compose.setContent { Cards(fontScale = 1f, width = gridCardWidth, watched = true) }

        val count = compose.onNodeWithTag(TestTags.viewCount(counted.videoId), useUnmergedTree = true)
        count.assertIsDisplayed()
        compose.onNodeWithText("116K", useUnmergedTree = true).assertIsDisplayed()
        val countLeft = count.getUnclippedBoundsInRoot().left
        val dateRight = countedCardDate().right
        assertTrue("The count starts at $countLeft, left of the date's end at $dateRight.", countLeft >= dateRight)
        assertTrue("A card with no count shows one.", !countIsPlaced(uncounted.videoId))
    }

    @Test
    fun aCardWithACountIsAsTallAsACardWithout() {
        compose.setContent { Cards(fontScale = 1f, width = gridCardWidth, watched = true) }
        assertEquals(cardHeight(uncounted.videoId), cardHeight(counted.videoId))
    }

    @Test
    fun aCardWithACountIsAsTallAsACardWithoutAtTheLargestFontScale() {
        compose.setContent { Cards(fontScale = 1.3f, width = gridCardWidth, watched = true) }
        assertEquals(cardHeight(uncounted.videoId), cardHeight(counted.videoId))
    }

    @Test
    fun atALargeFontScaleTheCountGivesWayAndTheDateIsWhole() {
        // A card narrow enough that the date and the watched marker fit and the count does not.
        compose.setContent { Cards(fontScale = 1.3f, width = 150.dp, watched = true) }

        assertTrue("The count is still on the line.", !countIsPlaced(counted.videoId))
        val whole = compose.onNodeWithTag(wholeDate).getUnclippedBoundsInRoot().width
        val onCard = countedCardDate().width
        assertTrue("The card's date is $onCard wide and a whole one is $whole.", onCard >= whole)
    }
}
