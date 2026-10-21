package local.youtubetv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The one SQLite file this app keeps, and the only place its schema is written.
 *
 * There is no ORM here on purpose. Android ships SQLite, so this needs no dependency and no
 * annotation processor, and AGP 9 compiles Kotlin itself with no Kotlin Gradle plugin applied.
 * Every rule worth testing already lives behind [SourceStore] and [WatchStore] and is covered
 * on the JVM against their in-memory versions.
 *
 * Two kinds of table live in this file, and they must never be treated alike.
 *
 * - **Cache**: [TABLE_SOURCES], [TABLE_VIDEOS] and [TABLE_WATCH_STATE]. Every row can be got
 *   back: the first two by re-walking the Source, the third by deriving the Watch log again.
 * - **History**: [TABLE_WATCH_LOG], [TABLE_KEPT_VIDEO] and the device id in [TABLE_META].
 *   Nothing can get these back. They are the household's own record of what it watched, and
 *   they are what cloud sync will one day replay. **No upgrade may ever drop them.**
 */
private const val DATABASE_NAME = "youtube-tv.db"

/**
 * Bumped whenever a table in here changes shape.
 *
 * | Version | What changed |
 * | --- | --- |
 * | 1 | Ticket 02: the Sources and their videos. |
 * | 2 | Ticket 04: the Watch log, the derived table, the kept videos and the settings. |
 * | 3 | Ticket 04: `watch_log_seq_is_unique`, so a repeated `seq` fails rather than lands. |
 * | 4 | Ticket 04: drops `watch_log_by_device_seq`, which version 3 replaced. |
 * | 5 | Ticket 06: `sources.direction`, the Source's own `Order` choice. |
 * | 6 | Ticket 08: `sources.autoplay_next`, the Source's own autoplay-next choice. |
 * | 7 | Ticket 11: `sources.initial_mark_offered` and `sources.added_at`. |
 * | 8 | Ticket 13: `sources.remote_count`, what the change check last heard this Source's size was. |
 * | 9 | Ticket 27: `sources.typed_name`, the name the household typed for this Source. |
 * | 10 | Ticket 40: `videos.newly_listed`, whether a walk has only just listed this video. |
 * | 11 | Ticket 59: `videos.view_count`, YouTube's own view count for this video. |
 * | 12 | Ticket 69: `sources.shuffle` and `sources.shuffle_run`, the Shuffle setting and where its run is up to. |
 * | 13 | Fork 0.5: `sources.video_quality`, `sources.playback_rate`, `sources.skip_start`, `sources.skip_end`. How the Source plays. |
 *
 * **`sources.title` and `sources.typed_name` are two different facts and are never merged.**
 * `title` is what YouTube calls the Source and the change check keeps it up to date for free.
 * `typed_name` is what the household typed over it. Clearing the typed one has to bring
 * YouTube's back, and it can only do that if YouTube's was never written over. The owner chose that
 * on 2026-09-20 over leaving the card blank: with one column, the only way back would be to
 * remove the Source and add it again, which spends a full walk and splits the Watch log.
 *
 * **`sources.initial_mark_offered` is dead and is deliberately still here.** Ticket 25 removed
 * the one-time mark-everything-watched offer, which was the column's only reader and its only
 * writer, and chose to leave the column rather than drop it. SQLite cannot drop a column without
 * rebuilding the table, this file holds the household's only copy of its Watch log, and a
 * rebuild would put that log at risk to delete four bytes per Source. Nothing reads the column,
 * nothing writes it, and it is not in `SOURCE_COLUMNS`.
 *
 * [YouTubeTvDatabase.onUpgrade] walks the steps one at a time. A future change to the **video**
 * tables may drop and recreate them, because videos are cache and re-walking costs a few
 * hundred units of the daily 10,000. A change to the Watch log may not: it has to be a
 * migration.
 */
private const val DATABASE_VERSION = 13

internal const val TABLE_SOURCES = "sources"
internal const val TABLE_VIDEOS = "videos"

/** The append-only log. History. */
internal const val TABLE_WATCH_LOG = "watch_log"

/** One row per video, derived from the log. Cache: delete it and it is rebuilt. */
internal const val TABLE_WATCH_STATE = "watch_state"

/** A marked video's title and publish date, kept after YouTube stops serving it. History. */
internal const val TABLE_KEPT_VIDEO = "kept_video"

/** This device's id, and the derivation version last used. */
internal const val TABLE_META = "app_meta"

/**
 * Opens the app's database, and creates or migrates it on the way.
 *
 * One helper for the whole app, shared by [SqliteSourceStore] and [SqliteWatchStore]. Two
 * helpers over one file would each think they owned its version number.
 *
 * @param databaseName **the app leaves this alone.** It is here so that an instrumentation
 *   test in `app/src/androidTest` can open a database file of its own, with this exact schema
 *   and these exact migrations, and never the file the app is using. The alternative was a
 *   test that scribbled in the app's own `youtube-tv.db`, and the one table this file says may
 *   never be dropped is the Watch log.
 */
class YouTubeTvDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(database: SQLiteDatabase) {
        createVideoTables(database)
        createWatchTables(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // One step per version, and never a blanket drop. The television already holds four
        // walked Sources and the household's own Watches, and only the first of those could
        // be fetched again.
        if (oldVersion < 2) {
            createWatchTables(database)
        }
        if (oldVersion < 3) {
            createWatchLogSeqIndex(database)
        }
        if (oldVersion < 4) {
            // `watch_log_seq_is_unique` covers the same two columns and differs only in
            // being unique, so nothing reads this one any more. An index is derived from the
            // rows, so dropping one loses nothing and costs one write less per append.
            database.execSQL("DROP INDEX IF EXISTS watch_log_by_device_seq")
        }
        if (oldVersion < 5) {
            // Added rather than recreated, although `sources` is cache. Recreating it would
            // make the television re-walk all four Sources for a column that starts empty,
            // and an empty column already reads as newest first.
            //
            // **Only if it is not already there.** Every other step in this file is
            // idempotent, and `ALTER TABLE ... ADD COLUMN` is not: it throws
            // `duplicate column name`. [onDowngrade] keeps every row and lets an older build
            // write the lower version number back, so this database can legitimately arrive
            // here at version 4 with the column already present: install this build, install
            // the previous one, install this one again. The throw would escape
            // `getWritableDatabase` and the app would fail to start until its data was wiped,
            // which is the one outcome this file exists to prevent. Found by `/code-review`.
            if (!hasColumn(database, TABLE_SOURCES, "direction")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN direction TEXT")
            }
        }
        if (oldVersion < 6) {
            // Guarded for exactly the reason the step above is: `ALTER TABLE ... ADD COLUMN`
            // throws `duplicate column name`, and [onDowngrade] lets this database arrive
            // here with the column already present. An empty column reads as autoplay-next
            // on, which is what a new Source starts as.
            if (!hasColumn(database, TABLE_SOURCES, "autoplay_next")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN autoplay_next INTEGER")
            }
        }
        if (oldVersion < 7) {
            // Two more columns on `sources`, added and not recreated for the reason step 5
            // gives, and each guarded with `PRAGMA table_info` for the reason step 5 gives.
            //
            // Both read correctly when empty, which is what makes this safe on the four
            // Sources the television already holds:
            //
            // - `initial_mark_offered` is dead since ticket 25 and nothing reads it. The step
            //   still runs, so that a database at version 6 and one created fresh have the
            //   same shape, which is what keeps this file's upgrade path simple to reason
            //   about.
            // - `added_at` empty reads as added before this was recorded, and such a Source
            //   keeps its place at the front of the rail in Source id order, which is exactly
            //   where it is today.
            if (!hasColumn(database, TABLE_SOURCES, "initial_mark_offered")) {
                database.execSQL(
                    "ALTER TABLE $TABLE_SOURCES ADD COLUMN initial_mark_offered INTEGER",
                )
            }
            if (!hasColumn(database, TABLE_SOURCES, "added_at")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN added_at INTEGER")
            }
        }
        if (oldVersion < 8) {
            // Added and not recreated, and guarded with `PRAGMA table_info`, for the reasons
            // step 5 gives. Empty reads as "the change check has never asked about this
            // Source", which is true of every Source the television already holds, and which
            // makes the next check re-list it once and then cost nothing.
            if (!hasColumn(database, TABLE_SOURCES, "remote_count")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN remote_count INTEGER")
            }
        }
        if (oldVersion < 9) {
            // Added and not recreated, and guarded with `PRAGMA table_info`, for the reasons
            // step 5 gives. This is the step that carries the household's four walked Sources
            // and their 81 Watch log entries from version 8 to 9, and the whole of it is one
            // `ALTER TABLE ... ADD COLUMN` on `sources`, which is cache: it copies no row,
            // rebuilds no table and cannot reach `watch_log`, `kept_video` or `app_meta`.
            //
            // Empty reads as "the household has not typed a name for this Source", which is
            // true of every Source that exists today, and which leaves every card reading
            // exactly the name it read before the upgrade. See
            // `StoredSource.typedNameOfInput` for the one rule that says what the column
            // means, and `sourceNameOrId` for what is drawn when it is empty.
            if (!hasColumn(database, TABLE_SOURCES, "typed_name")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN typed_name TEXT")
            }
        }
        if (oldVersion < 10) {
            // **The first `ALTER TABLE` this app has ever made on `videos`.** Every step above
            // is on `sources`. The note at the top of this file says the video tables may be
            // dropped and recreated, because they are cache, and that is true of every fact
            // they hold but this one.
            //
            // Dropping them here would be wrong all the same. `newly_listed` is set by
            // `mergeRefresh` for a video a walk lists for the **first** time, so an empty
            // `videos` table would make the next walk read all 6,904 of the household's rows
            // as first listings. The one rule that stops it, `stored.isNotEmpty()`, is exactly
            // what a dropped table defeats. So this is an add, and it is guarded with
            // `PRAGMA table_info` for the reason step 5 gives.
            //
            // **There is no backfill here, and there must not be one.** An empty column reads
            // as not newly listed, which is the right answer for every row the television
            // already holds: the household has seen those Sources. So no card's badge, Next
            // unwatched slot or Last played can move as a result of this upgrade, and that is
            // true by construction rather than by measurement. See
            // `StoredVideo.newlyListed`.
            if (!hasColumn(database, TABLE_VIDEOS, "newly_listed")) {
                database.execSQL("ALTER TABLE $TABLE_VIDEOS ADD COLUMN newly_listed INTEGER")
            }
        }
        if (oldVersion < 11) {
            // Build ticket 59. An add and not a drop of `videos`, for the reason step 10 gives:
            // an empty `videos` table would defeat the Newly listed rule, and getting the rows
            // back would cost a full walk of every Source. Guarded with `PRAGMA table_info` for
            // the reason step 5 gives.
            //
            // **Empty reads as "no count", which is true of every row the television holds.**
            // The card then shows the date alone, exactly as it did before this build. The
            // counts arrive from the one-time first read in `ViewCountFirstRead` on the first
            // launch, not from here: a migration runs with no network and must not spend quota.
            if (!hasColumn(database, TABLE_VIDEOS, "view_count")) {
                database.execSQL("ALTER TABLE $TABLE_VIDEOS ADD COLUMN view_count INTEGER")
            }
        }
        if (oldVersion < 12) {
            // Build ticket 69. Two adds on `sources`, guarded with `PRAGMA table_info` for the
            // reason step 5 gives. No backfill: an empty `shuffle` reads as off, which is what
            // every Source was before Shuffle existed, and an empty `shuffle_run` reads as no run
            // yet. Nothing here reaches `watch_log`: a run is not a Watch.
            if (!hasColumn(database, TABLE_SOURCES, "shuffle")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN shuffle INTEGER")
            }
            if (!hasColumn(database, TABLE_SOURCES, "shuffle_run")) {
                database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN shuffle_run TEXT")
            }
        }
        if (oldVersion < 13) {
            // Fork 0.5. Four adds on `sources`, guarded like every step above. No backfill: an
            // empty column reads as the app's own picture setting, 1x, and no skip, which is
            // what every Source did before these existed. Nothing here reaches `watch_log`.
            for (column in listOf("video_quality TEXT", "playback_rate REAL", "skip_start INTEGER", "skip_end INTEGER")) {
                if (!hasColumn(database, TABLE_SOURCES, column.substringBefore(' '))) {
                    database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN $column")
                }
            }
        }
    }

    /**
     * Keeps every row when a newer database meets an older build.
     *
     * The default throws, which would leave the household with an app that will not start
     * until its data is wiped. Every read in this app names its columns, so an extra table or
     * an extra column it has never heard of costs it nothing.
     */
    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * Whether a table already has a column, from SQLite's own `PRAGMA table_info`.
 *
 * It is what makes an `ALTER TABLE ... ADD COLUMN` step safe to run twice, which every other
 * step in this file already is.
 */
private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean =
    database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val name = cursor.getColumnIndex("name")
        if (name < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(name) == column) return true
        }
        false
    }

private fun createVideoTables(database: SQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_SOURCES (
            source_id TEXT PRIMARY KEY NOT NULL,
            title TEXT,
            last_walked_at INTEGER,
            direction TEXT,
            autoplay_next INTEGER,
            -- Dead since ticket 25 and kept on purpose. See the version table above.
            initial_mark_offered INTEGER,
            added_at INTEGER,
            remote_count INTEGER,
            -- What the household typed. `title` is what YouTube says. See the version table.
            typed_name TEXT,
            -- Shuffle, and where its run is up to. Empty reads as off and no run. Ticket 69.
            shuffle INTEGER,
            shuffle_run TEXT,
            -- How the Source plays. Empty reads as the app's picture setting, 1x, no skip. Fork 0.5.
            video_quality TEXT,
            playback_rate REAL,
            skip_start INTEGER,
            skip_end INTEGER
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_VIDEOS (
            video_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            video_published_at INTEGER,
            title TEXT NOT NULL,
            thumbnail_url TEXT,
            duration_seconds INTEGER,
            live_broadcast_content TEXT NOT NULL,
            live_ended_at INTEGER,
            availability TEXT NOT NULL,
            is_short INTEGER NOT NULL,
            -- Whether a walk has only just listed this video. Empty reads as no, which is
            -- what every row written before database version 10 means. Ticket 40.
            newly_listed INTEGER,
            -- YouTube's own view count. Empty reads as no count, never as zero. Ticket 59.
            view_count INTEGER,
            PRIMARY KEY (source_id, video_id)
        )
        """.trimIndent(),
    )
    // Every read is "this Source's listed videos, newest first".
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS videos_by_source_and_date " +
            "ON $TABLE_VIDEOS (source_id, is_short, video_published_at DESC)",
    )
}

private fun createWatchTables(database: SQLiteDatabase) {
    // The log. `payload` holds the kind's own fields as JSON, so an entry of a kind this
    // build does not recognise keeps every field it was written with. See WatchEntry.
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_WATCH_LOG (
            id TEXT PRIMARY KEY NOT NULL,
            device_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            at INTEGER NOT NULL,
            kind TEXT NOT NULL,
            video_id TEXT,
            payload TEXT NOT NULL
        )
        """.trimIndent(),
    )
    // `video_id` is a copy of what the payload already says, kept only so that "every entry
    // about this video" is an index lookup rather than a scan of the whole log. The payload
    // is still the record. It is null for a kind that names no video, including any kind this
    // build does not recognise, because this app must not guess at a meaning it invented.
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS watch_log_by_video ON $TABLE_WATCH_LOG (video_id)",
    )
    createWatchLogSeqIndex(database)
    createWatchStateTable(database)
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_KEPT_VIDEO (
            video_id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            video_published_at INTEGER
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_META (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL
        )
        """.trimIndent(),
    )
}

/**
 * The index that makes `seq` a real per-device counter.
 *
 * It is unique, so two entries of one device can never share a number. ADR 0001 orders one
 * device's own entries by `seq` alone, and a repeat would leave two entries with no order
 * between them. Unique means a mistake fails the insert instead of landing quietly.
 *
 * It also serves the "highest `seq` this device has written" read that every append makes.
 */
private fun createWatchLogSeqIndex(database: SQLiteDatabase) {
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS watch_log_seq_is_unique " +
            "ON $TABLE_WATCH_LOG (device_id, seq)",
    )
}

/**
 * The derived table, created wherever it is touched.
 *
 * It is the one table in this file that is meant to be deleted. Deleting it and relaunching is
 * an acceptance criterion of ticket 04, so nothing may treat its absence as an error: every
 * read and every write of it calls this first, and [WatchLog.openAndDerive] turns the absence
 * into a rebuild.
 */
internal fun createWatchStateTable(database: SQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_WATCH_STATE (
            video_id TEXT PRIMARY KEY NOT NULL,
            watched INTEGER NOT NULL,
            position_seconds INTEGER,
            completed INTEGER NOT NULL,
            last_played_at INTEGER
        )
        """.trimIndent(),
    )
}
