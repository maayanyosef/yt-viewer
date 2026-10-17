package local.youtubetv.ui

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import local.youtubetv.data.Direction
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.Period
import local.youtubetv.data.PeriodUnit
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchState
import local.youtubetv.data.isListed

/**
 * Every string the Source screen puts on the television.
 *
 * It is kept apart from the Composables so each rule is a JVM unit test with no device. The
 * one rule that matters most here is that a date is always an exact day. A relative age such
 * as "3 weeks ago" appears nowhere in this app. See spec section 7.3.
 */

private val EXACT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

private val UPDATED_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.UK)

/** A month Period, as `Jun 2025`. The same shape as [EXACT_DATE] without the day. */
private val PERIOD_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK)

/** What a card with no date says. A private or deleted video is the only one without one. */
private const val NO_DATE = "no date"

/**
 * The publish date, as `3 Jun 2025`.
 *
 * Never a relative age. The household browses by date, and "3 weeks ago" says nothing about
 * where a video sits in a Source that goes back fifteen years.
 */
fun exactDate(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
    if (instant == null) NO_DATE else EXACT_DATE.format(instant.atZone(zone))

/** What sits between the date and the eye on a card's date line. Build ticket 59. */
const val DATE_LINE_SEPARATOR = " · "

/** The units [roundedViewCount] steps through, smallest first, as YouTube writes them. */
private val VIEW_COUNT_UNITS = listOf(1_000L to "K", 1_000_000L to "M", 1_000_000_000L to "B")

/**
 * A video's view count, shortened, or null when there is no count. Build ticket 59 put the
 * count on the card. The owner asked for it on 2026-10-03: "number rounded".
 *
 * **The digits that are not shown are cut off, and nothing is ever rounded up.** Build ticket 60
 * replaced ticket 59's table, which rounded half up. The owner chose this on 2026-10-03: "cutoff like
 * youtube, as far as you remember. No need to check real youtube."
 *
 * - The rule is YouTube's as remembered. Nobody checked it against real YouTube pages.
 * - The name still says rounded, because cutting off is rounding down. Do not change it back to
 *   rounding half up to match the name.
 *
 * | Count | Shown |
 * | --- | --- |
 * | 845 | `845` |
 * | 1,099 | `1K` |
 * | 1,250 | `1.2K` |
 * | 9,960 | `9.9K` |
 * | 52,539 | `52K` |
 * | 999,600 | `999K` |
 * | 1,999,999 | `1.9M` |
 * | 1,500,000,000 | `1.5B` |
 *
 * - **Below 1,000 the count is shown in full.** Each unit after that is K, then M, then B.
 * - **One decimal place below 10 of a unit, none at 10 or above, and never a trailing `.0`.**
 * - **A count never moves to the next unit early**, because nothing rounds up. 9,999 is `9.9K`,
 *   and 999,999 is `999K`.
 * - **Null is no count, and the card then shows the date alone**, with no eye and no
 *   separator. A count of zero is a real count and shows `0`.
 *
 * It divides by a tenth of the unit rather than multiplying the count by ten, so no count a
 * `Long` can hold overflows. No test holds that. Multiplying by ten overflows only far above
 * 999B, where the rule names no unit and so defines no text.
 */
fun roundedViewCount(count: Long?): String? {
    if (count == null || count < 0) return null
    val (size, suffix) = VIEW_COUNT_UNITS.lastOrNull { count >= it.first }
        ?: return count.toString()
    val tenths = count / (size / 10)
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (whole >= 10 || fraction == 0L) "$whole$suffix" else "$whole.$fraction$suffix"
}

/**
 * The duration badge on the thumbnail, or null when there is no honest length to show.
 *
 * A stream that is live right now, and a premiere that has not started, both report `P0D`,
 * which parses to zero seconds. Showing those as `0:00` says the video is empty, which is
 * wrong. This closes the gap ticket 02 left, where a live stream showed a `0:00` length and
 * nothing else.
 *
 * Only one of the two still reaches a card. A live stream carries `LIVE NOW` in place of the
 * length; a premiere that has not started is no longer drawn at all, since ticket 23. See
 * [cardBadge] and spec section 4.4. The zero-second guard still covers both, because it is over
 * a length and knows nothing about why the length is zero.
 */
fun durationBadge(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

/**
 * The badge on the thumbnail, or null for an ordinary video.
 *
 * **`LIVE NOW` is the only badge left**, and it is there because a stream running right now is
 * a video the household can play, on a card the grid really draws. Every other reason a card
 * might have carried a badge has turned into a reason the card is not drawn at all.
 *
 * - There is no `SHORT` badge. A Short is never shown, so no card can carry one. Spec 4.3.
 * - There is **no `UNAVAILABLE` and no `UPCOMING` badge**, since 2026-09-20 and ticket 23. Both
 *   used to be drawn here. Spec section 4.4 now makes a private, deleted or upcoming video an
 *   unlisted video, so neither has a card for a badge to sit on. The branches are gone rather
 *   than left unreachable: a badge the app can never draw is a promise it no longer keeps.
 *   Spec sections 4.4 and 7.3 were amended to match.
 * - There is **no `WAS LIVE` badge** either, since 2026-09-19. A finished live stream is an
 *   ordinary video and is drawn as one. The badge covered part of the thumbnail and said
 *   almost nothing, which the Hasson Source made plain: that channel streams nearly
 *   everything, so nearly every card carried one. The owner had it removed after seeing it.
 *   Spec sections 7.3 and 4.4 were amended to match.
 *
 * It still returns a nullable string rather than a boolean, because the card draws one badge
 * from one place and a second one may yet be earnt.
 */
fun cardBadge(video: StoredVideo): String? = when {
    video.liveBroadcastContent == LiveBroadcastContent.LIVE -> "LIVE NOW"
    else -> null
}

/**
 * When the household watched the focused video, or null when it has no watch date.
 *
 * It is drawn in green beside the focused card's full title, and it is the **only** place a
 * watch date appears. Spec section 7.3 took the text line off the card itself: a label under
 * one card's date made that whole grid row taller and left a gap under the other three.
 *
 * Two rules from ADR 0001 meet here.
 *
 * - A video the household has since marked unwatched has no watch date, because it has no
 *   Watch. Spec section 7.2 says "its watch date in green **when it has one**".
 * - Otherwise the date is the newest `played` entry's own date, never today's. That matters
 *   from ticket 05 on: a Bulk mark written today can cover a video the household really
 *   played in June, and "Watched before September" would replace a fact with a vaguer one.
 *
 * A video marked watched but never played has no `played` date to show and so shows nothing.
 * That cannot happen yet, because the only way to be watched today is a manual mark, which
 * writes a `played` entry and so a date.
 */
fun watchDateLine(state: WatchState?, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (state == null || !state.watched) return null
    val playedAt = state.lastPlayedAt ?: return null
    return "Watched ${EXACT_DATE.format(playedAt.atZone(zone))}"
}

/**
 * The two strings of the quiet line beside the Source name.
 *
 * They come back together because they are one line, drawn in two colours, and because both
 * are read off the same screen state. Splitting them into two functions meant two switches
 * over the same type, which is two places to forget a new state in.
 *
 * @property count what the grid lists, as `588 items`, or the walk's progress while it runs.
 * @property updated when the Source was last walked, as `(updated 19/09 23:14)`, or null when
 *   it never has been. Drawn dimmer than [count]: it is the least urgent thing on the screen.
 */
data class HeaderLine(val count: String, val updated: String?)

/**
 * The quiet line under the Source name.
 *
 * It carries the count once the list is ready, and the progress while the first walk runs.
 * Both are deliberately quiet: no banner and no dialog. See spec sections 5.4 and 6.5.
 *
 * The count carries **no thousands separator**: `5303 items`, not `5,303 items`. Settled by
 * The owner on 2026-09-19. The progress line keeps its separators, because spec section 6.5
 * writes it that way: `loading 400 of 3,000`.
 *
 * The word on screen is `items`. In the code the same videos are still the listed videos,
 * which is the ones that are not Shorts, and `CONTEXT.md` has no term for either.
 *
 * Ticket 02 also wrote "from storage, no network call" here, which is language for whoever
 * built the app rather than for the household. The proof that a second launch makes no
 * network call is the logcat line `no API call made`.
 */
fun headerLine(state: SourceScreenState, zone: ZoneId = ZoneId.systemDefault()): HeaderLine =
    when (state) {
        SourceScreenState.Starting -> HeaderLine("opening", null)
        is SourceScreenState.Walking -> HeaderLine(loadingLine(state.loaded, state.total), null)
        // A walk that is still running says so, even though the grid is already drawing what
        // has arrived. The count of a list that is still growing would be a number the
        // household watched change, and it would read as `1 item` for a moment.
        is SourceScreenState.Ready -> HeaderLine(
            count = state.walking
                ?.let { loadingLine(it.loaded, it.total) }
                ?: itemCount(state.videos.size),
            updated = if (state.walking != null) {
                null
            } else {
                state.updatedAt?.let { "(updated ${UPDATED_TIME.format(it.atZone(zone))})" }
            },
        )
        is SourceScreenState.Failed -> HeaderLine("could not load this Source", null)
    }

/** The quiet progress line of spec section 6.5: `loading 400 of 3,000`. */
private fun loadingLine(loaded: Int, total: Int?): String =
    if (total == null) "loading ${loaded.grouped()}" else "loading ${loaded.grouped()} of ${total.grouped()}"

/**
 * `5813` reads as `5,813`.
 *
 * This is for the progress line of a running walk only, which spec section 6.5 writes as
 * `loading 400 of 3,000`. The item count of a finished list is deliberately **not** grouped.
 */
fun Int.grouped(): String = "%,d".format(Locale.UK, this)

/**
 * When this Source was last walked, as `(updated 19/09 23:14)`, or null when it never has been.
 *
 * It is a separate string from [statusLine] because it is drawn in a dimmer colour beside the
 * count. The day and the month are padded to two digits, so the line does not change width as
 * the months roll over. Settled by the owner on 2026-09-19. See spec section 5.4.
 */
fun updatedLine(state: SourceScreenState, zone: ZoneId = ZoneId.systemDefault()): String? {
    val updatedAt = (state as? SourceScreenState.Ready)?.updatedAt ?: return null
    return "(updated ${UPDATED_TIME.format(updatedAt.atZone(zone))})"
}

/** `588 items`, and `1 item` when a Source lists only one. No thousands separator. */
private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

/**
 * The `Order` chip, which is the Direction chip and is per Source.
 *
 * It states the Direction the Source is in **now**, not the one pressing it would move to. A
 * chip that named the other state would read as a question on a screen where every other chip
 * names what it does.
 *
 * `CONTEXT.md` calls this Direction. The words on the television are "Newest first" and
 * "Oldest first", never ascending or descending, and never sort order.
 */
fun orderChipLabel(direction: Direction): String = when (direction) {
    Direction.NEWEST_FIRST -> "Order: Newest first"
    Direction.OLDEST_FIRST -> "Order: Oldest first"
}

/** The three chips whose wording never changes. See spec section 7.2. */
const val JUMP_TO_LAST_PLAYED_CHIP = "Jump to last played"
const val NEXT_UNWATCHED_CHIP = "Next unwatched"
const val PERIOD_CHIP = "Period"

/**
 * What the Source screen draws in place of the grid when the Source lists no video.
 *
 * It was a bare literal inside `ui/SourceScreen.kt` until build ticket 52, which needed to name
 * it from a test: the message is what stands behind the Period jump list when a refresh empties
 * the Source, and it holds nothing focusable, which is why that close puts the highlight on the
 * `Period` chip instead of on a card.
 */
const val NO_VIDEOS_MESSAGE = "This Source lists no videos."

/**
 * The `Autoplay` chip, which is per Source, exactly as `Order` is.
 *
 * **It names the state in words, and the fill says the same thing again.** The chip carries
 * both signals at once, on purpose.
 *
 * - It read `Autoplay: On` and `Autoplay: Off` until 2026-09-20, when ticket 18 cut it to the
 *   single word `Autoplay` and left the state to the fill alone.
 * - The owner was shown the two fills on the household television the same day and asked whether
 *   he could tell them apart from the sofa. **His answer was no.** He asked for the words
 *   back and for the fill to stay: "put the word back, but when On is chosen, also keep the
 *   highlight fill it has now." See ticket 26.
 * - So this is not a revert of ticket 18. The fill rule that ticket built is untouched and
 *   still lives in [autoplayChipFill] in `ChipPaint.kt`. Only the label changed back.
 * - It costs width, and the cost turned out to be nothing. Ticket 18's argument for one word
 *   was that the row of five is tight. Measured on the household panel on 2026-09-21,
 *   `Autoplay: Off` is 237 px and is only the fourth widest chip on the row, behind
 *   `Jump to last played` at 318 px. The row ends 438 px short of the panel's edge.
 *
 * It states what autoplay-next **is** now, not what pressing it would do, which is
 * [orderChipLabel]'s rule and the rule of every chip on this row.
 *
 * It is the last chip on the row, after `Period`. The other four were settled on the
 * television on 2026-09-19, and putting a new one among them would move a chip the household
 * has already learnt the place of. See spec section 7.2, amended by tickets 08, 18 and 26.
 *
 * The caller passes the same boolean to this and to [autoplayChipFill], so the word and the
 * fill cannot disagree with **each other**.
 *
 * **They can both be wrong for a moment, and this is the one trap on this function.** Before
 * the Source's own row has been read there is no stored value, so `SourceScreen` passes
 * `StoredSource.AUTOPLAY_NEXT_DEFAULT`, which is off. That is right for a new Source and
 * **wrong for a stored Source whose `autoplay_next` column is null**, because
 * `StoredSource.autoplayNextOfStored(null)` reads null as **on**. Two of the household's four
 * Sources are null, so opening one shows `Autoplay: Off` while the state is
 * `SourceScreenState.Starting`, then flips to `Autoplay: On` when `Ready` arrives.
 *
 * - The flicker is not new. It was there from ticket 18, as a quiet fill turning accent, and
 *   `/code-review` found it here because ticket 26 turned it into a **readable word**.
 * - A press inside that window does nothing: `SourceScreenModel.toggleAutoplayNext` returns
 *   null unless the state is `Ready`, and writes no log line.
 * - It is left as it is rather than papered over with a third label. Closing it means the
 *   stored row reaching the screen before the video list does, which is a change to the state
 *   machine and not to a string. See ticket 26's known gaps.
 */
fun autoplayChipLabel(autoplayNext: Boolean): String =
    if (autoplayNext) "Autoplay: On" else "Autoplay: Off"

/**
 * The card menu line that opens the Period jump list.
 *
 * It is here because the household remote's hamburger button cannot reach this app. The TCL's
 * firmware takes `MENU` above Android's key dispatch, exactly as it takes `GUIDE`, so the key
 * that spec section 7.4 first named as the panel's door was never a door at all on this set.
 * The owner asked for the jump list to be put on the card's long-press menu instead, on
 * 2026-09-20. See spec sections 7.3, 7.4 and 8.5, and ticket 19.
 */
const val JUMP_TO_A_PERIOD = "Jump to a Period"

/**
 * The heading of the Period jump list, so it can never be read as the card menu.
 *
 * It is [JUMP_TO_A_PERIOD] itself, and not a second string saying the same thing. The menu line
 * names the panel it opens, so the two must never drift apart.
 */
const val PERIOD_PANEL_TITLE = JUMP_TO_A_PERIOD

/**
 * What the `Period` chip and `MENU` say when a Source has no Period to jump to.
 *
 * It happens only when nothing in the Source has a publish date, which means every listed
 * video is private or deleted. The chip stays live and says so, for the same reason
 * `Next unwatched` does: a chip that came and went would be a chip whose place on the row the
 * household could never learn.
 */
const val NO_PERIODS = "This Source has no dates to jump to."

/**
 * One Period, as the panel writes it: `2025`, or `Jun 2025`.
 *
 * Never a relative age, and never a season number. A Period is a calendar year or a calendar
 * month and the label says which one it is. See `CONTEXT.md` and spec section 7.4.
 */
fun periodLabel(period: Period): String =
    if (period.month == null) {
        period.year.toString()
    } else {
        PERIOD_MONTH.format(YearMonth.of(period.year, period.month))
    }

/**
 * How many videos one Period holds, as `84 videos`.
 *
 * No thousands separator, the same as the header count line and the card menu's own counts.
 * Hidden Shorts are in none of these numbers, because a Short is never listed. Spec 4.3.
 */
fun periodVideoCount(period: Period): String = videoCount(period.count)

/**
 * One logcat line saying which bucket a Source got, meant for whoever is reading logcat.
 *
 * Whether a Source is cut into years or months is a rule, not a picture, so this is what
 * proves it on the television. It names the unit and the entry count, which together are the
 * whole answer of spec section 7.4's code block.
 */
fun periodListLogLine(unit: PeriodUnit, entryCount: Int, videoCount: Int): String =
    "Period list: $entryCount $unit entries over $videoCount listed videos."

/**
 * One logcat line saying a refresh moved the highlight to the screen's new landing. Build
 * ticket 55.
 *
 * It is the proof on the television that the move happened, and that it happened because no
 * key had been pressed. A screenshot can show where the highlight is, and not why.
 */
fun refreshLandingLogLine(videoId: String, index: Int): String =
    "A refresh changed where this Source lands, and no key has been pressed since it landed, " +
        "so the highlight moves to $videoId at index $index."

/**
 * One logcat line saying where choosing a Period sent the household.
 *
 * It is [landingLogLine] with the Period's own label in the chip's place, so a jump reads the
 * same way as `Next unwatched` and `Jump to last played` do in the same log.
 */
fun periodLandingLogLine(period: Period, videoId: String, count: Int): String = landingLogLine(
    chip = "$PERIOD_CHIP ${periodLabel(period)}",
    videoId = videoId,
    index = period.firstIndex,
    count = count,
)

/**
 * What `Next unwatched` says when the Source has no gap left.
 *
 * The chip stays live rather than going dim, unlike `Jump to last played`. Whether a Source
 * has a gap changes with every mark and every play, and a chip that came and went as the
 * household worked down a Source would be a chip whose place could never be learnt. Saying so
 * is the honest answer.
 */
const val NO_NEXT_UNWATCHED = "Every video in this Source has been watched."

/**
 * One logcat line saying where a chip sent the household, meant for whoever is reading logcat.
 *
 * Direction, Next unwatched and Last played are rules rather than pictures, so this is what
 * proves them on the television. It names the video id, because that is the one thing a
 * screenshot of a grid of Hebrew titles cannot be checked against.
 */
fun landingLogLine(chip: String, videoId: String?, index: Int?, count: Int): String =
    if (videoId == null || index == null) {
        "$chip had nowhere to land among $count listed videos."
    } else {
        "$chip landed on $videoId, at index $index of $count listed videos."
    }

/**
 * The two marking lines of the card menu, in order.
 *
 * Both open a submenu. Neither marks anything by itself, so the household always sees what it
 * is about to do before it happens. See spec section 7.3.
 */
const val MARK_AS_WATCHED = "Mark as watched"
const val MARK_AS_UNWATCHED = "Mark as unwatched"

/**
 * The line that plays a video from zero instead of resuming it.
 *
 * Spec section 8.6 puts it in this menu. Pressing the centre button on a card resumes an In
 * progress video, and this is the only way to ask for the beginning instead.
 *
 * **It is the first line. The owner chose that on 2026-09-20, and it replaced the opposite rule.**
 * The line was last until then, and the argument for last was that the first line takes the
 * highlight the moment the menu opens and the household had already learnt that
 * `Mark as watched` was there. He was shown that argument, judged the menu again with the
 * fourth line on it, and put `Play from start` first. See ticket 19 and spec section 7.3.
 *
 * It is shown on every card, including one with no position to resume from, where it does the
 * same thing as the centre button. A line that came and went would be a line the household
 * could never learn the place of.
 */
const val PLAY_FROM_START = "Play from start"

/**
 * The four lines of the card menu's top level, in the order the owner chose on 2026-09-20.
 *
 * Every one of them is live on every card. Only a submenu ever goes dim, and only on its one
 * `Just this`. See [cardMenuSubLines].
 */
fun cardMenuTopLines(): List<String> =
    listOf(PLAY_FROM_START, JUMP_TO_A_PERIOD, MARK_AS_WATCHED, MARK_AS_UNWATCHED)

/**
 * How much of the Source one submenu line marks.
 *
 * The wording is always **older** and **newer**, never "before" and "after". Before and after
 * invert the moment Direction is flipped, and the household would read the same two words to
 * mean opposite things. Spec section 7.3.
 */
enum class MarkScope {
    /** The chosen video and nothing else. Writes one `played` or `unwatched` entry. */
    JUST_THIS,

    /** The chosen video and every video published on or before it. Writes one `range`. */
    ALL_OLDER,

    /** The chosen video and every video published on or after it. Writes one `range`. */
    ALL_NEWER,
}

/**
 * One line of a submenu.
 *
 * @property count how many videos the line marks, **over listed videos only**. Shorts are
 *   hidden and never counted anywhere in this app, so they are in none of these numbers. A
 *   `range` still covers them by date; only what the household is shown leaves them out. See
 *   spec section 4.3.
 * @property label what the television draws, count included.
 */
data class CardMenuSubLine(val scope: MarkScope, val count: Int, val label: String)

/**
 * The lines of a submenu, in order.
 *
 * Three lines, each stating its own count, which spec section 7.3 asks for in so many words.
 * The count is what tells the household apart a press that changes one card from a press that
 * changes four hundred, and it is the only warning before the change happens.
 *
 * **Two lines, not three, for a video with no publish date.** A private or deleted video has
 * no `videoPublishedAt`, so there is no pivot to put a range on and no way to say what is
 * older or newer than it. Only `Just this` is offered. Drawing lines that could not act would
 * be worse than not drawing them.
 *
 * **Both counts include every video tied with the pivot**, meaning exactly the same publish
 * time. The grid orders a tie by video id. So a tied video on the newer side of the card is
 * still in the `older` count. A tied video on the older side is still in the `newer` count.
 * [local.youtubetv.data.covers] covers a tied video in the same way. The owner chose this on
 * 2026-10-03, in build ticket 58. See spec section 4.2.
 *
 * @param pivot the card the menu was opened on.
 * @param listed the videos the grid shows. Anything hidden is filtered out again here, so the
 *   counts are right however the caller got the list.
 * @param walkFinished false while the Source's first walk is still running. Only `Just this`
 *   is offered then, for the same reason a video with no publish date gets only that line: a
 *   Bulk mark needs the whole list and there is not one yet.
 */
fun cardMenuSubLines(
    pivot: StoredVideo,
    listed: List<StoredVideo>,
    walkFinished: Boolean = true,
): List<CardMenuSubLine> {
    val justThis = CardMenuSubLine(MarkScope.JUST_THIS, count = 1, label = "Just this (1 video)")
    // A Bulk mark covers every listed video on one side of a pivot date, so it needs the
    // whole list. Half a list would promise a count that is about to be wrong and write a
    // range the household did not mean. Spec section 6.5.
    if (!walkFinished) return listOf(justThis)
    val pivotPublishedAt = pivot.videoPublishedAt ?: return listOf(justThis)

    val dates = listed.filter { it.isListed }.mapNotNull { it.videoPublishedAt }
    val older = dates.count { !it.isAfter(pivotPublishedAt) }
    val newer = dates.count { !it.isBefore(pivotPublishedAt) }
    return listOf(
        justThis,
        CardMenuSubLine(MarkScope.ALL_OLDER, older, "This and all older (${videoCount(older)})"),
        CardMenuSubLine(MarkScope.ALL_NEWER, newer, "This and all newer (${videoCount(newer)})"),
    )
}

/**
 * Which lines of a submenu are live on this card, in the same order [cardMenuSubLines] returns.
 *
 * **On any one card exactly one `Just this` does nothing.** `Mark as watched → Just this` on a
 * card that is already watched, and `Mark as unwatched → Just this` on one that is not, both
 * write an entry that says what the log already says. That line is drawn dim and is not
 * focusable, the way the `Jump to last played` chip is dim when it has nowhere to land. The owner
 * chose dim over hiding it on 2026-09-20: the place stays learnable, and the card's own state
 * becomes visible from inside the menu.
 *
 * **The two range lines are never dim.** A Bulk mark's scope is independent of the pivot's own
 * state, and that is why both marking lines stay on the top level. The real case is the newest
 * video unwatched with twenty watched below it: the household stands on that newest card and
 * asks for `Mark as unwatched → This and all older`. Hiding `Mark as unwatched` on an unwatched
 * card would put that range out of reach of the card it is naturally pivoted on.
 *
 * It is kept apart from [cardMenuSubLines] because the two answer different questions. That one
 * walks the Source to work out counts and wording, and neither depends on the card's watch
 * state; this one is the one rule the watch state decides. See ticket 19 and spec section 7.3.
 *
 * @param markWatched true under `Mark as watched`, false under `Mark as unwatched`.
 * @param pivotWatched whether the card the menu was opened on is watched right now.
 */
fun cardMenuSubLinesEnabled(
    lines: List<CardMenuSubLine>,
    markWatched: Boolean,
    pivotWatched: Boolean,
): List<Boolean> = lines.map { line ->
    line.scope != MarkScope.JUST_THIS || markWatched != pivotWatched
}

/** `1 video`, `443 videos`. No thousands separator, the same as the header count line. */
private fun videoCount(count: Int): String = if (count == 1) "1 video" else "$count videos"

/** The heading of a submenu, which is the top line the household chose. */
fun cardMenuSubTitle(markWatched: Boolean): String =
    if (markWatched) MARK_AS_WATCHED else MARK_AS_UNWATCHED

/**
 * What the household is told after a single video was marked.
 *
 * The card itself says the same thing with its green tick, so this is short and goes away on
 * its own. The wording is the glossary's: a Watch, never a view or a history entry.
 */
fun markedLine(watched: Boolean): String =
    if (watched) "Marked as watched." else "Marked as unwatched."

/**
 * What the household is told after a Bulk mark.
 *
 * It repeats the count, because hundreds of cards changing at once is the one action on this
 * screen whose effect runs off the edge of the picture. The number is the same one the menu
 * line stated, so what was promised and what happened can be compared.
 */
fun markedRangeLine(count: Int, watched: Boolean): String =
    "Marked ${videoCount(count)} as ${if (watched) "watched" else "unwatched"}."
