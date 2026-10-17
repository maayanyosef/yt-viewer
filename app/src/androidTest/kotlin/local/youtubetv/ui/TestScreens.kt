package local.youtubetv.ui

import java.time.Instant
import local.youtubetv.data.Availability
import local.youtubetv.data.Direction
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.SourceCard
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchState
import local.youtubetv.youtube.CandidateKind
import local.youtubetv.youtube.SourceCandidate

/**
 * The made-up Sources and videos the instrumentation tests draw.
 *
 * **Nothing here reads the database and nothing here calls YouTube.** Both screens take their
 * whole state as a parameter, which is what makes this possible: `HomeScreen` takes a
 * [local.youtubetv.ui.HomeScreenState] and `SourceScreen` takes a
 * [local.youtubetv.ui.SourceScreenState], and neither of them knows where that state came from.
 * The JVM tests use the same property through `SourceStore`'s in-memory implementation.
 *
 * It is a second copy of `app/src/test/kotlin/local/youtubetv/data/TestVideos.kt` and not a
 * shared file, because the unit-test source set and the instrumentation source set are compiled
 * apart and joining them would put the JVM tests' fakes into an APK.
 */

/** The date every test video is measured from. Day 1 is the day after it. */
val TEST_EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** Video "day n" is published n days after [TEST_EPOCH], so a higher number is newer. */
fun testDay(day: Int): Instant = TEST_EPOCH.plusSeconds(day * 86_400L)

fun testVideo(
    videoId: String,
    day: Int = 1,
    sourceId: String = "UUx",
    title: String = "Video $videoId",
): StoredVideo = StoredVideo(
    videoId = videoId,
    sourceId = sourceId,
    videoPublishedAt = testDay(day),
    title = title,
    thumbnailUrl = null,
    durationSeconds = 600,
    liveBroadcastContent = LiveBroadcastContent.NONE,
    liveEndedAt = null,
    availability = Availability.AVAILABLE,
    isShort = false,
)

/**
 * One Source's rail card, with a real Hebrew name and a two-line title.
 *
 * Both are what a household's real Sources look like, and both are what the card is tightest
 * on: a Hebrew name has to stay on one line, and a long title is what fills the Next unwatched
 * slot to its full two lines.
 */
fun testSourceCard(
    sourceId: String = "UUx",
    title: String? = "ערוץ לדוגמה - כל הסרטונים",
    nextUnwatched: StoredVideo? = testVideo(
        "v1",
        day = 200,
        title = "A title long enough to need both of the lines the slot keeps for it",
    ),
    listedCount: Int = 57,
    unwatchedCount: Int = 12,
    walked: Boolean = true,
): SourceCard = SourceCard(
    sourceId = sourceId,
    title = title,
    typedName = null,
    direction = Direction.NEWEST_FIRST,
    listedCount = listedCount,
    unwatchedCount = unwatchedCount,
    nextUnwatched = nextUnwatched,
    lastPlayedVideoId = null,
    everythingUnwatchedCount = null,
    walked = walked,
)

/**
 * A Source screen with [count] videos, the newest first, and the first [watchedUpTo] of them
 * watched.
 *
 * The high-water mark is therefore [watchedUpTo] and the Source opens on the card after it, so
 * a screen that always lands on card 0 cannot pass a test written against this. That is build
 * ticket 33's defect, which the first television pass missed for exactly that reason.
 */
fun testSourceScreen(
    count: Int = 24,
    watchedUpTo: Int = -1,
    sourceId: String = "UUx",
): SourceScreenState.Ready {
    // Newest first, which is the default Direction, so the list is already in screen order.
    val videos = (0 until count).map { index ->
        testVideo(
            videoId = "v$index",
            day = (count - index) * 3,
            sourceId = sourceId,
            title = "Video number $index",
        )
    }
    val watches = videos.take(watchedUpTo + 1).associate { video ->
        video.videoId to WatchState(
            videoId = video.videoId,
            watched = true,
            positionSeconds = null,
            completed = true,
            lastPlayedAt = null,
        )
    }
    return SourceScreenState.Ready(
        videos = videos,
        updatedAt = TEST_EPOCH,
        fromStorage = true,
        watches = watches,
        direction = Direction.NEWEST_FIRST,
    )
}

/**
 * One row of the add screen's results, for build ticket 48's ladder tests.
 *
 * **Nothing here asks YouTube.** `AddSourceScreen` takes its whole state as a parameter, the
 * same way both other screens do, so a made-up candidate is all a test of that screen needs and
 * the `search.list` bucket of 100 calls a day is untouched.
 */
fun testCandidate(
    playlistId: String = "PLx",
    channelId: String? = "UCx",
    title: String = "Something worth watching",
    kind: CandidateKind = CandidateKind.PLAYLIST,
): SourceCandidate = SourceCandidate(
    kind = kind,
    playlistId = playlistId,
    channelId = channelId,
    title = title,
    handle = "@something",
    thumbnailUrl = null,
    videoCount = 57,
)
