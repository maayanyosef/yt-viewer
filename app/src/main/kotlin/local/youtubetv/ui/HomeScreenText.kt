package local.youtubetv.ui

import java.time.ZoneId
import local.youtubetv.data.Direction
import local.youtubetv.data.SourceCard
import local.youtubetv.player.VideoQuality
import local.youtubetv.player.videoQualityLine

/**
 * Every string the home rail puts on the television, and the one picture it puts there.
 *
 * It is kept apart from the Composables for the same reason `ui/SourceScreenText.kt` is: every
 * wording rule is then a JVM unit test with no device and no quota. See spec section 7.1.
 *
 * A date here is always an exact day, `3 Jun 2025`, and never a relative age. That rule belongs
 * to the whole app, so the formatter is `exactDate` in `ui/SourceScreenText.kt` and is not
 * written a second time here.
 */

/** The quiet label over the rail. The household's own word for what the rail holds. */
const val SOURCES_LABEL = "SOURCES"

/** The label over the Next unwatched slot on a card. `CONTEXT.md` fixes the wording. */
const val NEXT_UNWATCHED_LABEL = "NEXT UNWATCHED"

/**
 * What a card says when its Source has no gap left. Spec section 7.1 fixes the words.
 *
 * The card is **not** dimmed and does not move on the rail. This sentence and the `ALL WATCHED`
 * badge are the whole of how a finished Source is shown.
 */
const val NOTHING_LEFT = "Nothing left in this Source"

/** What a card says before its Source has ever been walked. */
const val NOT_LOADED_YET = "Open this Source to load it"

/**
 * The badge in the corner of a card: how much of the Source is left.
 *
 * Three states, and the third is the one that is easy to get wrong. A Source that has never
 * been walked knows of no videos, so its unwatched count is zero, and a bare count would tell
 * the household it had watched a Source it has never opened.
 */
fun unwatchedBadge(card: SourceCard): String = when {
    !card.walked -> "NOT LOADED"
    card.unwatchedCount == 0 -> "ALL WATCHED"
    else -> "${card.unwatchedCount} UNWATCHED"
}

/**
 * The Direction, in the words `CONTEXT.md` allows: newest-first or oldest-first.
 *
 * Never ascending, never descending, and never sort order. `orderChipLabel` in
 * `ui/SourceScreenText.kt` says the same two words with `Order: ` in front, because there the
 * household is standing on a chip and here it is reading a card.
 */
fun directionLabel(direction: Direction): String = when (direction) {
    Direction.NEWEST_FIRST -> "Newest first"
    Direction.OLDEST_FIRST -> "Oldest first"
}

/**
 * The parts of the quiet line under a Source's name: its Direction and how many videos it lists.
 *
 * **They stay apart and are never joined into one string.** That is ticket 24's rule and spec
 * section 6.2 says why: one piece of text is laid out as one bidirectional run, so a Hebrew
 * name in the middle of a run reorders the digits around it and tears `588 videos` in half.
 * Ticket 20 is the ticket that puts a **fetched** name on this card, and the household's
 * Sources are often named in Hebrew, so the rule is applied here before such a name arrives.
 *
 * The name itself is not one of these parts. It is drawn by its own `Text` above this line,
 * which is what keeps it out of this line's runs. See `CandidateMeta` in `ui/AddSourceText.kt`,
 * which is the same shape on the add screen.
 *
 * @property direction the Direction, in the glossary's words. Always present.
 * @property count how many videos the Source lists, or null when it has never been walked. A
 *   Source with no walk has no honest count to give, and `0 videos` would be a fact the app
 *   does not have.
 */
data class RailCardMeta(val direction: String, val count: String?) {

    /** The parts in reading order, with a count the app does not have left out. */
    val parts: List<String> get() = listOfNotNull(direction, count)

    /**
     * Which part of [parts] gives way when the line is too wide, or null when none may.
     *
     * **The count never gives way.** It is the point of the line, and a count cut short reads
     * as a smaller number rather than as a cut one. The Direction is two known words, so it is
     * the part that can lose its tail and still be understood. It is always first, so it is
     * index 0 whenever there is a count to protect.
     *
     * The same rule `CandidateMeta.shrinkingPart` follows in `ui/AddSourceText.kt`, where it
     * is the channel name that gives way.
     */
    val shrinkingPart: Int? get() = if (count == null) null else 0
}

/**
 * The quiet line under a Source's name. See [RailCardMeta] for why it is not one string.
 *
 * The count carries no thousands separator, the same as the Source screen's header line, so a
 * long Source reads `5303 videos`. Settled by the owner on 2026-09-19.
 */
fun railCardMeta(card: SourceCard): RailCardMeta = RailCardMeta(
    direction = directionLabel(card.direction),
    count = if (card.walked) videoCountLine(card.listedCount) else null,
)

/** `1 video`, `588 videos`. No thousands separator. */
private fun videoCountLine(count: Int): String = if (count == 1) "1 video" else "$count videos"

/**
 * What the app calls a Source: the name the household typed, then the name YouTube gave it,
 * then its raw id.
 *
 * **This is the one place the whole naming rule is written.** It is used by [railCardName] for
 * everything the home rail draws, and by `SourceScreenModel.name` for the Source screen's
 * header. Build ticket 29 made the header go through it, because the header used to hold
 * whatever the name was at the moment the screen opened and a second copy of this rule sat in
 * `MainActivity`.
 *
 * **Both fallbacks are one rule and not two, which is what makes clearing a typed name work.**
 * Build ticket 27 put the household's own name in a second column rather than over the first,
 * so clearing it simply stops the first step of this function matching and the name YouTube
 * gave the Source comes back with no API call at all. The owner chose that on 2026-09-20.
 *
 * A blank name counts as no name, at both steps. `SourceStore.fillMissingTitle` relies on that
 * for [title], so a Source whose stored name is an empty string is still named by the next
 * change check, and `StoredSource.typedNameOfInput` keeps a blank out of [typedName] in the
 * first place.
 *
 * @param typedName the name the household typed, or null when it has typed none.
 * @param title the title YouTube gave this Source, or null when storage holds none.
 * @param sourceId the playlist or channel id, which every Source always has.
 */
fun sourceNameOrId(typedName: String?, title: String?, sourceId: String): String =
    typedName?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: sourceId

/** What the card calls its Source. See [sourceNameOrId] for the whole naming rule. */
fun railCardName(card: SourceCard): String =
    sourceNameOrId(card.typedName, card.title, card.sourceId)

/**
 * The Next unwatched title on a card, and the exact date under it.
 *
 * @property title what the Source would play next, or the sentence that says there is nothing.
 * @property date the video's exact publish date, or null when there is no video to date. The
 *   slot keeps its height either way, so a card whose Source is finished is the same size as
 *   one whose Source is not, and the rail does not change shape as the household works down it.
 */
data class NextUnwatchedSlot(val title: String, val date: String?)

/**
 * What the Next unwatched slot of a card says.
 *
 * **Nothing here is about how far into a video the household got.** Spec section 7.1 took the
 * `In progress` line and the bar off this card on 2026-09-19. There is no position, no fraction
 * and no percentage on the home rail at all, and the in-progress video is one press away
 * through `Jump to last played` in the long-press menu.
 */
fun nextUnwatchedSlot(card: SourceCard, zone: ZoneId = ZoneId.systemDefault()): NextUnwatchedSlot {
    if (!card.walked) return NextUnwatchedSlot(NOT_LOADED_YET, null)
    val next = card.nextUnwatched ?: return NextUnwatchedSlot(NOTHING_LEFT, null)
    return NextUnwatchedSlot(
        title = next.title.ifBlank { next.videoId },
        date = exactDate(next.videoPublishedAt, zone),
    )
}

/**
 * Which video's thumbnail a rail card draws.
 *
 * @property videoId the video the picture is of. It is what the thumbnail cache is keyed by.
 * @property storedUrl what `videos.list` reported at the walk, or null when it stored none. It
 *   is a preference and not the only address: see [local.youtubetv.youtube.YouTubeUrls].
 */
data class RailCardPicture(val videoId: String, val storedUrl: String?)

/**
 * The picture on a rail card: the **Next unwatched** video's thumbnail, or null when the card
 * has no video to show a picture of.
 *
 * Added 2026-09-20 by ticket 21, and spec section 7.1 was amended in the same commit, because
 * ticket 10 built this card text-only and said in so many words that nothing may put a picture
 * on it without amending that section.
 *
 * **It is the video the card already names**, so the picture and the title under it are about
 * the same thing. The owner chose it over the Source's own channel or playlist picture, which
 * ticket 20 could have had for nothing.
 *
 * **A null is not an empty card.** The card draws the same plain tile the Source screen's grid
 * draws while a picture is still loading, in a slot of a fixed size, so a finished Source and a
 * Source that has never been walked are exactly the same size as every other card. That is the
 * property ticket 10 gave [NextUnwatchedSlot], and this keeps it.
 *
 * **The guard order is [nextUnwatchedSlot]'s, and it has to be.** A Source that has never been
 * walked is tested before its Next unwatched field, so the picture is there exactly when the
 * slot below it names a real video. A picture over the words `Open this Source to load it`
 * would contradict them.
 */
fun railCardPicture(card: SourceCard): RailCardPicture? {
    if (!card.walked) return null
    val next = card.nextUnwatched ?: return null
    return RailCardPicture(videoId = next.videoId, storedUrl = next.thumbnailUrl)
}

/**
 * One line of the long-press menu on a Source card.
 *
 * @property action what the line does when it is chosen.
 * @property label what the television draws.
 * @property enabled false draws the line dim and takes it out of the D-pad's walk, the same way
 *   a disabled chip works on the Source screen. A line that took focus and did nothing would be
 *   worse than a dim one.
 */
data class SourceMenuLine(
    val action: SourceMenuAction,
    val label: String,
    val enabled: Boolean,
)

/** What one line of the Source card's long-press menu does. Spec section 7.1. */
enum class SourceMenuAction {
    OPEN_AT_NEXT_UNWATCHED,
    OPEN_AT_LAST_PLAYED,
    OPEN_AT_NEWEST,
    REFRESH,
    RENAME,
    REMOVE,
    MARK_EVERYTHING_UNWATCHED,

    /**
     * Moves the player's quality cap on to the next setting. About the app, not this Source,
     * exactly like [API_KEY]. See `player/VideoQuality.kt`.
     */
    VIDEO_QUALITY,

    /** Opens the key screen. Build ticket 64. It is about the app, not about this Source. */
    API_KEY,
}

/**
 * The Source menu's line that opens the key screen. Build ticket 64.
 *
 * It is on this menu because it is the home screen's only menu. It sits after every line that
 * is about one Source except the last, which ticket 10 reserved for `Mark everything
 * unwatched`.
 */
const val API_KEY_LINE = "API key: replace or remove"

const val OPEN_AT_NEXT_UNWATCHED = "Open at Next unwatched"
const val OPEN_AT_LAST_PLAYED = "Open at Last played"
const val OPEN_AT_NEWEST = "Open at the newest video"

/**
 * The `Refresh` line of spec section 5.1, which is the one manual trigger a refresh has.
 *
 * It names the Source it acts on, exactly as `Remove` does and for the same reason: every other
 * line of this menu names what it acts on, and a one-word line in a column of five-word ones is
 * the line a household's eye slides over.
 *
 * **It asks nothing before it acts.** There is no confirmation, unlike `Remove` and
 * `Mark everything unwatched`, because a refresh changes nothing the household owns: it spends
 * a few units of the daily ten thousand and brings the Source up to date. The two lines that
 * ask are the two that cannot be undone.
 */
const val REFRESH_THIS_SOURCE = "Refresh this Source"

/**
 * What the rail says after a `Refresh`, while it runs.
 *
 * It is the same quiet message the rail uses for a removal, and it is the **only** thing a
 * refresh puts on the television beyond the `(updated ...)` line. Spec section 5.3 has no
 * banner, no dialog and no `New` badge anywhere.
 */
fun refreshingLine(sourceName: String): String = "Refreshing $sourceName…"

/**
 * The `Remove` line of spec section 7.1.
 *
 * It names the Source it acts on rather than being the bare verb the spec calls it by. Every
 * other line of this menu names what it acts on, and a one-word line in a column of five-word
 * ones is the line a household's eye slides over.
 */
const val REMOVE_THIS_SOURCE = "Remove this Source"

/**
 * The `Rename` line of build ticket 27.
 *
 * **It is the one line of this menu that is a bare verb, and that is deliberate.** Every other
 * line names what it acts on, because a one-word line in a column of five-word ones is the line
 * a household's eye slides over. This line is the exception for a safety reason that outweighs
 * that one: it sits immediately above `Remove this Source`, and calling it
 * `Rename this Source` would put three lines reading `Re… this Source` in a row, differing by
 * two characters in the middle of a word, read from a sofa. The word `Rename` cannot be
 * mistaken for `Remove this Source` at a glance. The owner wrote the line as `Rename` in ticket 27.
 */
const val RENAME = "Rename"
const val MARK_EVERYTHING_UNWATCHED = "Mark everything unwatched"

/**
 * The lines of the long-press menu, in order.
 *
 * The three landings come first, because they are what the household reaches for, and the one
 * action that changes the Source comes last, so a mis-aimed press lands on a landing and not on
 * something that rewrites four hundred cards.
 *
 * **`Remove` sits between the landings and `Mark everything unwatched`**, so that the line that
 * rewrites four hundred cards keeps the last place it has always had and the household never
 * has to learn a new position for it. `Refresh` is ticket 13's and goes immediately **above**
 * `Remove`, for the same reason: adding it there moves nothing the household has learnt.
 *
 * **`Rename` goes between `Refresh this Source` and `Remove this Source`.** The owner chose that
 * place on 2026-09-20, and it is a safety argument rather than a tidy one: it groups the two
 * lines that change the Source itself rather than where it opens, and it puts `Rename` where a
 * slip that overshoots `Refresh` lands on a line that asks a question instead of on `Remove`.
 * Build ticket 27. It moves `Remove` and `Mark everything unwatched` one place down the menu,
 * which is the one thing this function has avoided doing twice before; there is no way to add
 * a seventh line above `Remove` without it, and the alternative was putting the rename below
 * the line that rewrites four hundred cards.
 *
 * **`Remove` is live on every Source**, finished or not, walked or not. Spec section 6.6 is
 * explicit about it, and it has to be: a Source added by mistake, which has therefore never
 * been walked, is exactly the Source a household most wants off its rail.
 *
 * **Two lines can be dim, and both are dim for the same kind of reason: there is nowhere to go.**
 *
 * - `Open at Last played` is dim when the Source has nowhere to land, which is the same three
 *   cases the Source screen's own chip is dim for. See `lastPlayedIndex` in `data/Landings.kt`.
 * - `Refresh` is dim on a Source that has never been walked. There is no cached list to catch
 *   up, and opening the Source is what fills it, so a refresh there would spend a full walk's
 *   quota and leave the grid exactly as empty as it was.
 *
 * The other two landings are live even on a Source that has never been walked, and that is on
 * purpose twice over. Opening a Source is what walks it, so a dim `Open at Next unwatched`
 * would be a Source the household could never load. And a line that was dim before the walk and
 * live after it would be a line whose place on the menu could never be learnt, which is the
 * same reason `Next unwatched` on the Source screen stays live on a finished Source.
 *
 * @param everythingUnwatchedCount how many videos `Mark everything unwatched` would reach, or
 *   null when it can reach none and the line is dim.
 */
fun sourceMenuLines(
    card: SourceCard,
    everythingUnwatchedCount: Int?,
    videoQuality: VideoQuality = VideoQuality.AUTO,
): List<SourceMenuLine> =
    listOf(
        SourceMenuLine(
            SourceMenuAction.OPEN_AT_NEXT_UNWATCHED,
            OPEN_AT_NEXT_UNWATCHED,
            enabled = true,
        ),
        SourceMenuLine(
            SourceMenuAction.OPEN_AT_LAST_PLAYED,
            OPEN_AT_LAST_PLAYED,
            enabled = card.lastPlayedVideoId != null,
        ),
        SourceMenuLine(
            SourceMenuAction.OPEN_AT_NEWEST,
            OPEN_AT_NEWEST,
            enabled = true,
        ),
        SourceMenuLine(
            SourceMenuAction.REFRESH,
            REFRESH_THIS_SOURCE,
            // Dim on a Source that has never been walked. There is no cached list to catch
            // up, and opening the Source is what fills it: a refresh here would spend a full
            // walk's quota and still leave the grid empty until it was opened.
            enabled = card.walked,
        ),
        SourceMenuLine(
            SourceMenuAction.RENAME,
            RENAME,
            // Live on every Source, walked or not, named or not. A Source that has never been
            // walked is exactly the Source whose card is headed by a raw playlist id, so it is
            // the one a household most wants to name, and naming it costs no walk and no unit.
            enabled = true,
        ),
        SourceMenuLine(
            SourceMenuAction.REMOVE,
            REMOVE_THIS_SOURCE,
            enabled = true,
        ),
        // Build ticket 64. Second to last, so `Mark everything unwatched` keeps the last place
        // ticket 10 gave it. A slip past `Remove` lands here, which only opens a screen.
        // Next to `API key`, the other line about the whole app. It says the setting it is on,
        // so the household knows where a press will move it from.
        SourceMenuLine(
            SourceMenuAction.VIDEO_QUALITY,
            videoQualityLine(videoQuality),
            enabled = true,
        ),
        SourceMenuLine(SourceMenuAction.API_KEY, API_KEY_LINE, enabled = true),
        SourceMenuLine(
            SourceMenuAction.MARK_EVERYTHING_UNWATCHED,
            if (everythingUnwatchedCount == null) {
                MARK_EVERYTHING_UNWATCHED
            } else {
                "$MARK_EVERYTHING_UNWATCHED (${videoCountLine(everythingUnwatchedCount)})"
            },
            enabled = everythingUnwatchedCount != null,
        ),
    )

/** The heading of the confirmation. It is a question, because it is asking one. */
const val MARK_EVERYTHING_UNWATCHED_QUESTION = "Mark everything unwatched?"

/**
 * What the confirmation says before it acts.
 *
 * It names the count and the Source, because this is the one action on the rail that changes
 * hundreds of cards at once and there is no undo anywhere in this app.
 *
 * The count is **over listed videos only**, the same rule the card menu's three lines follow,
 * and it leaves out any video no range can cover. A private or deleted video has no publish
 * date, so a range cannot reach it and promising it would be a promise the log cannot keep.
 * See spec section 4.4 and `everythingUnwatched` in `data/HomeRail.kt`.
 */
fun markEverythingUnwatchedDetail(sourceName: String, count: Int): String =
    "All ${videoCountLine(count)} of $sourceName become unwatched, " +
        "and this Source forgets where it was last played."

/** The two lines of the confirmation. The one that acts states the count again. */
fun markEverythingUnwatchedYes(count: Int): String = "Yes, mark ${videoCountLine(count)} unwatched"

const val MARK_EVERYTHING_UNWATCHED_NO = "No, leave this Source alone"

/** The heading of the confirmation in front of `Remove`. It is a question, because it asks one. */
const val REMOVE_QUESTION = "Remove this Source?"

/**
 * What the confirmation says before it removes a Source.
 *
 * Three sentences, and the order of them is the point. It names the Source, so a household
 * that opened the menu on the wrong card sees that now. Then it says the watch history is
 * kept, which spec section 6.6 asks for by name: removing is the answer to a cluttered rail,
 * and a household that believes removing loses its history will never use it. Only then does
 * it say the videos are fetched again, which is the one real cost.
 */
fun removeSourceDetail(sourceName: String): String =
    "$sourceName comes off the home screen. Everything you have watched in it is kept, so " +
        "adding it again brings all of it back. Its videos are loaded from YouTube again."

/**
 * The two lines of the confirmation.
 *
 * The refusing line is **first**, so the highlight lands on it when the panel opens and a
 * household that held the centre button a moment too long has removed nothing. The same rule
 * `Mark everything unwatched` follows, and for the same reason: there is no undo in this app.
 */
const val REMOVE_NO = "No, keep this Source"
const val REMOVE_YES = "Yes, remove it"

/**
 * What the home screen says while storage is still being read, over the card-sized
 * placeholders of [HomeBody.LOADING].
 *
 * **It is a sentence in the middle of the screen and not a word in the corner.** The quiet
 * label over the rail used to read `opening`, and build ticket 36 is the record of that not
 * working: it sat above a rail holding nothing but `+ Add a Source`, which said the opposite
 * far more loudly, and the eye went to the card. The owner, 2026-09-21: "it looks like i have no
 * sources already chosen. Instead it should show something that makes it clear to the viewer
 * that the homescreen (sources) is loading."
 *
 * It names **Sources** because that is the word `CONTEXT.md` gives and the word the label over
 * the rail already uses. It says `your` because they are the household's own, and a household
 * that reads this has some.
 */
const val SOURCES_LOADING = "Loading your Sources…"

/**
 * One logcat line saying what the rail's launch read cost.
 *
 * Ticket 10 asked for this to be measured rather than guessed at. `HomeModel.load` reads
 * **every listed video of every Source** to work out each card's counts and its Next unwatched
 * title, so the cost grows with the whole library and not with what is on screen. It is fine
 * for a handful of Sources and it is the thing that will make the first frame late once a
 * household adds them freely.
 *
 * **`millis` is every video read the rail makes and nothing else.** That is the key list of
 * each Source and the one whole row each card names, which since build ticket 22 are two
 * different queries. Counting only the first would make this number look better than the rail
 * became, and this number is build ticket 22's acceptance criterion.
 *
 * **It is not the whole wait, and never was.** Deriving the Watch log sat beside it and was
 * larger than it until build ticket 22. [railLoadLine] is the whole of `load`, part by part,
 * and is the line to read first.
 */
fun railReadLine(sources: Int, videos: Int, millis: Long): String =
    "Home rail launch read: $videos listed ${plural(videos, "video", "videos")} across " +
        "$sources ${plural(sources, "Source", "Sources")} in $millis ms."

/**
 * How long each part of `HomeModel.load` took, in milliseconds.
 *
 * @property derive `WatchLog.openAndDerive`: the check that the derived table matches this
 *   build, and the rebuild when it does not.
 * @property sources `SourceStore.allSources`: the Source rows themselves.
 * @property videos every video read, which is what [railReadLine] reports on its own.
 * @property watches `WatchLog.railWatches`: one reading of the Watch log, and the Bulk mark
 *   ranges applied to every listed video.
 * @property cards building the cards and putting them in the rail's order.
 */
data class RailLoadParts(
    val derive: Long,
    val sources: Long,
    val videos: Long,
    val watches: Long,
    val cards: Long,
) {
    val total: Long get() = derive + sources + videos + watches + cards
}

/**
 * One logcat line saying what the whole of the rail's build cost, part by part.
 *
 * **It exists because reading [railReadLine] alone gives the wrong answer.** That line covers
 * the video read and nothing else. Build ticket 22 started from 1,327 ms of it and found
 * another 1,670 to 1,820 ms beside it, in applying the Bulk mark ranges, which nobody had
 * measured because no line said it was there. Twice in that ticket the split was added by hand
 * as a temporary `[DEBUG-t22]` line and then taken out again, and the session after it had to
 * put it back before it could see anything. So it is permanent, and it is one line.
 *
 * It is written once per rail build, which is the launch and every return from a Source.
 */
fun railLoadLine(parts: RailLoadParts): String =
    "Home rail built in ${parts.total} ms: derive ${parts.derive}, " +
        "Sources ${parts.sources}, videos ${parts.videos}, watches ${parts.watches}, " +
        "cards ${parts.cards}."

/**
 * One logcat line saying that a card was built from a video list storage had moved past.
 *
 * The rail counts a Source from its key list and then reads the one video the card names by
 * id, and those are two queries. A walk or a change check that drops that video between them
 * leaves the card holding a count with no video to name, and it would draw
 * `N UNWATCHED` beside [NOTHING_LEFT]. That contradiction is what ticket 23 removed from the
 * Source screen, and spec sections 4.4 and 7.1 forbid it.
 *
 * `HomeModel.load` reads the whole rail again when it sees this, once. The line is written
 * whether or not the retry then mends it, because a household seeing a wrong card twice in a
 * row should leave something behind that says why.
 */
fun railStaleCardLine(sourceId: String, videoId: String): String =
    "Home rail read $sourceId again: it counted $videoId as its Next unwatched and the store " +
        "no longer lists that video."

private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

/**
 * One logcat line saying what the rail drew and in what order.
 *
 * The rail's order is a rule and not a picture, so this is what proves it on the television,
 * the same way `landingLogLine` proves the Source screen's chips.
 */
fun railLogLine(cards: List<SourceCard>): String =
    "Home rail, most recently played first: " +
        cards.joinToString(", ") { "${it.sourceId} (${unwatchedBadge(it)})" } +
        "."
