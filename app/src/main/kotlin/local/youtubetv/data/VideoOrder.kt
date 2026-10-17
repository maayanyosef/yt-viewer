package local.youtubetv.data

/**
 * Which way round a Source is browsed.
 *
 * It is per Source and it is stored with the Source, never in the Watch log: it is a browsing
 * preference, not something the household watched. See `CONTEXT.md`, which calls this
 * Direction and nothing else.
 *
 * @property stored how the value is spelled in the `sources` table. Written down rather than
 *   taken from [name], so renaming the constant cannot silently flip every stored Source.
 */
enum class Direction(val stored: String) {
    NEWEST_FIRST("NEWEST_FIRST"),
    OLDEST_FIRST("OLDEST_FIRST"),
    ;

    /** The other one. `Order` flips between exactly two values and there is no third. */
    fun flipped(): Direction = if (this == NEWEST_FIRST) OLDEST_FIRST else NEWEST_FIRST

    companion object {
        /**
         * The Direction this text names, or [NEWEST_FIRST] when it names none.
         *
         * A Source row written before database version 5 has no Direction at all, and a row
         * written by a build that spells it differently is not worth failing over. Both read
         * as newest-first, which is what every Source did before Direction existed.
         */
        fun ofStored(stored: String?): Direction =
            entries.firstOrNull { it.stored == stored } ?: NEWEST_FIRST
    }
}

/**
 * Orders videos by their publish date, in the Source's Direction.
 *
 * The date is always `videoPublishedAt`. Playlist position is never used: the playlist owner
 * chooses it, the API does not expose it, and it has nothing to do with when a video came
 * out. See spec section 4.2.
 *
 * A video with no date is private or deleted. Those go last **in both Directions**, because
 * there is no honest place to put them among dated videos, and an undated video is not the
 * oldest thing in a Source merely because oldest-first was chosen.
 *
 * **A tie is drawn in video id order, the same in both Directions.** A tie is two dated videos
 * with exactly the same publish time, compared in whole milliseconds as the end of this
 * paragraph says. The undated videos among themselves take video id order too. Two videos from
 * the same day at different times are not a tie, and their publish times order them as usual.
 * Only the publish time key turns around with the Direction, so a tied pair reads in the same
 * order both ways. The result therefore depends on the videos alone and never on the order of
 * the list given, and it is the order `SqliteSourceStore` asks SQLite for, which compares the
 * id byte by byte. A YouTube video id is ASCII, so that is the same order as
 * [String.compareTo]. The publish time is compared in whole milliseconds, which is what SQLite
 * stores. See spec section 4.2.
 *
 * Until build ticket 57 this was a stable sort and a tie kept the order of the list given,
 * while SQL kept the order of rowids. The two could disagree, and on the `googletv1080p`
 * emulator on 2026-10-03 they did.
 *
 * Flipping Direction is therefore not the same as reversing the list, which is why the flip
 * calls this again rather than calling `reversed()`.
 */
fun orderByPublishDate(
    videos: List<StoredVideo>,
    direction: Direction = Direction.NEWEST_FIRST,
): List<StoredVideo> {
    val (dated, undated) = videos.partition { it.videoPublishedAt != null }
    // Compared in whole milliseconds, because that is what `SqliteSourceStore` stores and so
    // what SQL compares. Two publish times less than a millisecond apart are a tie in SQL, so
    // they must be one here too, or the id key would decide in SQL and the date would decide
    // here.
    val millis: (StoredVideo) -> Long? = { it.videoPublishedAt?.toEpochMilli() }
    val byDate = when (direction) {
        Direction.NEWEST_FIRST -> compareByDescending(millis)
        Direction.OLDEST_FIRST -> compareBy(millis)
    }
    return dated.sortedWith(byDate.then(byVideoId)) + undated.sortedWith(byVideoId)
}

/** The last ordering key, the same in both Directions. See [orderByPublishDate]. */
private val byVideoId: Comparator<StoredVideo> = compareBy { it.videoId }

/**
 * The same list with the Newly listed flag cleared on one video and on everything at or past
 * it, in the Source's Direction.
 *
 * This is the in-memory twin of [SourceStore.clearNewlyListedUpTo], and the two must agree.
 * A play writes the flag away in storage; the home rail rebuilds from storage and so sees the
 * change at once, but a screen holding its own list of videos does not. Without this the
 * Source screen would go on offering a video the rail has already dropped, which is exactly
 * the card-versus-screen disagreement build ticket 30 had to undo. Found by `/code-review
 * high` on 2026-09-21, after build ticket 40 had been closed.
 *
 * `at or past it` is a date comparison and not a list position, for the same reason the SQL
 * is: it must give the same answer whatever order the caller is holding the list in.
 *
 * **A video with no publish date is never cleared**, in either Direction, which is what the
 * SQL does too. Such a video is private or deleted, so it is not listed and is never offered.
 * A null [videoPublishedAt] clears nothing at all, for the same reason.
 */
fun clearedNewlyListed(
    videos: List<StoredVideo>,
    direction: Direction,
    videoPublishedAt: java.time.Instant?,
): List<StoredVideo> {
    if (videoPublishedAt == null) return videos
    if (videos.none { it.newlyListed }) return videos
    return videos.map { video ->
        val date = video.videoPublishedAt
        val atOrPast = date != null && when (direction) {
            Direction.NEWEST_FIRST -> !date.isBefore(videoPublishedAt)
            Direction.OLDEST_FIRST -> !date.isAfter(videoPublishedAt)
        }
        if (video.newlyListed && atOrPast) video.copy(newlyListed = false) else video
    }
}
