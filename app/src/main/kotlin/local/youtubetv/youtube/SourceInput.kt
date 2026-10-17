package local.youtubetv.youtube

/**
 * What the household typed on the add screen, read as one of the shapes YouTube uses.
 *
 * This is a pure function over a string. It makes no network call and touches no Android
 * class, so every shape below, and every piece of rubbish a household can type, is a JVM unit
 * test with no device and no quota. See spec section 6.1.
 *
 * **Nothing here needs `search.list`.** Searching YouTube by name is a separate path with its
 * own bucket of 100 calls a day, and it is ticket 12. A word that is not an id, a handle or a
 * URL is returned as [SourceInput.Name] and refused by this ticket, naming the search path
 * that ticket 12 builds.
 */
sealed interface SourceInput {

    /**
     * An `@handle`, without its `@`.
     *
     * Resolved with `channels.list?forHandle=`, which costs 1 unit. YouTube's own handles are
     * case-insensitive, and the `@` is stripped here because `forHandle` wants the bare name.
     */
    data class Handle(val handle: String) : SourceInput

    /** A `UC...` channel id, resolved with `channels.list?id=`. */
    data class ChannelId(val channelId: String) : SourceInput

    /** A playlist id, resolved with `playlists.list?id=`. */
    data class PlaylistId(val playlistId: String) : SourceInput

    /**
     * A legacy `/user/<name>` URL, resolved with `channels.list?forUsername=`.
     *
     * YouTube stopped handing out these names years ago and most of the channels that had one
     * now have a handle as well. The endpoint still answers for the ones that do not, and it
     * costs the same 1 unit, so it is worth the branch.
     */
    data class Username(val username: String) : SourceInput

    /**
     * A single video, from a `watch?v=` URL, a `youtu.be/` URL or a bare video id.
     *
     * Spec section 6.2: a pasted video URL is not rejected. The video's channel is offered as
     * a Source, and when the URL also carries `list=`, the playlist is offered beside it.
     *
     * @property playlistId the `list=` parameter of the same URL, or null when it carried none.
     */
    data class Video(val videoId: String, val playlistId: String? = null) : SourceInput

    /**
     * Words, not an id. This ticket refuses it and names the search path.
     *
     * It is a distinct answer rather than [Unrecognised] on purpose: ticket 12 turns exactly
     * this case into a `search.list` call, and nothing else about this file has to change for
     * it.
     */
    data class Name(val text: String) : SourceInput

    /** Nothing at all, or something that cannot be any of the above. */
    data object Unrecognised : SourceInput
}

/**
 * The prefixes YouTube gives a playlist id.
 *
 * `UU` is a channel's uploads playlist and `UUSH` is its Shorts companion; both start with
 * `UU`, so one entry covers them. `PL` is a curated playlist. The rest are YouTube's own
 * generated lists, and they are here so that pasting one is refused as a playlist that does
 * not exist rather than as rubbish the app could not read.
 *
 * `UC` is deliberately absent: that is a **channel** id, and it is the same length as a `UU`
 * playlist id, so the two are told apart by the second letter alone.
 */
private val PLAYLIST_PREFIXES = listOf("PL", "UU", "FL", "LL", "OL", "RD", "TL", "PU", "SP", "WL")

/** A YouTube video id is always eleven of these characters. */
private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

/** A `UC` channel id is always twenty-four of these characters. */
private val CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{22}""")

/** Every id YouTube hands out uses this alphabet and no other. */
private val ID_CHARACTERS = Regex("""[A-Za-z0-9_-]+""")

/** A handle is letters, digits, dots, dashes and underscores, and between 3 and 30 of them. */
private val HANDLE = Regex("""[A-Za-z0-9._-]{3,30}""")

/** The hosts a YouTube link can arrive under. */
private val YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "m.youtube.com",
    "www.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
    "youtu.be",
    "www.youtu.be",
)

/**
 * Reads what the household typed.
 *
 * Nothing is fetched and nothing is guessed beyond the shape of the text. Whether the id or
 * the handle actually exists is YouTube's answer, one API call later.
 */
fun parseSourceInput(typed: String): SourceInput {
    val text = typed.trim()
    if (text.isEmpty()) return SourceInput.Unrecognised

    // A link is decided first, because a link can carry an id of any of the shapes below and
    // the path it sits on is what says which one it is.
    val link = parseLink(text)
    if (link != null) return link

    // A handle the household typed on its own, with the `@` YouTube shows it with.
    if (text.startsWith("@")) {
        val handle = text.removePrefix("@")
        return if (HANDLE.matches(handle)) SourceInput.Handle(handle) else SourceInput.Unrecognised
    }

    return parseBareId(text)
}

/**
 * A bare id, with no link around it.
 *
 * The order matters. A channel id and an uploads playlist id are both twenty-four characters
 * and differ only in their second letter, so the channel id is tested first and exactly.
 */
private fun parseBareId(text: String): SourceInput {
    if (!ID_CHARACTERS.matches(text)) return SourceInput.Name(text)
    if (CHANNEL_ID.matches(text)) return SourceInput.ChannelId(text)
    if (isPlaylistId(text)) return SourceInput.PlaylistId(text)
    if (VIDEO_ID.matches(text)) return SourceInput.Video(text)
    // Letters and digits, but no shape this app recognises. Ticket 12 searches for it.
    return SourceInput.Name(text)
}

/**
 * Whether this is a playlist id.
 *
 * A prefix alone is not enough, because `PL` is also an ordinary word. A real playlist id
 * carries at least thirteen characters, which is longer than any English word that could get
 * this far, and every generated one is far longer than that.
 */
private fun isPlaylistId(text: String): Boolean =
    text.length >= 13 && PLAYLIST_PREFIXES.any { text.startsWith(it) }

/**
 * A pasted YouTube link, or null when the text is not one.
 *
 * It is taken apart by hand rather than with `java.net.URI`. A household pastes what its
 * phone put on the clipboard, and that can carry a space, a trailing full stop or no scheme
 * at all, any of which makes `URI` throw. Splitting on the characters that matter cannot
 * throw and gives the same answer.
 */
private fun parseLink(text: String): SourceInput? {
    val withoutScheme = text
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("//")
    val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    if (host !in YOUTUBE_HOSTS) return null

    val afterHost = withoutScheme.drop(host.length)
    val path = afterHost.substringBefore('?').substringBefore('#').trim('/')
    val query = parseQuery(afterHost.substringAfter('?', "").substringBefore('#'))

    // `youtu.be/<video id>`, which carries the video in the path and may carry `list=`.
    if (host.endsWith("youtu.be")) {
        val videoId = path.substringBefore('/')
        return if (VIDEO_ID.matches(videoId)) {
            SourceInput.Video(videoId, playlistId = query["list"]?.takeIf { isPlaylistId(it) })
        } else {
            SourceInput.Unrecognised
        }
    }

    val segments = path.split('/').filter { it.isNotEmpty() }
    val first = segments.firstOrNull().orEmpty()

    // `/watch?v=...`, `/embed/...`, `/shorts/...`, `/live/...`: all one video.
    val videoId = when {
        first == "watch" -> query["v"]
        first in setOf("embed", "shorts", "live", "v") -> segments.getOrNull(1)
        else -> null
    }
    if (videoId != null) {
        return if (VIDEO_ID.matches(videoId)) {
            SourceInput.Video(videoId, playlistId = query["list"]?.takeIf { isPlaylistId(it) })
        } else {
            SourceInput.Unrecognised
        }
    }

    // `/playlist?list=...`
    if (first == "playlist") {
        val listId = query["list"]
        return if (listId != null && isPlaylistId(listId)) {
            SourceInput.PlaylistId(listId)
        } else {
            SourceInput.Unrecognised
        }
    }

    // `/channel/UC...`
    if (first == "channel") {
        val channelId = segments.getOrNull(1).orEmpty()
        return if (CHANNEL_ID.matches(channelId)) {
            SourceInput.ChannelId(channelId)
        } else {
            SourceInput.Unrecognised
        }
    }

    // `/user/<name>`, the one shape with its own endpoint.
    if (first == "user") {
        val name = segments.getOrNull(1).orEmpty()
        return if (HANDLE.matches(name)) SourceInput.Username(name) else SourceInput.Unrecognised
    }

    // `/c/<name>`, the custom URL YouTube handed out before handles existed.
    //
    // There is no endpoint that takes one. YouTube migrated nearly every custom URL to the
    // handle of the same spelling, so it is tried as a handle, which costs the same 1 unit and
    // answers for almost all of them. A channel that kept a custom URL and took no handle
    // comes back as not found, and the household can paste its `/channel/UC...` URL instead.
    if (first == "c") {
        val name = segments.getOrNull(1).orEmpty()
        return if (HANDLE.matches(name)) SourceInput.Handle(name) else SourceInput.Unrecognised
    }

    // `/@handle`, which is how YouTube writes a channel's address today.
    if (first.startsWith("@")) {
        val handle = first.removePrefix("@")
        return if (HANDLE.matches(handle)) SourceInput.Handle(handle) else SourceInput.Unrecognised
    }

    return SourceInput.Unrecognised
}

/** The query string as a map. A repeated name keeps its first value, the way a browser does. */
private fun parseQuery(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    val found = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val name = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        if (name.isEmpty() || value.isEmpty()) continue
        found.putIfAbsent(name, value)
    }
    return found
}
