# YT Viewer

An app for Google TV that walks YouTube channels and playlists in order.
It remembers what you watched, and it takes you back to where you left off.

The shared repository is `https://github.com/yt-viewer/yt-viewer`.

This README has two parts.
The first part is for everybody who wants to install the app and use it.
The second part, from "For developers" on, is for people who want to build or change the code.

## Contents

1. [What the app is](#1-what-the-app-is)
2. [What you need](#2-what-you-need)
3. [Install the app on a Google TV with Downloader](#3-install-the-app-on-a-google-tv-with-downloader)
4. [Create your own YouTube Data API key](#4-create-your-own-youtube-data-api-key)
5. [Give the key to the app](#5-give-the-key-to-the-app)
6. [Using the app](#6-using-the-app)
7. [Privacy](#7-privacy)
8. [For developers](#8-for-developers)
9. [Working on the code with Claude Code](#9-working-on-the-code-with-claude-code)
10. [Troubleshooting](#10-troubleshooting)
11. [Problems, forks and the licence](#11-problems-forks-and-the-licence)

## 1. What the app is

The app runs on a Google TV.
You add YouTube channels and playlists to it.
The app calls each one a **Source**.

What the app does:

- It shows your Sources as a row of cards on its home screen.
- It lists the videos of one Source by publish date.
  - You choose **newest first** or **oldest first** for each Source.
- It remembers every video you played, how far you got, and when.
- It takes you to the **next unwatched** video of a Source with one press.
- It lets you jump to a year or a month of a long Source.
- It lets you mark many videos as watched in one action, for example everything you saw before you installed the app.
- It plays videos with YouTube's own embedded player.

What the app does not do:

- It does not replace the official YouTube app. Keep that app installed.
- It has no home feed and no recommendations.
- It does not search for single videos. It only searches for channels and playlists to add.
- It does not sign in to your Google account. It does not see your YouTube subscriptions or your YouTube history.
- It does not change the playback speed.

This app is **not** made by YouTube or by Google.
It is not connected to them, and they do not support it.
"YouTube" and "Google TV" are trademarks of Google LLC.
The app uses YouTube's public APIs with a key that you create yourself.

## 2. What you need

- A television with Google TV, or a Google TV streamer, connected to your home wifi.
- A phone or a computer on the same home wifi network as the television. A phone can scan the QR code. A computer is easier for creating the key.
- A Google account. Any free account works.
- A phone alone is enough. The Key page can send its own address from a phone to a computer.
- About 15 minutes.

## 3. Install the app on a Google TV with Downloader

The app is not in the Google Play Store.
You install it with a free app called **Downloader by AFTVnews**.

> - Downloader code: `5623701`
> - Download address: `https://github.com/yt-viewer/yt-viewer/releases/latest/download/yt-viewer.apk`
>
> Both always give the newest release, so the same code also installs an update.

### 3.1 Turn on developer mode on the television

Newer Google TV devices hide the setting that allows installing apps from outside the Play Store.
Developer mode makes it appear.

1. On the television, open **Settings**.
2. Open **System**.
3. Open **About**.
4. Scroll to the bottom, to **Android TV OS build**.
5. Press the OK button on it about seven times, until the television says **"You are now a developer!"**

### 3.2 Install Downloader

1. Open the **Google Play Store** on the television.
2. Search for **Downloader by AFTVnews**.
   - If it does not appear, search for **AFTVnews Downloader**.
3. Select it and choose **Install**.
4. Open **Downloader** when the install finishes.
5. Accept its first questions, such as the request for storage access.

### 3.3 Download and install YT Viewer

1. In Downloader, select the text field on the **Home** tab.
2. Type the Downloader code from the box above.
   - Without the code, type the Releases page address instead. Downloader opens the page in its browser. Under **Assets** of the newest release, select the file that ends in `.apk`.
3. Select **Go**.
4. Wait for the download to finish.
5. The television shows a security message. Select **Settings** in it.
6. On the **Install unknown apps** screen, select **Downloader** so that it shows **Allowed**.
7. Press the **BACK** button on the remote to return to the install question.
8. Select **Install**.
   - If no install question appears, open the **Files** tab in Downloader and select the downloaded file.
9. When the install finishes, select **Done**.
10. Downloader asks if you want to delete the downloaded file. Select **Delete**. You do not need it any more.
11. Open **YT Viewer** from the **Apps** row of the Google TV home screen.

### 3.4 When Google TV blocks the install

You may see one of these messages.

- **"Blocked by Play Protect"**
  1. Select **Details**.
  2. Select **Install anyway**.
- **"'Unknown Sources' is disabled"** or no security message at all
  1. Developer mode is not on yet.
  2. Do the steps of section 3.1 again.
  3. Then download the file again in Downloader.
- **"App not installed"** when you update
  - The new file was signed with a different key from the app that is already installed.
  - See "Updating to a new version" below.

### 3.5 Updating to a new version

1. Open Downloader.
2. Enter the same code or address again. It always points to the newest version.
3. Select **Install** when the television asks.
   - The television asks whether you want to install an update to the existing app. Select **Install**.

**Never uninstall the app before an update.**
Uninstalling deletes the app's data on the television.
That data is your Sources, your watch history and your API key.
An update installed over the old version keeps all of it.

## 4. Create your own YouTube Data API key

The app reads channel and playlist lists from the YouTube Data API.
Every user of the app brings their own key.
No key is built into the app.

**No billing account and no payment card are needed.**
The key is free.
Google gives every project a free daily quota, and the app never needs more.

The app shows these same steps on the Key page, which opens on a phone or a computer.
So you can also skip this section and follow the steps on the Key page.

### 4.1 Steps

Do these steps on a computer or on a phone browser.
The Key page can send its own address from a phone to a computer, with **Send it by email**, **Send it by WhatsApp** or **Copy the address**. See section 5.1.

1. Open <https://console.cloud.google.com/projectcreate>.
2. Sign in with your Google account.
3. If Google asks you to accept the terms of service, accept them.
4. In **Project Name**, type a name, for example `YT Viewer`.
5. Click **Create**.
6. Wait until the project exists. Check that the new project is selected at the top of the page.
7. Open <https://console.cloud.google.com/apis/library/youtube.googleapis.com>.
   - If the page shows something else, open **APIs & Services**, then **Library**, and search for `YouTube Data API v3`.
8. Click **Enable**.
9. Open the **Credentials** page: <https://console.cloud.google.com/apis/credentials>.
10. Click **Create credentials**.
11. Choose **API key**.
12. Restrict the key to YouTube.
    - If the form shows **API restrictions**:
      1. Choose **Restrict key**.
      2. Select **YouTube Data API v3**.
      3. Click **Create**.
    - If the key was created at once:
      1. On the Credentials page, click the key's name.
      2. Under **API restrictions**, choose **Restrict key**.
      3. Select **YouTube Data API v3**.
      4. Click **Save**.
    - Leave **Application restrictions** at **None**. The app runs on a television and cannot match a website or an Android restriction.
13. Copy the key. It is a long string that starts with `AIza`.

Keep the key private.
Anybody with the key can spend its daily quota.

### 4.2 The free daily quota

YouTube gives each project **10,000 units a day** for free.

- Reading one page of a playlist costs 1 unit.
- The app spends a few units each time it checks a Source for new videos.
- Adding a large channel for the first time costs more, because the app reads its whole list once. A channel with 5,000 videos costs about 200 units.
- Searching for a channel by name uses a separate allowance of 100 searches a day.

Normal use of one household stays far below the daily limit.
The quota starts again every day at midnight Pacific Time.

### 4.3 When the app reports a problem with the key

The app checks every key before it saves it.
The television and the Key page show the same sentence.

| The app says | What it means | What to do |
| --- | --- | --- |
| "Google does not know this key." | The key is wrong or incomplete. | Copy the whole key again from the Credentials page and send it again. |
| "The key is real, but YouTube Data API v3 is not enabled in its project." | Step 8 was not done, or it was done in a different project. | Do steps 7 and 8 in the key's project. Wait two minutes. Send the key again. |
| "The key's own restrictions refuse YouTube." | The key is restricted to a different API, or to a website or an app. | Open the key in the console. Under **API restrictions**, allow **YouTube Data API v3**. Set **Application restrictions** to **None**. |
| "The key is right and is saved, but its 10,000 units for today are used up." | The key works. Today's quota is spent. | Nothing. Sources update again after midnight Pacific Time. |
| "The television could not reach YouTube." | The television is offline. | Check the television's network and send the key again. |

## 5. Give the key to the app

The first time you open the app, it shows the key screen, **"Connect your YouTube API key"**.

### 5.1 With the QR code (recommended)

With a computer and no phone, type the address printed beside the QR code into a browser on a computer on the same home network.
Then do steps 6 to 9 on the computer.

1. Make sure the phone is on the same wifi network as the television.
2. Open the phone's camera and point it at the QR code on the television.
3. Open the link the camera shows.
   - The address looks like `http://192.168.x.x:<port>/<code>`. It is the television itself, on your home network.
   - If the phone cannot scan, type the address printed beside the QR code into the phone's browser.
4. The phone shows a page with the steps of section 4 and a field.
5. To do the steps on a computer instead, send the page's address to the computer:
   - Tap **Send it by email**, **Send it by WhatsApp** or **Copy the address**. Some phones also show **Share the address**.
   - Open the address on a computer on the **same home network** as the television. It does not work from any other network.
   - Then do steps 6 to 9 on the computer.
6. Paste the key into the field **Your API key**.
7. Tap or click **Send to the television**.
8. Wait a few seconds. The Key page and the television show the result.
9. When the key works, the television moves on to the home screen. You can close the Key page.

The Key page only works while the Key screen is open on the television. The same is true of an address you sent to a computer.

### 5.2 Typing the key with the remote

Use this when neither a phone nor a computer can open the Key page.

1. On the key screen, select **Type the key with the remote**.
2. Type the whole key with the on-screen keyboard. It starts with `AIza`.
3. Select **Check and save this key**.
4. To return to the QR code, select **Back to the QR code**.

### 5.3 Replacing or removing the key later

1. On the home screen, move the highlight to any Source card.
2. **Press and hold** the OK button on the remote. The Source menu opens.
3. Select **API key: replace or remove**.
4. To replace the key, send a new one with the QR code or with the remote. The new key replaces the old one.
5. To remove the key:
   1. Select **Remove the saved key**.
   2. Select **Yes, remove the key**.

Without a key the app cannot load or update any Source.
Your Sources and your watch history stay on the television.

When you have no Source yet, the first screen shows the line **API key: replace or remove** under **+ Add a Source**.

## 6. Using the app

### 6.1 Words the app uses

- **Source**: one YouTube channel or one playlist that you added.
- **Direction**: the order of a Source, **Newest first** or **Oldest first**. Always by publish date.
- **Watch**: the app's record that you played a video, how far you got and when.
- **API key**: your own YouTube Data API key. The app needs it to read Sources from YouTube.
- **Key screen**: the television screen that asks for the API key.
- **Key page**: the web page the television serves to your phone or computer while the Key screen is open.
- **Next unwatched**: the first video you have not watched, after the furthest point you reached in the Source.
- **Last played**: the video you played most recently in a Source.
- **Period**: a year or a month of a Source, used to jump through a long Source.
- **Bulk mark**: marking every video older or newer than a chosen one as watched, or as unwatched, in one action.

### 6.2 The home screen

The home screen is one row of Source cards.
The Source you played most recently is first.

Each card shows:

- the Source's name,
- its Direction and its number of videos,
- how many videos are unwatched,
- the Next unwatched video's picture, title and date.

What the keys do on the home screen:

- **Left** and **Right** move along the row.
- **OK** opens the Source at its Next unwatched video.
- **Press and hold OK** opens the Source menu. It has these lines:
  - **Open at Next unwatched**, **Open at Last played**, **Open at the newest video**
  - **Refresh this Source**, which asks YouTube for new videos now
  - **Rename**, which gives the Source your own name
  - **Remove this Source**
  - **API key: replace or remove**
  - **Mark everything unwatched**
- The last card, **+ Add a Source**, adds a new Source.

### 6.3 Adding a Source

Select **+ Add a Source**.
Then do one of these:

- Paste a YouTube link. A channel link, a playlist link or a video link all work.
- Type a channel's @handle, such as `@TED`.
- Type or say the name of a channel, and pick it from the results.

The app asks which Direction you want, then adds the Source.
It opens at once and fills as YouTube answers.

### 6.4 The Source screen

The Source screen shows the videos of one Source as a grid.
A row of buttons at the top holds every control:

- **Order: Newest first** or **Order: Oldest first** changes the Direction.
- **Jump to last played** moves to the video you played most recently.
- **Next unwatched** moves to the first unwatched video.
- **Period** opens a list of years or months. Select one to jump there.
- **Autoplay: On** or **Autoplay: Off** chooses whether the next video starts by itself.

What the keys do on the grid:

- The four arrow keys move the highlight.
- **Channel up** and **Channel down** page the grid by one screen.
- **OK** plays the video, from where you stopped.
- **Press and hold OK** on a video opens its menu:
  - **Play from start**
  - **Mark as watched** or **Mark as unwatched**, each with **Just this**, **This and all older** and **This and all newer**
  - **Jump to a Period**
- **BACK** returns to the home screen.

### 6.5 The player

| Remote key | What it does |
| --- | --- |
| **OK**, Play/Pause | Play or pause |
| **Left**, Rewind | Back 10 seconds. Hold it to go back faster |
| **Right**, Fast forward | Forward 10 seconds. Hold it to go forward faster |
| **Up** | Forward 5 minutes |
| **Down** | Back 5 minutes |
| **Channel up**, Next | Next video, in the Source's Direction |
| **Channel down**, Previous | Previous video, in the Source's Direction |
| **BACK** | Save the position and return to the grid |

The app saves your position while you watch.
A video counts as watched when it ends, or when less than 5% of it is left, capped at the last 60 seconds.
With **Autoplay: On**, the next video starts after a short countdown. Press **OK** to start it at once, or **BACK** for the list.

Some televisions keep the **MENU** and **GUIDE** buttons for themselves.
The app never needs them.

## 7. Privacy

- The API key is saved only on the television, in the app's private storage.
- The key travels from your phone or computer to the television over your home network, and nowhere else.
- Your Sources and your watch history are saved only on the television.
- The app sends requests only to YouTube: to the YouTube Data API for channel and playlist lists, and to YouTube's player for playback.
- The app has no account, no analytics and no server of its own.
- Uninstalling the app deletes all of its data from the television.

## 8. For developers

### 8.1 What the code is

- Kotlin and Jetpack Compose for TV. Native UI throughout.
- The package and the application id are both `local.youtubetv`.
- Playback is a `WebView` that hosts the YouTube IFrame Player API.
- Metadata comes from the YouTube Data API v3, with the user's own key.
- All storage is a SQLite database on the device.

Where the main parts are, under `app/src/main/kotlin/local/youtubetv/`:

| Folder | What it holds |
| --- | --- |
| `apikey/` | The Key screen, the Key page and its small web server, the key check |
| `data/` | The database, the Watch log, Bulk mark ranges, ordering and landings |
| `player/` | The `WebView` player, its HTML page, the remote keys while playing |
| `refresh/` | When and how Sources are checked for new videos |
| `ui/` | Every screen. The `*Model.kt` files hold each screen's flow with no Android import |
| `walk/` | Reading a whole Source from the API |
| `youtube/` | The YouTube Data API client and JSON parsing |

Other documents:

- `CONTEXT.md` is the glossary. The app's words have exact meanings.
- `docs/adr/` holds the architecture decisions. Read `docs/adr/0001-watch-model.md` before you change anything in `data/`.
- `docs/agents/testing.md` says which test belongs in which layer.

### 8.2 Build requirements

These versions are read from the build files.

| Tool | Version | Where it is set |
| --- | --- | --- |
| JDK | 17 | `jvmToolchain(17)` in `app/build.gradle.kts` |
| Gradle | 9.7.1, through the wrapper | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Android SDK platform to compile against | API 37, minor level 2 | `compileSdk` in `app/build.gradle.kts` |
| Target SDK | 36 | `targetSdk` in `app/build.gradle.kts` |
| Minimum SDK | 26 | `minSdk` in `app/build.gradle.kts` |

You do not install Gradle yourself. `./gradlew` downloads the right version.

Steps to prepare a computer:

1. Install a JDK 17. The JDK that Android Studio ships also works if it is version 17.
2. Install the Android SDK, for example with Android Studio.
   - In the SDK Manager, install the Android API 37 platform, **Android SDK Platform-Tools** and **Android Emulator**.
3. Create `local.properties` in the repository root with one line. Write the path with forward slashes:

   ```
   sdk.dir=C:/path/to/Android/Sdk
   ```

   On macOS or Linux, use the real path, such as `sdk.dir=/home/you/Android/Sdk`.
   The file is in `.gitignore`.
4. Set `JAVA_HOME` to the JDK 17 folder in the shell you build from.

### 8.3 Build a debug APK

```
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.
A debug build needs no key and no keystore.

### 8.4 Install it on a television over the network with adb

1. On the television, turn on developer mode as in section 3.1.
2. Open **Settings**, then **System**, then **Developer options**.
3. Turn on **USB debugging**.
4. Find the television's address in **Settings**, **Network & Internet**, then your wifi network.
5. On the computer, connect:

   ```
   adb connect 192.168.x.x:5555
   ```

6. The television asks whether to allow USB debugging from this computer. Select **Allow**. Tick **Always allow from this computer** first, if it is offered.
7. Install the APK:

   ```
   adb -s 192.168.x.x:5555 install -r app/build/outputs/apk/debug/app-debug.apk
   ```

8. Open the app from the television's **Apps** row.

If `adb connect` is refused on port 5555, look in **Developer options** for **Wireless debugging**. Where it exists, it shows the address and port to use.

The scripts in `tools/tv/` read the television's address from `tools/tv/device.properties`.
Create it once:

1. `cp tools/tv/device.properties.example tools/tv/device.properties` copies the example.
2. Edit `tools/tv/device.properties` and set `address=` to your television's address.

The file is in `.gitignore`, so your address is never committed.

**A debug APK and a release APK cannot update each other.**
They are signed with different keys.
Android refuses an update signed with a different key, and the only way past it is to uninstall, which deletes the app's data.

### 8.5 Run the tests

There are two layers. `docs/agents/testing.md` says what goes where.

The JVM unit tests are the default layer. They need no device:

```
export JAVA_HOME="/path/to/jdk-17"
./gradlew testDebugUnitTest
```

The emulator tests cover only focus, keys, measurement and composition identity.
They run on the `googletv1080p` emulator and never on a real television:

```
./gradlew emulatorTest
./gradlew emulatorTest -PemulatorTests=local.youtubetv.ui.MetaLineTest
```

`emulatorTest` drives only `emulator-5554` by default and refuses any serial that does not start with `emulator-`.
Android's own `connectedAndroidTest` tasks are switched off on purpose, because they install on every attached device, including a television.

### 8.6 Create the `googletv1080p` emulator

1. In the Android SDK Manager, install the system image **Google TV**, API 36, x86_64. Its package id is `system-images;android-36;google-tv;x86_64`.
2. Create the device from the `tv_1080p` hardware profile:

   ```
   avdmanager create avd -n googletv1080p -k "system-images;android-36;google-tv;x86_64" -d tv_1080p
   ```

   `avdmanager` may print a warning that it could not load `devices.xml`. The profile still applies.
3. Check `hw.lcd.width=1920`, `hw.lcd.height=1080` and `hw.lcd.density=320` in the device's `config.ini`, which is `~/.android/avd/googletv1080p.avd/config.ini`. On Windows, `~` is `%USERPROFILE%`.
4. Start it:

   ```
   emulator -avd googletv1080p -no-snapshot -no-audio
   ```

5. It appears as `emulator-5554`. Install the debug APK with `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.

To test the key screen on the emulator:

1. Start the app with no key saved. The key screen is the first screen.
2. Use **Type the key with the remote**, and type the key with the computer keyboard into the emulator.
   - The Key page opens on a phone or a computer on the same network as the device. The emulator's own network is private to the computer, so the QR code does not open from a phone.
   - The computer's own browser can open the emulator's page. Run `adb -s emulator-5554 forward tcp:18642 tcp:8642`, then open `http://localhost:18642/<token>`, with the token from the address on the emulator's screen.

### 8.7 Sign a release APK with your own keystore

A release build must be signed.
The keystore and its passwords are never committed.

1. Create a keystore. Keep the file and the passwords safe. You need the same keystore for every later update.

   ```
   keytool -genkeypair -v -keystore my-release.jks -alias my-key -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the repository root with these four lines:

   ```
   RELEASE_STORE_FILE=C:/path/to/my-release.jks
   RELEASE_STORE_PASSWORD=<store password>
   RELEASE_KEY_ALIAS=my-key
   RELEASE_KEY_PASSWORD=<key password>
   ```

   - `RELEASE_STORE_FILE` may be absolute, or relative to the repository root.
   - Use forward slashes. A backslash is an escape character in a `.properties` file.
   - `keystore.properties`, `*.jks` and `*.keystore` are in `.gitignore`.
3. Build:

   ```
   ./gradlew assembleRelease
   ```

4. The APK is `app/build/outputs/apk/release/app-release.apk`.

**Why an APK signed with a different key cannot update an installed one.**
Android remembers the certificate that signed the installed app.
It installs an update only when the update carries the same certificate.
So a friend who installed your release can only update from your releases.
Moving to a build signed by somebody else means uninstalling first, and uninstalling deletes the watch history.

## 9. Working on the code with Claude Code

`CLAUDE.md` in the repository root is the guide for Claude Code sessions.
It sets up:

- what the project is and where the code lives,
- the glossary in `CONTEXT.md`, which is binding,
- the build and test commands,
- how the API key works, and that no key is ever committed,
- how to drive your television over `adb` with `tools/tv/`, safely,
- the ticket tracker under `.scratch/`, one ticket per agent context, and a `/code-review` before every ticket closes,
- the commit rule and the writing style.

The folders it points to are `docs/agents/`, `docs/adr/`, `.claude/skills/` and `tools/tv/`.

## 10. Troubleshooting

### The computer cannot reach the television over adb

- Check that the computer and the television are on the same network.
- Check the address in `tools/tv/device.properties`. A router can give the television a new address after a restart. Give it a fixed address in the router to stop that.
- Run `adb connect 192.168.x.x:5555` again. Then run `adb devices`.
  - `unauthorized` means the television still waits for you to select **Allow** on its screen.
  - `offline` usually means the television is in standby. Wake it with its remote. Nothing over the network can wake it from standby.
- Check that **USB debugging** is still on in **Developer options**. Some updates turn it off.

### The install is blocked

See section 3.4.

### The Key page does not open on the phone or the computer

- The phone or the computer must be on the same network as the television.
  - A phone or a computer on mobile data, on a guest wifi, or on a different wifi band with client isolation cannot reach the television.
  - Turn off mobile data on the phone, or join the same wifi, and scan the code or type the address again.
- The page works only while the key screen is open on the television. Keep it open while you work on the phone or the computer.
- If the television left the key screen, the old address stops working. Open the key screen again and scan the new code, or type the new address.
- An address sent by email or WhatsApp follows the same rules. It works only on a device on the same network as the television, and only while the key screen is open.
- If nothing helps, use **Type the key with the remote**.

### A video does not play

The player shows one sentence for each YouTube player error, and "Press BACK to return to the list."

| The app says | What it means |
| --- | --- |
| "The owner does not allow this video to be played outside YouTube." | Player error 101 or 150. Watch it in the official YouTube app. |
| "This video has been removed, or is private." | Player error 100. |
| "YouTube does not know this video id." | Player error 2. |
| "This television's player could not play this video." | Player error 5, a problem in the television's own web player. Restart the television and try again. |
| "This app asked YouTube the wrong way. The video was not played." | Player error 153. This is a defect in the app. Please report it. |
| "YouTube could not play this video. Player error N." | Any other error. Check the television's internet connection and try again. |

### The app says the quota is used up

The key's 10,000 units for today are spent.
The app keeps working from what it already has.
Sources update again after midnight Pacific Time.

## 11. Problems, forks and the licence

### Reporting a problem

Report a problem as a GitHub Issue on this repository: `https://github.com/yt-viewer/yt-viewer/issues`.

Write in the issue:

- what you did, step by step,
- what you expected,
- what happened instead, with the exact words the app showed,
- your television's brand and model.

Never paste your API key into an issue. Issues are public.

### Changing the code

The repository is public. Anyone can fork it on GitHub with no invitation.

Changes flow one way only:

- This repository publishes new versions.
- You keep your own changes in your own fork.
- Pull requests are not merged back into this repository. Open an issue instead when you want to suggest something.

To take a new version into your fork, merge this repository's `main` branch into yours.

### Licence

The code is licensed under the GNU General Public License, version 3 (GPL-3.0).
The full text is in `LICENSE`.

Copyright (C) 2026 yt-viewer

In short, and the licence text is what counts:

- You may use, copy, change and share the code.
- When you share a changed version, or an APK built from it, you must share its source code under the same licence.
- There is no warranty.
