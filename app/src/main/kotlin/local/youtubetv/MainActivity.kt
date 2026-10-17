package local.youtubetv

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.youtubetv.apikey.ApiKeyModel
import local.youtubetv.apikey.ApiKeyStore
import local.youtubetv.apikey.FirstScreen
import local.youtubetv.apikey.HttpKeyChecker
import local.youtubetv.apikey.KeyFrom
import local.youtubetv.apikey.KeyPageServer
import local.youtubetv.apikey.PreferencesApiKeyStore
import local.youtubetv.apikey.apiKeyStatusLine
import local.youtubetv.apikey.deviceLocalAddress
import local.youtubetv.apikey.firstScreen
import local.youtubetv.apikey.keyPageUrl
import local.youtubetv.apikey.newKeyPageToken
import local.youtubetv.data.Landing
import local.youtubetv.data.SourceCard
import local.youtubetv.data.SqliteSourceStore
import local.youtubetv.data.SqliteWatchStore
import local.youtubetv.data.PlayStep
import local.youtubetv.data.RangeSide
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.refresh.REFRESH_INTERVAL_MINUTES
import local.youtubetv.refresh.RefreshRun
import local.youtubetv.refresh.RefreshTrigger
import local.youtubetv.refresh.SourceRefresh
import local.youtubetv.refresh.SourceStored
import local.youtubetv.refresh.millisUntilNextCheck
import local.youtubetv.refresh.refreshPausedForPlaybackLine
import local.youtubetv.refresh.sourceReachedScreensLine
import local.youtubetv.data.YouTubeTvDatabase
import local.youtubetv.data.nextPlayableVideo
import local.youtubetv.player.AfterVideo
import local.youtubetv.player.COUNTDOWN_SECONDS
import local.youtubetv.player.CountdownUi
import local.youtubetv.player.HeldKeyGate
import local.youtubetv.player.NO_NEXT_VIDEO
import local.youtubetv.player.NO_PREVIOUS_VIDEO
import local.youtubetv.player.PlaybackSession
import local.youtubetv.player.PlayerAction
import local.youtubetv.player.PlayerController
import local.youtubetv.player.PlayerEvent
import local.youtubetv.player.PlayerNotice
import local.youtubetv.player.PlayerScreen
import local.youtubetv.player.UNATTENDED_LIMIT_SECONDS
import local.youtubetv.player.afterVideo
import local.youtubetv.player.coverMayComeDown
import local.youtubetv.player.isUnattended
import local.youtubetv.player.playerAction
import local.youtubetv.player.playerKeepsKey
import local.youtubetv.player.resumeFromSeconds
import local.youtubetv.ui.AddSourceModel
import local.youtubetv.ui.AddSourceScreen
import local.youtubetv.ui.ApiKeyScreen
import local.youtubetv.ui.HomeModel
import local.youtubetv.ui.HomeScreen
import local.youtubetv.ui.HttpThumbnailLoader
import local.youtubetv.ui.RenameSourceModel
import local.youtubetv.ui.RenamedSource
import local.youtubetv.ui.RenameSourceScreen
import local.youtubetv.ui.SourceScreen
import local.youtubetv.ui.SourceScreenModel
import local.youtubetv.ui.railCardName
import local.youtubetv.ui.sourceNameOrId
import local.youtubetv.ui.sourceRemovedLine
import local.youtubetv.walk.SourceWalk
import local.youtubetv.youtube.AlwaysFailingYouTubeApi
import local.youtubetv.youtube.HttpYouTubeApi
import local.youtubetv.youtube.SEARCH_CALLS_PER_DAY
import local.youtubetv.youtube.SEARCH_LIST
import local.youtubetv.youtube.SourceResolver
import local.youtubetv.youtube.SourceSearch
import local.youtubetv.youtube.YouTubeApiException
import local.youtubetv.youtube.signingCertificateSha1

/** The one logcat tag this app writes under. `adb logcat -s YouTubeTv` shows everything. */
const val LOG_TAG = "YouTubeTv"

/**
 * Asks the launch to throw the derived table away and build it again from the Watch log.
 *
 * ADR 0001 wants a manual rebuild, and spec section 7.2 fixes what the chip row holds, so
 * there is no button for it. This is how it is reached:
 *
 * `adb shell am start -n local.youtubetv/.MainActivity --ez rebuild_derived true`
 */
const val EXTRA_REBUILD_DERIVED = "rebuild_derived"

/**
 * Shortens the unattended stop of spec section 8.6, so it can be watched happening.
 *
 * The app ships [UNATTENDED_LIMIT_SECONDS], which is two hours, and nothing in the app
 * changes it. Two hours is not a thing anyone can sit through, so the rule is proven twice:
 * on the JVM by `PlaybackRulesTest`, and on a device by handing in a short limit here.
 *
 * `adb shell am start -n local.youtubetv/.MainActivity --ei unattended_seconds 20`
 *
 * It is read once, at launch, and a value below one second is ignored.
 */
const val EXTRA_UNATTENDED_SECONDS = "unattended_seconds"

/**
 * Shortens the 30 minute refresh timer of spec section 5.1, so it can be watched happening.
 *
 * The same idea as [EXTRA_UNATTENDED_SECONDS] and for the same reason: thirty minutes is not a
 * thing anyone can sit and watch, so the rule is proven twice. `RefreshTriggersTest` settles
 * the arithmetic on the JVM, and this is what makes the timer, its pause during playback and
 * the one check when playback ends visible in logcat on a real device.
 *
 * `adb shell am start -n local.youtubetv/.MainActivity --ei refresh_seconds 20`
 *
 * It is read once, at launch, it is **only ever shortening**, and a value below five seconds
 * is ignored. The app ships thirty minutes and nothing else changes it.
 */
const val EXTRA_REFRESH_SECONDS = "refresh_seconds"

/**
 * Makes every YouTube call of this launch fail, so the two failure paths can be seen.
 *
 * Spec section 5.4 has two of them and both are invisible from the outside: with no network,
 * and with the quota spent, everything still browses and plays from the cache. A rule about
 * what does **not** happen cannot be photographed, so this is how it is driven.
 *
 * `adb shell am start -n local.youtubetv/.MainActivity --es refresh_failure network`
 * `adb shell am start -n local.youtubetv/.MainActivity --es refresh_failure quota`
 *
 * **It is the `YouTubeApi` seam and nothing else**, which is the whole reason that seam exists.
 * Nothing touches the television's wifi, which on the household set carries the `adb`
 * connection this app is driven over. Only refresh is affected: playback is a `WebView` and
 * makes no Data API call at all.
 *
 * Any other value, and the absence of the extra, leave the real API in place.
 */
const val EXTRA_REFRESH_FAILURE = "refresh_failure"

/** How long a write may hold the main thread at `BACK` or at `onStop` before it is given up on. */
private const val FINAL_WRITE_TIMEOUT_SECONDS = 2L

/**
 * How long after acting on `BACK` a second `BACK` is read as the same press arriving twice.
 *
 * From Android 15 one press reaches the app by two routes, a key event and a back callback,
 * milliseconds apart. A household pressing the button twice cannot be anywhere near this
 * fast, so nothing a person does is swallowed.
 */
private const val BACK_ECHO_MILLIS = 800L

/**
 * How often the refresh timer looks again while a video is playing.
 *
 * The timer of spec section 5.1 is thirty minutes; this is only how often the loop asks
 * whether the player has gone away, once that thirty minutes is already up.
 */
private const val REFRESH_TICK_MILLIS = 60_000L

/** How often the open key screen reads the television's address again. Build ticket 64. */
private const val KEY_ADDRESS_RECHECK_MILLIS = 5_000L

class MainActivity : ComponentActivity() {

    private lateinit var watches: WatchLog
    private lateinit var sources: SqliteSourceStore
    private lateinit var walk: SourceWalk
    private lateinit var home: HomeModel
    private lateinit var resolver: SourceResolver

    /**
     * The one door to `search.list`, built once and shared by every add screen.
     *
     * **It has to outlive the add screen**, unlike [adding]. Its cache is what makes repeating
     * a query cost nothing, and its latch is what stops the app asking again once YouTube has
     * said the day's hundred are gone. A new one per opening would throw both away every time
     * the household pressed `BACK`, and one search could then be paid for several times over.
     * Spec section 4.1.
     */
    private lateinit var search: SourceSearch

    /**
     * What catches every Source up with YouTube. Spec section 5.
     *
     * The rules are all in `refresh/SourceRefresh.kt` and are JVM tests. What lives here is the
     * three things only an Activity knows: that the app was launched, that a video is playing,
     * and that thirty minutes have passed in the foreground.
     */
    private lateinit var refresher: SourceRefresh

    /** The foreground timer of spec section 5.1, or null while the app is not in the foreground. */
    private var refreshJob: Job? = null

    /** The gap between checks this launch runs with. See [EXTRA_REFRESH_SECONDS]. */
    private var refreshIntervalSeconds = REFRESH_INTERVAL_MINUTES * 60

    /**
     * The add screen, or null when the household is not adding a Source.
     *
     * A fresh model per opening, on purpose and unlike [sourceModels]. It holds one typed
     * string and one lookup, both of which should start empty every time, and it costs nothing
     * to build. A kept one would put the household back on the last refusal it saw.
     */
    private var adding by mutableStateOf<AddSourceModel?>(null)

    /**
     * The rename screen, or null when the household is not renaming a Source.
     *
     * A fresh model per opening, for the same reason [adding] is: it holds one field, seeded
     * from the rail card the household chose `Rename` on, and a kept one would put them back on
     * the last Source they renamed. Build ticket 27.
     */
    private var renaming by mutableStateOf<RenameSourceModel?>(null)

    /** Where the user's API key is saved. Build ticket 64. */
    private lateinit var keyStore: ApiKeyStore

    /** Checks a key with one Data API call. Built once, because it reads the signing SHA-1. */
    private lateinit var keyChecker: HttpKeyChecker

    /**
     * The key screen, or null when it is not open. Build ticket 64, spec section 7.5.
     *
     * Open at launch when no key is saved, and from the home screen's menu to replace or remove
     * one. Drawn in front of everything else, because with no key there is nothing else to use.
     */
    private var keyScreen by mutableStateOf<ApiKeyModel?>(null)

    /**
     * The Key page's server, running exactly while [keyScreen] is open and the app is in the
     * foreground. A new one, with a new token, every time it starts.
     */
    private var keyServer: KeyPageServer? = null

    /**
     * Re-reads the television's address every few seconds while the key screen is open.
     *
     * At boot the app can open before wifi has an address, and DHCP can hand out a new one.
     * Either way the QR code would be missing or wrong until the screen was opened again.
     * Found by `/code-review` on build ticket 64.
     */
    private var keyAddressJob: Job? = null

    /**
     * True once the launch refresh of spec section 5.1 has run in this process.
     *
     * With no key saved it cannot run at launch, so it runs the moment a key is saved instead.
     */
    private var launchRefreshStarted = false

    /**
     * The Source on screen right now, or null when the household is on the home rail.
     *
     * The app opens on the rail, so there is no Source until one is chosen. Its
     * [SourceScreenModel] is built at that moment rather than in [onCreate], because each
     * Source has its own: the model holds one Source's videos, its Direction and its watch map.
     */
    private var open by mutableStateOf<OpenSource?>(null)

    /** The Source the household last came back from, so the rail can put the highlight back. */
    private var lastOpenedSourceId by mutableStateOf<String?>(null)

    /** Bumped every time the household comes back to the rail, so two equal asks are never one. */
    private var returnedFromSource by mutableLongStateOf(0L)

    /**
     * One [SourceScreenModel] per Source, kept for as long as the app lives.
     *
     * **This map is what stops a Source being walked twice.** A model walks when storage holds
     * no row for that Source, and a first walk of a long Source takes a while. Building a fresh
     * model on every opening meant that leaving during the first walk and coming straight back
     * started a second walk beside the first, spending the quota twice and racing two
     * `replaceVideos` calls over the same Source. Reusing the model means the walk already
     * running is the only one.
     *
     * It also means the grid comes back where the household left it, and that reopening a
     * Source costs no SQLite read at all. Found by `/code-review`.
     *
     * The household has a handful of Sources, so nothing is ever evicted. An entry is dropped
     * only when something outside the Source changes what it holds: see [closeSource].
     */
    private val sourceModels = mutableMapOf<String, SourceScreenModel>()

    /**
     * One Source being browsed: which it is, where it opened, and what is driving it.
     *
     * **What it is called is not here, and build ticket 29 took it out.** A name held here was
     * read once, when the screen opened, so a name that changed while the household stood
     * inside the Source never reached the header. It lives in `SourceScreenModel.name` now,
     * which follows storage the same way the rail's cards do.
     */
    private class OpenSource(
        val sourceId: String,
        val landing: Landing,
        val model: SourceScreenModel,
    )

    /**
     * The video on screen right now, or null when the household is on the grid.
     *
     * It is Compose state and a plain field at the same time, which reads oddly and is on
     * purpose: [setContent] draws from it, and [dispatchKeyEvent] and [onStop] are Activity
     * callbacks that are nowhere near a composition.
     */
    private var playback by mutableStateOf<Playback?>(null)

    /** Bumped when the household comes back from the player, so the grid takes focus again. */
    private var returnedFromPlayer by mutableLongStateOf(0L)

    /**
     * Which video the grid should land on when the player hands back, or null for the card
     * the household left.
     *
     * A finished video with autoplay-next off lands on the **next** video in Direction, which
     * spec section 8.6 asks for. `BACK` out of a video leaves this null, so the household
     * comes back to the video they were watching.
     */
    private var landOnVideoId by mutableStateOf<String?>(null)

    /** Bumped on every key the player handled, so the title and position come back on screen. */
    private var playerNudge by mutableLongStateOf(0L)

    /** The five second countdown over the paused next video, or null. Spec section 8.6. */
    private var countdown by mutableStateOf<CountdownUi?>(null)

    /** The countdown's own coroutine, so a next or previous press can cancel and restart it. */
    private var countdownJob: Job? = null

    /**
     * True while the opaque cover of ticket 17 is over the player.
     *
     * It goes up on `ENDED`, because from that instant YouTube's related-video wall is on the
     * page, and it comes down when the player confirms the next video is on the page and no
     * countdown is running. See [coverMayComeDown].
     *
     * It is the Activity's and not the session's, because a handover replaces the session:
     * the video that ended raises it and the video that follows is the one that takes it
     * down, so no single session can hold it.
     */
    private var covered by mutableStateOf(false)

    /** True once the Source has run out. The player stays put and says so. Spec section 8.6. */
    private var endOfSource by mutableStateOf(false)

    /** One short sentence over the player, such as the answer to next on the last video. */
    private var playerNotice by mutableStateOf<PlayerNotice?>(null)
    private var playerNotices = 0L

    /**
     * When the remote was last touched, on a clock that only goes up.
     *
     * `elapsedRealtime` and never the wall clock, which a television corrects over the network
     * and can move backwards. Spec section 8.6 stops playback after two hours of this not
     * moving, and any key press moves it.
     */
    private var lastKeyAtMillis = SystemClock.elapsedRealtime()

    /** The unattended limit this launch runs with. See [EXTRA_UNATTENDED_SECONDS]. */
    private var unattendedLimitSeconds = UNATTENDED_LIMIT_SECONDS

    /** What a held key means. One for the Activity, because it holds the time of the last seek. */
    private val heldKeys = HeldKeyGate()

    /** When `BACK` was last acted on, so the echo of the same press can be recognised. */
    private var lastBackAtMillis = 0L

    /**
     * `BACK`, whichever way this Android delivers it.
     *
     * **One press can arrive twice, by two different routes, and it does on API 36.**
     *
     * - Up to Android 14 the press is an ordinary `KEYCODE_BACK` key event, which
     *   [dispatchKeyEvent] takes while a video is on screen.
     * - From **Android 16 (API 36)**, for an app targeting 36 or later, and this app targets
     *   36, predictive back is on by default. Android documents it so. Only API 34 and API 36
     *   were measured, by build ticket 54. The system calls an `OnBackInvokedCallback`
     *   instead, which is this one.
     *
     * The household's TCL is **API 34**, where only the first route exists, which is why
     * ticket 07 verified `BACK` on the television and it passed.
     *
     * On the emulator, which is API 36, one press did both: this callback left the player,
     * and then the key **up** of the same press reached the framework, found no play left,
     * and finished the whole Activity. The household was dropped on the Google TV launcher
     * from one press of `BACK`.
     *
     * So this is always enabled and decides for itself, and [lastBackAtMillis] is what tells
     * the echo of a press from a real second press. A household cannot press `BACK` twice in
     * under [BACK_ECHO_MILLIS]; the two routes of one press are milliseconds apart.
     */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val now = SystemClock.elapsedRealtime()
            if (playback != null) {
                lastBackAtMillis = now
                lastKeyAtMillis = now
                playerNudge += 1
                leavePlayback()
                return
            }
            // The other route of the press that just left the player, or that just left a
            // Source. Leaving is what it asked for and leaving is what happened.
            //
            // **It puts a floor of [BACK_ECHO_MILLIS] under stepping back through the add
            // screen**, which has up to four steps to walk. A household pressing `BACK` faster
            // than once every 800 ms loses a press. That is the price of the echo guard and it
            // is paid on API 36 and later only; the household's set is API 34, where one press
            // arrives once.
            if (now - lastBackAtMillis < BACK_ECHO_MILLIS) return
            // The key screen is drawn in front of everything, so it answers first. It steps
            // back out of its own confirmation and its typing field. Then it closes when a key
            // is saved, and leaves the app when none is, because without a key there is no
            // screen behind it that works. Build ticket 64.
            val keyModel = keyScreen
            if (keyModel != null) {
                lastBackAtMillis = now
                if (keyModel.back()) return
                if (keyModel.state.value.replacing) closeKeyScreen() else finish()
                return
            }
            // **First, and in the same order `setContent` draws them.** The rename screen is
            // only ever reached from the rail, so nothing else can be open underneath it and
            // the two orders cannot disagree today. They are kept the same anyway, because a
            // screen drawn by one order and closed by another is a defect nobody would see
            // until something else changed. Build ticket 27.
            //
            // There is one step on that screen, so there is nothing to walk back through:
            // `BACK` leaves it, and leaving it writes nothing, which is what `BACK` means
            // everywhere else in this app.
            if (renaming != null) {
                lastBackAtMillis = now
                closeRename()
                return
            }
            // On the add screen, `BACK` walks the steps back one at a time, and closes the
            // screen only when there is nothing left to go back to. The model owns that, for
            // the reason this whole callback exists: a Compose `BackHandler` is added during
            // composition and would be asked before this one.
            val addModel = adding
            if (addModel != null) {
                lastBackAtMillis = now
                if (!addModel.back()) closeAddSource()
                return
            }
            // On a Source, `BACK` returns to the home rail. Ticket 10. The time is written
            // down for the same reason the player does it: on Android 15 and later one press
            // arrives twice, and the second route would otherwise close the rail as well.
            if (open != null) {
                lastBackAtMillis = now
                closeSource()
                return
            }
            // On the rail, `BACK` leaves the app, which is what it has always done.
            finish()
        }
    }

    /**
     * The one thread every Watch write during a play happens on.
     *
     * Two reasons, and both are rules this project already follows.
     *
     * - **Not the main thread.** A position is written every ten seconds, and a SQLite write
     *   on the main thread is a dropped frame at best.
     * - **One thread, not a pool.** [PlaybackSession] holds the state of the play, so its
     *   events have to arrive one at a time and in order. A single-thread executor gives
     *   both by construction, with no lock to get wrong.
     */
    private val playbackWrites: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * One video being played: the page, the rules, and the video they are about.
     *
     * It carries its own [SourceScreenModel] rather than reading a field, because the chain
     * asks the Source for its list and its autoplay-next choice at **every video boundary**,
     * which is long after the household chose the first video. A build that keeps one model
     * per Source, which is what ticket 10 does, would otherwise have to guess which Source a
     * finished video belonged to.
     */
    private class Playback(
        val video: StoredVideo,
        val controller: PlayerController,
        val session: PlaybackSession,
        val model: SourceScreenModel,
    )

    /**
     * The page, kept alive across every video of a chain.
     *
     * Ticket 07 built one per play and threw it away on leaving, which costs about two
     * seconds of page and player loading. Autoplay-next has to replace the related-video wall
     * **before it is seen**, and two seconds is far too long, so the page outlives the play.
     * It is built on the first play and destroyed when the household leaves the player.
     */
    private var controller: PlayerController? = null

    /**
     * The session every player event goes to, as a plain field and not Compose state.
     *
     * The page's bridge answers on a WebView thread and the events are handed to
     * [playbackWrites], so this is read from two threads that are neither of them the main
     * one. `@Volatile` is what makes a swap at a video boundary visible to both at once.
     *
     * Events from the video that just finished are dropped by [PlayerController]'s own load
     * number before they ever reach here, so this never has to judge whose event it is.
     */
    @Volatile
    private var currentSession: PlaybackSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyStore = PreferencesApiKeyStore(this)
        // Read once here. It is the one read on the main thread that loads the file from disk.
        val savedKey = keyStore.read()
        Log.i(LOG_TAG, apiKeyStatusLine(savedKey))
        val certSha1 = signingCertificateSha1(this)
        keyChecker = HttpKeyChecker(androidPackage = packageName, androidCertSha1 = certSha1)

        // Added before anything composes, so the card menu's own `BackHandler`, which is
        // added during composition, is asked first. The two never both want the key: the
        // menu cannot be open while a video is on screen.
        onBackPressedDispatcher.addCallback(this, back)

        // The key is read at every call, from the device, and never from the build. It can be
        // replaced or removed on the key screen while the app runs. Build ticket 64.
        val api = HttpYouTubeApi(
            apiKey = { keyStore.read().orEmpty() },
            androidPackage = packageName,
            androidCertSha1 = certSha1,
        )
        // One helper for the whole app. Two over the same file would each think they owned
        // its version number.
        val database = YouTubeTvDatabase(this)
        // The one store, built before the log, because the log writes one column of the video
        // cache: a `played` entry ends the Newly listed state of everything at or past the
        // video played. One helper means one transaction covers both tables. Ticket 40.
        sources = SqliteSourceStore(database)
        watches = WatchLog(SqliteWatchStore(database), sources)
        walk = SourceWalk(api)
        home = HomeModel(
            store = sources,
            watches = watches,
            log = { line -> Log.i(LOG_TAG, line) },
        )
        // One resolver for the whole app, so the add screen can be built and thrown away
        // without rebuilding the thing that talks to YouTube.
        resolver = SourceResolver(api) { endpoint ->
            Log.i(LOG_TAG, "Add a Source called $endpoint, which costs 1 unit.")
        }
        // One for the whole session, for the reason on the field itself: the cache and the
        // exhaustion latch are what keep the day's hundred `search.list` calls from draining.
        search = SourceSearch(
            api = api,
            onCall = { endpoint ->
                if (endpoint == SEARCH_LIST) {
                    Log.w(LOG_TAG, "Source search called $endpoint, one of $SEARCH_CALLS_PER_DAY today.")
                } else {
                    Log.i(LOG_TAG, "Source search called $endpoint to price its results, 1 unit.")
                }
            },
            log = { line -> Log.i(LOG_TAG, line) },
        )
        // The rail is the first screen, so it is what derives the log and what honours a
        // launch asking for a rebuild.
        home.start(
            lifecycleScope,
            rebuildDerived = intent?.getBooleanExtra(EXTRA_REBUILD_DERIVED, false) == true,
        )

        // The two failure paths of spec section 5.4, driven through the seam and nothing else.
        // Nothing here touches the network settings: on the household's set the `adb`
        // connection runs over the same wifi the app does. See [EXTRA_REFRESH_FAILURE].
        val refreshApi = when (intent?.getStringExtra(EXTRA_REFRESH_FAILURE)) {
            "network" -> AlwaysFailingYouTubeApi(YouTubeApiException.Reason.NETWORK)
            "quota" -> AlwaysFailingYouTubeApi(YouTubeApiException.Reason.QUOTA_EXCEEDED)
            else -> api
        }
        if (refreshApi !== api) {
            Log.w(
                LOG_TAG,
                "Every YouTube call of this launch is refused on purpose, as " +
                    "${intent?.getStringExtra(EXTRA_REFRESH_FAILURE)}. " +
                    "Browsing and playback are untouched; only refresh sees it.",
            )
        }
        refresher = SourceRefresh(
            api = refreshApi,
            store = sources,
            watches = watches,
            walk = SourceWalk(refreshApi),
            log = { line -> Log.i(LOG_TAG, line) },
            onSourceStored = { stored -> showStoredSource(stored) },
        )

        // Read once, at launch, and only ever shortened, exactly as the unattended stop is.
        // A value below five seconds is ignored, so a mistyped extra cannot make the app walk
        // every Source in a loop. See [EXTRA_REFRESH_SECONDS].
        val askedInterval = intent?.getIntExtra(EXTRA_REFRESH_SECONDS, 0) ?: 0
        if (askedInterval in 5 until refreshIntervalSeconds.toInt()) {
            refreshIntervalSeconds = askedInterval.toLong()
            Log.w(
                LOG_TAG,
                "The refresh timer is shortened to $askedInterval s for this launch. " +
                    "The app ships $REFRESH_INTERVAL_MINUTES minutes.",
            )
        }
        // The launch trigger of spec section 5.1, and the weekly full walk after it when one is
        // owed. Both are **in the background and never blocking**: the rail is drawn from
        // storage, as it always was, and each Source catches up the moment it is stored.
        //
        // **The launch check runs first even when the full walk is owed.** Build ticket 55. It
        // used to run the full walk instead, and the household Source then waited behind every
        // other Source's walk. `refreshOnLaunch` holds the rule and runs both passes.
        //
        // **With no key saved it waits**, because every call would be refused. The key screen
        // opens instead, and saving a key there starts it. Build ticket 64.
        if (firstScreen(savedKey) == FirstScreen.HOME) {
            startLaunchRefresh()
        } else {
            openKeyScreen()
        }

        // Read once, at launch, and only ever shortened. A value below one second is ignored,
        // so a mistyped extra cannot stop a video the moment it starts. See
        // [EXTRA_UNATTENDED_SECONDS]; the shipped limit is two hours and nothing else changes
        // it.
        val asked = intent?.getIntExtra(EXTRA_UNATTENDED_SECONDS, 0) ?: 0
        if (asked in 1 until UNATTENDED_LIMIT_SECONDS) {
            unattendedLimitSeconds = asked
            Log.w(
                LOG_TAG,
                "The unattended stop is shortened to $asked s for this launch. " +
                    "The app ships $UNATTENDED_LIMIT_SECONDS s, which is two hours.",
            )
        }

        // One loader for the whole screen, so its picture cache survives every scroll.
        val thumbnails = HttpThumbnailLoader()

        setContent {
            YouTubeTvTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    val opened = open
                    val addModel = adding
                    val renameModel = renaming
                    val keyModel = keyScreen
                    if (keyModel != null) {
                        // First of all. With no key saved, nothing else in the app can work.
                        val keyState by keyModel.state.collectAsState()
                        ApiKeyScreen(
                            state = keyState,
                            onStartTyping = keyModel::startTyping,
                            onTyped = keyModel::typed,
                            onSubmitTyped = {
                                val typed = keyModel.state.value.typed
                                lifecycleScope.launch(Dispatchers.IO) {
                                    keyModel.submit(typed, KeyFrom.REMOTE)
                                }
                            },
                            onBackToQr = { keyModel.back() },
                            onAskRemove = keyModel::askRemove,
                            onRemove = {
                                lifecycleScope.launch(Dispatchers.IO) { keyModel.remove() }
                            },
                        )
                    } else if (renameModel != null) {
                        // Before the add screen and before a Source, because it is reached
                        // from the rail and nothing else can be open underneath it. It is
                        // first for the same reason the player is drawn last: whatever is
                        // nearest the household wins.
                        val renameState by renameModel.state.collectAsState()
                        RenameSourceScreen(
                            state = renameState,
                            onTyped = renameModel::typed,
                            onSave = {
                                renameSource(renameState.sourceId, renameModel::save)
                            },
                            onClear = {
                                renameSource(renameState.sourceId, renameModel::clear)
                            },
                        )
                    } else if (addModel != null) {
                        val addState by addModel.state.collectAsState()
                        AddSourceScreen(
                            state = addState,
                            thumbnails = thumbnails,
                            onTyped = addModel::typed,
                            // Every one of these writes to storage or to the network, so they
                            // go on the Activity's own scope and off the main thread, exactly
                            // like a write to the household's log.
                            onFind = { lifecycleScope.launch(Dispatchers.IO) { addModel.find() } },
                            onChoose = { candidate ->
                                lifecycleScope.launch(Dispatchers.IO) { addModel.choose(candidate) }
                            },
                            onDirection = addModel::setDirection,
                            onAdd = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val added = addModel.add() ?: return@launch
                                    // Back on the main thread to change what is on screen.
                                    // The new Source opens at once and walks itself, which is
                                    // spec section 6.5.
                                    withContext(Dispatchers.Main) {
                                        closeAddSource()
                                        openSource(
                                            sourceId = added.sourceId,
                                            name = added.title,
                                            landing = Landing.NEWEST,
                                        )
                                    }
                                }
                            },
                            // The Source's name is read from SQLite, so it is read off the
                            // main thread like every other store read in this file, and the
                            // screen is changed back on the main thread. Found by
                            // `/code-review`.
                            onOpenExisting = { sourceId ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    // Through `sourceNameOrId`, so a stored title that is
                                    // blank is no title here either. It was a bare `?:` and
                                    // would have handed the header an empty string. Ticket 29.
                                    val row = sources.source(sourceId)
                                    val name = sourceNameOrId(row?.typedName, row?.title, sourceId)
                                    withContext(Dispatchers.Main) {
                                        closeAddSource()
                                        openSource(sourceId, name, Landing.NEXT_UNWATCHED)
                                    }
                                }
                            },
                            onBack = { if (!addModel.back()) closeAddSource() },
                        )
                    } else if (opened == null) {
                        val homeState by home.state.collectAsState()
                        HomeScreen(
                            state = homeState,
                            onOpen = ::openSource,
                            onAddSource = { adding = AddSourceModel(
                                store = sources,
                                watches = watches,
                                resolver = resolver,
                                search = search,
                                log = { line -> Log.i(LOG_TAG, line) },
                            ) },
                            onRemove = ::removeSource,
                            // The manual trigger of spec section 5.1. On the Activity's own
                            // scope and off the main thread, like every other call that
                            // touches the network and SQLite. The household is back on the
                            // rail while it runs; nothing blocks and nothing is drawn over.
                            onRefresh = { card ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    runRefresh(RefreshTrigger.MANUAL, card.sourceId)
                                }
                            },
                            // A screen of its own, built from the card the household is
                            // standing on. Nothing is written until they save there.
                            onRename = ::openRename,
                            // The last line of the menu. Build ticket 64.
                            onApiKey = ::openKeyScreen,
                            // One `range` entry. On the Activity's scope and off the main
                            // thread, exactly like every other write to the household's log.
                            onMarkEverythingUnwatched = { card ->
                                // The Source's own model is dropped first. It holds a watch
                                // map read before this range existed, and a kept model would
                                // otherwise draw green ticks this mark has just taken away.
                                sourceModels.remove(card.sourceId)
                                lifecycleScope.launch(Dispatchers.IO) {
                                    home.markEverythingUnwatched(card.sourceId)
                                }
                            },
                            onAction = { line -> Log.i(LOG_TAG, line) },
                            focusOnSourceId = lastOpenedSourceId,
                            returnedFromSource = returnedFromSource,
                            // The same loader the Source screen and the add screen are given,
                            // so its one cache is shared. A Next unwatched video whose picture
                            // the grid already decoded is drawn on the rail in the frame the
                            // household comes back.
                            thumbnails = thumbnails,
                        )
                    } else {
                        val state by opened.model.state.collectAsState()
                        // Its own flow, so the header follows a name that changes while the
                        // household is standing in the Source. Ticket 29.
                        val sourceName by opened.model.name.collectAsState()
                        SourceScreen(
                            sourceId = opened.sourceId,
                            sourceName = sourceName,
                            state = state,
                            thumbnails = thumbnails,
                            // On the Activity's own scope, not the composition's. A write to
                            // the household's log must not be cancelled because the screen
                            // went away mid-write. Off the main thread as well: it is a
                            // SQLite write, and a write on the main thread is a dropped
                            // frame at best.
                            onMarkJustThis = { video, watched ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    opened.model.markJustThis(video, watched)
                                }
                            },
                            onMarkRange = { pivot, side, watched ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    opened.model.markRange(pivot, side, watched)
                                }
                            },
                            onPlay = ::startPlayback,
                            // The `Order` chip writes the Source's row, so it goes on the
                            // Activity's scope and off the main thread, like a mark.
                            onFlipDirection = {
                                lifecycleScope.launch(Dispatchers.IO) { opened.model.flip() }
                            },
                            // The `Autoplay` chip writes the Source's row, exactly as
                            // `Order` does, so it goes the same way.
                            onToggleAutoplay = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    opened.model.toggleAutoplayNext()
                                }
                            },
                            // Direction and the two landings are rules, not pictures. This
                            // is what makes them checkable from logcat rather than from a
                            // screenshot.
                            onLanding = { line -> Log.i(LOG_TAG, line) },
                            returnedFromPlayer = returnedFromPlayer,
                            landOnVideoId = landOnVideoId,
                            // The player is drawn over this screen rather than navigated to,
                            // so the Source screen stays composed underneath and still sees
                            // keys while the player is loading. It answers none of them
                            // while this is true. Ticket 09.
                            playing = playback != null,
                            openAt = opened.landing,
                        )
                    }

                    playback?.let { current ->
                        val playbackState by current.session.state.collectAsState()
                        PlayerScreen(
                            controller = current.controller,
                            session = current.session,
                            nudge = playerNudge,
                            countdown = countdown,
                            covered = covered,
                            notice = playerNotice,
                            endOfSource = endOfSource,
                        )
                        // Ticket 17: the cover comes down when the player says the next
                        // video is on the page, which is the nearest thing to a painted
                        // frame the IFrame API has, and not before the countdown is over,
                        // because the countdown is drawn on the cover. [coverMayComeDown]
                        // is the rule and it is a JVM test.
                        LaunchedEffect(
                            current.session,
                            playbackState.finished,
                            playbackState.onScreen,
                            countdown,
                        ) {
                            val down = coverMayComeDown(
                                videoFinished = playbackState.finished,
                                videoOnScreen = playbackState.onScreen,
                                countingDown = countdown != null,
                            )
                            if (down) lowerCover()
                        }
                        // The video ended, so spec section 8.6 decides what happens next:
                        // the countdown, the next video on the grid, the end of the Source,
                        // or the unattended stop. [onVideoFinished] is where all four are.
                        //
                        // Keyed on the session as well as on the flag, because one player
                        // now shows a whole chain of videos and each video's own session
                        // has to be able to finish.
                        LaunchedEffect(current.session, playbackState.finished) {
                            if (playbackState.finished) onVideoFinished()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sources

    /**
     * The household chose a Source on the home rail.
     *
     * Its [SourceScreenModel] is built here, one per Source. **This is also the only
     * thing in the app that walks a Source**, because `SourceScreenModel.load` walks
     * when storage holds no row for it, and a walk costs quota. Arriving at the home
     * screen must never trigger one, which is why the rail draws only what storage
     * already has.
     */
    private fun openSource(card: SourceCard, landing: Landing) =
        openSource(card.sourceId, railCardName(card), landing)

    /**
     * Opens a Source by id, for the two callers that have no rail card to hand.
     *
     * The add screen is one: a Source added a moment ago is not on the rail yet, because the
     * rail is rebuilt when the household comes back to it. The offer to open a Source the
     * household already has is the other.
     */
    private fun openSource(sourceId: String, name: String, landing: Landing) {
        if (open != null) return
        val kept = sourceModels[sourceId]
        val model = kept ?: SourceScreenModel(
            sourceId = sourceId,
            store = sources,
            walk = walk,
            watches = watches,
            log = { line -> Log.i(LOG_TAG, line) },
        )
        // The name the caller is drawing right now, handed over synchronously so the header
        // has something to draw in the first frame instead of the raw playlist id. **It is a
        // guess and not the answer**, because the rail card it comes from can be older than
        // storage: `closeSource` rebuilds the rail in the background, and a household that
        // presses `BACK` and then `DPAD_CENTER` straight away opens a card built before the
        // rebuild landed. Storage settles it a moment later, below. Ticket 29, and the second
        // half of this was found by `/code-review`.
        //
        // **It is before `start` below, and that is not tidiness.** `start` reads the Source's
        // row on `Dispatchers.IO`, so handing the name over afterwards would be two writers to
        // the same flow in no fixed order, and the losing one is the older name.
        model.openedWithName(name)
        if (kept == null) {
            sourceModels[sourceId] = model
            // Started once, on the opening that created it. A model already in the map
            // has either finished reading or is still walking, and either way a second
            // start would be a second walk. `load` reads the Source's row, so a new model
            // needs no separate name read.
            model.start(lifecycleScope)
        } else {
            // A kept model runs no `load`, so this is the only thing that lets storage
            // correct the guess above. One row read by primary key, off the main thread like
            // every other store read in this file. It is also what will make build ticket
            // 27's rename reach the header whether or not the rail card was rebuilt first.
            lifecycleScope.launch(Dispatchers.IO) { model.refreshName() }
        }
        open = OpenSource(
            sourceId = sourceId,
            landing = landing,
            model = model,
        )
        lastOpenedSourceId = sourceId
    }

    /**
     * The household left the add screen, whichever way.
     *
     * The model goes with it, so the next opening starts on an empty field rather than on the
     * last refusal the household saw.
     */
    private fun closeAddSource() {
        adding = null
    }

    /**
     * The household left the rename screen, whichever way.
     *
     * The rail is **not** rebuilt here. `BACK` writes nothing, so there is nothing for a
     * rebuild to show, and a read of every listed video of every Source is not a thing to do
     * for a press that changed nothing. The two paths that do write ask for the rebuild
     * themselves, in [renameSource].
     */
    private fun closeRename() {
        renaming = null
        // **The rail is composed again from nothing when this returns, so it has forgotten
        // which card had the highlight.** `RenameSourceScreen` is a sibling branch of
        // `HomeScreen` in [setContent], so opening it takes `HomeScreen` out of the composition
        // and its remembered `focusedSourceId` with it. `Remove` and `Mark everything
        // unwatched` do not have this problem, because those draw over the rail and it stays
        // composed; a rename is the first rail action that tears it down.
        //
        // So the rail is asked for the highlight the same way `closeSource` asks: the Source id
        // in [lastOpenedSourceId], which [openRename] has already set to the renamed card, and
        // a bump of [returnedFromSource] so that renaming the same card twice is two asks and
        // not one silently dropped. Without it the household lands on whichever Source they
        // last opened, or on the first card, with the rail scrolled back to the start. Found by
        // `/code-review`.
        returnedFromSource += 1
    }

    /**
     * The household chose `Rename` on a rail card. Build ticket 27.
     *
     * Both names come off the card rather than out of storage, so the field is filled in the
     * first frame. See [RenameSourceModel], which says why a field must not be corrected under
     * a household that has started typing.
     */
    private fun openRename(card: SourceCard) {
        // Where the highlight goes back to when this screen closes. See [closeRename] for why
        // the rail cannot remember it by itself. The field is named for coming back from a
        // Source, and this is the same question: which card the rail should stand on.
        lastOpenedSourceId = card.sourceId
        renaming = RenameSourceModel(
            sourceId = card.sourceId,
            fetchedName = card.title,
            typedName = card.typedName,
            store = sources,
            log = { line -> Log.i(LOG_TAG, line) },
        )
    }

    /**
     * Writes a rename, or clears one, and puts the rail back in step.
     *
     * **On the Activity's own scope and off the main thread**, exactly like every other write
     * to storage in this file. The order is: write, patch the one card, close the screen, read
     * the rail again.
     *
     * **The screen closes before the rail is read, and the patch is why that is not a stale
     * frame.** The write is one `UPDATE` by primary key, so holding the household on a field
     * until it lands would say something was happening when nothing is. The read afterwards is
     * not cheap: it touches every listed video of every Source, which is over a second on the
     * household's 6,904. Closing after it would be a second of a screen that had finished, and
     * closing before it without the patch would be a second of the rail under the old name.
     *
     * **Nothing drops the Source's kept [SourceScreenModel], and nothing has to.** A rename
     * touches no video and no Watch, so that model's grid and watch map are still right, and
     * [openSource] reads the Source's row on every reopening of a kept model, which is what
     * carries the new name to the header. Ticket 29 built that and named this ticket as the
     * one that would use it.
     */
    private fun renameSource(sourceId: String, write: suspend () -> RenamedSource?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val done = write()
            // The one card whose heading changed, patched before the rail is read again. The
            // read below is the honest answer and this is not a substitute for it; it closes
            // the second or more between the write landing and that read finishing, during
            // which the household would otherwise be looking at the old name. See
            // [HomeModel.renamed].
            if (done != null) home.renamed(sourceId, done.typedName)
            withContext(Dispatchers.Main) { closeRename() }
            home.load()
        }
    }

    /**
     * The household confirmed `Remove`. Spec section 6.6.
     *
     * Three things happen and the order matters:
     *
     * 1. The Source's kept [SourceScreenModel] is dropped. It holds that Source's videos and
     *    its watch map in memory, and a kept one would draw the Source again, fully populated,
     *    if the household added it back in the same sitting.
     * 2. The row and the cached videos go, in one transaction.
     * 3. One `sourceRemoved` entry is appended to the Watch log. **Nothing is deleted from
     *    it.** Every Watch and every Bulk mark range that Source earned stays exactly where it
     *    is, which is what makes adding the same playlist id back restore the household's
     *    history whole.
     *
     * The rail is then built again, because the card has to go.
     */
    private fun removeSource(card: SourceCard) {
        val name = railCardName(card)
        sourceModels.remove(card.sourceId)
        lifecycleScope.launch(Dispatchers.IO) {
            sources.removeSource(card.sourceId)
            watches.sourceRemoved(card.sourceId)
            Log.i(LOG_TAG, sourceRemovedLine(card.sourceId, name))
            home.load()
        }
    }

    /**
     * `BACK` from a Source. The rail is built again before it is shown.
     *
     * It has to be. Playing a video is what moves a Source to the front of the rail,
     * and every mark made inside the Source changes that Source's counts, so the rail
     * the household left behind is stale the moment they leave it.
     */
    private fun closeSource() {
        if (open == null) return
        open = null
        returnedFromSource += 1
        home.reload(lifecycleScope)
    }

    // ------------------------------------------------------------------- refresh, spec 5

    /**
     * Runs one refresh and puts back in step whatever it changed.
     *
     * **Call it off the main thread.** Everything under it is network and SQLite.
     *
     * @param onlySourceId the one Source the rail's `Refresh` line asked about, or null for
     *   every Source.
     */
    private suspend fun runRefresh(trigger: RefreshTrigger, onlySourceId: String? = null) {
        // With no key every call would be refused. The key screen is up, and saving a key there
        // is what starts the next refresh. Build ticket 64.
        if (keyStore.read() == null) return
        finishRefreshRun(refresher.refresh(trigger, onlySourceId))
    }

    // ------------------------------------------------------------------- the API key, ticket 64

    /**
     * The launch refresh of spec section 5.1, once per process.
     *
     * In the background and never blocking: the rail is drawn from storage, and each Source
     * catches up the moment it is stored.
     */
    private fun startLaunchRefresh() {
        if (launchRefreshStarted) return
        launchRefreshStarted = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                refresher.refreshOnLaunch(afterEachPass = { run -> finishRefreshRun(run) })
            }.onFailure { Log.w(LOG_TAG, "The launch refresh failed and was left alone.", it) }
        }
    }

    /** Opens the key screen and starts its Key page. Spec section 7.5. */
    private fun openKeyScreen() {
        if (keyScreen != null) return
        val model = ApiKeyModel(
            store = keyStore,
            checker = keyChecker,
            // Called on whichever thread checked the key, which for the Key page is the
            // server's own. The screen changes on the main thread.
            onSaved = { runOnUiThread { keySaved() } },
            log = { line -> Log.i(LOG_TAG, line) },
        )
        keyScreen = model
        Log.i(LOG_TAG, "The key screen is open.")
        startKeyServer()
    }

    /**
     * Starts the Key page for the open key screen, with a new token, and keeps the address
     * it prints current.
     *
     * One token for the whole time the screen is open. The server listens on every interface,
     * so a new address needs no new server, only a new line on the screen and a new QR code.
     * With no home network the screen says so and offers the remote instead.
     */
    private fun startKeyServer() {
        val model = keyScreen ?: return
        if (keyServer != null) return
        val token = newKeyPageToken()
        val server = KeyPageServer(
            token = token,
            onKey = { key -> runBlocking { model.submit(key, KeyFrom.KEY_PAGE) } },
            log = { line -> Log.i(LOG_TAG, line) },
            // The same value the key screen prints and the QR code holds. Build ticket 66.
            pageAddress = { model.state.value.pageUrl },
        )
        val port = runCatching { server.start() }.getOrElse { failure ->
            Log.w(LOG_TAG, "The Key page could not start.", failure)
            model.pageReady(null)
            return
        }
        keyServer = server
        keyAddressJob = lifecycleScope.launch(Dispatchers.IO) {
            var shown: String? = null
            var first = true
            while (true) {
                val address = deviceLocalAddress()?.hostAddress
                if (first || address != shown) {
                    if (address == null) {
                        Log.i(LOG_TAG, "The key screen found no home network, so no address is shown.")
                    }
                    // `deviceLocalAddress` blocks and ignores cancellation, so a screen that closed
                    // meanwhile must not get its address back. Found by `/code-review` on ticket 66.
                    ensureActive()
                    model.pageReady(address?.let { keyPageUrl(it, port, token) })
                    shown = address
                    first = false
                }
                delay(KEY_ADDRESS_RECHECK_MILLIS)
            }
        }
    }

    /** Stops the Key page. After this the port is closed. */
    private fun stopKeyServer() {
        keyAddressJob?.cancel()
        keyAddressJob = null
        keyServer?.stop()
        keyServer = null
        keyScreen?.pageReady(null)
    }

    /** Leaves the key screen, whichever way. The Key page stops with it. */
    private fun closeKeyScreen() {
        val model = keyScreen ?: return
        model.close()
        stopKeyServer()
        keyScreen = null
        Log.i(LOG_TAG, "The key screen is closed.")
        // The home screen was composed out while the key screen was up, so it is asked to put
        // the highlight back, as it is after the rename screen.
        returnedFromSource += 1
    }

    /**
     * A key was checked and saved. The app moves on to the home screen.
     *
     * A quota refusal earned by the old key does not stand for the new one, so both latches
     * are let go, and the Sources are checked now with the new key rather than at midnight
     * Pacific Time. Found by `/code-review` on build ticket 64.
     */
    private fun keySaved() {
        closeKeyScreen()
        refresher.forgetQuotaRefusal()
        search.forgetQuotaRefusal()
        if (launchRefreshStarted) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { runRefresh(RefreshTrigger.TIMER) }
                    .onFailure { Log.w(LOG_TAG, "The check after a new key failed.", it) }
            }
        } else {
            startLaunchRefresh()
        }
    }

    /**
     * One re-listed Source is stored, so the screens show it now. Build ticket 55.
     *
     * `SourceRefresh` calls this for every Source it re-lists, the moment that Source is
     * stored, and not when the whole run ends. On 2026-10-03 the difference was 13 seconds
     * against 105 for the household Source, because a 5,841-video Source was walked after it.
     *
     * **Which screen holds the Source is decided on the main thread**, where [openSource] runs.
     * Deciding it here on the IO thread left a gap: [openSource] could reuse the kept model
     * between this reading `open` as null and the model being dropped, and the household would
     * then stand in a Source drawn from before the refresh, with nothing left to reload it.
     */
    private suspend fun showStoredSource(stored: SourceStored) {
        val onScreen = withContext(Dispatchers.Main) {
            val opened = open
            if (opened != null && opened.sourceId == stored.sourceId) {
                opened.model
            } else {
                // Ticket 10's rule. A kept model holds the videos and the watch map read
                // before this refresh, so it would draw green ticks and counts from before it.
                // A Source whose videos did not change has nothing stale in its model, apart from
                // view counts, which build ticket 59 writes without counting them as a change.
                if (stored.changed || stored.viewCountsWritten) sourceModels.remove(stored.sourceId)
                null
            }
        }
        if (!stored.changed && stored.viewCountsWritten) {
            // Only view counts moved. Build ticket 59. The open screen reads storage again so
            // its cards show them, and that is all: this is not a change, so no "reaches the
            // screens" line is written and the rail, which shows no count, is not built again.
            // Build ticket 56's criterion depends on both. The `(updated ...)` line moves with
            // the read, because the read takes `last_walked_at` from the same row.
            onScreen?.reloadFromStorage()
            return
        }
        if (!stored.changed) {
            // The Source on screen did catch up, even though nothing in it moved, and the
            // quiet `(updated ...)` line of spec section 5.4 is the one thing that says so.
            // It is one row read and not the whole list, because there is nothing else to
            // read: a Source of five thousand videos would otherwise be read off the disk
            // every thirty minutes to change one time. Found by `/code-review`.
            onScreen?.refreshUpdatedAt()
            return
        }
        Log.i(LOG_TAG, sourceReachedScreensLine(stored.sourceId, onScreen = onScreen != null))
        // The Source is on screen right now. Spec section 5.3 applies the change at once, so
        // the model reads storage again rather than being dropped: dropping it would blank the
        // grid the household is standing in. Where the highlight goes is the screen's rule,
        // `highlightAfterRefresh` in `refresh/RefreshMerge.kt`.
        onScreen?.reloadFromStorage()
        // The card's count and its Next unwatched title come from the videos that just
        // changed, so the rail is stale the moment they land.
        home.load()
    }

    /**
     * The end of one refresh pass: what is left once every Source has been shown.
     *
     * Every re-listed Source has already reached the screens through [showStoredSource]. What
     * is left is the name, which a check can fill without a single video moving.
     */
    private suspend fun finishRefreshRun(run: RefreshRun) {
        // A Source is usually named without one of its videos moving, so nothing above
        // carried the name to the header. `refreshUpdatedAt` gives up whenever
        // `last_walked_at` did not move, which is exactly the naming-only case.
        //
        // It is not guarded on `run.namedSourceIds`: the header follows the Source's name
        // whatever changed it, and a rename is build ticket 27. Ticket 29.
        //
        // The `(updated ...)` line is not touched here. Only a re-listed Source moves its
        // `last_walked_at`, and [showStoredSource] has already put each of those on screen.
        open?.model?.refreshName()
        // **A Source can be named without one of its videos moving, and that is the usual
        // case.** All four of the household's Sources are up to date and all four were
        // headed by a raw playlist id, so the run that fills their names changes no video
        // at all. Without this the names would appear only at the next relaunch. Ticket 20.
        if (run.namedSourceIds.isNotEmpty()) home.load()
    }

    /**
     * The 30 minute timer of spec section 5.1, restarted whenever the app comes back.
     *
     * **It is paused during playback, and one check runs when playback ends.** Since ticket 08
     * a play is a whole chain of videos rather than one, so "during playback" means the whole
     * chain: the loop simply asks whether the player is on screen, and the player is on screen
     * for the whole chain.
     *
     * There is no work at all while the app is closed. The job is cancelled in [onPause].
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            // **Every turn of this loop is caught.** `SourceRefresh` maps every YouTube failure
            // it knows, and nothing else: an `SQLiteException` from a locked or full database
            // would escape a root coroutine, take the whole app down, and kill the timer for
            // the rest of the process. A refresh that failed is a logcat line and a wait, which
            // is what every other failure on this path already is. Found by `/code-review`.
            while (true) {
                // With no key, a check returns at once without moving `lastCheckAt`, so this
                // loop would spin with no delay. It waits a tick instead. Found by
                // `/code-review` on build ticket 64.
                if (keyStore.read() == null) {
                    delay(minOf(REFRESH_TICK_MILLIS, refreshIntervalSeconds * 1000))
                    continue
                }
                val wait = millisUntilNextCheck(
                    Instant.now(),
                    refresher.lastCheckAt,
                    refreshIntervalSeconds,
                )
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                if (playback != null) {
                    // Written once a minute rather than once, so a household that watches for
                    // three hours can see in logcat that the timer really did stand still.
                    Log.i(LOG_TAG, refreshPausedForPlaybackLine())
                    // Never longer than the gap itself, so a launch that shortened the gap to
                    // watch this happening does not then wait a minute to see it.
                    delay(minOf(REFRESH_TICK_MILLIS, refreshIntervalSeconds * 1000))
                    continue
                }
                runCatching { runRefresh(RefreshTrigger.TIMER) }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Log.w(LOG_TAG, "The refresh timer's check failed and was left alone.", failure)
                        delay(minOf(REFRESH_TICK_MILLIS, refreshIntervalSeconds * 1000))
                    }
            }
        }
    }

    // ------------------------------------------------------------------ playback

    /**
     * The household chose a video.
     *
     * Where it starts is decided here and not inside the player, because `Play from start` is
     * a choice made on the Source screen before the player exists. Spec section 8.6.
     */
    private fun startPlayback(video: StoredVideo, playFromStart: Boolean) {
        if (playback != null) return
        if (video.videoId.isBlank()) return

        // A play is a key press, so the two hours start again here. Without it, a household
        // that browsed the grid for an hour would get half a video.
        lastKeyAtMillis = SystemClock.elapsedRealtime()
        // The Source this video belongs to, carried on the play itself rather than read
        // from a field. There is always one, because the only way to reach a video is
        // through a Source screen.
        val model = open?.model ?: return
        show(video, playFromStart = playFromStart, pauseOnStart = false, model = model)
    }

    /**
     * Puts one video on the page, whether it is the first of a chain or the next of one.
     *
     * Everything a video boundary has to do in the right order:
     *
     * 1. the finished play is closed, so spec section 3.3's one mutable entry freezes;
     * 2. the new session is in place before the load, so the page's first ticks about the new
     *    video have somewhere to go;
     * 3. the load is sent, which is what replaces the related-video wall.
     *
     * The page is **not** rebuilt. [PlayerController] outlives one play, because building a
     * page and a player takes about two seconds and the wall would be on the television for
     * all of it. Its own load number is what keeps the finished video's late ticks off the
     * new video's entry.
     *
     * @param pauseOnStart true for a video the autoplay-next countdown is about to sit over.
     */
    private fun show(
        video: StoredVideo,
        playFromStart: Boolean,
        pauseOnStart: Boolean,
        model: SourceScreenModel,
    ) {
        val previous = playback
        if (previous != null) finishWrite { previous.session.onLeaving() }

        val resumeFrom = resumeFromSeconds(model.watchState(video.videoId), playFromStart)
        val session = PlaybackSession(
            video = video,
            watches = watches,
            resumeFrom = resumeFrom,
            // A video the countdown is about to sit over writes nothing until the household
            // lets it play. Otherwise `BACK` during the countdown, and even the moment of
            // PLAYING before the pause lands, would write a `played` entry at the resumed
            // position for a video nobody watched, and move Last played onto it. Found by
            // `/code-review`.
            heldForCountdown = pauseOnStart,
            log = { line -> Log.i(LOG_TAG, line) },
            // The card behind the player already carries its green tick and its red In
            // progress bar by the time the household is back on the grid.
            onWatchState = { state -> model.applyWatchState(state) },
        )
        currentSession = session

        val page = controller ?: PlayerController(
            context = this,
            // The session is read at the moment the event arrives, never captured. One page
            // now outlives many plays, so a captured session would go on taking the ticks of
            // whatever video came after it.
            onEvent = { event ->
                // The cover goes up here and not off the session's `finished` flag, because
                // this lambda runs on the main thread at the instant the page reports the
                // end, and the flag is set on [playbackWrites] and read back through a
                // recomposition. Those hops are what the household sees the wall through.
                // Ticket 17.
                if (event is PlayerEvent.Ended) raiseCover()
                playbackWrites.execute { currentSession?.onEvent(event) }
            },
            log = { line -> Log.i(LOG_TAG, line) },
        ).also { controller = it }

        page.load(video.videoId, resumeFrom ?: 0, video.durationSeconds, pauseOnStart)
        playback = Playback(video, page, session, model)
        endOfSource = false
        Log.i(
            LOG_TAG,
            "Playing ${video.videoId}" +
                if (resumeFrom == null) " from the start." else ", resuming from $resumeFrom s.",
        )
    }

    /**
     * The household left the player, by `BACK` or because there was nothing left to play.
     *
     * The last write is waited for. It is one SQLite write and takes milliseconds, and giving
     * it up would lose the position the household is owed.
     *
     * @param landOn the video the grid should put the highlight on, or null for the card the
     *   household left, which is the video they were watching.
     */
    private fun leavePlayback(landOn: StoredVideo? = null) {
        val current = playback ?: return
        cancelCountdown()
        // The player is going, and the grid takes the whole screen. A cover left standing
        // would be over the next play of the next video, which has no wall behind it.
        covered = false
        playback = null
        currentSession = null
        endOfSource = false
        playerNotice = null
        finishWrite { current.session.onLeaving() }
        current.controller.destroy()
        controller = null
        landOnVideoId = landOn?.videoId
        returnedFromPlayer += 1
        // Spec section 5.1: the 30 minute timer is paused during playback, and one check runs
        // when playback ends. Since ticket 08 a play is a whole chain of videos, and this is
        // the one place the chain ends, whether it ran out or the household pressed `BACK`.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { runRefresh(RefreshTrigger.TIMER) }
                .onFailure { Log.w(LOG_TAG, "The check at the end of playback failed.", it) }
        }
    }

    // ------------------------------------------------------------- the chain of videos

    /**
     * The video ended. Spec section 8.6 decides what happens next, and [afterVideo] is it.
     *
     * The next video is read from the Source screen's own list, which is already in the
     * Source's Direction, so next means the next index and nothing here consults a date.
     * `nextPlayableVideo` is what steps over a Short, an unavailable video and an upcoming
     * premiere.
     */
    private fun onVideoFinished() {
        val current = playback ?: return
        // This video's own Source, carried by the play, not a field. See [Playback].
        val ready = current.model.ready()
        val next = nextPlayableVideo(
            videos = ready?.videos.orEmpty(),
            fromVideoId = current.video.videoId,
            step = PlayStep.NEXT,
        )
        val unattended = isUnattended(
            lastKeyAtMillis = lastKeyAtMillis,
            nowMillis = SystemClock.elapsedRealtime(),
            limitSeconds = unattendedLimitSeconds,
        )
        val autoplayNext = ready?.autoplayNext ?: StoredSource.AUTOPLAY_NEXT_DEFAULT
        when (val ending = afterVideo(next, autoplayNext, unattended)) {
            is AfterVideo.Countdown -> {
                Log.i(LOG_TAG, "Autoplay-next: loading ${ending.next.videoId} behind a countdown.")
                // Loaded first and counted down afterwards. The related-video wall cannot be
                // turned off, so the only way never to show it is to have replaced it before
                // the countdown is drawn. Spec sections 8.6 and 8.7.
                show(
                    ending.next,
                    playFromStart = false,
                    pauseOnStart = true,
                    model = current.model,
                )
                startCountdown(ending.next)
            }
            is AfterVideo.LandOnNext -> {
                Log.i(
                    LOG_TAG,
                    "Autoplay-next is off, so the grid lands on ${ending.next.videoId}.",
                )
                leavePlayback(landOn = ending.next)
            }
            AfterVideo.EndOfSource -> {
                Log.i(LOG_TAG, "End of the Source after ${current.video.videoId}. No wrap-around.")
                endOfSource = true
            }
            is AfterVideo.Unattended -> {
                Log.i(
                    LOG_TAG,
                    "No key press for $unattendedLimitSeconds s, so playback stopped at the " +
                        "end of ${current.video.videoId}.",
                )
                leavePlayback(landOn = ending.next)
            }
        }
    }

    /**
     * The household asked for the next or the previous video, or the countdown did.
     *
     * At either end of the Source it says so and stays where it is. There is no wrap-around
     * anywhere in this app. Spec section 8.6.
     */
    private fun stepVideo(step: PlayStep) {
        val current = playback ?: return
        val videos = current.model.ready()?.videos.orEmpty()
        val target = nextPlayableVideo(videos, current.video.videoId, step)
        if (target == null) {
            val message = if (step == PlayStep.NEXT) NO_NEXT_VIDEO else NO_PREVIOUS_VIDEO
            // Said on the screen and written to logcat. The message on screen fades after two
            // and a half seconds, which is a race a verification loses; the line does not.
            Log.i(LOG_TAG, "No wrap-around from ${current.video.videoId}: $message")
            say(message)
            return
        }
        val counting = countdown != null
        Log.i(
            LOG_TAG,
            "${if (step == PlayStep.NEXT) "Next" else "Previous"} from " +
                "${current.video.videoId} is ${target.videoId}.",
        )
        // During a countdown the next video changes and the countdown starts again, so the
        // household can walk along the Source without a video ever starting under them. Spec
        // section 8.6. Outside one, the video plays at once.
        show(target, playFromStart = false, pauseOnStart = counting, model = current.model)
        if (counting) startCountdown(target) else cancelCountdown()
    }

    /** Counts five seconds over the paused next video, and then plays it. Spec section 8.6. */
    private fun startCountdown(next: StoredVideo) {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (left in COUNTDOWN_SECONDS downTo 1) {
                countdown = CountdownUi(next, left)
                delay(1_000)
            }
            countdown = null
            // Released before it plays, not after, so the very first tick of the play it is
            // now allowed to write is written.
            playback?.session?.release()
            playback?.controller?.play()
            Log.i(LOG_TAG, "Countdown finished, playing ${next.videoId}.")
        }
    }

    /**
     * A video ended, so the opaque cover goes over the player at once. Ticket 17.
     *
     * Called from the event lambda on the main thread, before anything else in the app has
     * been told the video is over. Nothing here decides what happens next: that is
     * [onVideoFinished], and it runs a recomposition later, which is exactly the delay this
     * is here to draw over.
     */
    private fun raiseCover() {
        if (covered) return
        covered = true
        Log.i(LOG_TAG, "The related-video wall is covered.")
    }

    /** The next video is on the page and no countdown is running, so the cover comes down. */
    private fun lowerCover() {
        if (!covered) return
        covered = false
        Log.i(LOG_TAG, "The next video is on the page, so the cover is down.")
    }

    /** The countdown is over, one way or another. */
    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown = null
    }

    /** `DPAD_CENTER` during the countdown: play the next video now rather than in five. */
    private fun playCountdownNow() {
        val next = countdown?.next ?: return
        cancelCountdown()
        playback?.session?.release()
        playback?.controller?.play()
        Log.i(LOG_TAG, "Countdown cut short, playing ${next.videoId} now.")
    }

    /** One short sentence over the player. The same words twice are still two messages. */
    private fun say(message: String) {
        playerNotices += 1
        playerNotice = PlayerNotice(playerNotices, message)
    }

    /** Runs one last write on the playback thread, and waits a bounded time for it. */
    private fun finishWrite(write: () -> Unit) {
        runCatching {
            playbackWrites.submit(write).get(FINAL_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { failure ->
            Log.w(LOG_TAG, "The last position write did not finish: ${failure.message}")
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * Every key, while a video is on screen.
     *
     * The WebView is not focusable, so nothing reaches the page on its own and the whole of
     * spec section 8.5 happens here. What each key means is [playerAction], which is a plain
     * function and a JVM unit test.
     *
     * A key the player owns is kept on the way up as well as on the way down. The WebView and
     * the grid behind it must never see half of an event.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Every key, on the grid as well as in the player, and on the way up as well as the
        // way down. Spec section 8.6 says "any key press resets the timer", and it means any.
        //
        // `elapsedRealtime` and **not** `event.eventTime`, although the event carries one.
        // `eventTime` is on `uptimeMillis`, which stops while the device is in deep sleep,
        // and the limit is measured against `elapsedRealtime`, which does not. Mixing the two
        // clocks would make a television that slept for an hour think an hour had not passed.
        lastKeyAtMillis = SystemClock.elapsedRealtime()

        val current = playback ?: return super.dispatchKeyEvent(event)

        if (event.action != KeyEvent.ACTION_DOWN) {
            return playerKeepsKey(event.keyCode) || super.dispatchKeyEvent(event)
        }

        val action = playerAction(event.keyCode)
        if (action == PlayerAction.NONE) return super.dispatchKeyEvent(event)

        // A held key repeats about thirty times a second. Only the small seek is worth
        // repeating, and even that is slowed to a rate a person can steer by. The gate also
        // says how far a seek moves, because a held seek grows its step the longer it is
        // held. The key is still kept, so a repeat this swallows cannot reach the grid
        // underneath.
        val command = heldKeys
            .command(action, event.repeatCount, event.eventTime, event.downTime)
            ?: return true

        // Every key the player keeps brings the title and the position back on screen. With
        // `controls=0` there is nothing else to say what just happened.
        playerNudge += 1

        // The end-of-Source message is the end of playback, not a pause in it. The only key
        // that means anything on it is the one the message names. Spec section 8.6.
        if (endOfSource) {
            // Nothing is seeking, so a key held through the message must not be climbing the
            // ladder behind it.
            heldKeys.restartHold(event.eventTime)
            if (action == PlayerAction.LEAVE) {
                lastBackAtMillis = SystemClock.elapsedRealtime()
                leavePlayback()
            }
            return true
        }

        // During the countdown the video behind is paused on its first frame, and a seek
        // would move the position the next play starts from. Only the four keys spec section
        // 8.6 names act: play now, leave, next and previous.
        val counting = countdown != null
        // A seek thrown away is a seek that did not happen, so the ladder goes back to the
        // bottom. Without this, a household leaning on the key through a five second
        // countdown would come out of it at a minute a jump, on a video that had just begun.
        if (counting) heldKeys.restartHold(event.eventTime)

        when (command.action) {
            PlayerAction.TOGGLE_PLAY ->
                if (counting) playCountdownNow() else current.controller.togglePlay()
            PlayerAction.PLAY ->
                if (counting) playCountdownNow() else current.controller.play()
            PlayerAction.PAUSE -> if (!counting) current.controller.pause()
            // All four seeks are one branch. The gate has already turned the key into a
            // signed number of seconds: 10 for a press, more for a held key that has climbed
            // the ladder, and 300 for the big seek on `DPAD_UP` and `DPAD_DOWN`.
            //
            // `ytSeekBy` clamps at zero always, and at `SEEK_END_MARGIN` before the end
            // **whenever the Data API gave a duration**. A video whose duration is missing,
            // which is what a live stream and an upcoming premiere give, has no upper clamp
            // and never had one. The player's own `getDuration` is not an answer to that: it
            // reports the ad's length during an in-stream ad, which is why spec section 8.4
            // forbids it.
            PlayerAction.SEEK_BACK,
            PlayerAction.SEEK_FORWARD,
            PlayerAction.BIG_SEEK_BACK,
            PlayerAction.BIG_SEEK_FORWARD,
            -> if (!counting) current.controller.seekBy(command.seekSeconds)
            PlayerAction.NEXT_VIDEO -> stepVideo(PlayStep.NEXT)
            PlayerAction.PREVIOUS_VIDEO -> stepVideo(PlayStep.PREVIOUS)
            // Up to Android 14 this is the only route `BACK` takes. The time is written down
            // so that the same press arriving again through the back dispatcher, which is
            // what Android 15 and later do, is recognised as an echo. See [back].
            PlayerAction.LEAVE -> {
                lastBackAtMillis = SystemClock.elapsedRealtime()
                leavePlayback()
            }
            // The key is kept so it cannot reach the grid, which is still composed and still
            // focusable underneath.
            PlayerAction.SWALLOW -> Unit
            PlayerAction.NONE -> Unit
        }
        return true
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The last chance to write the position. Spec section 8.4 names it explicitly.
     *
     * Android can stop this Activity with no key press ever arriving: the television switched
     * off at the remote, or `HOME` pressed, or another app taking the foreground.
     */
    override fun onStop() {
        super.onStop()
        // **The Key page is not stopped here, on purpose.** Creating a key takes about five
        // minutes on a phone or a computer, and the Google TV screensaver can stop this Activity
        // in that time. Stopping the page here would make the page already open in the browser
        // answer `Not found` when the user finally presses Send. It stops when the key screen closes,
        // and in [onDestroy]. Found by `/code-review` on build ticket 64.
        val current = playback ?: return
        finishWrite { current.session.onStopped() }
    }

    /**
     * The app is leaving the screen, so the video stops.
     *
     * Without this, `HOME` left the video playing under the Google TV launcher, with nothing
     * stopping it until Android happened to kill the Activity. The household heard a video
     * they had walked away from.
     */
    override fun onPause() {
        super.onPause()
        // The countdown stops with it. Otherwise it would keep counting under the Google TV
        // launcher and then ask a paused page to play, which is the same defect `onPause`
        // exists to fix: the household hears a video they walked away from. The next video
        // stays loaded and paused, so coming back shows it stopped on its first frame.
        cancelCountdown()
        playback?.controller?.pauseForBackground()
        // Spec section 5.1: there is no background work while the app is closed. The timer is
        // a foreground timer and nothing else, so it stops here and starts again in [onResume].
        refreshJob?.cancel()
        refreshJob = null
    }

    /** The app is back. The page wakes, and the video stays paused where it was left. */
    override fun onResume() {
        super.onResume()
        playback?.controller?.resumeFromBackground()
        startRefreshTimer()
    }

    override fun onDestroy() {
        stopKeyServer()
        countdownJob?.cancel()
        refreshJob?.cancel()
        // The controller and not `playback?.controller`. One page now outlives one play, so
        // the page is what has to be destroyed, whatever the play is doing.
        controller?.destroy()
        controller = null
        playback = null
        currentSession = null
        playbackWrites.shutdown()
        super.onDestroy()
    }
}

/** The seam every later screen hangs on. Colours come from res/values/colors.xml. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun YouTubeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = colorResource(R.color.surface),
            surface = colorResource(R.color.surface),
            onBackground = colorResource(R.color.on_surface),
            onSurface = colorResource(R.color.on_surface),
            primary = colorResource(R.color.accent),
        ),
        content = content,
    )
}
