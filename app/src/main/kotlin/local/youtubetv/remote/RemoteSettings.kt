package local.youtubetv.remote

import android.content.Context
import local.youtubetv.apikey.newKeyPageToken

/**
 * The remote's token, kept across launches so the phone's bookmark keeps working.
 *
 * In the same `SharedPreferences` file as the video quality setting, and never in the
 * database: it is a setting of this television, not something the household watched.
 */
class RemoteSettings(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The token, made on first use. [reset] makes a new one, which cuts every old bookmark off. */
    fun token(): String {
        preferences.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = newKeyPageToken()
        preferences.edit().putString(KEY, fresh).apply()
        return fresh
    }

    fun reset(): String {
        val fresh = newKeyPageToken()
        preferences.edit().putString(KEY, fresh).apply()
        return fresh
    }

    private companion object {
        const val FILE = "player_settings"
        const val KEY = "remote_token"
    }
}
