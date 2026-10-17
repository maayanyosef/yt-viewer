package local.youtubetv.refresh

import local.youtubetv.walk.ApiCallCount

/**
 * Every line a refresh writes to logcat.
 *
 * It is kept apart from the work for the same reason `ui/SourceScreenText.kt` is kept apart
 * from the screen: almost everything ticket 13 has to prove is a rule rather than a picture,
 * and a rule written into a log line is a JVM unit test plus one `logcat -d` on the device.
 *
 * Nothing here is shown to the household. Spec section 5.3 is explicit that a refresh has no
 * banner, no dialog and no `New` badge, and the only thing it puts on the television is the
 * quiet `(updated ...)` line the Source screen already draws.
 */

/** One line per trigger, so the four triggers of spec section 5.1 are checkable in the log. */
fun refreshStartLine(trigger: RefreshTrigger, sources: Int): String =
    "Refresh started by ${trigger.stored}: $sources ${plural(sources, "Source", "Sources")} to check."

/**
 * What the change check cost and what it found.
 *
 * The two units of spec section 5.2 are the point of this line: one `channels.list` and one
 * `playlists.list` cover every Source the household has.
 */
fun refreshCheckLine(channelCalls: Int, playlistCalls: Int, checked: Int, changed: Int): String =
    "Change check: $channelCalls channels.list and $playlistCalls playlists.list, " +
        "${channelCalls + playlistCalls} ${plural(channelCalls + playlistCalls, "unit", "units")} " +
        "over $checked ${plural(checked, "Source", "Sources")}. " +
        "$changed changed and ${checked - changed} did not, so ${checked - changed} made no listing call."

/** One Source was re-listed. The kind says whether it was the whole Source or its newest page. */
fun refreshSourceLine(
    sourceId: String,
    fullWalk: Boolean,
    outcome: RefreshOutcome,
    calls: ApiCallCount,
): String {
    val how = if (fullWalk) "a full walk" else "one re-list of its newest page"
    return "Refreshed $sourceId by $how, ${calls.total} " +
        "${plural(calls.total, "unit", "units")} " +
        "(${calls.playlistPages} playlistItems, ${calls.videoPages} videos, " +
        "${calls.shortsPages} UUSH): ${outcome.added.size} new, " +
        "${outcome.dropped.size} dropped, ${outcome.keptUnavailable.size} kept unavailable, " +
        "${outcome.videos.size} stored."
}

/**
 * A Source that had no stored name and has just been given the one YouTube has. Ticket 20.
 *
 * It says **no further unit** in so many words, because that is the claim the whole change is
 * built on and this line is how it is checked on the television: the `Change check` line above
 * it still reads two units, and this one costs nothing more.
 */
fun refreshNamedLine(sourceId: String, title: String): String =
    "$sourceId had no name and is now \"$title\", from the change check reply, " +
        "for no further unit."

/** A Source whose count did not move. Written so the "no listing call" rule can be seen. */
fun refreshUnchangedLine(sourceId: String, count: Int): String =
    "$sourceId still reports $count, so it was not re-listed and cost no further unit."

/** What the whole run cost, for the budget of spec section 5.2. */
fun refreshFinishedLine(trigger: RefreshTrigger, sources: Int, units: Int): String =
    "Refresh by ${trigger.stored} finished: $sources " +
        "${plural(sources, "Source", "Sources")} re-listed, $units " +
        "${plural(units, "unit", "units")} spent in total."

/**
 * The network was not there.
 *
 * Spec section 5.4: everything still browses and plays from the cache, so this is a logcat
 * line and nothing else. No banner, no dialog, and no retry.
 */
fun refreshOfflineLine(trigger: RefreshTrigger): String =
    "Refresh by ${trigger.stored} could not reach YouTube. " +
        "The cached list is what the household keeps browsing and playing. Nothing is retried."

/**
 * `403 quotaExceeded`. Spec section 5.4 stops refresh until midnight Pacific Time.
 *
 * **There is no retry loop**, which is what makes this line the whole of the behaviour: the
 * run stops where it stands and every later trigger reads the instant it wrote down.
 */
fun refreshQuotaExceededLine(trigger: RefreshTrigger, resetsAt: java.time.Instant): String =
    "Refresh by ${trigger.stored} stopped: YouTube refused with 403 quotaExceeded. " +
        "It is not tried again until $resetsAt, which is midnight Pacific Time. No retry loop."

/** A trigger that arrived while the quota refusal still stands. */
fun refreshStillStoppedLine(trigger: RefreshTrigger, resetsAt: java.time.Instant): String =
    "Refresh by ${trigger.stored} was not started: the quota refusal stands until $resetsAt."

/** A trigger that arrived while a video was playing. Spec section 5.1 pauses the timer. */
fun refreshPausedForPlaybackLine(): String =
    "The 30 minute refresh timer is paused: a video is playing. " +
        "One check runs when the chain of videos ends."

/**
 * The weekly full walk was not owed on this launch. Build ticket 55.
 *
 * It names the day the walk is next owed, so the weekly rule can be checked from one line.
 */
fun fullWalkNotOwedLine(lastDay: java.time.LocalDate, nextDay: java.time.LocalDate): String =
    "The weekly full walk last completed for $lastDay and is next owed on $nextDay, " +
        "so this launch does a change check only."

/**
 * The weekly full walk is owed, and it waits for the launch check. Build ticket 55.
 *
 * Written before the launch check starts, so the log reads in the order the work happens: this
 * line, the launch check, every Source it re-listed reaching the screens, and only then the
 * line that starts the full walk.
 */
fun fullWalkOwedLine(lastDay: java.time.LocalDate?): String =
    "The weekly full walk is owed (" +
        (lastDay?.let { "last completed for $it" } ?: "none has completed") +
        "), so it runs after the launch check, as a second pass."

/**
 * One re-listed Source has reached the screens, before the rest of its run. Build ticket 55.
 *
 * @param onScreen true when the household was standing in that Source and its screen read the
 *   new list. False when it was not open, and a kept model of it was dropped instead.
 */
fun sourceReachedScreensLine(sourceId: String, onScreen: Boolean): String =
    "Refreshed $sourceId reaches the screens now, before the rest of the run: " +
        (if (onScreen) "the open Source screen reads it again" else "it is not open, so no kept screen of it is used") +
        ", and the home rail is built again."

/**
 * Showing a re-listed Source failed. The refresh goes on to the next Source. Build ticket 55.
 *
 * A screen that could not be drawn again is not a reason to leave the other Sources behind
 * YouTube. The stored list is already right, and the end of the run draws the rail again.
 */
fun sourceReachScreensFailedLine(sourceId: String, message: String): String =
    "Refreshed $sourceId was stored, but drawing it again failed: ${message.take(300)}. " +
        "The refresh goes on to the next Source."

/** Any other failure. It is written down and nothing else happens. */
fun refreshFailedLine(trigger: RefreshTrigger, sourceId: String?, message: String): String =
    "Refresh by ${trigger.stored} failed" + (sourceId?.let { " on $it" } ?: "") +
        ": ${message.take(300)}. The cached list is unchanged."

/**
 * A re-list found the Source unchanged except for view counts, and wrote those. Build ticket 59.
 *
 * It says **unchanged** in so many words, because that is the claim build ticket 56 depends on:
 * no rewrite of the Source's rows and no "reaches the screens" line follow this one.
 */
fun viewCountsWrittenLine(sourceId: String, written: Int): String =
    "Refreshed $sourceId is unchanged, and only its view counts moved: " +
        "$written ${plural(written, "count", "counts")} written, no row rewritten."

/** The one-time view count first read of build ticket 59 is starting. */
fun viewCountFirstReadStartLine(sources: Int): String =
    "View count first read started, once for this build: $sources walked " +
        "${plural(sources, "Source", "Sources")} to read."

/** The first read wrote one Source's counts. */
fun viewCountFirstReadSourceLine(sourceId: String, asked: Int, counted: Int): String =
    "View count first read wrote $sourceId: $asked stored ${plural(asked, "video", "videos")} asked about, " +
        "$counted with a count."

/** The first read finished, and it will not run again. */
fun viewCountFirstReadFinishedLine(sources: Int, units: Int, sourceFailed: Boolean): String =
    "View count first read finished: $sources ${plural(sources, "Source", "Sources")} written, " +
        "$units ${plural(units, "unit", "units")}. It will not run again." +
        (if (sourceFailed) " A Source that failed gets its counts from the next weekly full walk." else "")

/** A quota refusal or a lost network stopped the first read. A later run goes on from this Source. */
fun viewCountFirstReadStoppedLine(sourceId: String, reason: String, units: Int): String =
    "View count first read stopped on $sourceId by $reason after $units ${plural(units, "unit", "units")}. " +
        "It goes on from this Source at the next launch or 30 minute check."

/** Any other failure on one Source. The first read goes on to the next Source. */
fun viewCountFirstReadFailedLine(sourceId: String, message: String): String =
    "View count first read failed on $sourceId: ${message.take(300)}. It goes on to the next Source."

private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many
