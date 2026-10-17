package local.youtubetv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Landing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refresh that adds a video leaves the highlight on the video it was on.
 *
 * This is **composition identity**, and it is the one thing on this list that a reader cannot
 * see by reading the code. A `LazyVerticalGrid` rebuilds an item whose key changed, and a
 * rebuilt node is a new node, so the highlight it was holding is gone. The grid here is keyed
 * by `video.videoId`, which does not change when the list grows, so a card that moves down a
 * place keeps its own node and keeps the highlight by itself.
 *
 * **Build ticket 33 shipped the other version of this**, keyed on a value that changes when one
 * video is added, and every refresh destroyed the node holding the highlight. Spec section 5.3
 * is the rule: a refresh is applied at once and the focused video keeps focus, so the list moves
 * around it. A refresh is not something the household did, so it may not move the highlight at
 * all.
 *
 * Nothing about this is visible to a JVM test. `refocusIndexAfterRefresh` is unit-tested and is
 * only half of it: that function re-anchors the screen's **index**, and what keeps the
 * **highlight** is Compose reusing the node, which needs Compose.
 */
@RunWith(AndroidJUnit4::class)
class GridRefreshFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val before = testSourceScreen(watchedUpTo = 8)

    /** Where the Source opens: the first gap after the nine watched videos. */
    private val openedOn = before.videos[9]

    /** The same Source after a refresh found one video newer than everything it had. */
    private val after = before.copy(
        videos = listOf(testVideo("fresh", day = 500, title = "Video that just arrived")) +
            before.videos,
    )

    private val state = mutableStateOf<SourceScreenState>(before)

    @Test
    fun aRefreshDoesNotMoveTheHighlight() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = state.value,
                thumbnails = NoThumbnails,
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            compose
                .onAllNodes(hasTestTag(TestTags.videoCard(openedOn.videoId)) and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // The refresh. Every card after the new one moves down a place.
        compose.runOnUiThread { state.value = after }
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.videoCard(openedOn.videoId)).assertIsFocused()

        // And the screen's own idea of which video is selected moved with it, which is what
        // `refocusIndexAfterRefresh` is for. The title is on the card and on the focused-title
        // line under the Source name, so two nodes carry it and neither is the new video's.
        compose.onAllNodesWithText(openedOn.title).assertCountEquals(2)
    }
}

/** Long enough for the grid's own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L
