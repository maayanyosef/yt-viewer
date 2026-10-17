package local.youtubetv.walk

import java.time.Instant

/**
 * The day YouTube raised the Shorts length limit from 60 seconds to 180 seconds.
 *
 * A video published before this day is a Short only up to 60 seconds. One published on or
 * after it is a Short up to 180 seconds.
 */
private val SHORTS_LIMIT_RAISED_ON: Instant = Instant.parse("2024-10-15T00:00:00Z")

private const val SHORT_MAX_SECONDS_BEFORE = 60
private const val SHORT_MAX_SECONDS_AFTER = 180

/**
 * Classifies one video as a Short from its embed shape and its length.
 *
 * `isShort = embedWidth < embedHeight && secs <= (publishedAt < 2024-10-15 ? 60 : 180)`
 *
 * The embed size comes from `videos.list` with `part=...,player&maxHeight=720`. The
 * `maxHeight` parameter is mandatory: without it YouTube returns a fixed embed size and the
 * width-versus-height test says nothing. See spec section 4.3.
 *
 * **Any missing input returns false.** A live stream and an unstarted premiere report no
 * embed size, and a private video carries no date, so unknowns are common and not an error.
 * Returning false on an unknown keeps a real video visible. Hiding a real video would
 * silently break Next unwatched, which is the worse failure of the two.
 */
fun isShort(
    embedWidth: Int?,
    embedHeight: Int?,
    durationSeconds: Int?,
    publishedAt: Instant?,
): Boolean {
    if (embedWidth == null || embedHeight == null) return false
    if (durationSeconds == null || publishedAt == null) return false
    if (embedWidth >= embedHeight) return false
    val limit =
        if (publishedAt < SHORTS_LIMIT_RAISED_ON) SHORT_MAX_SECONDS_BEFORE else SHORT_MAX_SECONDS_AFTER
    return durationSeconds <= limit
}
