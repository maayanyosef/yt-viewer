package local.youtubetv.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/**
 * The real store on the television, in SQLite.
 *
 * There is no ORM here on purpose. Android ships SQLite, so this needs no dependency and no
 * annotation processor, and AGP 9 compiles Kotlin itself with no Kotlin Gradle plugin
 * applied. Every rule worth testing already lives behind [SourceStore] and is covered against
 * [InMemorySourceStore] on the JVM.
 *
 * The schema and the migrations are in [YouTubeTvDatabase], which this shares with
 * [SqliteWatchStore]. Two helpers over one file would each think they owned its version
 * number.
 */
class SqliteSourceStore(private val helper: SQLiteOpenHelper) : SourceStore {

    override fun source(sourceId: String): StoredSource? =
        helper.readableDatabase.query(
            TABLE_SOURCES,
            SOURCE_COLUMNS,
            "source_id = ?",
            arrayOf(sourceId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toStoredSource()
        }

    /**
     * Every Source the household added, oldest added first.
     *
     * **Never by rowid.** [saveSource] replaces a row on conflict, which gives it a fresh
     * rowid, so ordering by rowid would let a re-walk move a card along the home rail with
     * nothing the household did. `added_at` is written once and never again, and the Source id
     * breaks a tie between two rows that share one, including every row stored before
     * `added_at` existed.
     */
    override fun allSources(): List<StoredSource> =
        helper.readableDatabase.query(
            TABLE_SOURCES,
            SOURCE_COLUMNS,
            null,
            null,
            null,
            null,
            "added_at IS NULL DESC, added_at ASC, source_id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toStoredSource())
            }
        }

    override fun saveSource(source: StoredSource) {
        val values = ContentValues().apply {
            put("source_id", source.sourceId)
            put("title", source.title)
            put("last_walked_at", source.lastWalkedAt?.toEpochMilli())
            put("direction", source.direction.stored)
            put("autoplay_next", if (source.autoplayNext) 1 else 0)
            put("added_at", source.addedAt?.toEpochMilli())
            put("remote_count", source.remoteCount)
            // Named here although a Source being added never has one, because this write
            // replaces the whole row on conflict: a column left out of these values is a
            // column set back to null. Spelling it out is what makes that a decision rather
            // than an omission.
            put("typed_name", StoredSource.typedNameOfInput(source.typedName))
            // Build ticket 69. Spelled out for the same reason `typed_name` is.
            put("shuffle", if (source.shuffle) 1 else 0)
            put("shuffle_run", source.shuffleRun?.stored())
            // Fork 0.5. Spelled out for the same reason `typed_name` is.
            put("video_quality", source.playback.qualityName)
            put("playback_rate", source.playback.rate.toDouble())
            put("skip_start", source.playback.skipStartSeconds)
            put("skip_end", source.playback.skipEndSeconds)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_SOURCES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * The Source's row and its cached videos, in one transaction.
     *
     * Either both go or neither does. A row left behind with no videos would read to the next
     * launch as a Source with nothing in it, which is a Source the household could never get
     * back by re-adding, and videos left behind with no row would be rows nothing ever reads
     * again. The Watch log is in this same file and is not touched.
     */
    override fun removeSource(sourceId: String) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_VIDEOS, "source_id = ?", arrayOf(sourceId))
            database.delete(TABLE_SOURCES, "source_id = ?", arrayOf(sourceId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun markWalked(sourceId: String, at: Instant) {
        val values = ContentValues().apply { put("last_walked_at", at.toEpochMilli()) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    override fun setRemoteCount(sourceId: String, count: Int) {
        val values = ContentValues().apply { put("remote_count", count) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    /**
     * Fills a missing title. **The "only when missing" rule is in the `WHERE`.**
     *
     * Reading the row, deciding in Kotlin and writing it back would be the same rule with a
     * gap in the middle of it. Here the row a household has just renamed cannot be matched at
     * all, so no refresh can write over it however the two land.
     *
     * `trim(title) = ''` is SQLite's own trim, which takes spaces off and nothing else, where
     * `InMemorySourceStore` asks Kotlin's `isBlank`. The two disagree only about a title made
     * entirely of tabs, which YouTube does not give.
     *
     * **The answer is the number of rows the `UPDATE` really touched**, and not a guess made
     * before it ran. That is the only honest one: the refusal happens in the `WHERE`, so the
     * two cases the caller cannot see from outside, a row renamed a moment ago and a title
     * the two implementations disagree about, both come back as false. Found by
     * `/code-review`.
     */
    override fun fillMissingTitle(sourceId: String, title: String): Boolean {
        if (title.isBlank()) return false
        val values = ContentValues().apply { put("title", title) }
        return helper.writableDatabase.update(
            TABLE_SOURCES,
            values,
            "source_id = ? AND (title IS NULL OR trim(title) = '')",
            arrayOf(sourceId),
        ) > 0
    }

    /**
     * Writes the household's own name for a Source, or clears it.
     *
     * **One column, and it is `typed_name`.** `title` is not touched, so the name YouTube gave
     * this Source is still there to fall back on when the household clears this one, and
     * [fillMissingTitle] goes on being the only writer of `title` there has ever been.
     *
     * A null or blank name is written as SQL `NULL` rather than as an empty string, so that
     * `title IS NULL OR trim(title) = ''` keeps meaning the same thing on the other column and
     * no reader has to know about two ways of holding nothing.
     *
     * **The answer is the number of rows the `UPDATE` really touched**, the same as
     * [fillMissingTitle]'s, and for the same reason: the only refusal is a Source with no row,
     * and the caller cannot see that from outside.
     */
    override fun setTypedName(sourceId: String, typedName: String?): Boolean {
        val values = ContentValues()
        val cleaned = StoredSource.typedNameOfInput(typedName)
        if (cleaned == null) values.putNull("typed_name") else values.put("typed_name", cleaned)
        return helper.writableDatabase.update(
            TABLE_SOURCES,
            values,
            "source_id = ?",
            arrayOf(sourceId),
        ) > 0
    }

    /**
     * One remembered value out of the shared `app_meta` table.
     *
     * The table is created by the Watch side of [YouTubeTvDatabase], which is also where the
     * device id lives. Nothing here writes or reads that key.
     */
    override fun meta(key: String): String? =
        helper.readableDatabase.query(
            TABLE_META,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    override fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_META,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * How a listed-video read of one Source is ordered, in that Source's own Direction.
     *
     * An undated video is private or deleted, and goes last in **both** Directions. The
     * `IS NULL` key is the same either way and only the date key turns around, which is the
     * same rule `orderByPublishDate` applies for the in-memory store.
     *
     * **`video_id` is the last key. It does not turn around with the Direction.** So two videos
     * with exactly the same publish time have one fixed order. The undated videos among
     * themselves have one too. Two videos from the same day at different times are not a tie,
     * and the `video_published_at` key orders them before this key is reached. Without this key
     * SQL promises no order for a tie. On Android's SQLite the answer followed rowids, which is
     * the order the rows were last written in. Build ticket 57, and [TIE_KEY].
     *
     * The Direction is read before the videos and applied by SQLite, so a Source of a few
     * thousand videos is ordered once rather than sorted again in Kotlin.
     */
    private fun orderOf(sourceId: String): String {
        return orderClause(source(sourceId)?.direction ?: Direction.NEWEST_FIRST)
    }

    override fun listedVideoKeys(sourceId: String): List<VideoKey> =
        helper.readableDatabase.rawQuery(
            """
            SELECT video_id, video_published_at, newly_listed
            FROM $TABLE_VIDEOS
            WHERE $LISTED_WHERE
            ${orderOf(sourceId)}
            """.trimIndent(),
            listedArguments(sourceId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ListedVideoKey(
                            videoId = cursor.getString(0),
                            sourceId = sourceId,
                            videoPublishedAt = cursor.instantOrNull(1),
                            // Null for every row written before database version 10, and that
                            // reads as not newly listed. See [StoredVideo.newlyListed].
                            newlyListed = cursor.booleanOrFalse(2),
                        ),
                    )
                }
            }
        }

    override fun listedVideo(sourceId: String, videoId: String): StoredVideo? =
        helper.readableDatabase.rawQuery(
            """
            SELECT $VIDEO_COLUMNS
            FROM $TABLE_VIDEOS
            WHERE $LISTED_WHERE
              AND video_id = ?
            LIMIT 1
            """.trimIndent(),
            listedArguments(sourceId) + videoId,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toStoredVideo().takeIf { it.isListed }
        }

    override fun listedVideos(sourceId: String): List<StoredVideo> {
        val order = orderOf(sourceId)
        // [LISTED_WHERE] is the same rule [StoredVideo.isListed] applies, written in SQL.
        //
        // **The rule really is written twice, and the `isListed` test below is not a safety
        // net.** That `WHERE` only narrows, so it can drop a row `isListed` would have kept,
        // and no later filter can put that row back. The test below catches the other
        // direction alone: a row the query lets through that `isListed` rejects. So a change
        // to `isListed` that **relaxes** it, or a third [Availability] value, must be made in
        // [LISTED_WHERE] as well, or videos vanish from the grid on the television while every
        // JVM test still passes. Named by `/code-review` on ticket 23.
        //
        // **That sentence used to end "There is no `androidTest` source set, so nothing else
        // would notice", and that stopped being true on 2026-09-21.** Build ticket 28 added
        // `app/src/androidTest/`, and `ReplaceVideosNewlyListedTest` in this same package opens
        // a real [YouTubeTvDatabase] on a file of its own through the `databaseName` parameter.
        // So a divergence between [LISTED_WHERE] and [StoredVideo.isListed] **can** be caught by
        // a test now, and one belongs beside that file rather than nowhere.
        // `docs/agents/testing.md` calls this the fifth thing that layer is for: a rule that
        // lives in SQL, which the JVM fake cannot express. Corrected by `/code-review high` on
        // 2026-09-21, reviewing build ticket 40.
        return helper.readableDatabase.rawQuery(
            """
            SELECT $VIDEO_COLUMNS
            FROM $TABLE_VIDEOS
            WHERE $LISTED_WHERE
            $order
            """.trimIndent(),
            listedArguments(sourceId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val video = cursor.toStoredVideo()
                    if (video.isListed) add(video)
                }
            }
        }
    }

    /**
     * Every stored video of a Source, Shorts included, newest first.
     *
     * The Direction is deliberately not applied: nothing draws this list. Only a refresh reads
     * it, and a refresh replaces the whole Source's rows from it. See [SourceStore.allVideos].
     * A tie is broken by [TIE_KEY], the same as every other read.
     */
    override fun allVideos(sourceId: String): List<StoredVideo> =
        helper.readableDatabase.rawQuery(
            """
            SELECT $VIDEO_COLUMNS
            FROM $TABLE_VIDEOS
            WHERE source_id = ?
            ${orderClause(Direction.NEWEST_FIRST)}
            """.trimIndent(),
            arrayOf(sourceId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toStoredVideo())
                }
            }
        }

    override fun setDirection(sourceId: String, direction: Direction) {
        val values = ContentValues().apply { put("direction", direction.stored) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    override fun setAutoplayNext(sourceId: String, autoplayNext: Boolean) {
        val values = ContentValues().apply { put("autoplay_next", if (autoplayNext) 1 else 0) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    override fun setShuffle(sourceId: String, shuffle: Boolean) {
        val values = ContentValues().apply { put("shuffle", if (shuffle) 1 else 0) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    override fun setShuffleRun(sourceId: String, run: ShuffleRun) {
        val values = ContentValues().apply { put("shuffle_run", run.stored()) }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    override fun setPlaybackSettings(sourceId: String, playback: PlaybackSettings) {
        val values = ContentValues().apply {
            put("video_quality", playback.qualityName)
            put("playback_rate", playback.rate.toDouble())
            put("skip_start", playback.skipStartSeconds)
            put("skip_end", playback.skipEndSeconds)
        }
        helper.writableDatabase.update(TABLE_SOURCES, values, "source_id = ?", arrayOf(sourceId))
    }

    /**
     * Replaces this Source's videos inside one transaction.
     *
     * A half-written list would look to the next launch exactly like a short Source, so the
     * delete and the insert must succeed or fail together.
     */
    override fun replaceVideos(sourceId: String, videos: List<StoredVideo>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            // Which of this Source's rows have already had their Newly listed flag cleared.
            // Read inside the transaction and before the delete, because the caller's list was
            // built from a snapshot taken before the network calls, and a walk can run for
            // minutes. A play during that walk clears the flag in storage; without this the
            // stale `true` from the snapshot would be written straight back and the video
            // would be offered as Next unwatched again. Found by `/code-review high` on
            // 2026-09-21. See [clearedNewlyListedIds].
            val alreadyCleared = clearedNewlyListedIds(database, sourceId)
            database.delete(TABLE_VIDEOS, "source_id = ?", arrayOf(sourceId))
            val statement = database.compileStatement(
                """
                INSERT INTO $TABLE_VIDEOS
                (video_id, source_id, video_published_at, title, thumbnail_url, duration_seconds,
                 live_broadcast_content, live_ended_at, availability, is_short, newly_listed,
                 view_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            )
            statement.use {
                for (video in videos) {
                    statement.clearBindings()
                    statement.bindString(1, video.videoId)
                    statement.bindString(2, video.sourceId)
                    statement.bindLongOrNull(3, video.videoPublishedAt?.toEpochMilli())
                    statement.bindString(4, video.title)
                    statement.bindStringOrNull(5, video.thumbnailUrl)
                    statement.bindLongOrNull(6, video.durationSeconds?.toLong())
                    statement.bindString(7, video.liveBroadcastContent)
                    statement.bindLongOrNull(8, video.liveEndedAt?.toEpochMilli())
                    statement.bindString(9, video.availability.name)
                    statement.bindLong(10, if (video.isShort) 1 else 0)
                    // **Written on every walk, because this deletes the Source's rows and
                    // inserts them again.** A flag that `mergeRefresh` did not carry forward is
                    // lost here, which is the trap build ticket 40 names in as many words.
                    //
                    // A row whose flag storage has already cleared can never get it back here.
                    // The flag only ever travels one way for a video that is already known:
                    // set on the walk that first listed it, cleared by the first play at or
                    // past it, and never set again.
                    val stillNew = video.newlyListed && video.videoId !in alreadyCleared
                    statement.bindLong(11, if (stillNew) 1 else 0)
                    statement.bindLongOrNull(12, video.viewCount)
                    statement.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Writes the view counts of some of this Source's videos, one column each. Build ticket 59.
     *
     * One `UPDATE` per video, all inside one transaction, so a run of a few thousand rows is
     * one commit. A video id with no row is matched by nothing and is skipped: a count is never
     * a reason to create a row. See [SourceStore.writeViewCounts].
     */
    override fun writeViewCounts(sourceId: String, counts: Map<String, Long?>) {
        if (counts.isEmpty()) return
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val statement = database.compileStatement(
                "UPDATE $TABLE_VIDEOS SET view_count = ? WHERE source_id = ? AND video_id = ?",
            )
            statement.use {
                for ((videoId, count) in counts) {
                    statement.clearBindings()
                    statement.bindLongOrNull(1, count)
                    statement.bindString(2, sourceId)
                    statement.bindString(3, videoId)
                    statement.executeUpdateDelete()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * The ids of this Source's videos whose Newly listed flag is already 0.
     *
     * A video with no row at all is not in the set, which is what [replaceVideos] wants: a
     * first listing has no stored flag to respect and keeps the one `mergeRefresh` gave it.
     *
     * **The caller's own [database] is passed in, and that is not decoration.** The one caller
     * runs inside a transaction it opened on `helper.writableDatabase`, and its comment says
     * this read happens inside that transaction. `SQLiteOpenHelper.getReadableDatabase()`
     * returns the same object as `getWritableDatabase()` unless the file cannot be opened for
     * writing, so reading through the helper would have been right by accident. Taking the
     * handle the caller already holds makes it right by construction.
     *
     * **A row whose `newly_listed` is NULL is not in the set**, because SQL's `= 0` does not
     * match NULL, while `Cursor.booleanOrFalse` reads NULL as false. The two disagree and it
     * cannot matter: `mergeRefresh` is handed `SourceStore.allVideos`, every row of the
     * Source, so a video with a NULL row always takes the "already known" branch and carries
     * `before.newlyListed`, which is false. `stillNew` in [replaceVideos] is therefore already
     * false for it, and membership here could not change the answer. Every row the household's
     * television holds is NULL today: database version 10's migration adds the column with no
     * backfill.
     */
    private fun clearedNewlyListedIds(
        database: SQLiteDatabase,
        sourceId: String,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        database.query(
            TABLE_VIDEOS,
            arrayOf("video_id"),
            "source_id = ? AND newly_listed = 0",
            arrayOf(sourceId),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    /**
     * Clears the Newly listed flag on the played video and on everything at or past it.
     *
     * One `UPDATE`, whose comparison turns around with the Source's Direction. See
     * [SourceStore.clearNewlyListedUpTo], which holds the whole rule.
     *
     * **A row whose own `video_published_at` is NULL is matched by neither comparison**, which
     * is SQL's three-valued logic and is the answer this rule wants: such a video is private or
     * deleted, so it is not listed and is never offered.
     *
     * **`newly_listed = 1` is in the `WHERE` clause, and it is not an optimisation of taste.**
     * Without it this statement rewrites every row of the Source at or past the date whether or
     * not that row carries the flag, and it does it **on the main thread**: the player's
     * JavaScript bridge posts its events with `view.post`, so `PlaybackSession.record` calls
     * `WatchLog.startPlay` there, and `startPlay` calls this inside the transaction that appends
     * the log entry. On a Source the size of `TED` that is over 5,000 rows rewritten on the
     * frame a play passes ten seconds, and on a Source of 600 videos it is 600, to change the
     * one or two rows that really carry the flag. Raised by `/code-review high` on 2026-09-21.
     *
     * **The clause changes no answer, and here is why in full.** With it, a row whose column is
     * NULL stays NULL where it used to be written to 0. The only reader that can tell NULL from
     * 0 is [clearedNewlyListedIds], whose `newly_listed = 0` does not match NULL, so such a row
     * stops being in its set. That set is used in exactly one expression,
     * `video.newlyListed && video.videoId !in alreadyCleared`, and a row that exists with a NULL
     * column always makes the left side false: `mergeRefresh` is handed every row of the Source
     * by [allVideos], so a video whose row exists takes the "already known" branch and carries
     * `before.newlyListed`, which reads NULL as false. The `&&` never reaches the set. A row
     * this statement really does clear still goes to 0 and is still in the set, so the guard
     * against a stale snapshot writing the flag back on is untouched.
     *
     * It also makes the SQL agree with the fake. `InMemorySourceStore.clearNewlyListedUpTo`
     * already writes only where the flag is set, and `docs/agents/testing.md` says the fake's
     * whole promise is that a test passing against it is testing the rule and not the SQL.
     */
    override fun clearNewlyListedUpTo(sourceId: String, videoPublishedAt: Instant?) {
        if (videoPublishedAt == null) return
        val direction = source(sourceId)?.direction ?: Direction.NEWEST_FIRST
        val atOrPast = when (direction) {
            Direction.NEWEST_FIRST -> "video_published_at >= ?"
            Direction.OLDEST_FIRST -> "video_published_at <= ?"
        }
        // `execSQL` and not `update`, because `update` binds every argument as text and the
        // date is a number. A text value compared against an INTEGER column relies on SQLite
        // applying the column's affinity, which is true but is not worth resting a date
        // comparison on. Here the date is bound as a `Long`.
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE_VIDEOS SET newly_listed = 0 " +
                "WHERE source_id = ? AND newly_listed = 1 AND $atOrPast",
            arrayOf<Any>(sourceId, videoPublishedAt.toEpochMilli()),
        )
    }

}

/**
 * Every column of a video's row, in the one order [Cursor.toStoredVideo] reads them in.
 *
 * Named once for the same reason [SOURCE_COLUMNS] is: two reads that spell the list out
 * separately can drift apart, and the reader would then build a video out of the wrong columns.
 */
private const val VIDEO_COLUMNS =
    "video_id, source_id, video_published_at, title, thumbnail_url, " +
        "duration_seconds, live_broadcast_content, live_ended_at, availability, is_short, " +
        "newly_listed, view_count"

/**
 * The last key of every `ORDER BY` over videos, the same in both Directions.
 *
 * It is what `orderByPublishDate` does in Kotlin, and the two must agree. The column has no
 * declared collation, so SQLite compares it byte by byte, and a YouTube video id is ASCII, so
 * that is the same order as Kotlin's `String.compareTo`. Spelled `ASC` on purpose: it does not
 * turn around with the Direction. Build ticket 57.
 */
private const val TIE_KEY = "video_id ASC"

/**
 * The one `ORDER BY` every read of videos uses, in one Direction.
 *
 * Undated videos last in both Directions, then the date in the Direction, then [TIE_KEY]. Written
 * once, so `listedVideos`, `listedVideoKeys` and `allVideos` cannot come to disagree with each
 * other, and only this has to be kept in step with `orderByPublishDate`. Found by `/code-review`
 * on build ticket 57, which had to edit two copies of it.
 */
private fun orderClause(direction: Direction): String {
    val byDate = when (direction) {
        Direction.NEWEST_FIRST -> "DESC"
        Direction.OLDEST_FIRST -> "ASC"
    }
    return "ORDER BY video_published_at IS NULL, video_published_at $byDate, $TIE_KEY"
}

/**
 * The three conditions that say a stored row is a **listed** video of this Source.
 *
 * They are the same three [StoredVideo.isListed] applies, written again in SQL so that a Source
 * of a few thousand rows does not travel through the cursor only to be dropped in Kotlin.
 * Ticket 23 added the availability and upcoming conditions; before it, only Shorts were
 * dropped.
 *
 * **It is written once and shared by all three listed reads**, so `listedVideos`,
 * `listedVideoKeys` and `listedVideo` cannot come to disagree about which videos a Source has.
 * Build ticket 22 added the other two, and a second copy of this `WHERE` would have been a card
 * counting videos its own grid does not draw.
 *
 * The `NOT NULL` on both columns is what keeps the `!=` comparison out of SQL's three-valued
 * logic, where a NULL would never match and the row would be dropped.
 */
private const val LISTED_WHERE =
    "source_id = ? AND is_short = 0 AND availability = ? AND live_broadcast_content != ?"

/** The three values [LISTED_WHERE] binds, in its own order. */
private fun listedArguments(sourceId: String): Array<String> =
    arrayOf(sourceId, Availability.AVAILABLE.name, LiveBroadcastContent.UPCOMING)

/**
 * Every column of a Source's row, in the one order [Cursor.toStoredSource] reads them in.
 *
 * Named once, so that a read cannot ask for fewer columns than it builds a [StoredSource]
 * from. `allSources` used to ask for four of the five there were and quietly told every card
 * that autoplay-next was on, which nothing on the home rail happens to draw.
 *
 * **`initial_mark_offered` is deliberately not here.** The column is still in the table and is
 * dead: ticket 25 removed the one-time mark-everything-watched offer that was its only reader
 * and its only writer. See the note on it in `Database.kt`.
 */
private val SOURCE_COLUMNS = arrayOf(
    "source_id",
    "title",
    "last_walked_at",
    "direction",
    "autoplay_next",
    "added_at",
    "remote_count",
    "typed_name",
    "shuffle",
    "shuffle_run",
    "video_quality",
    "playback_rate",
    "skip_start",
    "skip_end",
)

private fun Cursor.toStoredSource(): StoredSource = StoredSource(
    sourceId = getString(0),
    title = if (isNull(1)) null else getString(1),
    // Null between adding a Source and its first walk finishing. It is what says the Source
    // has been walked, and the presence of the row itself only says it was added.
    lastWalkedAt = instantOrNull(2),
    // Null for a row written before database version 5, and that reads as newest first,
    // which is what every Source did before Direction existed.
    direction = Direction.ofStored(if (isNull(3)) null else getString(3)),
    // Null for a row written before database version 6, and that reads as on. That is no
    // longer what [StoredSource.autoplayNext] defaults to: ticket 18 made a new Source start
    // off and left this rule where it was, so the four Sources the household has already
    // walked keep the setting they have been living with. The rule itself is
    // [StoredSource.autoplayNextOfStored], so a JVM test can hold the two answers apart.
    autoplayNext = StoredSource.autoplayNextOfStored(if (isNull(4)) null else getInt(4)),
    addedAt = instantOrNull(5),
    // Null for a row written before database version 8, and for one no change check has ever
    // reached. Either way the next check re-lists it once and then stops costing anything.
    remoteCount = intOrNull(6),
    // Null for a row written before database version 9, which is every row the household has
    // today, and that reads as "no typed name": the card goes on showing the title YouTube
    // gave it. A blank is read as null here as well, so that a row written by an older or a
    // stranger build cannot put an empty name on a card. See [StoredSource.typedNameOfInput].
    typedName = StoredSource.typedNameOfInput(if (isNull(7)) null else getString(7)),
    // Null for a row written before database version 12, and that reads as off. Build ticket 69.
    shuffle = booleanOrFalse(8),
    // Null until the Source first has a run. A run this build cannot read is no run.
    shuffleRun = shuffleRunOfStored(if (isNull(9)) null else getString(9)),
    // Null for a row written before database version 13, and that reads as the app's own
    // picture setting, 1x and no skip. Fork 0.5.
    playback = PlaybackSettings(
        qualityName = if (isNull(10)) null else getString(10),
        rate = PlaybackSettings.rateOfStored(if (isNull(11)) null else getDouble(11)),
        skipStartSeconds = PlaybackSettings.skipOfStored(intOrNull(12)),
        skipEndSeconds = PlaybackSettings.skipOfStored(intOrNull(13)),
    ),
)

private fun Cursor.instantOrNull(column: Int): Instant? =
    if (isNull(column)) null else Instant.ofEpochMilli(getLong(column))

private fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)

private fun Cursor.toStoredVideo(): StoredVideo = StoredVideo(
    videoId = getString(0),
    sourceId = getString(1),
    videoPublishedAt = instantOrNull(2),
    title = getString(3),
    thumbnailUrl = if (isNull(4)) null else getString(4),
    durationSeconds = intOrNull(5),
    liveBroadcastContent = getString(6),
    liveEndedAt = instantOrNull(7),
    availability = availabilityNamed(getString(8)),
    isShort = getInt(9) != 0,
    // Null for every row written before database version 10, which is every row the household
    // has today, and that reads as not newly listed. See [StoredVideo.newlyListed].
    newlyListed = booleanOrFalse(10),
    // Null for every row written before database version 11, and that reads as no count, so
    // the card shows the date alone. Build ticket 59.
    viewCount = longOrNull(11),
)

private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

/** An integer column that may hold nothing, read as false when it does. */
private fun Cursor.booleanOrFalse(column: Int): Boolean = !isNull(column) && getInt(column) != 0

private fun android.database.sqlite.SQLiteStatement.bindLongOrNull(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun android.database.sqlite.SQLiteStatement.bindStringOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}
