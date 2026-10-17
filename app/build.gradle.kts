import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = isolated.rootProject.projectDirectory.file("keystore.properties")

val howToFixKeystore =
    """
    Create ${keystorePropertiesFile.asFile.absolutePath} with these four lines:
      RELEASE_STORE_FILE=<path to the .jks keystore>
      RELEASE_STORE_PASSWORD=<store password>
      RELEASE_KEY_ALIAS=<key alias>
      RELEASE_KEY_PASSWORD=<key password>
    RELEASE_STORE_FILE may be absolute, or relative to the repository root.
    Write the path with forward slashes. A backslash is an escape character in a .properties file.
    No value may have a leading or a trailing space. Both are stripped.
    The keystore and keystore.properties are gitignored on purpose. Debug builds need neither.
    See the section "For developers" in README.md.
    """.trimIndent()

/** The four values AGP needs in order to sign the release APK. */
class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * The release signing details, or the failure that says why they are not available.
 *
 * AGP's `signingConfigs` block is not lazy, so this is read while the build is configured and
 * not while it runs. A failure is therefore stored here instead of being thrown. Throwing here
 * would break `./gradlew assembleDebug` and `./gradlew tasks` on a machine that has no
 * keystore, and only the release build needs one. The failure is thrown later, by
 * `checkReleaseSigning`, which only the release packaging tasks depend on.
 */
val releaseSigning: Result<ReleaseSigning> = runCatching {
    val text = providers.fileContents(keystorePropertiesFile).asText.orNull
        ?: throw GradleException(
            "keystore.properties is missing, so the release build cannot be signed.\n$howToFixKeystore",
        )

    val properties = Properties()
    text.reader().use { properties.load(it) }

    fun requireValue(name: String): String {
        val value = properties.getProperty(name).orEmpty().trim()
        if (value.isEmpty()) {
            throw GradleException("keystore.properties has no value for $name.\n$howToFixKeystore")
        }
        return value
    }

    val configuredPath = File(requireValue("RELEASE_STORE_FILE"))
    val storeFile =
        if (configuredPath.isAbsolute) {
            configuredPath
        } else {
            File(isolated.rootProject.projectDirectory.asFile, configuredPath.path)
        }
    if (!storeFile.isFile) {
        throw GradleException(
            "RELEASE_STORE_FILE in keystore.properties points at ${storeFile.absolutePath}, " +
                "and there is no file there.\n$howToFixKeystore",
        )
    }

    ReleaseSigning(
        storeFile = storeFile,
        storePassword = requireValue("RELEASE_STORE_PASSWORD"),
        keyAlias = requireValue("RELEASE_KEY_ALIAS"),
        keyPassword = requireValue("RELEASE_KEY_PASSWORD"),
    )
}

/** The failure message, kept apart so no build-script class is captured by a task action. */
val releaseSigningProblem: String? = releaseSigning.exceptionOrNull()?.message

/**
 * Fails a release build when the keystore is not set up, with a message that says how to fix it.
 *
 * Without this task AGP would quietly produce an unsigned release APK, which cannot be
 * installed and which nobody would notice until the television refused it.
 */
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Checks that keystore.properties and the release keystore are in place."
    doFirst {
        val problem = releaseSigningProblem
        if (problem != null) {
            throw GradleException(problem)
        }
    }
}

// The APK task and the AAB task. Both must refuse to produce an unsigned release artifact.
val releasePackagingTaskNames = setOf("packageRelease", "packageReleaseBundle")

tasks.matching { it.name in releasePackagingTaskNames }.configureEach {
    dependsOn(checkReleaseSigning)
}

android {
    namespace = "local.youtubetv"

    compileSdk {
        // androidx (core-ktx 1.19.0, Compose BOM 2026.09.00) requires API 37 or later.
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "local.youtubetv"
        minSdk {
            // 26 is the floor for java.time without core library desugaring, and every video
            // in this app is ordered by its publish date. The only device is the TCL, API 34.
            version = release(26)
        }
        targetSdk {
            // The newest full release. The TCL runs API 34, so anything above 34 changes
            // nothing on the device.
            version = release(36)
        }
        versionCode = 1
        versionName = "0.1"

        // The instrumentation tests of `app/src/androidTest`. See `emulatorTest` below for the
        // task that runs them, and `docs/agents/testing.md` for what belongs in that source set.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only created when keystore.properties is readable. When it is absent this block adds
        // nothing, the release build type keeps a null signing config, and `checkReleaseSigning`
        // is what stops the release build.
        releaseSigning.getOrNull()?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
                // minSdk is 26, so v1 (JAR signing) adds nothing. v2 and v3 cover API 24 and
                // later, and the TCL is API 34. AGP drops v1 above minSdk 24 whatever
                // `enableV1Signing` says, so it is not set here.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        // No `BuildConfig`. It carried the household's YouTube Data API key until build ticket
        // 64, and nothing else. No build carries a key now: each user enters their own on the
        // key screen, and it is saved on the device.
    }
}

kotlin {
    // Android's core libraries stop at OpenJDK 17, so this stays 17 on every AGP version.
    jvmToolchain(17)
}

// ------------------------------------------------------------- instrumentation tests

/**
 * The serial `emulatorTest` drives, overridable with `-PemulatorSerial=emulator-5556`.
 *
 * The default is the `googletv1080p` emulator, which is 1920x1080 at 320dpi and so has the
 * household television's own geometry: 960dp of width, two physical pixels to the dp.
 */
val emulatorSerial: String = (findProperty("emulatorSerial") as String?) ?: "emulator-5554"

/**
 * `-PemulatorTests=local.youtubetv.ui.RailCardFontScaleTest` runs one class instead of all.
 *
 * The name has to be the whole one, package included, because it is handed to
 * `am instrument -e class` and `AndroidJUnitRunner` looks a bare name up in the default package
 * and fails. A method is `<the whole class name>#theMethod`.
 */
val emulatorTestClass: String? = findProperty("emulatorTests") as String?

/** The SDK's own adb. `local.properties` names the SDK, and the SDK owns the only adb here. */
val adbPath: Provider<String> = providers.fileContents(
    isolated.rootProject.projectDirectory.file("local.properties"),
).asText.map { text ->
    val properties = Properties()
    text.reader().use { properties.load(it) }
    val sdk = properties.getProperty("sdk.dir").orEmpty().trim()
    if (sdk.isEmpty()) {
        throw GradleException("local.properties has no sdk.dir, so adb cannot be found.")
    }
    "$sdk/platform-tools/adb"
}.orElse(
    // A missing file is not an empty value: `map` never runs, and `get()` would then throw
    // Gradle's own "this provider has no value available", which names no file and suggests
    // nothing. Found by `/code-review`.
    providers.provider {
        throw GradleException(
            "local.properties is missing, so the Android SDK's adb cannot be found. " +
                "Create it with a line: sdk.dir=C:/path/to/Android/Sdk",
        )
    },
)

/**
 * Runs one adb command and hands back its exit code and its whole output.
 *
 * **The output is read on a thread of its own, and that is not tidiness.** Reading it on this
 * thread blocks until the child closes its output, which a wedged `am instrument` never does,
 * and the timeout below would then be code that can never run: the build would hang with no
 * message at all. Found by `/code-review`.
 */
fun runAdb(adb: String, arguments: List<String>, timeoutMinutes: Long = 20): Pair<Int, String> {
    val process = ProcessBuilder(listOf(adb, "-s", emulatorSerial) + arguments)
        .redirectErrorStream(true)
        .start()

    val lines = StringBuilder()
    val reader = Thread {
        process.inputStream.bufferedReader().forEachLine { line ->
            // adb writes CRLF. `forEachLine` drops both, and `appendLine` puts back a plain
            // newline, so the caller's search for "\nOK (" means what it says.
            synchronized(lines) { lines.appendLine(line) }
        }
    }
    reader.isDaemon = true
    reader.start()

    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw GradleException(
            "adb ${arguments.joinToString(" ")} did not finish in $timeoutMinutes minutes.",
        )
    }
    // The child has gone, so its output is closed and this returns almost at once. The bound is
    // there so that a stream held open by something else cannot hang the build either.
    reader.join(10_000)
    return process.exitValue() to synchronized(lines) { lines.toString() }
}

/**
 * AGP's own connected-test tasks are refused outright, because they would drive the television.
 *
 * **Adding `app/src/androidTest` is what made them real.** Before build ticket 28 they existed
 * and had nothing to run. Now `connectedDebugAndroidTest` installs the app and the test APK on
 * **every** attached device and runs the suite over each, and the household television is
 * attached over the network for the whole of a working session. It is also the obvious command:
 * anybody who has not read `docs/agents/testing.md` will reach for it first.
 *
 * So it stops before it touches anything, and says what to run instead. Found by
 * `/code-review`.
 */
tasks.matching {
    // `connectedAndroidTest`, `connectedDebugAndroidTest` and `connectedCheck`, which are the
    // three AGP adds, plus `installDebugAndroidTest`, which puts the test APK on every device
    // without running anything. `installDebug` is deliberately left alone: putting the app on
    // the set is what `tools/tv/tv.sh install` does on purpose.
    it.name.startsWith("connected") || it.name.startsWith("installDebugAndroidTest")
}.configureEach {
    doFirst {
        throw GradleException(
            "REFUSED: '$name' installs and drives an app on EVERY attached device, and the " +
                "household television is one of them for the whole of a working session. " +
                "Run ./gradlew emulatorTest instead: it names one emulator and refuses any " +
                "serial that is not one. See docs/agents/testing.md.",
        )
    }
}

/**
 * Runs the instrumentation tests of `app/src/androidTest`, on the emulator and never anywhere
 * else. See `docs/agents/testing.md` for which kind of test belongs in that source set.
 *
 * **It is not `connectedDebugAndroidTest`, and that is the whole reason it exists.** AGP's own
 * task installs on and drives **every** attached device. The household television is attached
 * over the network for the whole of a working session, so that task would install a test APK on
 * the set holding the household's Watch log and drive an app over it. This task names one
 * serial and refuses any serial that is not an emulator, so it cannot reach the set even when
 * somebody passes the wrong `-PemulatorSerial`.
 *
 * It drives adb itself rather than wrapping AGP's task because `ANDROID_SERIAL`, the only way
 * to narrow AGP's device list, is read from the **Gradle daemon's** environment and not from
 * the shell that started the build. A daemon started earlier, without it, would silently run on
 * both devices, and there would be nothing in the output saying so.
 */
tasks.register("emulatorTest") {
    group = "verification"
    description =
        "Runs app/src/androidTest on the googletv1080p emulator, and never on the television."
    dependsOn("assembleDebug", "assembleDebugAndroidTest")

    val adb = adbPath
    val outputs = layout.buildDirectory.dir("outputs/apk")
    val serial = emulatorSerial
    val onlyClass = emulatorTestClass
    val logger = this.logger

    doLast {
        if (!serial.startsWith("emulator-")) {
            throw GradleException(
                "REFUSED: '$serial' is not an emulator. This task installs and drives an app, " +
                    "and the household television must never be driven by it. " +
                    "Pass -PemulatorSerial=emulator-NNNN.",
            )
        }

        val adbBinary = adb.get()
        val appApk = outputs.get().file("debug/app-debug.apk").asFile
        val testApk = outputs.get().file("androidTest/debug/app-debug-androidTest.apk").asFile
        for (apk in listOf(appApk, testApk)) {
            if (!apk.isFile) throw GradleException("No APK at ${apk.absolutePath}.")
        }

        val (stateCode, state) = runAdb(adbBinary, listOf("get-state"), timeoutMinutes = 1)
        if (stateCode != 0 || state.trim() != "device") {
            throw GradleException(
                "The emulator '$serial' is not attached. Start the googletv1080p AVD first. " +
                    "adb said: ${state.trim()}",
            )
        }

        for (apk in listOf(appApk, testApk)) {
            val (code, output) = runAdb(adbBinary, listOf("install", "-r", "-t", apk.absolutePath))
            if (code != 0 || output.contains("Failure")) {
                throw GradleException("Installing ${apk.name} on $serial failed:\n$output")
            }
        }

        val instrument = mutableListOf("shell", "am", "instrument", "-w")
        if (onlyClass != null) {
            instrument += listOf("-e", "class", onlyClass)
        }
        instrument += "local.youtubetv.test/androidx.test.runner.AndroidJUnitRunner"

        val started = System.currentTimeMillis()
        val (_, report) = runAdb(adbBinary, instrument)
        val seconds = (System.currentTimeMillis() - started) / 1000.0

        logger.lifecycle(report.trim())
        logger.lifecycle("Instrumentation on $serial took %.1f seconds.".format(seconds))

        // `am instrument` answers 0 whatever the tests did, so the report is what says.
        if (!report.contains("\nOK (") && !report.startsWith("OK (")) {
            throw GradleException("Instrumentation tests failed on $serial. The report is above.")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    // Draws the key screen's QR code. Build ticket 64. Encoding a QR code by hand is Reed-Solomon
    // and eight masks, and this is a small pure Java library with nothing under it.
    implementation(libs.qrcodegen)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    // The instrumentation layer. It runs on the `googletv1080p` emulator and never on the
    // household television. `docs/agents/testing.md` says which kind of test belongs here.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // `androidx.compose.ui:ui-test-manifest` is deliberately **not** here. It is a manifest and
    // nothing else, and it declares its Activity exported, which would put an empty screen any
    // app could start into the debug APK that is installed on the household television.
    // `app/src/debug/AndroidManifest.xml` declares the same Activity, not exported, and says why.
}
