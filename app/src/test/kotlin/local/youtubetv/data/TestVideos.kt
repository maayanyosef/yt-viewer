package local.youtubetv.data

import java.time.Instant

/** The date every test video is measured from. Day 1 is the day after it. */
val TEST_EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** Video "day n" is published n days after [TEST_EPOCH], so a higher number is newer. */
fun testDay(day: Int): Instant = TEST_EPOCH.plusSeconds(day * 86_400L)

/**
 * One stored video, with everything ordinary unless a test says otherwise.
 *
 * Every test that needs a [StoredVideo] builds it here, so a change to the type lands in one
 * place instead of three.
 *
 * @param day null makes the video undated, which is what a private or deleted video looks
 *   like.
 * @param newlyListed whether a walk has only just listed this video. It is false unless a test
 *   says otherwise, which is what every row written before database version 10 reads as. See
 *   [StoredVideo.newlyListed] and build ticket 40.
 */
fun testVideo(
    videoId: String,
    day: Int? = 1,
    sourceId: String = "UUx",
    title: String = "Video $videoId",
    thumbnailUrl: String? = null,
    durationSeconds: Int? = 600,
    isShort: Boolean = false,
    availability: Availability = Availability.AVAILABLE,
    liveBroadcastContent: String = LiveBroadcastContent.NONE,
    liveEndedAt: Instant? = null,
    newlyListed: Boolean = false,
): StoredVideo = StoredVideo(
    videoId = videoId,
    sourceId = sourceId,
    videoPublishedAt = day?.let(::testDay),
    title = title,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    liveBroadcastContent = liveBroadcastContent,
    liveEndedAt = liveEndedAt,
    availability = availability,
    isShort = isShort,
    newlyListed = newlyListed,
)
