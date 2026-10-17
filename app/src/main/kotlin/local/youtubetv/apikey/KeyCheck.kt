package local.youtubetv.apikey

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import local.youtubetv.youtube.YouTubeJson

/**
 * What one check of an API key found. Build ticket 64.
 *
 * Each value has its own sentence on the television and on the Key page, in
 * `ApiKeyText.kt`, because each one asks the user to do something different.
 */
enum class KeyCheckResult {
    /** YouTube answered the call. The key is saved. */
    WORKS,

    /**
     * The key is right, but its 10,000 units for today are spent.
     *
     * The key is saved all the same. It is a working key, and it starts answering again at
     * midnight Pacific Time without the user doing anything.
     */
    WORKS_BUT_QUOTA_USED,

    /** Nothing was pasted or typed. No call is made. */
    EMPTY,

    /** Google does not know this key: `API_KEY_INVALID`. */
    WRONG_KEY,

    /** The key's project does not have YouTube Data API v3 enabled: `accessNotConfigured`. */
    API_NOT_ENABLED,

    /**
     * The key exists, but one of its own restrictions refuses this call.
     *
     * An API restriction that leaves YouTube Data API v3 out, or an application restriction
     * this app does not meet. Both are fixed on the key's own page in the console.
     */
    KEY_RESTRICTED,

    /** The call never reached YouTube. */
    NO_NETWORK,

    /** Any other answer. */
    UNEXPECTED,

    /**
     * The key screen closed before this key could be saved, so nothing was saved.
     *
     * A key that arrives after another one was saved, or after the user left the screen.
     * Found by `/code-review` on build ticket 64: such a key used to be answered `WORKS` while
     * the old key stayed in use.
     */
    SCREEN_CLOSED,
}

/** True for the two results that save the key. */
val KeyCheckResult.saves: Boolean
    get() = this == KeyCheckResult.WORKS || this == KeyCheckResult.WORKS_BUT_QUOTA_USED

/**
 * What a user pasted or typed, made into the string that is checked and saved.
 *
 * A key copied from the console can carry a space or a line break at either end, and a key
 * copied out of a document can arrive in quotes. Neither is ever part of a key. Spaces in the
 * middle are taken out as well, because a phone keyboard sometimes adds one after a paste.
 */
fun normalizeKey(raw: String): String =
    raw.filterNot { it.isWhitespace() }.trim('"', '\'')

/**
 * Turns YouTube's answer to the check call into a [KeyCheckResult].
 *
 * The reasons are read from both places Google writes one. `error.errors[].reason` is the old
 * shape and carries `keyInvalid`, `accessNotConfigured` and `quotaExceeded`.
 * `error.details[].reason` is the newer `ErrorInfo` shape and carries `API_KEY_INVALID`,
 * `SERVICE_DISABLED` and the `API_KEY_..._BLOCKED` family. `YouTubeJson.errorReasonsIn` is the
 * one reader of both, shared with the rest of the app.
 *
 * @param status the HTTP status, or null when no answer arrived at all.
 */
fun classifyKeyCheck(status: Int?, body: String?): KeyCheckResult {
    if (status == null) return KeyCheckResult.NO_NETWORK
    if (status in 200..299) return KeyCheckResult.WORKS
    val reasons = body?.let { YouTubeJson.errorReasonsIn(it) }.orEmpty()
    return when {
        "quotaExceeded" in reasons || "dailyLimitExceeded" in reasons ->
            KeyCheckResult.WORKS_BUT_QUOTA_USED
        "API_KEY_INVALID" in reasons || "keyInvalid" in reasons -> KeyCheckResult.WRONG_KEY
        "SERVICE_DISABLED" in reasons || "accessNotConfigured" in reasons ->
            KeyCheckResult.API_NOT_ENABLED
        reasons.any { it.startsWith("API_KEY_") && it.endsWith("_BLOCKED") } ->
            KeyCheckResult.KEY_RESTRICTED
        // A fallback only. On 2026-10-17 a wrong key and a malformed one both came back as 400
        // with `API_KEY_INVALID` in `details`, which the line above already reads. The message
        // is kept as a second signal in case the `details` array is ever left out.
        status == 400 && body.orEmpty().contains("API key not valid") -> KeyCheckResult.WRONG_KEY
        else -> KeyCheckResult.UNEXPECTED
    }
}

/** Checks one key. The key screen's model is tested through a fake of this. */
fun interface KeyChecker {
    suspend fun check(key: String): KeyCheckResult
}

/**
 * The call that checks a key: `videos.list?part=id` for one video, which costs 1 unit.
 *
 * Any 2xx answer means the key works, whether or not the video still exists, so the video
 * chosen does not matter. It is the first video ever uploaded to YouTube, which is the one
 * least likely to go away.
 */
internal const val KEY_CHECK_URL = "https://youtube.googleapis.com/youtube/v3/videos?part=id&id=jNQXAC9IVRw"

/** The URL of the check, with the key on it. Never written to the log. */
internal fun keyCheckUrl(key: String): String =
    "$KEY_CHECK_URL&key=${URLEncoder.encode(key, "UTF-8")}"

/**
 * The real [KeyChecker], over `HttpURLConnection`, for the same reasons `HttpYouTubeApi` uses
 * it. It is blocking, so call it off the main thread.
 *
 * It sends the two Android headers `HttpYouTubeApi` sends, so a key with an Android
 * application restriction is checked the way the rest of the app will use it.
 */
class HttpKeyChecker(
    private val androidPackage: String? = null,
    private val androidCertSha1: String? = null,
    private val openConnection: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
) : KeyChecker {

    override suspend fun check(key: String): KeyCheckResult {
        if (key.isEmpty()) return KeyCheckResult.EMPTY
        val connection = try {
            openConnection(keyCheckUrl(key))
        } catch (failure: IOException) {
            return KeyCheckResult.NO_NETWORK
        }
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            androidPackage?.let { connection.setRequestProperty("X-Android-Package", it) }
            androidCertSha1?.let { connection.setRequestProperty("X-Android-Cert", it) }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                null
            } else {
                connection.errorStream?.use { it.bufferedReader().readText() }
            }
            classifyKeyCheck(status, body)
        } catch (failure: IOException) {
            KeyCheckResult.NO_NETWORK
        } finally {
            connection.disconnect()
        }
    }
}
