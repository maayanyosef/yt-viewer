package local.youtubetv.youtube

import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns YouTube Data API replies into this app's own types.
 *
 * These are plain functions over a string, with no network and no Android class in sight, so
 * they are unit-tested on the JVM against replies captured from the real API.
 *
 * `org.json` ships inside Android, so this costs the app no dependency. On the unit-test
 * classpath Android's copy is a stub that throws, which is why `app/build.gradle.kts` adds
 * the real `org.json` as a test-only dependency.
 */
object YouTubeJson {

    fun parsePlaylistItemPage(body: String): PlaylistItemPage = wrapFailures {
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: JSONArray()
        return@wrapFailures PlaylistItemPage(
            items = items.objects().map { item ->
                val contentDetails = item.getJSONObject("contentDetails")
                PlaylistItemRef(
                    videoId = contentDetails.getString("videoId"),
                    // Absent for a private or deleted video. That absence is a signal, not an
                    // error. See spec section 4.4.
                    videoPublishedAt = contentDetails.optInstant("videoPublishedAt"),
                )
            },
            nextPageToken = root.optStringOrNull("nextPageToken"),
            totalResults = root.optJSONObject("pageInfo")?.optIntOrNull("totalResults"),
        )
    }

    fun parseVideos(body: String): List<VideoDetails> = wrapFailures {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return@wrapFailures items.objects().map { item ->
            val snippet = item.getJSONObject("snippet")
            // A live stream and an unstarted premiere do carry a `player` object, but it
            // holds `embedHtml` alone. Both sizes stay null, so the Shorts rule declines to
            // guess. Read from real replies on 2026-09-19.
            val player = item.optJSONObject("player")
            VideoDetails(
                videoId = item.getString("id"),
                title = snippet.optString("title"),
                thumbnailUrl = snippet.optJSONObject("thumbnails")?.bestThumbnailUrl(),
                durationSeconds = parseIso8601DurationSeconds(
                    item.optJSONObject("contentDetails")?.optStringOrNull("duration"),
                ),
                liveBroadcastContent = snippet.optStringOrNull("liveBroadcastContent")
                    ?: "none",
                liveEndedAt = item.optJSONObject("liveStreamingDetails")
                    ?.optInstant("actualEndTime"),
                // YouTube sends these two as strings, not numbers.
                embedWidth = player?.optStringOrNull("embedWidth")?.toIntOrNull(),
                embedHeight = player?.optStringOrNull("embedHeight")?.toIntOrNull(),
                channelId = snippet.optStringOrNull("channelId"),
                // A string, like every number in `statistics`. Missing on an upcoming premiere,
                // and missing stays null: no count is not a count of zero. Build ticket 59.
                viewCount = item.optJSONObject("statistics")
                    ?.optStringOrNull("viewCount")?.toLongOrNull(),
            )
        }
    }

    /**
     * Every channel in a `channels.list` reply.
     *
     * An empty `items` array is YouTube's answer for a handle, a name or an id it knows
     * nothing about. It is not an error: the reply is HTTP 200 with nothing in it, so the
     * caller turns an empty list into "no such channel".
     */
    fun parseChannels(body: String): List<ChannelDetails> = wrapFailures {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return@wrapFailures items.objects().map { item ->
            val snippet = item.optJSONObject("snippet")
            ChannelDetails(
                channelId = item.getString("id"),
                title = snippet?.optString("title").orEmpty(),
                // `customUrl` is the handle, and YouTube writes it with its `@` already on.
                handle = snippet?.optStringOrNull("customUrl")?.let {
                    if (it.startsWith("@")) it else "@$it"
                },
                thumbnailUrl = snippet?.optJSONObject("thumbnails")?.bestThumbnailUrl(),
                // A channel that hides its counts sends no `statistics`, and a hidden count
                // arrives as the string "0". Either way the add screen simply says nothing.
                videoCount = item.optJSONObject("statistics")
                    ?.optStringOrNull("videoCount")?.toIntOrNull(),
                uploadsPlaylistId = item.optJSONObject("contentDetails")
                    ?.optJSONObject("relatedPlaylists")
                    ?.optStringOrNull("uploads"),
            )
        }
    }

    /** Every playlist in a `playlists.list` reply, whether it was asked for by id or by channel. */
    fun parsePlaylists(body: String): List<PlaylistDetails> = wrapFailures {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return@wrapFailures items.objects().map { item ->
            val snippet = item.optJSONObject("snippet")
            PlaylistDetails(
                playlistId = item.getString("id"),
                title = snippet?.optString("title").orEmpty(),
                channelId = snippet?.optStringOrNull("channelId"),
                channelTitle = snippet?.optStringOrNull("channelTitle"),
                thumbnailUrl = snippet?.optJSONObject("thumbnails")?.bestThumbnailUrl(),
                itemCount = item.optJSONObject("contentDetails")?.optIntOrNull("itemCount"),
            )
        }
    }

    /**
     * The channels and playlists in a `search.list` reply, in the order YouTube ranked them.
     *
     * Two things about this endpoint that none of the others do:
     *
     * - **The id is an object, not a string.** `id.kind` says which of `id.channelId` and
     *   `id.playlistId` carries the id. An item of any other kind is dropped rather than
     *   guessed at; `type=channel,playlist` should mean there are none.
     * - **`snippet.title` is HTML-escaped.** `search.list` sends `Tom &amp; Jerry` and
     *   `It&#39;s` where `channels.list` and `playlists.list` send the plain text. Left alone
     *   it reaches the television exactly like that, so it is undone here.
     */
    fun parseSearchResults(body: String): List<SearchHit> = wrapFailures {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return@wrapFailures items.objects().mapNotNull { item ->
            val id = item.optJSONObject("id") ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet")
            val kind = when {
                id.optStringOrNull("channelId") != null -> CandidateKind.CHANNEL
                id.optStringOrNull("playlistId") != null -> CandidateKind.PLAYLIST
                else -> return@mapNotNull null
            }
            SearchHit(
                kind = kind,
                id = when (kind) {
                    CandidateKind.CHANNEL -> id.getString("channelId")
                    CandidateKind.PLAYLIST -> id.getString("playlistId")
                },
                title = unescapeHtml(snippet?.optString("title").orEmpty()),
                channelTitle = snippet?.optStringOrNull("channelTitle")?.let { unescapeHtml(it) },
                channelId = snippet?.optStringOrNull("channelId"),
                thumbnailUrl = snippet?.optJSONObject("thumbnails")?.bestThumbnailUrl(),
            )
        }
    }

    /**
     * Maps an error reply onto a reason this app can act on.
     *
     * The body is best-effort. A failure that arrives without one still has to map to
     * something, so the HTTP status alone decides in that case.
     */
    fun parseErrorReason(httpStatus: Int, body: String?): YouTubeApiException.Reason {
        val reasons = body?.let { errorReasonsIn(it) }.orEmpty()
        return when {
            "playlistNotFound" in reasons -> YouTubeApiException.Reason.PLAYLIST_NOT_FOUND
            "quotaExceeded" in reasons -> YouTubeApiException.Reason.QUOTA_EXCEEDED
            "API_KEY_INVALID" in reasons -> YouTubeApiException.Reason.FORBIDDEN
            "API_KEY_ANDROID_APP_BLOCKED" in reasons -> YouTubeApiException.Reason.FORBIDDEN
            httpStatus == 403 -> YouTubeApiException.Reason.FORBIDDEN
            else -> YouTubeApiException.Reason.HTTP
        }
    }

    /** A short line for the screen and the log. Never includes the URL, which carries the key. */
    fun parseErrorMessage(httpStatus: Int, body: String?): String {
        val message = body
            ?.let { runCatching { JSONObject(it).optJSONObject("error")?.optStringOrNull("message") }.getOrNull() }
        return message ?: "YouTube replied with HTTP $httpStatus"
    }

    /**
     * Every `reason` in the body, from both places Google puts one.
     *
     * `error.errors[].reason` is the old shape and carries `playlistNotFound` and
     * `quotaExceeded`. `error.details[].reason` is the newer `ErrorInfo` shape and carries
     * `API_KEY_INVALID` and `API_KEY_ANDROID_APP_BLOCKED`. Both were read from real replies
     * on 2026-09-19.
     */
    internal fun errorReasonsIn(body: String): Set<String> = runCatching {
        val error = JSONObject(body).optJSONObject("error") ?: return@runCatching emptySet()
        buildSet {
            error.optJSONArray("errors")?.objects()?.forEach { addIfPresent(it.optStringOrNull("reason")) }
            error.optJSONArray("details")?.objects()?.forEach { addIfPresent(it.optStringOrNull("reason")) }
        }
    }.getOrDefault(emptySet())

    private fun MutableSet<String>.addIfPresent(value: String?) {
        if (value != null) add(value)
    }

    private inline fun <T> wrapFailures(block: () -> T): T = try {
        block()
    } catch (failure: JSONException) {
        throw YouTubeApiException(
            YouTubeApiException.Reason.BAD_RESPONSE,
            "YouTube sent a reply this app could not read",
            failure,
        )
    }
}

/** The HTML entities `search.list` writes, plus the numeric escapes it uses for the rest. */
private val HTML_ENTITY = Regex("""&(amp|lt|gt|quot|apos|nbsp|#\d{1,7}|#[xX][0-9a-fA-F]{1,6});""")

/**
 * Undoes the HTML escaping `search.list` applies to `snippet.title`.
 *
 * **One pass, not one replacement per entity.** Replacing `&amp;` first turns `&amp;lt;`, the
 * escaping of the literal text `&lt;`, into a tag that was never in the title. A single regex
 * pass cannot do that, because the text a replacement produces is never looked at again.
 *
 * Only this endpoint needs it. `channels.list` and `playlists.list` send the plain text.
 */
internal fun unescapeHtml(text: String): String {
    if ('&' !in text) return text
    return HTML_ENTITY.replace(text) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val digits = name.drop(1)
                val code = if (digits.startsWith("x") || digits.startsWith("X")) {
                    digits.drop(1).toIntOrNull(16)
                } else {
                    digits.toIntOrNull()
                }
                // An escape outside Unicode is left exactly as it arrived, because showing
                // the household the raw `&#99999999;` is better than showing it nothing.
                if (code == null || code !in 1..0x10FFFF) match.value else String(Character.toChars(code))
            }
        }
    }
}

/** The app window on the television is 1920x1080, so a wider image can never help. */
private const val WIDEST_USEFUL_THUMBNAIL = 1920

/**
 * The widest thumbnail that is still worth downloading.
 *
 * This reads each entry's own `width` rather than trusting the key names. The names are not a
 * fixed set and not ordered by size: a normal video offers `uhd` at 3840 wide, which is
 * wider than `maxres`, and a live stream offers `fhd`, which no other video has. Both were
 * read from real replies on 2026-09-19.
 *
 * Anything wider than the app window is wasted bytes on a television, so those are skipped.
 * If every entry is too wide, the narrowest one is taken, because something must be shown.
 */
private fun JSONObject.bestThumbnailUrl(): String? {
    val sized = keys().asSequence()
        .mapNotNull { optJSONObject(it) }
        .mapNotNull { entry ->
            val url = entry.optStringOrNull("url") ?: return@mapNotNull null
            url to entry.optIntOrNull("width")
        }
        .toList()
    if (sized.isEmpty()) return null
    val withWidth = sized.mapNotNull { (url, width) -> width?.let { url to it } }
    if (withWidth.isEmpty()) return sized.first().first
    val useful = withWidth.filter { (_, width) -> width <= WIDEST_USEFUL_THUMBNAIL }
    return if (useful.isNotEmpty()) {
        useful.maxBy { (_, width) -> width }.first
    } else {
        withWidth.minBy { (_, width) -> width }.first
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)

/** Every timestamp in these replies is RFC 3339, which [Instant.parse] reads directly. */
private fun JSONObject.optInstant(name: String): Instant? {
    val text = optStringOrNull(name) ?: return null
    return try {
        Instant.parse(text)
    } catch (failure: DateTimeParseException) {
        null
    }
}
