package local.youtubetv.data

import java.time.Instant

/**
 * The few facts about a video that a count and a watch rule need, and nothing else.
 *
 * It exists because the home rail reads **every** video of **every** Source to work out four
 * numbers and one title, and reading a whole [StoredVideo] for each of them is what made the
 * first frame late. Measured on the household television by build ticket 22, on the Source of
 * 5,304 videos:
 *
 * | Read | Cold |
 * | --- | --- |
 * | Ten columns into a [StoredVideo] | 1,142 ms |
 * | The two columns the narrow read then had | 123 ms |
 *
 * The cost is per column and per row, not in SQLite: the query itself took 57 ms of that
 * 1,142 and `moveToNext` took 60 ms. The rest was `Cursor` accessors and `Availability.valueOf`.
 *
 * The narrow read asks for **three** columns since build ticket 40 added [newlyListed], which
 * the rail's badge now depends on. One more integer accessor a row is the price of the card and
 * the Source screen giving the same answer, and it is nowhere near the seven the read still
 * leaves out.
 *
 * [StoredVideo] is one of these, so everything that used to be handed a whole video list still
 * is. Only the home rail asks for the narrow read, through [SourceStore.listedVideoKeys].
 *
 * @property videoId the video. It is what a [WatchState] is keyed by.
 * @property sourceId which Source this row belongs to. A `range` covers one Source, so a watch
 *   rule cannot be applied without it.
 * @property videoPublishedAt the date a `range` is compared against, and the order the rail
 *   reads in. Null means private or deleted, which no range can cover.
 * @property newlyListed whether a walk has only just listed this video, and the household has
 *   played nothing at or past it since. See [StoredVideo.newlyListed], which is where the whole
 *   of what it means is written down. It is here, on the narrow read as well, because the rail
 *   works out the badge and the Next unwatched slot from these rows alone: a card that could
 *   not see the flag would disagree with the Source screen, which is the one thing ticket 23
 *   and build ticket 30 both had to undo.
 */
interface VideoKey {
    val videoId: String
    val sourceId: String
    val videoPublishedAt: Instant?
    val newlyListed: Boolean
}

/**
 * One row of [SourceStore.listedVideoKeys]: a listed video, with only what the rail needs.
 *
 * **Every row of such a read is already listed.** The three conditions of [StoredVideo.isListed]
 * are applied by the query, so nothing that holds one of these has to test it again, and
 * nothing can: the fields the test is made of are not here.
 */
data class ListedVideoKey(
    override val videoId: String,
    override val sourceId: String,
    override val videoPublishedAt: Instant?,
    override val newlyListed: Boolean = false,
) : VideoKey

/**
 * One video of one Source, as stored on the television.
 *
 * Videos are cache. They are not part of the synced log, and they are rebuilt by re-walking
 * the Source. See spec section 3.4 and ADR 0001.
 *
 * Every field here is raw: it is what YouTube said, not what this app concluded. There is
 * deliberately no "skip this video" flag. Whether a video is shown, counted, or eligible as
 * Next unwatched is decided at query time from these fields, by the extensions below.
 */
data class StoredVideo(
    override val videoId: String,
    override val sourceId: String,
    /**
     * `playlistItems.contentDetails.videoPublishedAt`. This is the only date this app orders
     * by. Null means the video is private or deleted, so YouTube gave no date.
     */
    override val videoPublishedAt: Instant?,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    /** `snippet.liveBroadcastContent`: `none`, `live` or `upcoming`. */
    val liveBroadcastContent: String,
    /** `liveStreamingDetails.actualEndTime`. Set only on a stream that has ended. */
    val liveEndedAt: Instant?,
    val availability: Availability,
    /** Classified once, at the walk, and never changed. See [local.youtubetv.walk.isShort]. */
    val isShort: Boolean,
    /**
     * Whether a walk listed this video for the **first** time, and the household has played
     * nothing at or past it in this Source since. Build ticket 40.
     *
     * **It is about when the app first saw the video, never about when YouTube published it.**
     * A video published last week that the app lists today, because nobody opened the app, is
     * newly listed. See `CONTEXT.md`, which calls this **Newly listed** and nothing else.
     *
     * What it is for: in a **newest-first** Source such a video arrives above the high-water
     * mark, where `nextUnwatchedLanding` used to throw it away with the videos the household
     * really did skip. The household never had the chance to skip it, so it is offered. An
     * oldest-first Source needs no exception at all, and the flag changes nothing there: a new
     * video already arrives after the mark.
     *
     * Who writes it, and there are only two:
     *
     * - `mergeRefresh` sets it on a video a walk added, **and only when the Source already had
     *   videos**. So the first walk of a newly added Source flags nothing, and neither does the
     *   first walk after the video tables are rebuilt.
     * - `SourceStore.clearNewlyListedUpTo` clears it, from the two places in `WatchLog` that
     *   append a `played` entry. A Bulk mark does not clear it, on purpose: see ticket 40.
     *
     * **A flag and not a date.** A date would have to be compared against the time of the last
     * Watch, and `WatchDerivation.kt` forbids that: an Android TV can boot with a wrong clock,
     * which is the whole reason `seq` exists. A flag involves no clock.
     *
     * **Losing it is safe, and that is why it is stored on cache at all.** `videos` is cache and
     * can be rebuilt by re-walking. If the table is ever rebuilt every video reads as not newly
     * listed, because an empty column reads as false, so the app offers less and never more.
     * That is also what makes database version 10 need no backfill: every one of the
     * household's 6,904 existing rows reads correctly as not newly listed.
     */
    override val newlyListed: Boolean = false,
    /**
     * `statistics.viewCount`: how many times anyone has viewed this video on YouTube, as the
     * last walk that read it heard it. Build ticket 59. It is YouTube's number and has nothing
     * to do with the household's own Watches.
     *
     * **Null means no count, and never zero.** YouTube sends none for an upcoming premiere, and
     * every row stored before database version 11 has none until the one-time first read of
     * `ViewCountFirstRead` reaches it.
     *
     * **It is the one field a refresh does not compare.** It moves every day, so a full walk
     * that compared it would find every Source changed and rewrite all of them, which is the
     * work build ticket 56 took away. `storedVideosChanged` leaves it out, and a count that
     * moved on its own is written by `SourceStore.writeViewCounts` instead.
     */
    val viewCount: Long? = null,
) : VideoKey

/** Whether YouTube still serves this video. Both states can change back on their own. */
enum class Availability {
    AVAILABLE,

    /**
     * Private or deleted. The video stays in `playlistItems.list` with no `videoPublishedAt`
     * and is missing from `videos.list`. See spec section 4.4.
     */
    UNAVAILABLE,
}

/**
 * An [Availability] by the name it is stored under, without `Enum.valueOf`.
 *
 * **It is here because it was measured.** Build ticket 22 timed the home rail's launch read on
 * the household television and found `Availability.valueOf` costing 220 ms of a 1,142 ms read
 * of 5,304 rows, which is about 41 microseconds a row. `Enum.valueOf` goes through the class's
 * reflective constant directory; two string comparisons do not.
 *
 * **An unknown name still throws, exactly as `valueOf` did.** Only this app writes the column,
 * and it writes [Enum.name], so no stored row can hold anything else. A row that somehow does
 * is a broken database and must say so, rather than being quietly read as available.
 */
fun availabilityNamed(name: String): Availability = when (name) {
    "AVAILABLE" -> Availability.AVAILABLE
    "UNAVAILABLE" -> Availability.UNAVAILABLE
    else -> Availability.valueOf(name)
}

/** `none`, `live` and `upcoming` as YouTube spells them in `snippet.liveBroadcastContent`. */
object LiveBroadcastContent {
    const val NONE = "none"
    const val LIVE = "live"
    const val UPCOMING = "upcoming"
}

/**
 * A finished live stream. The video is kept and shown like any other.
 *
 * **Nothing on screen marks it.** The card carried a `WAS LIVE` badge until 2026-09-19, when
 * The owner had it removed: it covered part of the thumbnail and said almost nothing, which a
 * Source whose channel streams almost everything made obvious. See spec sections 4.4 and 7.3.
 *
 * The fact itself is still worth keeping, because it is what YouTube said and this type holds
 * what YouTube said. The walk sets it and `SourceWalkTest` covers it.
 */
val StoredVideo.wasLive: Boolean
    get() = liveEndedAt != null

/**
 * A premiere or a scheduled stream that has not started.
 *
 * Not a listed video. See [isListed]. A live stream that is **running** is a different thing
 * and is listed like any other video: the household can play it right now.
 */
val StoredVideo.isUpcoming: Boolean
    get() = liveBroadcastContent == LiveBroadcastContent.UPCOMING

/** Private or deleted: YouTube will not serve it. Not a listed video. See [isListed]. */
val StoredVideo.isUnavailable: Boolean
    get() = availability == Availability.UNAVAILABLE

/**
 * Whether the grid shows this video at all.
 *
 * Three facts drop a video from the list, and spec section 4.3 spells out what follows for all
 * three: not drawn on the grid, in none of the counts the household sees, and never a landing
 * point.
 *
 * - A **Short**. Always hidden, never counted, no toggle anywhere in the app. Spec 4.3.
 * - An **unavailable** video: private or deleted. Spec 4.4.
 * - An **upcoming** premiere or scheduled stream. Spec 4.4.
 *
 * **The last two are read fresh every time, and that is the whole of how they come back.**
 * `isShort` is classified once and stored, because a Short never stops being one. Availability
 * and the live state are not conclusions at all: they are what YouTube said at the last walk,
 * held in [availability] and [liveBroadcastContent]. The moment a walk hears that a private
 * video is public again, the stored fields change, this flag turns true again, and the card is
 * back on the grid with no household action. There is no "hidden" flag to clear, because a
 * stored flag is the one shape that could not undo itself. See spec sections 4.4 and 5.2.
 *
 * **It is the next *full* walk, and that can be up to a week away.** Neither of these two
 * changes moves the Source's remote item count, so `SourceRefresh`'s change check reads the
 * Source as unchanged and does not re-list it. Only `RefreshTrigger.WEEKLY_FULL_WALK` re-lists
 * regardless, and the household's own `Refresh` on the card menu is not a full walk. A premiere
 * that starts at 20:00 therefore has no card until the next weekly full walk. Found by
 * `/code-review` on ticket 23, recorded there as a known gap, and not changed here: making a
 * manual `Refresh` a full walk is a quota decision that belongs to spec section 5.1, not to
 * this rule.
 *
 * **Amended 2026-09-20 by build ticket 23.** Until then an unavailable or upcoming video was
 * listed and drawn with a badge. It was hidden because the household's 19-video Source could
 * never reach `ALL WATCHED`: two deleted videos were counted as unwatched, while no landing and
 * no Bulk mark could ever reach them. The card read `2 UNWATCHED` beside `Nothing left in this
 * Source`. The owner was shown the cost, which is that a watched video YouTube later drops leaves
 * the grid, and chose to hide both anyway.
 */
val StoredVideo.isListed: Boolean
    get() = !isShort && !isUnavailable && !isUpcoming
