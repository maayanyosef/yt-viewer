package local.youtubetv.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The text sizes of the television screens, in one place.
 *
 * They are declared here rather than read from the Material typography because every size in
 * this app is tuned for a screen watched from about three metres away, and a type scale meant
 * for a phone held at arm's length is the wrong starting point. The sizes were settled on the
 * TCL on 2026-09-19.
 */
object Fonts {
    /** The Source name at the top of the Source screen. */
    val SourceTitle = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold)

    /**
     * The full title of the focused card, shown once under the Source name.
     *
     * A grid card only has room for about 20 characters, so this line is the only place a
     * whole title can be read.
     */
    val FocusedTitle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp)

    /** The one-line title on a grid card. */
    val CardTitle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)

    /** The publish date on a card, and the quiet status line. */
    val Meta = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)

    /** The chip row. */
    val Chip = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** The duration badge, and the badge on a video that cannot simply be played. */
    val Badge = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)

    /** The empty state and the failure message, which are read, not scanned. */
    val Body = TextStyle(fontSize = 20.sp, lineHeight = 26.sp)
}
