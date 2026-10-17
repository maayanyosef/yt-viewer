package local.youtubetv.youtube

/**
 * Turns what the household typed into something it can be offered and added.
 *
 * It sits on the [YouTubeApi] seam, so every path below is a JVM unit test against a fake and
 * costs no quota. What it is **not** allowed to do is call `search.list`: that endpoint has
 * its own bucket of 100 calls a day, it is the tightest limit in the project, and none of the
 * three paths of this ticket needs it. A typed name comes back as [Resolved.NeedsSearch] and
 * ticket 12 is what answers it.
 *
 * Every path here costs **1 unit**, except a pasted video URL, which costs up to 3. See the
 * table in spec section 6.1 and the cost note on [resolve].
 *
 * @param onCall told the name of each endpoint as it is called, so the quota a session spent
 *   can be read out of logcat rather than guessed at afterwards.
 */
class SourceResolver(
    private val api: YouTubeApi,
    private val onCall: (String) -> Unit = {},
) {

    /**
     * What the household typed, looked up.
     *
     * The cost, by input:
     *
     * | Input | Calls | Units |
     * | --- | --- | --- |
     * | `@handle`, `/c/name` | `channels.list?forHandle=` | 1 |
     * | `UC...`, channel URL | `channels.list?id=` | 1 |
     * | `/user/name` | `channels.list?forUsername=` | 1 |
     * | playlist id or URL | `playlists.list?id=` | 1, or 2 for an uploads playlist |
     * | video id or URL | `videos.list`, `channels.list?id=`, and `playlists.list?id=` when the URL carried `list=` | 1 to 3 |
     * | a typed name | none | 0 |
     *
     * A [YouTubeApiException] is thrown rather than swallowed. The screen turns it into one
     * sentence, the same way the Source screen turns a failed walk into one.
     */
    suspend fun resolve(input: SourceInput): Resolved = when (input) {
        is SourceInput.Handle -> channelOrNotFound { api.channelByHandle(input.handle) }
        is SourceInput.ChannelId -> channelOrNotFound { api.channelById(input.channelId) }
        is SourceInput.Username -> channelOrNotFound { api.channelByUsername(input.username) }
        is SourceInput.PlaylistId -> resolvePlaylist(input.playlistId)
        is SourceInput.Video -> resolveVideo(input)
        is SourceInput.Name -> Resolved.NeedsSearch(input.text)
        SourceInput.Unrecognised -> Resolved.Unreadable
    }

    private suspend inline fun channelOrNotFound(lookup: () -> ChannelDetails?): Resolved {
        onCall(CHANNELS_LIST)
        val channel = lookup() ?: return Resolved.NotFound
        return Resolved.Found(channel = channel, playlist = null)
    }

    /**
     * A pasted playlist id or URL.
     *
     * **A channel's uploads playlist is the awkward case.** YouTube does not reliably list a
     * `UU...` id through `playlists.list`, so a household that pasted one would be told its
     * own Source does not exist. When the first call comes back empty and the id has exactly
     * the shape of an uploads playlist, the channel of the same suffix is looked up and its
     * `contentDetails.relatedPlaylists.uploads` is **compared** with what was pasted.
     *
     * That comparison is the point. Spec section 6.2 forbids *substituting* `UU` for `UC` to
     * decide what a channel's uploads playlist is, because the answer has to come from the
     * channel itself. Nothing is substituted here: the channel is asked, and its own answer
     * either matches the pasted id or the pasted id is not found after all.
     */
    private suspend fun resolvePlaylist(playlistId: String): Resolved {
        onCall(PLAYLISTS_LIST)
        val playlist = api.playlistById(playlistId)
        if (playlist != null) return Resolved.Found(channel = null, playlist = playlist)

        // `UU` plus twenty-two characters, which is an uploads playlist and nothing else.
        // `UUSH`, `UULF` and the rest of YouTube's generated lists are longer than this.
        if (playlistId.length != UPLOADS_PLAYLIST_LENGTH || !playlistId.startsWith("UU")) {
            return Resolved.NotFound
        }
        onCall(CHANNELS_LIST)
        val channel = api.channelById("UC" + playlistId.removePrefix("UU")) ?: return Resolved.NotFound
        if (channel.uploadsPlaylistId != playlistId) return Resolved.NotFound
        return Resolved.Found(channel = channel, playlist = null)
    }

    /**
     * A pasted `watch?v=` or `youtu.be` link, which spec section 6.2 says is never rejected.
     *
     * The video's own channel is offered. When the link also carried `list=`, the playlist is
     * offered beside it and the household picks which of the two it meant.
     */
    private suspend fun resolveVideo(input: SourceInput.Video): Resolved {
        onCall(VIDEOS_LIST)
        val video = api.videos(listOf(input.videoId)).firstOrNull()

        val channel = video?.channelId?.let { channelId ->
            onCall(CHANNELS_LIST)
            api.channelById(channelId)
        }
        val playlist = input.playlistId?.let { playlistId ->
            onCall(PLAYLISTS_LIST)
            api.playlistById(playlistId)
        }
        if (channel == null && playlist == null) return Resolved.NotFound
        return Resolved.Found(channel = channel, playlist = playlist)
    }

    /**
     * The second step of picking a channel: `All uploads` and then its public playlists.
     *
     * One `playlists.list?channelId=` call and one unit, which is what spec section 6.2 asks
     * for. A channel with no public playlist leaves `All uploads` on its own, which is a
     * complete answer and not an empty screen.
     */
    suspend fun channelStep(
        channel: ChannelDetails,
        uploadsLabel: String,
    ): List<SourceCandidate> {
        onCall(PLAYLISTS_LIST)
        return channelStepRows(channel, api.playlistsOfChannel(channel.channelId), uploadsLabel)
    }

    companion object {
        /** The names the cost line uses, one per endpoint, so a log line can be totted up. */
        const val CHANNELS_LIST = "channels.list"
        const val PLAYLISTS_LIST = "playlists.list"
        const val VIDEOS_LIST = "videos.list"

        /** `UU` plus the twenty-two characters of a channel id. Nothing else is this length. */
        private const val UPLOADS_PLAYLIST_LENGTH = 24
    }
}

/** What a lookup found. */
sealed interface Resolved {

    /**
     * YouTube answered.
     *
     * At least one of the two is present. Both are present only for a pasted video link that
     * also carried `list=`, which spec section 6.2 says offers the playlist and the channel
     * together.
     */
    data class Found(val channel: ChannelDetails?, val playlist: PlaylistDetails?) : Resolved

    /** YouTube knows of no such channel or playlist. */
    data object NotFound : Resolved

    /**
     * Words rather than an id, a handle or a URL.
     *
     * This ticket refuses it and names the search path. Ticket 12 is what turns it into a
     * `search.list` call, which is the one call in the app with a bucket of 100 a day.
     */
    data class NeedsSearch(val text: String) : Resolved

    /** Not a shape this app can read at all, so nothing was asked of YouTube. */
    data object Unreadable : Resolved
}

/** Whether a row on the add screen is a channel or a playlist. Spec section 6.2 badges it. */
enum class CandidateKind { CHANNEL, PLAYLIST }

/**
 * One thing the household can choose, on either step of adding a Source.
 *
 * @property playlistId **what would actually be stored.** Every Source is a playlist id, so a
 *   channel row carries its uploads playlist here. This is also the key the duplicate check
 *   of spec section 6.4 is made against, which is why a channel and its `UU...` playlist
 *   cannot both be added.
 * @property channelId set on a channel row, so picking it can ask for that channel's second
 *   step. Null on a playlist row.
 * @property secondStep true for a row that opens the channel's playlist list rather than the
 *   add screen. Only the channel row of the first step has it.
 * @property videoCount what YouTube says the channel or the playlist holds. It is null when
 *   YouTube gave no number, and the screen then says nothing rather than `0 videos`.
 */
data class SourceCandidate(
    val kind: CandidateKind,
    val playlistId: String,
    val channelId: String?,
    val title: String,
    val handle: String?,
    val thumbnailUrl: String?,
    val videoCount: Int?,
    val secondStep: Boolean = false,
)

/**
 * The rows of the first step, from what [SourceResolver.resolve] found.
 *
 * It is a pure function so that the order of the rows, and the shape of each one, is a unit
 * test. The channel comes first when both are present, because a household that pasted a
 * video link is far more often after the channel than after whatever playlist it was playing
 * from.
 *
 * A channel row **carries no picture of its own on the second step**, which is why
 * [secondStep] is set here: picking it asks YouTube for that channel's playlists rather than
 * going straight to the add screen. Spec section 6.2.
 */
fun firstStepRows(found: Resolved.Found): List<SourceCandidate> = buildList {
    found.channel?.let { channel ->
        add(
            SourceCandidate(
                kind = CandidateKind.CHANNEL,
                // Null only for a channel with no uploads playlist at all, which is a channel
                // that has never posted. The row still opens the second step, where the
                // `All uploads` line is left out for the same reason.
                playlistId = channel.uploadsPlaylistId.orEmpty(),
                channelId = channel.channelId,
                title = channel.title,
                handle = channel.handle,
                thumbnailUrl = channel.thumbnailUrl,
                videoCount = channel.videoCount,
                secondStep = true,
            ),
        )
    }
    found.playlist?.let { add(playlistRow(it)) }
}

/**
 * The rows of the second step: `All uploads`, then the channel's public playlists.
 *
 * `All uploads` takes its playlist id from the channel's own
 * `contentDetails.relatedPlaylists.uploads` and never from substituting `UU` for `UC`. Spec
 * section 6.2 is explicit about it, and a channel whose uploads playlist YouTube does not
 * report simply has no such row rather than a guessed one.
 *
 * The channel's own uploads playlist is left out of the second list when YouTube happens to
 * report it there as well, so the household is never shown the same Source twice.
 *
 * **The uploads row is titled `All uploads`, not the channel's own name.** A channel called
 * `TED` can own a playlist called `TED`, and two rows of the same name told apart only by a
 * badge is the case this step exists to avoid. The channel's name is on the heading above the
 * rows, and it is repeated under this row where the handle goes. Found by `/code-review`.
 *
 * @param uploadsLabel what the uploads row is called. It is a parameter so that this file
 *   stays free of wording; `ui/AddSourceText.kt` owns every string the television shows.
 */
fun channelStepRows(
    channel: ChannelDetails,
    playlists: List<PlaylistDetails>,
    uploadsLabel: String = "All uploads",
): List<SourceCandidate> = buildList {
    val uploads = channel.uploadsPlaylistId
    if (!uploads.isNullOrBlank()) {
        add(
            SourceCandidate(
                kind = CandidateKind.CHANNEL,
                playlistId = uploads,
                channelId = channel.channelId,
                title = uploadsLabel,
                // The channel's name under the row, so `All uploads` still says whose.
                handle = channel.handle ?: channel.title,
                thumbnailUrl = channel.thumbnailUrl,
                videoCount = channel.videoCount,
            ),
        )
    }
    playlists.filter { it.playlistId != uploads }.forEach { add(playlistRow(it)) }
}

private fun playlistRow(playlist: PlaylistDetails): SourceCandidate = SourceCandidate(
    kind = CandidateKind.PLAYLIST,
    playlistId = playlist.playlistId,
    channelId = playlist.channelId,
    title = playlist.title,
    // A playlist has no handle of its own. The channel that owns it is what the row names
    // underneath, which is what tells two playlists of the same name apart.
    handle = playlist.channelTitle,
    thumbnailUrl = playlist.thumbnailUrl,
    videoCount = playlist.itemCount,
)
