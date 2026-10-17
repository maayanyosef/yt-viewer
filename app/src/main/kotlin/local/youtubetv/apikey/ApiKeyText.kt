package local.youtubetv.apikey

/**
 * Every word of the Key screen and of the Key page. Build ticket 64.
 *
 * One sentence per [KeyCheckResult], and the television and the Key page show the same one, so a
 * user looking at either sees the same thing. Each sentence says what to do next.
 */
fun keyCheckMessage(result: KeyCheckResult): String = when (result) {
    KeyCheckResult.WORKS ->
        "The key works and is saved on the television."
    KeyCheckResult.WORKS_BUT_QUOTA_USED ->
        "The key is right and is saved, but its 10,000 units for today are used up. " +
            "Sources update again after midnight Pacific Time."
    KeyCheckResult.EMPTY ->
        "Nothing was entered. Paste the whole key, which starts with AIza."
    KeyCheckResult.WRONG_KEY ->
        "Google does not know this key. Copy it again from the Credentials page, " +
            "all of it, and send it again."
    KeyCheckResult.API_NOT_ENABLED ->
        "The key is real, but YouTube Data API v3 is not enabled in its project. " +
            "Do step 2 of the guide, wait two minutes, and send the key again."
    KeyCheckResult.KEY_RESTRICTED ->
        "The key's own restrictions refuse YouTube. Open the key in the console and make " +
            "sure API restrictions allow YouTube Data API v3. Leave application restrictions " +
            "at None."
    KeyCheckResult.NO_NETWORK ->
        "The television could not reach YouTube. Check that it is online and send the " +
            "key again."
    KeyCheckResult.UNEXPECTED ->
        "YouTube gave an answer this app did not expect. Wait a minute and send the key again."
    KeyCheckResult.SCREEN_CLOSED ->
        "The television left the key screen, so this key was not saved. Open the key screen " +
            "again and send the key from the new address."
}

/** The one logcat line a check leaves. It names the result and never the key. */
fun keyCheckLogLine(from: KeyFrom, result: KeyCheckResult): String =
    "An API key arrived from ${from.logName}. The check says $result" +
        if (result.saves) ", so it is saved." else ", so nothing is saved."

/** Where a key came from, for the log. */
enum class KeyFrom(val logName: String) {
    KEY_PAGE("the Key page"),
    REMOTE("the remote"),
}

const val KEY_SCREEN_TITLE = "Connect your YouTube API key"
const val KEY_SCREEN_REPLACE_TITLE = "Replace or remove your YouTube API key"

/**
 * Why there is a key screen at all, in two sentences.
 *
 * The app reads Source lists from the YouTube Data API, and every user brings their own key.
 */
const val KEY_SCREEN_WHY =
    "This app reads your Sources from YouTube with your own free API key. " +
        "Creating one takes about five minutes, and the page the QR code opens shows every step."

const val KEY_SCREEN_REPLACE_WHY =
    "A key is saved on this television. Send a new one to replace it, or remove it."

const val KEY_SCREEN_SCAN = "Scan the code with your phone."

/**
 * Beside the QR code, for a phone that cannot scan and for a computer. Build ticket 68.
 *
 * It names a computer as well as a phone, because the Key page itself says a computer is easier
 * for its steps, and the Key screen must not contradict the Key page. The Key screen prints the
 * address on its own line after this sentence.
 */
const val KEY_SCREEN_ADDRESS_INTRO =
    "Or type this address into a browser on a computer or phone on the same network:"

const val KEY_SCREEN_NO_NETWORK =
    "This television is not on a home network, so the page with the steps cannot open. " +
        "Connect it to wifi, or type the key with the remote."

const val KEY_SCREEN_WAITING = "Waiting for a key…"
const val KEY_SCREEN_CHECKING = "Checking the key with YouTube…"

const val KEY_TYPE_BUTTON = "Type the key with the remote"
const val KEY_CHECK_BUTTON = "Check and save this key"
const val KEY_BACK_TO_QR = "Back to the QR code"
const val KEY_REMOVE_BUTTON = "Remove the saved key"
const val KEY_REMOVE_CONFIRM = "Yes, remove the key"
const val KEY_REMOVE_REFUSE = "No, keep the key"
const val KEY_FIELD_HINT = "Your key starts with AIza"

/**
 * Said before the key is removed, because the app can do nothing with YouTube until a new one
 * is entered.
 */
const val KEY_REMOVE_QUESTION =
    "Without a key the app cannot load or update any Source. Your Sources and everything you " +
        "have watched stay on the television."
