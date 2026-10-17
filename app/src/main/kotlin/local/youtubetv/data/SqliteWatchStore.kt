package local.youtubetv.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

private const val META_DEVICE_ID = "deviceId"
private const val META_DERIVATION_VERSION = "derivationVersion"

/**
 * The real Watch store on the television, in SQLite.
 *
 * It holds no rule of its own. Everything that decides an answer is in [WatchLog] and in
 * `WatchDerivation.kt` and is covered on the JVM against [InMemoryWatchStore]. What is here is
 * the SQL and nothing else, so the two cannot drift apart in a way a test would not catch.
 *
 * The one thing to be careful with: [TABLE_WATCH_LOG] and [TABLE_KEPT_VIDEO] are the only
 * rows in this app that nothing can fetch again. Nothing here deletes from either.
 */
class SqliteWatchStore(private val helper: SQLiteOpenHelper) : WatchStore {

    private val database: SQLiteDatabase get() = helper.writableDatabase

    /**
     * This device's id, made once and then kept forever.
     *
     * It is what makes `seq` meaningful: ordering within a device is by `seq`, and a device
     * that forgot its id would start counting from one again beside its own older entries.
     */
    private val deviceId: String by lazy {
        meta(META_DEVICE_ID) ?: UUID.randomUUID().toString().also { putMeta(META_DEVICE_ID, it) }
    }

    /**
     * Appends one entry.
     *
     * Reading the highest `seq` and writing the next one are one transaction, because they
     * are one decision. Two appends running at once would otherwise both read the same
     * highest value and both claim the number after it, and ADR 0001 needs `seq` to be a
     * per-device counter that only ever goes up. `watch_log_seq_is_unique` is the belt to
     * that brace: a duplicate fails the insert rather than quietly landing.
     */
    override fun append(kind: String, payload: String, at: Instant): WatchEntry =
        inTransaction {
            val entry = WatchEntry(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                seq = nextSeq(),
                at = at,
                kind = kind,
                payload = payload,
            )
            val values = ContentValues().apply {
                put("id", entry.id)
                put("device_id", entry.deviceId)
                put("seq", entry.seq)
                put("at", entry.at.toEpochMilli())
                put("kind", entry.kind)
                put("video_id", entry.namedVideoId())
                put("payload", entry.payload)
            }
            database.insertOrThrow(TABLE_WATCH_LOG, null, values)
            entry
        }

    override fun <T> inTransaction(body: () -> T): T {
        val database = this.database
        database.beginTransaction()
        return try {
            val result = body()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    /** One past the highest this device has written. Never a count, which a deletion could dent. */
    private fun nextSeq(): Long =
        database.rawQuery(
            "SELECT MAX(seq) FROM $TABLE_WATCH_LOG WHERE device_id = ?",
            arrayOf(deviceId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) + 1 else 1L
        }

    /**
     * The one edit the log allows. See [WatchStore.replacePayload].
     *
     * `video_id` is left alone, because only the `position` and `completed` of a play ever
     * change and neither is the video. Rewriting it would be one more thing to get wrong on
     * the one write path that is allowed to touch an entry that already exists.
     */
    override fun replacePayload(entryId: String, payload: String) {
        val values = ContentValues().apply { put("payload", payload) }
        database.update(TABLE_WATCH_LOG, values, "id = ?", arrayOf(entryId))
    }

    override fun entries(): List<WatchEntry> = readEntries(where = null, arguments = null)

    override fun entriesNaming(videoId: String): List<WatchEntry> =
        readEntries(where = "video_id = ?", arguments = arrayOf(videoId))

    private fun readEntries(where: String?, arguments: Array<String>?): List<WatchEntry> =
        database.query(
            TABLE_WATCH_LOG,
            arrayOf("id", "device_id", "seq", "at", "kind", "payload"),
            where,
            arguments,
            null,
            null,
            "at ASC, seq ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WatchEntry(
                            id = cursor.getString(0),
                            deviceId = cursor.getString(1),
                            seq = cursor.getLong(2),
                            at = Instant.ofEpochMilli(cursor.getLong(3)),
                            kind = cursor.getString(4),
                            payload = cursor.getString(5),
                        ),
                    )
                }
            }
        }

    override fun derivedTableAbsent(): Boolean =
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(TABLE_WATCH_STATE),
        ).use { !it.moveToFirst() }

    override fun watchStates(): Map<String, WatchState> {
        createWatchStateTable(database)
        return readStates(where = null, arguments = null).associateBy { it.videoId }
    }

    override fun watchState(videoId: String): WatchState? {
        createWatchStateTable(database)
        return readStates(where = "video_id = ?", arguments = arrayOf(videoId)).firstOrNull()
    }

    private fun readStates(where: String?, arguments: Array<String>?): List<WatchState> =
        database.query(
            TABLE_WATCH_STATE,
            arrayOf("video_id", "watched", "position_seconds", "completed", "last_played_at"),
            where,
            arguments,
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toWatchState())
                }
            }
        }

    override fun putWatchState(state: WatchState) {
        createWatchStateTable(database)
        database.insertWithOnConflict(
            TABLE_WATCH_STATE,
            null,
            state.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Replaces the whole derived table inside one transaction.
     *
     * A half-written derived table would look to the next launch like a complete one that
     * happens to say the household watched nothing, and nothing would rebuild it.
     */
    override fun replaceWatchStates(states: Collection<WatchState>) {
        createWatchStateTable(database)
        inTransaction {
            database.delete(TABLE_WATCH_STATE, null, null)
            for (state in states) {
                database.insertOrThrow(TABLE_WATCH_STATE, null, state.toValues())
            }
        }
    }

    override fun storedDerivationVersion(): Int? = meta(META_DERIVATION_VERSION)?.toIntOrNull()

    override fun setDerivationVersion(version: Int) {
        putMeta(META_DERIVATION_VERSION, version.toString())
    }

    /**
     * Writes the copy only when this video has none.
     *
     * `INSERT OR IGNORE`, so the first copy is the one kept. A later mark must not overwrite
     * it: by then the walk may have dropped the video, and the title on hand would be worse
     * than the one already stored, or missing altogether.
     */
    override fun keepVideo(video: KeptVideo) {
        val values = ContentValues().apply {
            put("video_id", video.videoId)
            put("title", video.title)
            put("video_published_at", video.videoPublishedAt?.toEpochMilli())
        }
        database.insertWithOnConflict(
            TABLE_KEPT_VIDEO,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    override fun keptVideo(videoId: String): KeptVideo? =
        readKept(where = "video_id = ?", arguments = arrayOf(videoId)).firstOrNull()

    override fun keptVideos(): List<KeptVideo> = readKept(where = null, arguments = null)

    private fun readKept(where: String?, arguments: Array<String>?): List<KeptVideo> =
        database.query(
            TABLE_KEPT_VIDEO,
            arrayOf("video_id", "title", "video_published_at"),
            where,
            arguments,
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        KeptVideo(
                            videoId = cursor.getString(0),
                            title = cursor.getString(1),
                            videoPublishedAt =
                                if (cursor.isNull(2)) null else Instant.ofEpochMilli(cursor.getLong(2)),
                        ),
                    )
                }
            }
        }

    private fun meta(key: String): String? =
        database.query(
            TABLE_META,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun putMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        database.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private fun WatchState.toValues(): ContentValues = ContentValues().apply {
    put("video_id", videoId)
    put("watched", if (watched) 1 else 0)
    put("position_seconds", positionSeconds)
    put("completed", if (completed) 1 else 0)
    put("last_played_at", lastPlayedAt?.toEpochMilli())
}

private fun Cursor.toWatchState(): WatchState = WatchState(
    videoId = getString(0),
    watched = getInt(1) != 0,
    positionSeconds = if (isNull(2)) null else getInt(2),
    completed = getInt(3) != 0,
    lastPlayedAt = if (isNull(4)) null else Instant.ofEpochMilli(getLong(4)),
)
