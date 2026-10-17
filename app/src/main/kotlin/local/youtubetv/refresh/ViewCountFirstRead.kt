package local.youtubetv.refresh

import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import local.youtubetv.data.SourceStore
import local.youtubetv.youtube.YOUTUBE_PAGE_SIZE
import local.youtubetv.youtube.YouTubeApi
import local.youtubetv.youtube.YouTubeApiException

/**
 * The `app_meta` key that says the one-time view count first read has finished. Build ticket 59.
 *
 * It holds the instant the first read finished. Only its presence is read: any value means done.
 *
 * The word is "first read" and not "backfill", because `CONTEXT.md` reserves "backfill" as a
 * word to avoid for a Bulk mark. Found by `/code-review` on build ticket 59.
 */
const val META_VIEW_COUNTS_FIRST_READ = "view_counts.first_read_at"

/**
 * The `app_meta` key, one per Source, that says the first read has written that Source's counts.
 *
 * It is what lets a run that a quota refusal or a lost network stopped go on from where it
 * stopped, instead of paying again for the Sources it already read. Found by `/code-review`.
 */
fun metaViewCountsFirstReadOf(sourceId: String): String = "view_counts.first_read.$sourceId"

/** What one run of [ViewCountFirstRead] did. */
data class FirstReadRun(
    /** False when the first read was already done, so nothing was asked of YouTube. */
    val ran: Boolean = false,
    /** True when the run reached the end of the Sources, and the flag in `app_meta` is now written. */
    val finished: Boolean = false,
    /** What it spent from the 10,000-unit daily pool: one unit per 50 videos. */
    val unitsSpent: Int = 0,
    /** The Sources whose counts were written, in the order they were written. */
    val writtenSourceIds: List<String> = emptyList(),
    /** True when a `403 quotaExceeded` stopped it. Spec section 5.4. */
    val stoppedByQuota: Boolean = false,
    /** True when YouTube could not be reached. Spec section 5.4. */
    val offline: Boolean = false,
)

/**
 * Reads the view count of every stored video once, on the first launch of build ticket 59.
 *
 * **Why it exists.** Every video stored before database version 11 has no count, and the weekly
 * full walk that would read them all is up to seven days away. Without this, every card would
 * show its date alone for up to a week after the household installs the build.
 *
 * **What it asks.** `videos.list` for the ids of every stored video of every walked Source, 50 ids
 * a call and one unit a call. It is the same request a walk makes, so there is still only one way
 * to ask YouTube about a video. No `playlistItems.list` and no `search.list`. On the household's
 * four Sources, about 6,900 stored videos, that is about 140 units.
 *
 * **What it writes.** Only the counts, through [SourceStore.writeViewCounts], one column each.
 * No row is added, deleted or rewritten, so no Newly listed flag, no publish date and no title can
 * move. A video missing from the reply is private or deleted, and it is left as it was.
 *
 * **It runs once, and it never pays twice for one Source.**
 *
 * - Each Source it writes is recorded under [metaViewCountsFirstReadOf], and a later run skips it.
 *   So a run that a quota refusal or a lost network stopped goes on from where it stopped.
 * - [META_VIEW_COUNTS_FIRST_READ] is written once a run reaches the end of the Sources, even when
 *   one Source failed for any other reason. That Source gets its counts from the next weekly full
 *   walk, at most seven days later. Retrying it on every launch could spend units for ever on a
 *   failure that repeats. Found by `/code-review`.
 * - [markDone] writes it too, after a weekly full walk that every Source came through, because
 *   that walk has read every count already.
 *
 * A Source added later needs none of this: its first walk reads the counts with every other field.
 *
 * There is no Android import, so the whole rule is a JVM test with no device and no quota.
 *
 * @param onSourceWritten called after each Source's counts are written, so a kept or open screen
 *   of that Source can read them.
 */
class ViewCountFirstRead(
    private val api: YouTubeApi,
    private val store: SourceStore,
    private val log: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {

    /** Whether the first read still has to run. */
    fun isOwed(): Boolean = store.meta(META_VIEW_COUNTS_FIRST_READ) == null

    /**
     * Records that no first read is needed any more. `SourceRefresh` calls it after a weekly full
     * walk that every Source came through, because that walk read every count. Found by
     * `/code-review`: without it, the first read stayed owed after it had nothing left to do.
     */
    fun markDone() {
        if (isOwed()) store.setMeta(META_VIEW_COUNTS_FIRST_READ, now().toString())
    }

    /**
     * Runs the first read, or does nothing when it has already finished.
     *
     * **The caller holds the refresh lock.** A walk that ran at the same time would read the
     * stored videos before this wrote a count, and its `replaceVideos` would then write the old
     * null back over it. See `SourceRefresh.refreshOnLaunch`.
     */
    suspend fun runIfOwed(onSourceWritten: suspend (String) -> Unit = {}): FirstReadRun {
        if (!isOwed()) return FirstReadRun()
        // A Source with no `lastWalkedAt` has no stored videos to read. Its first walk reads
        // the counts with everything else.
        val walked = store.allSources().filter { it.lastWalkedAt != null }
        log(viewCountFirstReadStartLine(walked.size))

        var units = 0
        val written = mutableListOf<String>()
        var anyFailed = false
        for (source in walked) {
            // Already read by an earlier run that was stopped part way through.
            if (store.meta(metaViewCountsFirstReadOf(source.sourceId)) != null) continue
            val ids = store.allVideos(source.sourceId).map { it.videoId }
            if (ids.isEmpty()) continue
            val counts = mutableMapOf<String, Long?>()
            try {
                for (batch in ids.chunked(YOUTUBE_PAGE_SIZE)) {
                    val replies = api.videos(batch)
                    units++
                    for (reply in replies) counts[reply.videoId] = reply.viewCount
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: YouTubeApiException) {
                when (failure.reason) {
                    YouTubeApiException.Reason.QUOTA_EXCEEDED,
                    YouTubeApiException.Reason.NETWORK,
                    -> {
                        log(viewCountFirstReadStoppedLine(source.sourceId, failure.reason.name, units))
                        return FirstReadRun(
                            ran = true,
                            unitsSpent = units,
                            writtenSourceIds = written,
                            stoppedByQuota = failure.reason == YouTubeApiException.Reason.QUOTA_EXCEEDED,
                            offline = failure.reason == YouTubeApiException.Reason.NETWORK,
                        )
                    }
                    else -> {
                        // This Source's problem alone, so the next Source is still read. This
                        // Source gets its counts from the next weekly full walk.
                        log(viewCountFirstReadFailedLine(source.sourceId, failure.message.orEmpty()))
                        anyFailed = true
                        continue
                    }
                }
            }
            store.writeViewCounts(source.sourceId, counts)
            store.setMeta(metaViewCountsFirstReadOf(source.sourceId), now().toString())
            written += source.sourceId
            log(viewCountFirstReadSourceLine(source.sourceId, asked = ids.size, counted = counts.values.count { it != null }))
            onSourceWritten(source.sourceId)
        }

        markDone()
        log(viewCountFirstReadFinishedLine(written.size, units, sourceFailed = anyFailed))
        return FirstReadRun(ran = true, finished = true, unitsSpent = units, writtenSourceIds = written)
    }
}
