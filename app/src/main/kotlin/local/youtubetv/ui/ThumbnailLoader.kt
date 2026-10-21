package local.youtubetv.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import local.youtubetv.youtube.YouTubeUrls

/**
 * Where a card's picture comes from.
 *
 * This is the third seam of the app, after the network seam in `youtube/YouTubeApi.kt` and the
 * storage seam in `data/SourceStore.kt`, and it has the same shape: an interface, one real
 * implementation for the television, and one that does nothing for everything else.
 *
 * It is a seam and not a rule, so there is no unit test behind it. Decoding a JPEG needs a real
 * Android runtime. What is testable about the grid lives in `GridNavigation.kt` and
 * `SourceScreenText.kt` instead.
 */
interface ThumbnailLoader {

    /**
     * The picture of one video, or null when none of its URLs can be had. A null leaves the
     * card's dark tile, and the grid stays browsable.
     *
     * @param storedUrl what `videos.list` reported at the walk. It is a preference, not the
     *   only address: see [YouTubeUrls.thumbnails].
     */
    suspend fun load(videoId: String, storedUrl: String?): ImageBitmap?

    /**
     * The picture if it has already been decoded, without waiting for anything.
     *
     * A card asks this while it composes, so a picture that is already in hand is drawn in the
     * same frame. Going through [load] instead meant a card scrolled back over drew its dark
     * tile first and its picture one frame later, which read as the grid stuttering.
     */
    fun cached(videoId: String): ImageBitmap?
}

/** Loads nothing. Used by Compose previews, where there is no network. */
object NoThumbnails : ThumbnailLoader {
    override suspend fun load(videoId: String, storedUrl: String?): ImageBitmap? = null

    override fun cached(videoId: String): ImageBitmap? = null
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/** About 40 cards' worth of pictures at the size below. Scrolling back is then instant. */
const val CACHE_BYTES = 24 * 1024 * 1024

/**
 * The cache on a television with little memory: about a dozen cards, one screen of the grid.
 *
 * Scrolling a Source of two thousand videos fills the whole 24 MB long before a video is
 * played, and the Source screen stays composed under the player, so that memory sits there
 * for the whole video. On a television with little memory it competes with the player's own
 * decoder and renderer.
 */
const val LOW_RAM_CACHE_BYTES = 8 * 1024 * 1024

/** What the cache keeps while a video plays: the cards around the one the household left. */
const val PLAYBACK_CACHE_BYTES = 4 * 1024 * 1024

/**
 * How big the picture cache may grow on this device.
 *
 * @param memoryClassMb `ActivityManager.memoryClass`, the heap this app may use.
 * @param lowRamDevice `ActivityManager.isLowRamDevice`.
 */
fun thumbnailCacheBytes(memoryClassMb: Int, lowRamDevice: Boolean): Int =
    if (lowRamDevice || memoryClassMb <= LOW_RAM_MEMORY_CLASS_MB) LOW_RAM_CACHE_BYTES else CACHE_BYTES

/** A heap this size or smaller is a low-memory television, whatever it says of itself. */
const val LOW_RAM_MEMORY_CLASS_MB = 192

/**
 * How wide a decoded thumbnail is kept, in pixels.
 *
 * A card is about 430 pixels wide on the television at four columns, and YouTube's
 * `maxresdefault` is 1280. Holding the full-size picture would spend nine times the memory
 * for nothing, and a television has very little of it.
 */
private const val TARGET_WIDTH_PX = 480

/** How many pictures download at once. The grid asks for fifteen the moment it opens. */
private const val PARALLEL_DOWNLOADS = 4

/** What came back from asking one URL for a picture. */
private enum class Attempt {
    /** YouTube answered, and the bytes decoded. */
    DECODED,

    /** YouTube answered, and there is no picture at that address. Asking again will not help. */
    MISSING,

    /** The television could not reach YouTube at all. Asking again later may well work. */
    UNREACHABLE,
}

/**
 * The real thumbnail loader.
 *
 * No image library is used, for the same reason no HTTP library is: this app downloads a JPEG
 * and decodes it, `HttpURLConnection` and `BitmapFactory` both ship inside Android, and a
 * library here would be another dependency to keep current.
 *
 * A missing picture is remembered, so a card scrolled past repeatedly does not ask again for
 * something that is not there. **An unreachable one is not remembered.** The television is
 * offline often enough that remembering those would leave every card a dark tile until the app
 * was killed, and a stored Source stays browsable offline on purpose. See spec section 5.4.
 */
class HttpThumbnailLoader(private val cacheBytes: Int = CACHE_BYTES) : ThumbnailLoader {

    private val cache = object : LruCache<String, ImageBitmap>(cacheBytes) {
        // Measured in bytes, so the cap is a real memory cap and not a count of pictures.
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Videos YouTube has no picture for. Keyed by video, because every URL of it was tried. */
    private val missing = Collections.synchronizedSet(mutableSetOf<String>())

    private val downloads = Semaphore(PARALLEL_DOWNLOADS)

    override fun cached(videoId: String): ImageBitmap? = cache.get(videoId)

    /**
     * A video is starting, so most of the pictures go. The most recently drawn stay, so the
     * cards around the one the household comes back to are drawn at once.
     */
    fun trimForPlayback() {
        cache.trimToSize(minOf(PLAYBACK_CACHE_BYTES, cacheBytes))
    }

    /** Android is short of memory. Every picture goes, and the grid asks again as it draws. */
    fun clear() {
        cache.evictAll()
    }

    override suspend fun load(videoId: String, storedUrl: String?): ImageBitmap? {
        cache.get(videoId)?.let { return it }
        if (videoId in missing) return null

        var image: ImageBitmap? = null
        var reachable = true

        withContext(Dispatchers.IO) {
            downloads.withPermit {
                // Checked again inside the permit: while this call waited its turn, the same
                // picture may already have been decoded.
                image = cache.get(videoId)
                if (image != null) return@withPermit
                for (url in YouTubeUrls.thumbnails(videoId, storedUrl)) {
                    val decoded = decode(url)
                    when (decoded.first) {
                        Attempt.DECODED -> {
                            image = decoded.second
                            return@withPermit
                        }
                        Attempt.MISSING -> Unit
                        Attempt.UNREACHABLE -> {
                            // No point trying the next address on the same dead network.
                            reachable = false
                            return@withPermit
                        }
                    }
                }
            }
        }

        val decoded = image
        when {
            decoded != null -> cache.put(videoId, decoded)
            reachable -> missing.add(videoId)
        }
        return decoded
    }

    private fun decode(url: String): Pair<Attempt, ImageBitmap?> {
        val bytes = try {
            download(url) ?: return Attempt.MISSING to null
        } catch (failure: IOException) {
            return Attempt.UNREACHABLE to null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth)
        }
        // Bytes that arrived but will not decode are as good as no picture at all.
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        return if (image == null) Attempt.MISSING to null else Attempt.DECODED to image
    }

    /** The bytes, or null when YouTube answered that there is nothing at this address. */
    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The power of two that brings a picture down to about [TARGET_WIDTH_PX].
 *
 * `BitmapFactory` only halves, so this halves while the result still covers the card.
 */
internal fun sampleSizeFor(sourceWidth: Int): Int {
    if (sourceWidth <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= TARGET_WIDTH_PX) {
        sample *= 2
    }
    return sample
}
