# YT Viewer

Rules for Claude Code sessions in this repository.
Each rule is short and says why it exists.
`README.md` is the human guide. This file is the agent guide.

## 1. What the project is

YT Viewer is a Google TV app that walks YouTube channels and playlists in publish-date order and remembers what was watched.

- Kotlin and Jetpack Compose for TV. Native UI, no web UI.
- The package and the application id are both `local.youtubetv`.
- Playback is a `WebView` hosting the YouTube IFrame Player API.
- Metadata is the YouTube Data API v3, with the user's own key.
- Storage is one SQLite database on the device. There is no server.
- The code is GPL-3.0. The licence text is `LICENSE`.
- This repository is public and changes flow one way. Each developer keeps changes in their own fork, and pull requests are not merged back. Problems are reported as GitHub Issues on the shared repository, `https://github.com/yt-viewer/yt-viewer/issues`. Releases, with the APK, are at `https://github.com/yt-viewer/yt-viewer/releases`.

The code is under `app/src/main/kotlin/local/youtubetv/`:

| Folder | What it holds |
| --- | --- |
| `apikey/` | The Key screen, the Key page, its small web server, the key check |
| `data/` | The database, the Watch log, Bulk mark ranges, ordering, landings |
| `player/` | The `WebView` player, its HTML page, remote keys while playing |
| `refresh/` | When and how a Source is checked for new videos |
| `ui/` | Every screen. Each `*Model.kt` holds a screen's flow and imports no Android |
| `walk/` | Reading a whole Source from the API |
| `youtube/` | The Data API client and JSON parsing |

## 2. The glossary is binding

`CONTEXT.md` is the glossary. Read it before you name anything.

- Use each term exactly as `CONTEXT.md` defines it: in code, tests, comments, tickets and every word shown on the television.
- Every term has an `_Avoid_` line. The words on that line are banned. Write **Source**, never subscription, feed or list.
- A concept missing from the glossary is a signal. Either you are inventing language, or the glossary has a gap. Say which in your report.

`docs/agents/domain.md` says how to read the glossary and the ADRs.

## 3. The ADRs

Architecture decisions are in `docs/adr/`.

- Read the ADRs that touch the area you change before you change it.
- `docs/adr/0001-watch-model.md` is the data model and the hardest decision to reverse. The Watch log is append-only and replayable. Read it before any change in `data/`.
- If your change contradicts an ADR, stop and say so. Name the ADR and the reason to reopen it.
- Write a new ADR in `docs/adr/` when a decision is hard to reverse, affects more than one module, or was chosen over a real alternative. Number it after the last one.

## 4. Build and test

Set `JAVA_HOME` to a JDK 17 in the same command as Gradle. A tool shell may not inherit it.

```
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleDebug testDebugUnitTest
```

`local.properties` must name the Android SDK with `sdk.dir=`. It is in `.gitignore`.

There are two test layers. `docs/agents/testing.md` decides which test goes where. Read it before you write a test.

- **JVM unit tests are the default.** `app/src/test/kotlin/`, run with `./gradlew testDebugUnitTest`. Put each rule in a pure function or a model class with no Android import, and test it here. `ui/HomeModel.kt` and `ui/SourceScreenModel.kt` are the pattern to copy.
- **Emulator tests are only for four things** that a pure function cannot describe: focus, keys, measurement and composition identity. `app/src/androidTest/kotlin/`, run with `./gradlew emulatorTest` on the `googletv1080p` emulator.
  - `emulatorTest` drives only a serial that starts with `emulator-`. It never reaches a real television.
  - Android's own `connectedAndroidTest` tasks are switched off on purpose. They install on every attached device, including a television that holds a household's watch history.
  - When you cannot run the emulator, still write the test and compile it with `./gradlew assembleDebugAndroidTest`.

`README.md`, section "For developers", says how to create the `googletv1080p` emulator.

## 5. The YouTube Data API key

- The app asks for the key on screen, on the key screen. It saves the key in the app's private storage on the device.
- **No key is ever committed, and no key is built into an APK.** No build file reads a key.
- Never print a key in a log, a test name, a ticket or a commit message. The app logs only that a key arrived and what the check said.
- The quota is the user's own: 10,000 units a day, and a separate allowance of 100 `search.list` calls a day. `search.list` is the tightest limit. Spend it only when the task needs it, and report every call.

To test the key screen on the emulator:

1. Clear the app's data or install it fresh, so no key is saved. The key screen is then the first screen.
2. Use `Type the key with the remote` and type the key into the emulator.
3. The Key page does not open from a real phone, because the emulator's network is private to the computer.
4. Open it in the computer's own browser instead: run `adb -s emulator-5554 forward tcp:18642 tcp:8642`, then open `http://localhost:18642/<token>`, with the token from the address on the emulator's screen.
   - `localhost` is a secure context, so `Share the address` appears there. On a phone over the home network it does not. Test the phone case on a real phone.

## 6. Stremio as a reference

Stremio (`github.com/Stremio`, open source) has solved problems this app meets. Its Android TV app plays YouTube through the IFrame Player in a `WebView` and drives a TV UI with the remote. Read the relevant file before you design or debug one of these:

- YouTube playback, player parameters, progress polling, error mapping: `stremio-video/src/YouTubeVideo/YouTubeVideo.js` and `stremio-video/src/StremioVideo/selectVideoImplementation.js`.
- D-pad focus and navigation, TV layout, rows and cards: `stremio-web`.

Two cautions:

- Stremio's UI is a web app in a `WebView`. This app is native Compose. Copy behaviour and algorithms, never the UI code.
- Stremio's Android shell is closed source, and its old YouTube wrapper predates YouTube's 2025 referer check (player error 153). Confirm every player detail against Google's IFrame Player API documentation.

## 7. Driving your television over adb

`tools/tv/` drives a real Google TV for verification. `tools/tv/README.md` describes every script.

### Setup

1. `cp tools/tv/device.properties.example tools/tv/device.properties` creates your own copy.
2. Set `address=` in `tools/tv/device.properties` to your television's address. The file is in `.gitignore`.
3. `TV_DEVICE=emulator-5554` in front of a command drives the emulator instead.

### Rules, each with its reason

- **`bash tools/tv/tv.sh` is the only way to press a key on the television.** It wakes the set before every interactive command. A key sent to a set that is going into standby is swallowed to wake it. The screenshot afterwards is bright and shows the wrong state, so you would judge a screen that never happened.
- **Start the keepalive before your first press, and leave it running while you drive the set:** `bash tools/tv/keepalive.sh start`. A television in standby stops `adbd`, and nothing over the network can wake it. Only the physical remote can. `start` is idempotent.
- **Check the keepalive by measurement, not by its pid.** A loop stuck in a hung `adb` call still has a live pid. Run `bash tools/tv/keepalive.sh status`, then `bash tools/tv/tv.sh shell "dumpsys power | grep lastUserActivityTime="`. The idle time falls back to a small number after each poke.
- **Stop the keepalive when the session ends:** `bash tools/tv/keepalive.sh stop`. Holding the set awake overnight wastes power and keeps the screen on.
- **Take the lock before the first press and release it when you are done:** `bash tools/tv/tv.sh lock NN` and `bash tools/tv/tv.sh unlock NN`, with `NN` the ticket number. Two agents pressing keys on one set confirm each other's dialogs. The lock is shared by every worktree of the checkout.
- **Never pipe `tv.sh lock`, `tv.sh unlock` or `keepalive.sh start` into another command.** A background child keeps the pipe open and the command hangs.
- **Never clear the app's data on your television.** The Watch log on the set is a household's record of what it watched. Nothing can fetch it again. Test a fresh install on the emulator.
- **Verify a data change by copying the database before and after and comparing row by row.** A screenshot misses one wrong tick among hundreds.
- **Never turn the television's wifi off.** `adb` reaches the set over that wifi. Test offline behaviour on the emulator.
- **Read screenshots from the small copy.** `tv.sh shot NAME` writes a full-size picture and a 960 px copy. The small copy is enough for judging.
- **The first launch after an install is slow**, because the system compiles the app. A screenshot before the first frame looks exactly like a crash. Wait for it.
- **`tv.sh hold` is a short long-press, not a held key.** It sends a press and one repeat about half a second later. Anything that grows with hold time cannot be tested with it. Only a person holding the remote can test that.
- **A logged number beats a number read off a picture.** The app's log tag is `YouTubeTv`. Read it with `bash tools/tv/tv.sh shell "logcat -d -s YouTubeTv:*"`.

### Traps specific to one television model

These were measured on one TCL Google TV set. Check them on yours before you rely on them.

- The `GUIDE` key and the `MENU` key are taken by the television's own firmware. The app never receives them. Nothing may depend on either.
- `sendevent` was refused, because the adb shell user is not in the `input` group. A genuine held key could not be injected.

## 8. Tickets

Work tickets are local markdown files under `.scratch/`. Never run `gh` or `glab` for them. GitHub Issues on the shared repository are for problem reports from users, not for work tickets.

`.scratch/` is in `.gitignore`. Each developer keeps their own, and it is never committed.

To raise a ticket:

1. Pick or create an effort folder, `.scratch/<effort>/`. An **effort** is one folder per body of work, such as `.scratch/my-first-change/`.
2. Create `.scratch/<effort>/issues/` and `.scratch/<effort>/map.md` if they do not exist yet.
3. Write the ticket at `.scratch/<effort>/issues/NN-<slug>.md`. `NN` is the next free number in that folder, from `01`. `<slug>` is a few words of the title, in lowercase, joined by `-`.
4. Start it with a `# NN: Title` line, a `**Status:** ready-for-agent` line, and a `**Blocked by:**` line when other tickets must finish first.
5. Write what to build, why, and a list of acceptance criteria as `- [ ]` lines.
6. Add one line for it to `map.md`: `- [NN Title](issues/NN-<slug>.md) - **ready-for-agent**: one sentence.`

The rules:

- **One ticket per agent context.** Never resolve a second ticket in the same context. Editing another ticket's markdown to record a finding is fine. Writing its code is taking a second ticket.
- The state is a `Status:` line near the top. Blocking is a `Blocked by:` line with ticket numbers.
- Set `Status: claimed` and commit it before you start.
- The statuses are `ready-for-agent`, `claimed`, `resolved`, `needs-device` (code done, a check on the real television left), `needs-decision` (code done, a choice for the project owner left) and `deferred`. `docs/agents/triage-labels.md` has the full table.
- **Every ticket gets a `/code-review` before it closes.** `/code-review` is a Claude Code command, not a file in this repository. A ticket may not reach `resolved` or `needs-device` until every finding is fixed or raised as its own ticket. Verify each finding against the real code before you act on it.
- A finished ticket carries closing notes:
  - each acceptance criterion, ticked only if it was verified, with the evidence under it,
  - "Things found while building",
  - "Decisions taken while building", each with its reason,
  - "Known gaps", each naming the ticket that inherits it.
- Never tick a criterion you could not verify. An emulator screenshot is not the television.

The rules are in full in `docs/agents/issue-tracker.md`. The statuses are in `docs/agents/triage-labels.md`. The brief for a ticket subagent is `docs/agents/subagent-brief.md`. `/drive`, in `.claude/skills/drive/`, runs many tickets wave by wave, one subagent each.

## 9. Commits

- Commit each coherent step as you go. Do not save the work for one commit at the end. A crash loses only uncommitted work.
- Commit with explicit paths, for example `git commit -m "..." -- path1 path2`, so you never commit somebody else's staged change.
- Never commit `keystore.properties`, `local.properties`, `tools/tv/device.properties` or a keystore. They are in `.gitignore`.
- `secrets.properties` is also in `.gitignore`. Old builds read the API key from it, and no build reads it now. If one exists on your machine, it may still hold a real key, so never commit it.

## 10. Writing style

For documents, tickets, comments and reports. The readers include developers who read English as a second language.

- Name what you refer to. Name files by their full path, and functions and commands by their exact name.
- One idea per sentence. Short sentences.
- When a sentence lists three or more items, use a bullet list.
- Use plain, literal words. A standard technical term is fine.
- Show, do not describe. Give the exact command to type, and quote new file content.
- Number steps that must run in order. One command per step, with its reason.
- Write numbers you measured, and say how you measured them.
