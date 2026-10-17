package local.youtubetv.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * **Shuffle** and the **Shuffle run**, as plain functions. Build ticket 69.
 *
 * Shuffle is a per-Source setting that makes next, previous and autoplay-next pick the
 * Source's videos in random order. A Shuffle run is one pass through the Source in which every
 * playable video plays once before any plays again. See `CONTEXT.md` for both terms and ADR
 * 0002 for why a Shuffle play never moves the High-water mark.
 *
 * Nothing here touches storage, Compose or Android, so every rule is a JVM unit test.
 *
 * ## How the run is stored, and why it is so small
 *
 * A run is a **seed** and the list of videos already played in it, in the order they played.
 * The order of the videos still to play is not stored at all. It is worked out from the seed:
 * every video gets a key from [shuffleKey], and the run's next video is the unplayed playable
 * video with the lowest key. Three rules of the ticket fall out of that shape with no code of
 * their own:
 *
 * - **A video a walk lists during the run joins it at a random place** among the videos not
 *   yet played, because its key is as random as everyone else's.
 * - **A Bulk mark never changes the run**, because nothing here reads a Watch.
 * - **A video that stops being playable is skipped, and the run does not end because of it**,
 *   because only playable videos are ever candidates.
 *
 * And the next video is **stable**: the same run and the same Source give the same answer on
 * every screen, at every opening, until something is played. That is what lets the Source
 * screen's highlight and the autoplay-next countdown name the same video.
 *
 * The run is not a Watch and is not in the Watch log. It lives on the Source's own row, beside
 * the Shuffle setting, so starting a new run never touches a Watch. ADR 0001 keeps the log for
 * what the household watched, and a run is where Shuffle is up to.
 *
 * @property seed what every video's key is worked out from. A new run has a new seed.
 * @property played the videos played in this run, oldest first. Each video appears once: a
 *   video played again by hand moves to the end. See [played].
 * @property avoidFirst the last video of the run before this one, which this run may not start
 *   with. Null when there is none. It is consulted only while [played] is empty.
 */
data class ShuffleRun(
    val seed: Long,
    val played: List<String> = emptyList(),
    val avoidFirst: String? = null,
) {

    /**
     * This run, or the one that follows it at once when every playable video has played.
     *
     * "A new run starts at once, with no message." It is worked out rather than stored, so a
     * run that has just finished needs no write to start its successor. The successor's seed
     * comes from this one's by [nextSeed], so asking twice gives the same new run, and the new
     * run is written down the moment its first video plays.
     *
     * A Source with no playable video at all keeps the run it has: there is nothing to start.
     */
    fun current(playable: List<String>): ShuffleRun {
        if (playable.isEmpty()) return this
        val playedSet = played.toHashSet()
        if (playable.any { it !in playedSet }) return this
        return ShuffleRun(seed = nextSeed(seed), played = emptyList(), avoidFirst = played.lastOrNull())
    }

    /**
     * The run's next video, or null when there is none to play.
     *
     * It is the unplayed playable video with the lowest [shuffleKey], in [current]. The first
     * video of a run is never [avoidFirst], which is how "the new run's first video is never
     * the old run's last video" holds. **A Source whose only playable video is that video has
     * no next video at all**, and the player ends there with the plain end-of-Source message,
     * rather than break the rule.
     *
     * @param besides a video on screen that is not yet in the run, which is what the autoplay
     *   countdown sits over before it plays. It is treated as played, so next from it is the
     *   video after it, and the run renews past it when it is the last one left.
     */
    fun next(playable: List<String>, besides: String? = null): String? {
        val asked = if (besides != null && besides !in played && besides in playable) {
            copy(played = played + besides)
        } else {
            this
        }
        val run = asked.current(playable)
        val playedSet = run.played.toHashSet()
        val avoid = if (run.played.isEmpty()) run.avoidFirst else null
        return playable
            .asSequence()
            .filter { it !in playedSet && it != avoid }
            .minWithOrNull(compareBy<String> { shuffleKey(run.seed, it) }.thenBy { it })
    }

    /**
     * The run after [videoId] has played in it, by Shuffle or by hand.
     *
     * The video goes to the end of [played]. A video already played in this run is moved there
     * rather than listed twice, so "the order they played" reads as the order of their last
     * play, and the count the household is shown counts videos and not plays. Build ticket 69,
     * rule 6: a card pressed by hand counts as played, so Shuffle does not pick it again.
     *
     * It is applied to [current], so the first play after a finished run is the first play of
     * the new one.
     */
    fun played(videoId: String, playable: List<String>): ShuffleRun {
        val run = current(playable)
        return run.copy(played = run.played.filter { it != videoId } + videoId)
    }

    /**
     * How many videos have played in this run, which is the number the hint line and the
     * confirmation say. A finished run counts as the new one, which has played nothing.
     */
    fun playedCount(playable: List<String>): Int = current(playable).played.size

    /**
     * Where `previous` goes from the video on screen. Build ticket 69, rule 8.
     *
     * It walks [played] backwards, in the order the videos played. It reads this run as it is
     * stored and not [current], because the household pressing previous on the last video of
     * a finished run is still in that run.
     *
     * @param onScreen the video the player shows.
     * @param at where in [played] the player already stands, when a previous press brought it
     *   there. Null when the video on screen is the newest play, or is not in the run yet.
     */
    fun previous(onScreen: String, at: Int?): ShuffleBack {
        val from = at?.takeIf { it in played.indices && played[it] == onScreen }
            ?: if (played.lastOrNull() == onScreen) played.lastIndex else played.size
        if (from <= 0) return ShuffleBack.FirstOfRun
        return ShuffleBack.To(videoId = played[from - 1], at = from - 1)
    }

    /** The run as stored in `sources.shuffle_run`. Read back by [shuffleRunOfStored]. */
    fun stored(): String = JSONObject()
        .put(KEY_SEED, seed.toString())
        .put(KEY_PLAYED, JSONArray(played))
        .apply { if (avoidFirst != null) put(KEY_AVOID_FIRST, avoidFirst) }
        .toString()

    companion object {
        /**
         * A new run, started early by the household or for a Source that has never had one.
         *
         * @param after the run it replaces, or null. Its last video is the one the new run may
         *   not start with, which is the same rule a run that finished on its own follows.
         */
        fun fresh(seed: Long, after: ShuffleRun? = null, playable: List<String> = emptyList()): ShuffleRun {
            val last = after?.current(playable)?.let { it.played.lastOrNull() ?: it.avoidFirst }
            return ShuffleRun(seed = seed, played = emptyList(), avoidFirst = last)
        }
    }
}

/** Where `previous` goes in a Shuffle run. See [ShuffleRun.previous]. */
sealed interface ShuffleBack {

    /** Back to [videoId], which stands at [at] in the run's played list. */
    data class To(val videoId: String, val at: Int) : ShuffleBack

    /** The video on screen is the run's first, so previous says so and stays. */
    data object FirstOfRun : ShuffleBack
}

/**
 * The key that puts one video in its place in a run.
 *
 * It must give the same answer on every device and in every build, because the household's run
 * is stored as a seed and nothing else. So it is written out here rather than taken from
 * [String.hashCode] or [kotlin.random.Random], whose algorithms are not promised to stay put:
 * FNV-1a over the id's UTF-16 code units, mixed with the seed by SplitMix64's finaliser.
 */
fun shuffleKey(seed: Long, videoId: String): Long {
    var hash = FNV_OFFSET
    for (unit in videoId) {
        hash = hash xor unit.code.toLong()
        hash *= FNV_PRIME
    }
    return mix(hash xor seed)
}

/** The seed of the run that follows a finished one. See [ShuffleRun.current]. */
fun nextSeed(seed: Long): Long = mix(seed + GOLDEN_GAMMA)

/**
 * A stored run read back, or null when there is none or it cannot be read.
 *
 * A run this build cannot read is treated as no run, and a new one is started. A run is where
 * Shuffle is up to and not something the household watched, so losing one costs a pass that
 * starts again, never a Watch.
 */
fun shuffleRunOfStored(stored: String?): ShuffleRun? {
    if (stored.isNullOrBlank()) return null
    return try {
        val json = JSONObject(stored)
        val seed = json.getString(KEY_SEED).toLong()
        val array = json.optJSONArray(KEY_PLAYED) ?: JSONArray()
        val played = (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
        ShuffleRun(
            seed = seed,
            played = played.distinct(),
            avoidFirst = if (json.has(KEY_AVOID_FIRST)) json.optString(KEY_AVOID_FIRST) else null,
        )
    } catch (failure: JSONException) {
        null
    } catch (failure: NumberFormatException) {
        null
    }
}

/**
 * The videos a run picks from: every video of the Source that the player can play, watched or
 * not. Build ticket 69, rule 3. It is the same rule autoplay-next follows, [canBeLandedOn], so
 * a hidden Short, an unavailable video and an upcoming premiere are never picked.
 */
fun shufflePlayable(videos: List<StoredVideo>): List<String> =
    videos.filter { it.canBeLandedOn }.map { it.videoId }

/**
 * Where the run's next video sits in [videos], or null when it has none.
 *
 * The one place the rule is written. The grid's highlight, the player's next and the
 * autoplay-next countdown all ask it, so they cannot name different videos. [besides] is a
 * video the run does not hold yet but must not offer, which is the video a countdown sits over.
 */
fun shuffleNextIndex(videos: List<StoredVideo>, run: ShuffleRun?, besides: String? = null): Int? {
    run ?: return null
    val next = run.next(shufflePlayable(videos), besides) ?: return null
    return videos.indexOfFirst { it.videoId == next }.takeIf { it >= 0 }
}

private const val KEY_SEED = "seed"
private const val KEY_PLAYED = "played"
private const val KEY_AVOID_FIRST = "avoidFirst"

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val GOLDEN_GAMMA = -0x61c8864680b583ebL

private fun mix(value: Long): Long {
    var z = value
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

/**
 * [ShuffleRun.previous], stepping over videos the player can no longer play.
 *
 * A video played earlier in the run can since have become a hidden Short, unavailable or an
 * upcoming premiere again, and previous must not stop on it: the player would answer the press
 * with an error. It is the same rule next and autoplay-next follow. See spec section 8.6.
 */
fun ShuffleRun.previousPlayable(onScreen: String, at: Int?, playable: Collection<String>): ShuffleBack {
    var back = previous(onScreen, at)
    while (back is ShuffleBack.To && back.videoId !in playable) {
        back = if (back.at <= 0) {
            ShuffleBack.FirstOfRun
        } else {
            ShuffleBack.To(played[back.at - 1], back.at - 1)
        }
    }
    return back
}
