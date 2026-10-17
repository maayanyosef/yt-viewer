package local.youtubetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * The one quiet line of small facts under a title, wherever this app draws one.
 *
 * There are two such lines: the badge, the channel and the count on a search result row and on
 * the add screen, and the Direction and the video count on a home rail card and in that card's
 * menu heading. They are drawn by this one Composable, so the rule below exists once.
 *
 * **One [Text] per part is the whole point, and joining them back is the defect.** A single
 * [Text] runs one bidirectional layout over everything it holds, so a Hebrew name between
 * `PLAYLIST` and `57 videos` pulls the digits out of the count and the household reads
 * `PLAYLIST 57 <Hebrew name> videos`. Each [Text] here gets its own run, and one run can never
 * reorder another. The owner saw it on the household television on 2026-09-20 and chose this over
 * dropping the name and over leaving it alone. Spec section 6.2 records it, section 7.1 points
 * at it, and ticket 24 built it.
 *
 * **No bidi control character is used.** One would work, and it hides the reason inside an
 * invisible character the next reader cannot see.
 *
 * It was two near-identical Composables until `/code-review` on ticket 20 said so. A rule that
 * lives in two places is a rule the next correction has to find twice.
 */
@Composable
fun MetaLine(
    parts: List<String>,
    /**
     * Which part gives way when the line is too wide, or null when none may.
     *
     * It gets `weight(1f, fill = false)`, and Compose measures the parts with no weight first,
     * so every other part is laid out at its full size. That is what keeps the count whole: a
     * long name ends in an ellipsis rather than pushing the count off the row.
     *
     * The callers decide it, because it is a rule about what the line means and both callers
     * pin theirs in a JVM test. See `CandidateMeta.shrinkingPart` and
     * `RailCardMeta.shrinkingPart`.
     */
    shrinkingPart: Int?,
    color: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(META_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            Text(
                text = part,
                modifier = if (index == shrinkingPart) {
                    Modifier.weight(1f, fill = false)
                } else {
                    Modifier
                },
                color = color,
                style = Fonts.Meta,
                maxLines = 1,
                softWrap = false,
                // Every part, not just the one that shrinks. A part cut without a mark reads
                // as a wrong number: `5813 videos` would become `5813 vide`. Found by
                // `/code-review` on ticket 24.
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The gap the parts sit apart by. Three spaces of `Fonts.Meta`. */
private val META_GAP = 10.dp
