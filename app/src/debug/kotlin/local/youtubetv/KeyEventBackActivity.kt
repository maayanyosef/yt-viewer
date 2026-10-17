package local.youtubetv

import androidx.activity.ComponentActivity

/**
 * An empty Activity on which `BACK` arrives the way it does on the household television.
 *
 * **It exists for its manifest entry.** `app/src/debug/AndroidManifest.xml` declares it with
 * `android:enableOnBackInvokedCallback="false"`, and only a class of its own can carry that
 * attribute without changing every other instrumentation test.
 *
 * **Why that attribute matters.** `BACK` reaches `BackHandler` by one of two routes, and which
 * one depends on the device. Build ticket 54 measured both on 2026-10-03.
 *
 * - **The household TCL is API 34**, and the app does not opt in to `OnBackInvokedCallback`. So
 *   `BACK` is only a key event. It goes down the view tree, through every `onPreviewKeyEvent`,
 *   and `Activity.onKeyUp` turns it into `onBackPressed` only if nothing consumed it on the way.
 * - **The `googletv1080p` emulator is API 36**, and the app targets 36. There the key event
 *   still goes down the view tree, but the system also calls `OnBackInvokedCallback` on the key
 *   down. That second route runs `BackHandler` whatever the view tree did with the key.
 *
 * Only API 34 and API 36 were measured. Android documents the second route as the default from
 * Android 16, API 36, for an app that targets 36. Turning the callback off on this Activity leaves only the first route, so a test
 * composed here sees `BACK` the way the household's set does. `SourceKeyGuardTest` is the test
 * that needs it.
 *
 * Debug only and not exported, for the reason the manifest gives for the Activity beside it.
 *
 * [keyRouteBacks] is written on the main thread and read by a test on the instrumentation
 * thread, so it is `@Volatile`.
 */
class KeyEventBackActivity : ComponentActivity() {

    /**
     * How many times `BACK` reached [onBackPressed], which only the key-event route calls.
     *
     * **This is what proves the attribute still works.** The callback route calls the back
     * dispatcher directly and never calls [onBackPressed]. If a later Android ignored the
     * attribute, the callback route would close a menu with this still at 0, and a test reading
     * it would say so instead of passing for the wrong reason. `/code-review` on build ticket 54
     * asked for it.
     */
    @Volatile
    var keyRouteBacks = 0
        private set

    @Deprecated("Overridden only to count the key-event route. The dispatcher still decides.")
    override fun onBackPressed() {
        keyRouteBacks++
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
