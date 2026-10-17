package local.youtubetv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The meta line draws one `Text` per part, and joining them back fails here.
 *
 * This is the test build ticket 24's closing notes asked for and could not write. That ticket
 * fixed a real defect on the household television: a Hebrew channel name sat between the number
 * `57` and the word `videos`, because one `Text` runs one bidirectional layout over everything
 * it holds and the Hebrew run pulled the digits out of the count.
 *
 *     PLAYLIST   57   <Hebrew name>   videos     the defect
 *     PLAYLIST   <Hebrew name>   57 videos       the fix
 *
 * **No JVM test in this project can see it.** `CandidateMetaTest` and `RailCardMetaTest` pin the
 * list of parts, and the screens do walk that list, but the drawing is where the defect lived.
 * A later session rewriting [MetaLine] as one joined `Text` would keep every one of those tests
 * green.
 *
 * What is asserted is the rule itself and not the appearance: **one text node per part**. A
 * joined line is one node, so both assertions below go red the moment the parts are joined,
 * whatever the join looks like. There is no need to reproduce the Hebrew reordering, which
 * depends on the font the set falls back to.
 */
@RunWith(AndroidJUnit4::class)
class MetaLineTest {

    @get:Rule
    val compose = createComposeRule()

    /** A real Hebrew channel name, a public radio channel, and a count. */
    private val parts = listOf("PLAYLIST", "גלגלץ", "57 videos")

    @Test
    fun eachPartIsItsOwnTextNode() {
        compose.setContent {
            MetaLine(parts = parts, shrinkingPart = 1, color = Color.White)
        }

        // One node carrying text, per part. A joined line is one node, so this counts 1.
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .assertCountEquals(parts.size)
    }

    @Test
    fun theCountIsWholeInsideOneNode() {
        compose.setContent {
            MetaLine(parts = parts, shrinkingPart = 1, color = Color.White)
        }

        // Exact text, not a substring. `57 videos` is one node's whole text only while the
        // parts are apart; joined, the only node's text is the whole line and this finds none.
        for (part in parts) {
            compose.onAllNodesWithText(part).assertCountEquals(1)
        }
    }
}
