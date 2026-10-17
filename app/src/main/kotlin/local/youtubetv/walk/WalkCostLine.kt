package local.youtubetv.walk

import kotlin.math.ceil

/**
 * What a walk should have cost, from what it actually listed.
 *
 * A full walk of N items costs `2 * ceil(N / 50)` units, plus one unit per `UUSH` page for a
 * channel Source. See spec section 4.1. Two details stop that from being a single division:
 *
 * - The two halves count different things. `playlistItems.list` pages over every **entry** in
 *   the playlist, and `videos.list` pages over every **distinct** video id. A playlist that
 *   lists the same video twice has more entries than videos.
 * - An empty playlist still costs one page. YouTube has to be asked before it can say there
 *   is nothing there.
 */
fun expectedApiCalls(playlistEntries: Int, distinctVideos: Int, shortsPages: Int): Int {
    val playlistPages = maxOf(1, pagesFor(playlistEntries))
    return playlistPages + pagesFor(distinctVideos) + shortsPages
}

private fun pagesFor(items: Int): Int = ceil(items / 50.0).toInt()

/**
 * The log line that reports what one walk cost.
 *
 * It prints the real count and the expected one, and says plainly whether they agree. A walk
 * that quietly starts costing double is then visible in logcat, instead of only in the quota
 * graph a day later.
 */
fun walkCostLine(
    sourceId: String,
    playlistEntries: Int,
    distinctVideos: Int,
    calls: ApiCallCount,
): String {
    val expected = expectedApiCalls(playlistEntries, distinctVideos, calls.shortsPages)
    val verdict = if (calls.total == expected) "matches" else "DOES NOT MATCH"
    val duplicates =
        if (playlistEntries == distinctVideos) "" else " ($playlistEntries playlist entries)"
    return "Walked $sourceId: $distinctVideos videos$duplicates, ${calls.total} API calls " +
        "(${calls.playlistPages} playlistItems + ${calls.videoPages} videos + " +
        "${calls.shortsPages} UUSH). Expected $expected, $verdict."
}
