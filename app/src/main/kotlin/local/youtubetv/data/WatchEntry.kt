package local.youtubetv.data

import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONException
import org.json.JSONObject

/**
 * One entry of the household's Watch log.
 *
 * The log is the single source of truth for what the household has watched, it is append-only,
 * and nothing in it is ever deleted. Everything else about a Watch is derived from it. The full
 * decision is `docs/adr/0001-watch-model.md`; spec sections 3.1 to 3.3 are the short version.
 *
 * Five fields are common to every entry and are stored as columns. The rest depend on the
 * [kind] and live in [payload] as JSON.
 *
 * **Why a payload and not one column per field.** ADR 0001 requires that an entry of a kind
 * this build does not recognise is preserved, ignored, and written back untouched. With a
 * column per field, an entry written by a newer build would lose any field this build has no
 * column for, and the loss would be silent. With a payload the row survives whole, because
 * nothing here has to understand it.
 *
 * @property id random and unique, so two devices can never write the same one.
 * @property deviceId which device wrote the entry.
 * @property seq a per-device counter that only goes up.
 * @property at wall-clock time, which on an Android TV can jump backwards shortly after boot.
 *   That is why [seq] exists as well. See [inLogOrder].
 * @property kind a plain string, never an enum, so an unknown one can be stored and read back.
 * @property payload the kind's own fields, as a JSON object. Opaque to everything but the
 *   readers below.
 */
data class WatchEntry(
    val id: String,
    val deviceId: String,
    val seq: Long,
    val at: Instant,
    val kind: String,
    val payload: String,
)

/**
 * The entry kinds ADR 0001 names.
 *
 * They are `const` strings and not an enum, because the log must be able to hold a kind this
 * build has never heard of. Only [PLAYED] and [UNWATCHED] are written or read today.
 */
object WatchKind {
    /** One play of one video. A manual `Mark as watched` writes one of these too. */
    const val PLAYED = "played"

    /** The household marked one video as not watched. */
    const val UNWATCHED = "unwatched"

    /** A Bulk mark range. One entry covers every video on one side of a pivot date. */
    const val RANGE = "range"

    /** A Source was added. */
    const val SOURCE_ADDED = "sourceAdded"

    /** A Source was removed. */
    const val SOURCE_REMOVED = "sourceRemoved"
}

/**
 * What a [WatchKind.SOURCE_ADDED] or [WatchKind.SOURCE_REMOVED] entry says.
 *
 * **Neither kind decides anything the household sees today.** They name no video, so
 * [deriveWatchStates] skips them and no card changes because one was written. They are in the
 * log because ADR 0001 wants the log to be the whole story of what the household did, and a
 * Source that came and went twice is part of that story. A later build replaying this log on a
 * second device is what will read them.
 *
 * @property title what the Source was called when it was added, or null for a removal, which
 *   names only the Source. The title is written down because the Source's own row is deleted
 *   on a removal and nothing else would remember what the household had added.
 */
data class SourceChange(val sourceId: String, val title: String?)

/**
 * What a [WatchKind.PLAYED] entry says.
 *
 * @property positionSeconds how far the play got, or null when there was no play to measure.
 *   A manual `Mark as watched` writes null: the household said it had seen the video, not that
 *   it had sat through a particular number of seconds on this television. Ticket 07 writes a
 *   real number here and keeps changing it while the play is on screen.
 */
data class Played(
    val videoId: String,
    val sourceId: String,
    val positionSeconds: Int?,
    val completed: Boolean,
    /**
     * True when Shuffle chose this play rather than the household. Build ticket 69.
     *
     * A Shuffle play is a Watch like any other play, and it moves Last played like any other,
     * but **it never moves the High-water mark**. See `docs/adr/0002-a-shuffle-play-never-moves-the-high-water-mark.md`
     * and [WatchState.countsForHighWaterMark].
     *
     * It is written into the payload only when it is true. **An entry with no such field reads
     * as false**, so every `played` entry written before build ticket 69 is an ordinary play,
     * and a replay of an old log gives the High-water mark it always gave.
     */
    val shuffle: Boolean = false,
)

/** What a [WatchKind.UNWATCHED] entry says. */
data class Unwatched(val videoId: String, val sourceId: String)

/**
 * Which side of the pivot a Bulk mark range covers.
 *
 * The wording is **older** and **newer**, everywhere, and never "before" and "after". Before
 * and after invert the moment the Source's Direction is flipped, so the household would be
 * reading the same two words to mean opposite things. See spec section 7.3.
 */
enum class RangeSide(
    /** How the side is spelled inside the payload, and so inside a synced log forever. */
    val stored: String,
) {
    OLDER("older"),
    NEWER("newer"),
    ;

    companion object {
        /** The side this payload names, or null when it names neither. */
        fun ofStored(stored: String): RangeSide? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * What a [WatchKind.RANGE] entry says: one Bulk mark, covering many videos.
 *
 * A Bulk mark writes exactly **one** of these, never one entry per video. That is what makes a
 * video found by a later walk fall inside a range already marked, with no second Bulk mark and
 * no fix-up code at walk time. See ADR 0001, "Bulk mark ranges".
 *
 * It names no video. It carries a pivot date and a side, so which videos it covers is worked
 * out whenever the question is asked, against whatever videos are stored at that moment.
 *
 * @property pivotPublishedAt the chosen video's own publish date. A video with no publish
 *   date, which is what a private or deleted video looks like, can never be a pivot.
 * @property madeAt the moment the household made this Bulk mark. It is the entry's own
 *   [WatchEntry.at] and nothing else, so no field was added to the log and no row was
 *   migrated.
 *
 *   On the `newer` side it is the far end of the range. See [coversDate], which explains why
 *   a `newer` Bulk mark stops at the moment it was made, and why the `older` side ignores
 *   this field.
 * @property watched true under `Mark as watched`, false under `Mark as unwatched`.
 * @property clearsLastPlayed set by one action only, `Mark everything unwatched`, which lives
 *   on the home screen. No other range touches Last played.
 *
 *   It is read by [lastPlayedVideoId], which ticket 06 wrote: such a range leaves the whole
 *   Source with no Last played until its next positioned `played` entry, and its side and its
 *   pivot date are not consulted. Ticket 10 is what writes one, from the home rail's
 *   long-press menu.
 */
data class Range(
    val sourceId: String,
    val pivotPublishedAt: Instant,
    val madeAt: Instant,
    val side: RangeSide,
    val watched: Boolean,
    val clearsLastPlayed: Boolean,
) {
    /**
     * The far end of the `newer` side, worked out once for the whole range.
     *
     * It is the later of [madeAt] and [pivotPublishedAt], and [coversDate] explains why the
     * floor is there. The `older` side never reads it.
     *
     * It sits in the class body and not in the constructor on purpose. No caller can pass a
     * value that disagrees with [madeAt] and [pivotPublishedAt], `copy` works it out again
     * from whatever it was handed, and `equals` and `hashCode` are unaffected because a data
     * class builds those from its constructor alone.
     *
     * Working it out here rather than inside [coversDate] is what build ticket 22 asked for.
     * That ticket measured this loop on the household television at 1,425 ms for 6,051 videos
     * over 14 ranges, and [coversDate] exists to keep the work inside it to the date rule
     * alone. Before this, the comparison ran once per video per range instead of once per
     * range.
     */
    val newerFarEnd: Instant = maxOf(madeAt, pivotPublishedAt)
}

/**
 * Whether this range covers a video.
 *
 * Both sides **include the pivot instant**, so the chosen video is always covered by its own
 * Bulk mark, and so is anything YouTube published at the same instant. Being inclusive is also
 * what lets the rule be stated with dates and a side alone, carrying no video id.
 *
 * **A tied video is covered whichever side the range names.** A tie is exactly the same publish
 * time. YouTube gives publish times to the second, so videos one second apart are not a tie.
 * The grid orders a tie by video id. So `This and all older` also covers a tied video the grid
 * draws on the newer side of the chosen card. `This and all newer` covers one drawn on the
 * older side. The owner chose to keep this on 2026-10-03, in build ticket 58, rather than add the
 * pivot's video id to the log. Spec section 4.2 says so.
 *
 * The `newer` side has a second end, at [Range.madeAt], and it is inclusive in the same way.
 * See [coversDate] for the whole rule and for why the `older` side has no such end.
 *
 * A video with no publish date is covered by nothing. There is no date to compare, and
 * guessing would mark a video the household never saw. See spec section 4.4.
 *
 * A range covers hidden Shorts as well. ADR 0001 is explicit about that: only the counts the
 * household is shown leave Shorts out.
 */
fun Range.covers(sourceId: String, videoPublishedAt: Instant?): Boolean =
    sourceId == this.sourceId && coversDate(videoPublishedAt)

/**
 * The date half of [covers], for a caller that already knows the range and the video share a
 * Source.
 *
 * It exists so that the rule is still written once. Build ticket 22 measured the home rail on
 * the household television: matching every range's `sourceId` against every video's, for
 * 6,051 videos, was most of the 1,425 ms the rail spent applying ranges. `LogReading` now keys
 * its ranges by Source id, so the Source is settled by a map lookup and the date rule below is
 * all that is left to ask per video.
 *
 * **It is not the whole rule.** A range belongs to one Source, so anything calling this has to
 * have matched the Source itself. Use [covers] wherever that is not already true.
 *
 * ## The `newer` side has two ends, and the `older` side has one
 *
 * A `newer` Bulk mark covers a video when **both** of these hold:
 *
 * 1. The video's publish time is not **before** [Range.pivotPublishedAt]. So a video tied with
 *    the pivot is covered, and the `older` side covers it too. See [covers] and spec section 4.2.
 * 2. The video's publish time is not **after** the later of [Range.madeAt] and
 *    [Range.pivotPublishedAt]. It is written that way, and not as [Range.madeAt] alone,
 *    because of the wrong-clock case in "The bound is a wall clock" below.
 *
 * In plain words, a `newer` Bulk mark means "everything from this video up to now", and not
 * "everything from this video onwards, for ever". Ticket 42 changed this on 2026-09-21. Before
 * it, a Source whose newest videos had been bulk-marked watched could never offer a new upload:
 * every future upload has a later publish date, so it arrived already watched and was never
 * Next unwatched.
 *
 * **The `older` side keeps its single end on purpose.** It is bounded by the pivot on the side
 * where new videos arrive, so no future upload can ever fall inside it. Its open end points
 * into the past, and the only thing that can appear there is a video published long ago and
 * listed late. ADR 0001 wants exactly that video covered, and that is why a range is a date
 * range and not a list of video ids.
 *
 * The same open end on the past side is what keeps ADR 0001's central case working for
 * `newer` too. A video published **before** the Bulk mark and listed weeks later is still
 * covered, because its publish date is before [Range.madeAt].
 *
 * ## The bound is a wall clock, and that was accepted
 *
 * [Range.madeAt] is the entry's `at`, which is wall-clock time from a television that can boot
 * with the wrong clock. ADR 0001 is explicit about that risk, and it is why `seq` exists. It
 * is accepted here, and this is not an oversight:
 *
 * - **A clock set in the past is the mild direction.** It writes a bound that is too early,
 *   so the Bulk mark covers fewer videos than the household meant, and the household can
 *   simply make the Bulk mark again.
 * - **A clock set in the future is the severe direction, and it is not fixed.** Found by
 *   `/code-review high` on 2026-09-21, reviewing this ticket's work again after the household
 *   television had run it. [WatchLog.markRange] writes `at = now()` with no ceiling on it, so
 *   a television that boots reading a date years ahead writes a `newer` range whose far end is
 *   that date. Every upload until then arrives already watched, which is the very defect build
 *   ticket 42 was raised to remove. Worse, the household cannot repair it: `applyRange` in
 *   [WatchDerivation] takes the **last covering** range, and a later, correctly clocked Bulk
 *   mark does not cover the new upload at all, so the bad range goes on deciding it.
 *
 *   It is **not a regression**. Before build ticket 42 every `newer` range ran on for ever, so
 *   a range that stops in 2030 is strictly better than one that never stops. Nothing in the
 *   household's own data is affected: every `newer` range in the log was made on 2026-09-20,
 *   which is a real date and later than every pivot. It is build ticket 45, which
 *   carries three options and needs the owner's choice, because every ceiling worth having changes
 *   what ADR 0001 promises about replaying the log.
 * - **The far end can never fall below the pivot**, which is why the bound above is
 *   `maxOf(madeAt, pivotPublishedAt)` and not `madeAt` alone. A television whose clock is
 *   behind the newest video's publish date would otherwise write a range that covers
 *   **nothing at all**, not even the video the household chose, and pressing
 *   `This and all newer` again would do nothing either until the clock was fixed. With the
 *   floor the worst case is a Bulk mark that covers only the pivot video.
 * - **For `This and all newer` that worst case is mild**, because the household chose the
 *   pivot: the result is what `Just this` would have done, which is a result they can see and
 *   act on.
 * - **For `Mark everything unwatched` it is not mild, and that path is still wrong.** Found by
 *   `/code-review high` on 2026-09-21 in the review of `14ce6e9` itself. `everythingUnwatched`
 *   in `data/HomeRail.kt` writes a `NEWER` range pivoted on the Source's **oldest** dated
 *   video, which the household never chose and never saw. A clock reading earlier than that
 *   video's publish date, which is what a television with no network can boot with, collapses
 *   the range to that one video. The confirmation promises every
 *   video in the Source and one video is unmarked. Worse, `clearsLastPlayed` is not date
 *   bounded at all, so Last played is cleared whatever the clock says: the household sees
 *   their place gone and nearly every video still ticked. The floor made this **better** than it was,
 *   from nothing unmarked to one, so it is not a regression, but it is not fixed. It is build
 *   ticket 44.
 * - The alternative is bounding by the newest video the Source had listed at the time. That
 *   is not stored, and storing it would mean a new field on every range entry.
 */
fun Range.coversDate(videoPublishedAt: Instant?): Boolean {
    if (videoPublishedAt == null) return false
    return when (side) {
        RangeSide.OLDER -> !videoPublishedAt.isAfter(pivotPublishedAt)
        RangeSide.NEWER ->
            !videoPublishedAt.isBefore(pivotPublishedAt) &&
                !videoPublishedAt.isAfter(newerFarEnd)
    }
}

private const val KEY_VIDEO_ID = "videoId"
private const val KEY_SOURCE_ID = "sourceId"
private const val KEY_POSITION = "position"
private const val KEY_COMPLETED = "completed"
private const val KEY_PIVOT_PUBLISHED_AT = "pivotPublishedAt"
private const val KEY_SIDE = "side"
private const val KEY_WATCHED = "watched"
private const val KEY_CLEARS_LAST_PLAYED = "clearsLastPlayed"
private const val KEY_TITLE = "title"
private const val KEY_DIRECTION = "direction"
private const val KEY_SHUFFLE = "shuffle"

/**
 * The payload of a [WatchKind.PLAYED] entry. `position` is left out when there is none.
 *
 * `shuffle` is written only when it is true, so an ordinary play's payload is exactly what it
 * was before build ticket 69. See [Played.shuffle].
 */
fun playedPayload(
    videoId: String,
    sourceId: String,
    positionSeconds: Int?,
    completed: Boolean,
    shuffle: Boolean = false,
): String = JSONObject()
    .put(KEY_VIDEO_ID, videoId)
    .put(KEY_SOURCE_ID, sourceId)
    .apply { if (positionSeconds != null) put(KEY_POSITION, positionSeconds) }
    .put(KEY_COMPLETED, completed)
    .apply { if (shuffle) put(KEY_SHUFFLE, true) }
    .toString()

/** The payload of a [WatchKind.UNWATCHED] entry. */
fun unwatchedPayload(videoId: String, sourceId: String): String = JSONObject()
    .put(KEY_VIDEO_ID, videoId)
    .put(KEY_SOURCE_ID, sourceId)
    .toString()

/**
 * The payload of a [WatchKind.RANGE] entry.
 *
 * The pivot date is written as ISO-8601 text rather than as a number of milliseconds. This log
 * is meant to be replayed one day by a device this build has never met, and text says what it
 * means on its own.
 */
fun rangePayload(
    sourceId: String,
    pivotPublishedAt: Instant,
    side: RangeSide,
    watched: Boolean,
    clearsLastPlayed: Boolean,
): String = JSONObject()
    .put(KEY_SOURCE_ID, sourceId)
    .put(KEY_PIVOT_PUBLISHED_AT, pivotPublishedAt.toString())
    .put(KEY_SIDE, side.stored)
    .put(KEY_WATCHED, watched)
    .put(KEY_CLEARS_LAST_PLAYED, clearsLastPlayed)
    .toString()

/**
 * The payload of a [WatchKind.SOURCE_ADDED] entry.
 *
 * The chosen Direction is written down as well, although the Source's own row is where the
 * app reads it from. It costs nothing, and a log meant to be replayed one day should say what
 * the household actually chose rather than leave a later build to guess a default.
 */
fun sourceAddedPayload(sourceId: String, title: String?, direction: Direction): String =
    JSONObject()
        .put(KEY_SOURCE_ID, sourceId)
        .apply { if (title != null) put(KEY_TITLE, title) }
        .put(KEY_DIRECTION, direction.stored)
        .toString()

/** The payload of a [WatchKind.SOURCE_REMOVED] entry. */
fun sourceRemovedPayload(sourceId: String): String = JSONObject()
    .put(KEY_SOURCE_ID, sourceId)
    .toString()

/** This entry read as a Source being added or removed, or null when it is neither. */
fun WatchEntry.sourceChange(): SourceChange? {
    if (kind != WatchKind.SOURCE_ADDED && kind != WatchKind.SOURCE_REMOVED) return null
    val json = payloadObject() ?: return null
    return SourceChange(
        sourceId = json.optString(KEY_SOURCE_ID).ifEmpty { return null },
        title = if (json.has(KEY_TITLE)) json.optString(KEY_TITLE) else null,
    )
}

/** This entry read as a play, or null when it is not one, or cannot be read. */
fun WatchEntry.played(): Played? {
    if (kind != WatchKind.PLAYED) return null
    val json = payloadObject() ?: return null
    return Played(
        videoId = json.optString(KEY_VIDEO_ID).ifEmpty { return null },
        sourceId = json.optString(KEY_SOURCE_ID),
        positionSeconds = if (json.has(KEY_POSITION)) json.optInt(KEY_POSITION) else null,
        completed = json.optBoolean(KEY_COMPLETED),
        // Missing reads as false: an entry written before build ticket 69 is an ordinary play.
        shuffle = json.optBoolean(KEY_SHUFFLE, false),
    )
}

/** This entry read as a manual unwatch, or null when it is not one, or cannot be read. */
fun WatchEntry.unwatched(): Unwatched? {
    if (kind != WatchKind.UNWATCHED) return null
    val json = payloadObject() ?: return null
    return Unwatched(
        videoId = json.optString(KEY_VIDEO_ID).ifEmpty { return null },
        sourceId = json.optString(KEY_SOURCE_ID),
    )
}

/**
 * This entry read as a Bulk mark range, or null when it is not one, or cannot be read.
 *
 * A range with no readable pivot date, or with a side this build does not recognise, reads as
 * null and is ignored. The row itself stays in the log untouched, which is ADR 0001's rule for
 * anything this build cannot make sense of.
 */
fun WatchEntry.range(): Range? {
    if (kind != WatchKind.RANGE) return null
    val json = payloadObject() ?: return null
    val pivot = try {
        Instant.parse(json.optString(KEY_PIVOT_PUBLISHED_AT))
    } catch (failure: DateTimeParseException) {
        return null
    }
    return Range(
        sourceId = json.optString(KEY_SOURCE_ID).ifEmpty { return null },
        pivotPublishedAt = pivot,
        // The entry's own wall-clock time. Nothing is read out of the payload, so no field was
        // added to the log and every range already written carries this bound. See
        // [Range.coversDate].
        madeAt = at,
        side = RangeSide.ofStored(json.optString(KEY_SIDE)) ?: return null,
        watched = json.optBoolean(KEY_WATCHED),
        clearsLastPlayed = json.optBoolean(KEY_CLEARS_LAST_PLAYED),
    )
}

/**
 * The video this entry names directly, or null when it names none.
 *
 * Null for a `range`, which covers videos by date and names none, and null for any kind this
 * build does not recognise. Not guessing is deliberate: an unknown kind's fields could mean
 * anything, and this app must not act on a meaning it invented. The entry is still kept.
 *
 * It is also the value of the indexed `video_id` column in SQLite, which is what makes
 * "every entry about this video" a cheap query.
 */
fun WatchEntry.namedVideoId(): String? = played()?.videoId ?: unwatched()?.videoId

/**
 * The payload parsed, or null when it is not a JSON object.
 *
 * A payload written by hand, or by a build that spells things differently, must never bring
 * the television down. A row this build cannot read is a row it ignores.
 */
private fun WatchEntry.payloadObject(): JSONObject? =
    try {
        JSONObject(payload)
    } catch (failure: JSONException) {
        null
    }
