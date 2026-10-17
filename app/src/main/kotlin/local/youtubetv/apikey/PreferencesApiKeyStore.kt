package local.youtubetv.apikey

import android.content.Context

/**
 * The [ApiKeyStore] the app runs with: one `SharedPreferences` file, private to the app.
 *
 * Not the SQLite database. That file holds the household's Sources and Watch log and is built to
 * be replayed on another device later (ADR 0001). A credential has no place in it.
 *
 * The manifest says `allowBackup="false"`, so this file is never copied off the device by
 * Android's backup.
 *
 * `commit` and not `apply`, so the key is on the disk before the app moves to the home screen.
 * It is one small file, and every caller is already off the main thread.
 */
class PreferencesApiKeyStore(context: Context) : ApiKeyStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)?.takeIf { it.isNotBlank() }

    override fun write(key: String) {
        preferences.edit().putString(KEY, key).commit()
    }

    override fun clear() {
        preferences.edit().remove(KEY).commit()
    }

    private companion object {
        const val FILE = "youtube_api_key"
        const val KEY = "youtube_data_api_key"
    }
}
