package local.youtubetv.data

import java.time.Instant

/**
 * Where a walked Source lives between launches.
 *
 * This is the second seam of ticket 02. The walk fills it and the screen reads it, so the
 * app can open on a stored list with no network call at all. See spec section 5.4.
 *
 * Only videos live here. The household's Watches are a separate append-only log and arrive in
 * ticket 04. Videos are cache and are thrown away and rebuilt by re-walking, which is why
 * [replaceVideos] replaces the whole Source in one go rather than merging.
 */
interface SourceStore {

    /** What is known about one Source, or null when the household has not added it. */
    fun source(sourceId: String): StoredSource?

    /**
     * Every Source the household has added, which is what the home rail draws.
     *
     * The order is oldest-added first, and a Source added before that was recorded keeps its
     * place at the front in Source id order. Neither key ever changes by itself: a re-walk
     * rewrites nothing that is sorted on here, so a card cannot move along the rail with
     * nothing the household did. The rail's own order is decided by [railOrder] from the
     * Watch log, never here. What this store knows is videos, and how recently a Source was
     * played is a fact about Watches.
     */
    fun allSources(): List<StoredSource>

    /**
     * Writes a Source's whole row.
     *
     * **It is the door for adding a Source and nothing else.** Every other writer here changes
     * one column, because this one would carry whatever the caller happened to be holding over
     * the top of a preference it never read. There are four such columns now: `direction`,
     * `autoplay_next`, `added_at` and `remote_count`.
     */
    fun saveSource(source: StoredSource)

    /**
     * The household removed a Source: its row and **its cached videos** both go.
     *
     * Spec section 6.6 deletes the cache on purpose. A removed Source is never refreshed, so
     * its stored list would rot silently, and putting a two-year-old list back as though it
     * were current is worse than a re-walk. The re-walk is cheap: about 120 units of the daily
     * 10,000 for a 3,000-video Source.
     *
     * **Nothing here touches the Watch log.** The log is append-only and is the household's
     * own record; ADR 0001 has no rule that deletes from it, and adding the same playlist id
     * again must find every Watch and every Bulk mark range exactly where it was. Removing a
     * Source writes a `sourceRemoved` entry, which is `WatchLog`'s job, not this one's.
     *
     * A Source that is not there is left alone rather than treated as an error.
     */
    fun removeSource(sourceId: String)

    /**
     * A walk of this Source finished, so its `last_walked_at` moves and nothing else does.
     *
     * One column, for the same reason [setDirection] writes one: a walk must not be able to
     * reset a Direction or an autoplay-next choice that were chosen before it. A Source with no
     * row is left alone, because a row is what says the household added it.
     */
    fun markWalked(sourceId: String, at: Instant)

    /**
     * Every video of a Source that the grid shows, **in that Source's Direction**.
     *
     * Newest first unless the household flipped `Order`, which is stored on the Source's own
     * row. A Source that has never been walked, and one stored before Direction existed, both
     * read as newest first.
     *
     * Unlisted videos are left out here, once, so that no screen has to remember to drop them.
     * Three kinds are left out, and [StoredVideo.isListed] is the one rule that says so: a
     * Short, an unavailable video and an upcoming premiere. See spec sections 4.3 and 4.4.
     *
     * **Amended 2026-09-20 by ticket 23.** Unavailable and upcoming videos used to be listed
     * and drawn with a badge. They are now hidden exactly as a Short is. [allVideos] still
     * holds every one of them, which is what lets a walk bring one back.
     */
    fun listedVideos(sourceId: String): List<StoredVideo>

    /**
     * The same rows [listedVideos] returns, in the same order, with only what a count needs.
     *
     * **This is the home rail's read, and the reason it exists is measured.** Build ticket 22
     * timed the launch on the household television: reading the Source of 5,304 videos as
     * whole [StoredVideo] rows cost 1,142 ms cold, of which the query itself was 57 ms. The
     * same rows read as two columns cost 123 ms. The cost was never SQLite; it was ten
     * `Cursor` accessors and an `Availability.valueOf` per row, for nine fields the rail never
     * looks at.
     *
     * The rail reads **every** video of **every** Source, so it pays that price over the whole
     * library. The Source screen reads one Source and draws its videos, so it goes on asking
     * for [listedVideos].
     *
     * **The filter and the order are [listedVideos]'s own**, so a card and a grid can never
     * disagree about which videos a Source has or which comes first.
     */
    fun listedVideoKeys(sourceId: String): List<VideoKey>

    /**
     * One listed video of a Source, or null when the Source does not list it.
     *
     * The rail asks for exactly one of these per card, the Next unwatched video, because that
     * is the only video a card names. See [listedVideoKeys] for why the other few thousand are
     * never read whole.
     *
     * **Null for a video that is stored but not listed**, which is the same rule
     * [listedVideos] applies. Spec sections 4.3 and 4.4.
     */
    fun listedVideo(sourceId: String, videoId: String): StoredVideo?

    /**
     * **Every** stored video of a Source, unlisted ones included, in publish-date order.
     *
     * Only a refresh wants this. Spec section 5.2 classifies a video as a Short once and never
     * again, so a refresh has to know about the Shorts as well: a merge handed only the listed
     * videos would see every Short as missing from the cache and as newly seen in the listing,
     * and would re-classify each of them on every check.
     *
     * No screen calls it. The grid never shows a Short and never counts one.
     */
    fun allVideos(sourceId: String): List<StoredVideo>

    /** Replaces every stored video of this Source with the result of a fresh walk. */
    fun replaceVideos(sourceId: String, videos: List<StoredVideo>)

    /**
     * Writes the view count of each named video of this Source, and nothing else. Build ticket 59.
     *
     * **It exists so that a count can move without the Source counting as changed.** View
     * counts move every day. A refresh whose only news is new counts must not run
     * [replaceVideos], which deletes and inserts every row, and must not tell the screens the
     * Source changed. Build ticket 56 made a full walk of an unchanged Source cost neither, and
     * this is what keeps that true. The counts are still written, here.
     *
     * A null count is written as no count. A video id this Source has no row for is skipped:
     * this never adds a row.
     */
    fun writeViewCounts(sourceId: String, counts: Map<String, Long?>)

    /**
     * The household played a video here, so nothing at or past it is **Newly listed** any more.
     *
     * Build ticket 40. A Newly listed video is offered as Next unwatched even though it sits
     * above the high-water mark, and this is the one thing that ends that. See
     * [StoredVideo.newlyListed].
     *
     * **"At or past it" is read in the Source's own Direction, and that is why this is a
     * store method and not a rule over a list.** It is the same shape as a Bulk mark: a pivot
     * date and a side. The Source's Direction is read here, from its own row, so no caller has
     * to hold it.
     *
     * - Newest first: every video published on or after [videoPublishedAt]. In that Direction
     *   an older video is a **later** position, so the videos at or past the played one are
     *   the ones published no earlier than it.
     * - Oldest first: every video published on or before [videoPublishedAt], for the mirror
     *   reason.
     *
     * The played video itself is always covered, because both tests include the date itself.
     *
     * **Only a `played` entry calls this.** A Bulk mark does not, and that is the owner's decision
     * of 2026-09-21: a Bulk mark over an old backlog does not reach a video listed today, and
     * under any other answer that video would be retired for ever without ever having been
     * shown.
     *
     * A video with no publish date is private or deleted, and it is never compared: a null
     * [videoPublishedAt] writes nothing at all, and a stored row whose own date is null is
     * matched by neither test. Such a video is not listed, so it is never offered anyway.
     *
     * A Source that has no row, and one with no videos, are both left alone rather than
     * treated as an error, the same as every other writer here.
     */
    fun clearNewlyListedUpTo(sourceId: String, videoPublishedAt: Instant?)

    /**
     * The household flipped `Order` on this Source.
     *
     * It writes the one column and nothing else, rather than saving a whole [StoredSource].
     * A flip must not be able to overwrite the title or the last walk time with whatever the
     * screen happened to be holding, and a walk must not be able to reset a Direction chosen
     * before it. Spec section 7.2 makes Direction per Source, and this is the only writer.
     *
     * **A Source with no row is left alone**, and no row is created for it. The presence of a
     * row is what says a Source has been walked, and `SourceScreenModel.load` reads it that
     * way: a row holding nothing but an id and a Direction would make the app show an empty
     * grid for that Source for ever and never walk it, with nothing in the app able to undo
     * it. The chip that calls this only exists on a Source that is already on screen, so
     * there is always a row. Found by `/code-review`.
     */
    fun setDirection(sourceId: String, direction: Direction)

    /**
     * The household turned autoplay-next on or off for this Source.
     *
     * It writes the one column and nothing else, for the same reasons [setDirection] does,
     * and it leaves a Source with no row alone for the same reason: the presence of a row is
     * what says a Source has been walked.
     *
     * Spec section 8.6 makes autoplay-next per Source. A household watches a podcast Source
     * straight through and dips into a music Source one video at a time, and one switch for
     * the whole app would be wrong for one of them.
     */
    fun setAutoplayNext(sourceId: String, autoplayNext: Boolean)

    /**
     * The change check of spec section 5.2 just read this Source's remote count.
     *
     * One column, for the same reason every writer above it writes one. A Source with no row
     * is left alone.
     */
    fun setRemoteCount(sourceId: String, count: Int)

    /**
     * Gives a Source the name YouTube has for it, **but only when it has none**. Ticket 20.
     *
     * [StoredSource.title] says the title is fetched once, when the Source is added. All four
     * of the household's own Sources were added by a build that had no add screen, so all four
     * have a null title and every card on the rail is headed by its raw playlist id. The change
     * check of spec section 5.2 already asks `channels.list` and `playlists.list` for the whole
     * household, and both replies carry a title it then drops, so filling it costs no unit of
     * the daily 10,000.
     *
     * **The "only when missing" rule is here and not in the caller.** Two things depend on it.
     * A title YouTube gave must not be re-written on every check, and above all a name the
     * household types must be safe from every later check and every later walk. A rule kept in
     * one caller is a rule the second caller does not know about.
     *
     * - A stored title that is blank counts as missing, because `railCardName` already reads a
     *   blank title as no title and the two must agree.
     * - A blank [title] is never written. It would change nothing a household can see and
     *   would take the Source out of reach of the next check, which would find it no longer
     *   missing.
     * - One column, for the same reason [markWalked] and [setRemoteCount] write one. Three of
     *   the household's four Sources have never had `autoplay_next` written at all and live on
     *   [StoredSource.autoplayNextOfStored], which reads null as on. A whole-row write here
     *   would turn autoplay-next off on three Sources with nothing the household did.
     * - A Source with no row is left alone, the same as every writer above.
     *
     * @return true when this really did write a name, false when it left the row as it was.
     *   **The caller must not assume it wrote.** The refusal is decided inside the store, so a
     *   caller that logged the new name anyway would write a line saying a Source is now
     *   called something it is not. Found by `/code-review`.
     */
    fun fillMissingTitle(sourceId: String, title: String): Boolean

    /**
     * The household typed a name for this Source, or cleared the one it had. Ticket 27.
     *
     * **It writes `typed_name` and never `title`.** Those are two different facts:
     * [StoredSource.title] is what YouTube calls the Source, and [StoredSource.typedName] is
     * what the household typed over it. Clearing the typed one has to bring YouTube's back,
     * which is only possible because YouTube's was never written over. The owner chose that on
     * 2026-09-20; see the version table in `Database.kt`.
     *
     * Three properties follow from writing the other column, and each of them matters:
     *
     * - **[fillMissingTitle] can never reach a typed name.** Its rule is over `title`, so a
     *   change check landing in the same second as a rename writes a column the rename does
     *   not own. Ticket 20 put that rule in the store's `WHERE` and this keeps it true for
     *   free, rather than adding a second rule that has to agree with it.
     * - **Clearing puts the Source back exactly where the backfill can reach it.** A Source
     *   whose `title` is still empty, because every check it has seen has failed, is missing a
     *   title before the rename and missing it after the clearing, so the next check fills it.
     * - **One column, for the same reason [markWalked] and [setRemoteCount] write one.** Three
     *   of the household's four Sources have never had `autoplay_next` written at all and live
     *   on [StoredSource.autoplayNextOfStored], which reads null as on. A whole-row write here
     *   would turn autoplay-next off on three Sources with nothing the household did.
     *
     * A Source with no row is left alone, the same as every writer above.
     *
     * @param typedName what the household typed, or null to clear it. It is put through
     *   [StoredSource.typedNameOfInput] first, so a name of nothing but spaces clears rather
     *   than storing a name that draws as an empty card.
     * @return true when the row was really written, false when there is no such Source. **The
     *   caller must not assume it wrote**, for the reason [fillMissingTitle] gives: a caller
     *   that logged the new name anyway would write a line saying a Source is now called
     *   something it is not.
     */
    fun setTypedName(sourceId: String, typedName: String?): Boolean

    /**
     * One remembered value that belongs to no Source, such as when the last full walk completed.
     *
     * It is the `app_meta` table, which [WatchStore] also writes the device id into. There is
     * one such table for the whole app, and this is the video side's door to it. A key it has
     * never been given reads as null.
     */
    fun meta(key: String): String?

    /** Writes one [meta] value, replacing whatever was there. */
    fun setMeta(key: String, value: String)
}

/**
 * One Source as stored.
 *
 * **The presence of a row means the household added the Source. It does not mean it has been
 * walked.** [lastWalkedAt] is what says that, and it is null between adding a Source and its
 * first walk finishing. Before ticket 11 the two were the same thing, because the one Source
 * the build knew of was hardcoded and a row could only appear from a walk.
 *
 * [title] is what the household sees on every card and every menu heading. It is fetched once,
 * when the Source is added, from the channel or the playlist it was resolved to.
 * [lastWalkedAt] also backs the quiet `updated <time>` line.
 *
 * [direction] is the household's `Order` choice for this Source. It lives here and not in the
 * Watch log on purpose: it is a browsing preference, not something the household watched, and
 * the log is what cloud sync will one day replay. [autoplayNext] is here for the same reason.
 */
data class StoredSource(
    val sourceId: String,
    val title: String?,
    val lastWalkedAt: Instant?,
    val direction: Direction = Direction.NEWEST_FIRST,
    /**
     * Whether a finished video rolls into the next one by itself. Spec section 8.6.
     *
     * **It starts off.** The owner chose that on the household television on 2026-09-20, with the
     * chip row in front of him, over ticket 08's choice of on. A household that wants a Source
     * to roll on turns the `Autoplay` chip on once for that Source and the choice is kept. See
     * ticket 18, which is where the decision and its reasoning are written down.
     *
     * **A Source stored before this column existed still reads as on**, which is
     * [autoplayNextOfStored] and not this default. The two answer differently on purpose: a
     * stored null is a Source the household walked while the default was on, so reading it as
     * off would take away a setting they were already living with.
     */
    val autoplayNext: Boolean = AUTOPLAY_NEXT_DEFAULT,
    /**
     * When the household added this Source, or null for one added before that was recorded.
     *
     * It is the rail's tie-break, so a Source added today sits at the end of the rail, beside
     * `+ Add a Source`, rather than wherever its id happened to sort. It is **not** the rail's
     * sort rule: spec section 7.1 has one of those and it is most recently played first.
     *
     * A rowid would have been the obvious tie-break and is the wrong one. [saveSource] replaces
     * a row on conflict, which gives it a fresh rowid, so ordering by rowid would let a re-walk
     * move a card along the rail with nothing the household did.
     */
    val addedAt: Instant? = null,
    /**
     * What the change check of spec section 5.2 last heard this Source's size was, or null
     * when it has never asked.
     *
     * It is YouTube's own number, `statistics.videoCount` for a channel Source and
     * `contentDetails.itemCount` for a playlist one, and it is **only ever compared with
     * itself**. Both count things this app does not: a channel counts videos its uploads
     * playlist leaves out, and a playlist counts its private and deleted entries. Comparing
     * either with how many videos are stored here would report a change on every check for
     * ever, and re-list every Source every thirty minutes.
     *
     * Null means the next check re-lists this Source, which is the safe way round: a Source
     * stored before this column existed catches up once and then stops costing anything.
     */
    val remoteCount: Int? = null,
    /**
     * The name the household typed for this Source, or null when it has typed none.
     *
     * **It is a second name and not a replacement for [title].** [title] goes on holding what
     * YouTube calls the Source, so clearing this one brings that name back with no API call at
     * all. Ticket 27, and the owner's decision of 2026-09-20.
     *
     * Nothing but [SourceStore.setTypedName] writes it. No walk and no change check can reach
     * it, because both of those write `title`.
     *
     * Null for every row written before database version 9, which is every row the household
     * has, and that reads as "no typed name", so every card keeps the name it already had.
     */
    val typedName: String? = null,
) {
    companion object {
        /**
         * What autoplay-next is for a Source no choice has ever been stored for.
         *
         * It is **off**. The owner decided that on 2026-09-20; see [autoplayNext] and ticket 18.
         * Every reader that has to answer for a Source with no row reads it from here, so
         * there is one place to change and no literal `true` left behind in a fallback.
         */
        const val AUTOPLAY_NEXT_DEFAULT = false

        /**
         * What the `sources.autoplay_next` column means, including when it holds nothing.
         *
         * **Null reads as on.** That is a migration rule and not the default: the column was
         * added by database version 6, and every Source walked before it existed was living
         * with autoplay-next on, because on was the default at the time. Rewriting those to
         * [AUTOPLAY_NEXT_DEFAULT] would change a household's setting with nothing the
         * household did, which is why ticket 18 changed the default and left this alone.
         *
         * It is here rather than in `SqliteSourceStore` so that a JVM test can hold the two
         * answers apart. A unit test cannot open an Android database.
         */
        fun autoplayNextOfStored(stored: Int?): Boolean = stored == null || stored != 0

        /**
         * What a typed name becomes on its way into storage, and what an empty one means.
         *
         * **A name of nothing but spaces is no name at all, and is stored as null.** Two rules
         * already in the app say it has to be: `sourceNameOrId` reads a blank name as no name,
         * and `SourceStore.fillMissingTitle` treats a blank `title` as missing. A stored
         * `"   "` would be a Source the household could see no way to clear, because the field
         * they typed it in would look empty when they came back to it.
         *
         * The name is trimmed for the same reason a household typing on a television remote
         * lands on the space key by accident, and a leading space would push the whole card's
         * name along by one character for ever.
         *
         * It is here, beside [autoplayNextOfStored], so that one rule serves both
         * [InMemorySourceStore] and `SqliteSourceStore` and a JVM test can hold it.
         */
        fun typedNameOfInput(input: String?): String? = input?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * A [SourceStore] that keeps everything in memory.
 *
 * Used by unit tests. It applies the same ordering, the same Shorts filter, the same Newly
 * listed clearing and the same one-way Newly listed rule in [replaceVideos] as the real store,
 * so a test that passes here is testing the rule and not the SQL.
 *
 * **That promise is only as good as the last rule somebody remembered to copy here.** It was
 * false for [replaceVideos] between `2838f1c` and 2026-09-21, and nothing went red. When a
 * rule is added to `SqliteSourceStore`, add it here in the same commit.
 */
class InMemorySourceStore : SourceStore {

    private val sources = mutableMapOf<String, StoredSource>()
    private val videos = mutableMapOf<String, List<StoredVideo>>()
    private val meta = mutableMapOf<String, String>()

    override fun source(sourceId: String): StoredSource? = sources[sourceId]

    // Oldest added first, then by Source id, which is the order the real store's query asks
    // SQLite for. A Source with no added-at date comes first, exactly as `NULL` sorts there.
    override fun allSources(): List<StoredSource> =
        sources.values.sortedWith(
            compareBy({ it.addedAt != null }, { it.addedAt }, { it.sourceId }),
        )

    override fun saveSource(source: StoredSource) {
        // Through [StoredSource.typedNameOfInput], because `SqliteSourceStore` puts this column
        // through it on the way in and on the way out. Without it a test could store a typed
        // name of nothing but spaces here, read it back, and pass, while the real store would
        // have read it back as no name at all.
        sources[source.sourceId] =
            source.copy(typedName = StoredSource.typedNameOfInput(source.typedName))
    }

    override fun removeSource(sourceId: String) {
        sources.remove(sourceId)
        videos.remove(sourceId)
    }

    override fun markWalked(sourceId: String, at: Instant) {
        val existing = sources[sourceId] ?: return
        sources[sourceId] = existing.copy(lastWalkedAt = at)
    }

    override fun listedVideos(sourceId: String): List<StoredVideo> =
        orderByPublishDate(
            videos[sourceId].orEmpty().filter { it.isListed },
            sources[sourceId]?.direction ?: Direction.NEWEST_FIRST,
        )

    override fun listedVideoKeys(sourceId: String): List<VideoKey> = listedVideos(sourceId)

    override fun listedVideo(sourceId: String, videoId: String): StoredVideo? =
        listedVideos(sourceId).firstOrNull { it.videoId == videoId }

    override fun allVideos(sourceId: String): List<StoredVideo> =
        orderByPublishDate(videos[sourceId].orEmpty())

    /**
     * The same one-way rule for the Newly listed flag that `SqliteSourceStore.replaceVideos`
     * applies: a video whose stored flag is already cleared never gets it back here.
     *
     * **This was missing until 2026-09-21 and its absence hid the rule from every JVM test.**
     * Commit `2838f1c` added the rule to the SQL store alone and said it wanted an
     * `androidTest`, which is true and is written. But while this fake was a plain assignment,
     * the rule could not be tested on the JVM at all, and this class's own promise — that a
     * test passing here is testing the rule and not the SQL — was false for it. Found by
     * `/code-review high` reviewing that very commit.
     *
     * Why the rule exists: `SourceRefresh.refreshOne` reads the stored videos, then spends
     * minutes on network calls, then writes the merged list back. `mergeRefresh` carries
     * `newlyListed` forward from that old snapshot. A play during the walk clears the flag in
     * storage, and without this the stale `true` would be written straight back and the video
     * would be offered as Next unwatched a second time.
     *
     * **The fake and the SQL agree exactly on everything the fake can represent.**
     * [StoredVideo.newlyListed] is a non-null `Boolean`, so "already cleared" is simply
     * `false`, and the app only ever binds 0 or 1 to the column. The one row the two would
     * read differently is a `newly_listed` of NULL, which SQL's `= 0` does not match: that is
     * what database version 10's migration leaves behind, there is no migration here, and
     * `SqliteSourceStore.clearedNewlyListedIds` records why it cannot change an answer.
     */
    override fun replaceVideos(sourceId: String, videos: List<StoredVideo>) {
        val alreadyCleared = this.videos[sourceId].orEmpty()
            .filterNot { it.newlyListed }
            .mapTo(mutableSetOf()) { it.videoId }
        this.videos[sourceId] = videos.map { video ->
            if (video.newlyListed && video.videoId in alreadyCleared) {
                video.copy(newlyListed = false)
            } else {
                video
            }
        }
    }

    // The same rule `SqliteSourceStore` writes in SQL: one field per named video, and a video
    // with no row is skipped rather than added.
    override fun writeViewCounts(sourceId: String, counts: Map<String, Long?>) {
        if (counts.isEmpty()) return
        val stored = videos[sourceId] ?: return
        videos[sourceId] = stored.map { video ->
            if (video.videoId in counts) video.copy(viewCount = counts[video.videoId]) else video
        }
    }

    // The same two tests `SqliteSourceStore` writes in SQL, so a JVM test of the rule is a
    // test of what the television does. A video with no date is matched by neither, exactly
    // as a SQL comparison against NULL matches nothing.
    override fun clearNewlyListedUpTo(sourceId: String, videoPublishedAt: Instant?) {
        if (videoPublishedAt == null) return
        val direction = sources[sourceId]?.direction ?: Direction.NEWEST_FIRST
        val stored = videos[sourceId] ?: return
        videos[sourceId] = stored.map { video ->
            val date = video.videoPublishedAt
            val atOrPast = date != null && when (direction) {
                Direction.NEWEST_FIRST -> !date.isBefore(videoPublishedAt)
                Direction.OLDEST_FIRST -> !date.isAfter(videoPublishedAt)
            }
            if (atOrPast && video.newlyListed) video.copy(newlyListed = false) else video
        }
    }

    override fun setDirection(sourceId: String, direction: Direction) {
        val existing = sources[sourceId] ?: return
        sources[sourceId] = existing.copy(direction = direction)
    }

    override fun setAutoplayNext(sourceId: String, autoplayNext: Boolean) {
        val existing = sources[sourceId] ?: return
        sources[sourceId] = existing.copy(autoplayNext = autoplayNext)
    }

    override fun setRemoteCount(sourceId: String, count: Int) {
        val existing = sources[sourceId] ?: return
        sources[sourceId] = existing.copy(remoteCount = count)
    }

    override fun fillMissingTitle(sourceId: String, title: String): Boolean {
        if (title.isBlank()) return false
        val existing = sources[sourceId] ?: return false
        if (!existing.title.isNullOrBlank()) return false
        sources[sourceId] = existing.copy(title = title)
        return true
    }

    override fun setTypedName(sourceId: String, typedName: String?): Boolean {
        val existing = sources[sourceId] ?: return false
        sources[sourceId] = existing.copy(typedName = StoredSource.typedNameOfInput(typedName))
        return true
    }

    override fun meta(key: String): String? = meta[key]

    override fun setMeta(key: String, value: String) {
        meta[key] = value
    }
}
