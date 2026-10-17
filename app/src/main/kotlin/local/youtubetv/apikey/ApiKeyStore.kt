package local.youtubetv.apikey

/**
 * Where the user's YouTube Data API key is kept on the device. Build ticket 64.
 *
 * No build carries a key. Each user creates their own and enters it on the key screen, and it
 * lives here from then on. The Android implementation is `PreferencesApiKeyStore`; tests use a
 * plain field.
 */
interface ApiKeyStore {
    /** The saved key, or null when none is saved. Never blank. */
    fun read(): String?

    fun write(key: String)

    fun clear()
}

/** An [ApiKeyStore] held in memory. For tests. */
class InMemoryApiKeyStore(private var key: String? = null) : ApiKeyStore {
    override fun read(): String? = key?.takeIf { it.isNotBlank() }
    override fun write(key: String) {
        this.key = key
    }
    override fun clear() {
        key = null
    }
}

/** Which screen the app opens on. */
enum class FirstScreen { KEY, HOME }

/**
 * With a saved key the app opens on the home screen, as it always has. With none, it opens on
 * the key screen, because every Data API call would fail without one.
 */
fun firstScreen(savedKey: String?): FirstScreen =
    if (savedKey.isNullOrBlank()) FirstScreen.KEY else FirstScreen.HOME

/**
 * One line for the log at launch, saying whether a key is saved.
 *
 * Nothing about the key itself appears in the line, not even its length. Logcat can be read by
 * anything with `adb` access, and the key is a credential.
 */
fun apiKeyStatusLine(savedKey: String?): String =
    if (savedKey.isNullOrBlank()) {
        "YouTube API key: none saved on this device, so the key screen opens."
    } else {
        "YouTube API key: saved on this device."
    }
