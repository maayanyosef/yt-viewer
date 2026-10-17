package local.youtubetv.youtube

/**
 * Matches an ISO 8601 duration.
 *
 * Two shapes matter on real data.
 *
 * - A normal video is `PT10M31S`, and a long one is `PT1H4M35S`.
 * - A stream that is live right now, and a premiere that has not started, are both `P0D`.
 *   - Those carry no length yet, so they parse to zero seconds rather than failing.
 *
 * The pattern carries no anchors, because `matchEntire` already anchors the whole text.
 */
private val ISO_DURATION =
    Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

private const val SECONDS_PER_DAY = 86_400
private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_MINUTE = 60

/**
 * Parses an ISO 8601 duration into whole seconds.
 *
 * Returns null when the text is missing or is not a shape this app has seen. A null means
 * "unknown", and every caller must treat unknown as a reason to classify nothing, never as
 * zero. See [local.youtubetv.walk.isShort].
 */
fun parseIso8601DurationSeconds(text: String?): Int? {
    val match = text?.let { ISO_DURATION.matchEntire(it) } ?: return null
    val (days, hours, minutes, seconds) = match.destructured
    // Every component is optional in the pattern, so bare "P" and "PT" match it. Neither says
    // anything about a length, so they are unknown, not zero.
    if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
    return days.toIntOrZero() * SECONDS_PER_DAY +
        hours.toIntOrZero() * SECONDS_PER_HOUR +
        minutes.toIntOrZero() * SECONDS_PER_MINUTE +
        seconds.toIntOrZero()
}

private fun String.toIntOrZero(): Int = if (isEmpty()) 0 else toInt()
