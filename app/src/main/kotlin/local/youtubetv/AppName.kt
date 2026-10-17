package local.youtubetv

/**
 * The name a person sees for this app. Build ticket 67 gave it this name on 2026-10-17.
 * It is shown in these places:
 *
 * - the header of the home screen,
 * - the title of the Key page and the example project name on it,
 * - the subject of the email the Key page offers to send.
 *
 * `app_name` in `res/values/strings.xml` is the same name for the Google TV app list.
 * Android reads that file and not this constant, so `AppNameTest` checks that the two agree.
 *
 * Some names keep the old spelling on purpose, because a person never sees them:
 *
 * - the package name `local.youtubetv`,
 * - the log tag `YouTubeTv`,
 * - the database file `youtube-tv.db`.
 *
 * Changing the package or the database file would also lose the household's data.
 */
const val APP_NAME = "YT Viewer"
