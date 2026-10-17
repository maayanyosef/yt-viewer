# Which kind of test belongs where

This project has **two** test layers. Every agent is told "put each rule behind a seam and
unit-test it on the JVM", which is right and is only half the instruction. This file is the
other half.

Build ticket 28 added the second layer on 2026-09-21, after a night in which seven real defects
were found on a screen by a person or an agent looking at it, and none of them could have failed
any of the 1,045 JVM tests.

## The two layers

| Layer | Where | Runs with | Costs |
| --- | --- | --- | --- |
| JVM unit tests | `app/src/test/kotlin/` | `./gradlew testDebugUnitTest` | about 30 s, no device |
| Instrumentation | `app/src/androidTest/kotlin/` | `./gradlew emulatorTest` | about 33 s, on the emulator |

**The first layer is the default and stays the default.** Almost every rule in this app is a pure
function behind a seam, and a pure function is cheaper, faster and clearer to test on the JVM.
Nothing in `app/src/test/` is to be moved into `app/src/androidTest/`.

## What belongs on the JVM

Anything that can be written as a function with no Compose and no Android import. The seams that
already exist are the pattern to copy:

- `ui/HomeModel.kt` and `ui/SourceScreenModel.kt` hold the whole flow of a screen and import no
  Android at all, so the flow is a JVM test with no device and no API quota.
- `data/SourceStore.kt` has an in-memory implementation the JVM tests drive.
- `ui/SourceSelection.kt`, `ui/CardMenu.kt`, `ui/GridNavigation.kt`, `ui/ChipPaint.kt` and the
  three `*Text.kt` files are rules and words, never drawing.

If a rule can be moved behind a seam, move it. A JVM test is worth more than an instrumentation
test of the same rule, because it is faster and because it says where the rule lives.

## What belongs in `androidTest`

Only the four things Compose does that a pure function cannot describe. Every one of them cost
this project real time in tickets 19, 21, 24, 27, 31, 33, 36, 37 and 38.

1. **Focus.** Compose moves focus by itself whenever the node holding it leaves composition, and
   the nearest focusable it finds is measured in pixels on the screen, not in lines of a panel.
   That behaviour does not exist off a device.
2. **Keys.** Which node a key reaches, and whether something consumed it first. A preview key
   event is delivered only down the **ancestor chain of the focused node**, which is why a guard
   written on a panel that is the focused node's sibling is dead code that reads as correct.
3. **Measurement.** What text really measures, at the real density and the real system text
   size, with the real font. A dp budget written on the JVM is a model of the layout and can be
   wrong; see the note below.
4. **Composition identity.** Whether a node survives a change to the data behind it. A lazy list
   rebuilds an item whose key changed, and a rebuilt node is a new node, so the highlight it was
   holding is gone.

Anything else drawn on a screen, colour, wording, spacing and order, is settled by a screenshot
on the household television and by a rule in a `*Text.kt` file. Do not write an instrumentation
test for it: it would be slow, it would need a picture to judge, and the JVM test is already
there.

### The fifth thing, and it is not Compose at all: real SQLite

Added 2026-09-21, by the review of the review of build tickets 40 and 42.

**Some rules live in SQL, and there is no SQLite on the JVM in this project.** `SourceStore`
has two implementations and a rule can be written in either one. `InMemorySourceStore` covers
most of them, and its promise is that a test passing against it is testing the rule and not the
SQL. Three kinds of rule break that promise, because the fake cannot express them:

- a rule that turns on a real transaction, such as reading rows before a delete inside the same
  transaction that does the delete;
- a rule that depends on SQL's own semantics, such as `= 0` not matching NULL, or a comparison
  against a column whose affinity decides the answer;
- a column that can hold NULL where the Kotlin field cannot, which is every column a migration
  added without a backfill.

`app/src/androidTest/kotlin/local/youtubetv/data/ReplaceVideosNewlyListedTest.kt` is the first
of these. It opens a real `YouTubeTvDatabase` on a file of its own, never the app's
`youtube-tv.db`, through the `databaseName` parameter that exists for exactly this.

**The JVM test is still written, and it is not redundant.** That file's six cases are written
twice on purpose, once against the fake and once against SQLite. The fake's promise is only as
good as the last rule somebody remembered to copy into it, and it was false for this very rule
between commit `2838f1c` and 2026-09-21 with nothing going red.

**Commit an instrumentation test even when you cannot run it.** A session forbidden to touch
the television is also forbidden `./gradlew emulatorTest`. Write the test, compile it with
`./gradlew assembleDebugAndroidTest`, which builds the APK and touches no device, and say in
the hand-back and in the file's own KDoc that it has never been run.

## The JVM budget and the real layout are not the same thing

`RailCardLayoutTest` on the JVM checks that the rail card's fixed height holds everything under
its picture at font scale 1.3. It adds up the styles' declared `lineHeight` values.

`RailCardFontScaleTest` in `androidTest` measures the same card on a real screen, and it found
that the budget is wrong about **how** the card fails. Put the height back to the 318dp ticket
21 rejected and the date does not spill past the card's background at all: a `Column` of a fixed
height hands its last child only the room that is left, so Compose squeezes the date's own line
instead, from 20dp to 19dp. Both tests are kept. The budget says a later session spent room the
card does not have, and the measurement says the household would really see it.

## Running them

```
export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/Android/jdk-17}"   # your JDK 17
./gradlew testDebugUnitTest          the JVM layer
./gradlew emulatorTest               the instrumentation layer
./gradlew emulatorTest -PemulatorTests=local.youtubetv.ui.MetaLineTest    one class
```

**`emulatorTest` is not `connectedDebugAndroidTest`, and the difference matters.** AGP's own task
installs on and drives **every** attached device, and the household television is attached over
the network for the whole of a working session. `emulatorTest` names one serial, defaults it to
`emulator-5554`, and refuses any serial that does not start with `emulator-`, so it cannot reach
the set even when somebody passes the wrong `-PemulatorSerial`. Narrowing AGP's own task with
`ANDROID_SERIAL` does not work here, because AGP reads that from the **Gradle daemon's**
environment and not from the shell that started the build.

**AGP's own tasks refuse to run at all**, and that is not belt and braces. Adding this source set
is what made them real: before it they existed with nothing to run. `connectedAndroidTest`,
`connectedDebugAndroidTest`, `connectedCheck` and `installDebugAndroidTest` now stop with a
message pointing here, before they touch a device. `installDebug` is left alone, because putting
the app on the set is what `tools/tv/tv.sh install` does on purpose.

The emulator is `googletv1080p`, and it is the right device for this on purpose: 1920x1080 at
320dpi is the household television's own geometry, so a card measures the same number of dp on
both.

**The debug APK gains two Activities that draw nothing of their own, and nothing else.** An instrumentation test runs inside
the app under test, so the Activity `createComposeRule()` puts a composition into has to be
declared in the app's own manifest. `app/src/debug/AndroidManifest.xml` declares it, not exported
and debug only. The usual artifact for this, `androidx.compose.ui:ui-test-manifest`, is
deliberately not used, because it declares the same Activity **exported** and the debug APK is
what is installed on the household television.

**The second is `KeyEventBackActivity`, added by build ticket 54.** It is the same empty Activity
with `android:enableOnBackInvokedCallback="false"`, also not exported, and it counts the presses
of `BACK` that reach `onBackPressed`. Use it for any test of whether a key handler lets `BACK`
through.

A test that only needs an overlay closed, and then checks where the highlight went, may stay on
the plain Activity and call `onBackPressedDispatcher.onBackPressed()`.
`HomeCloseFocusTest`, `OverlayFocusTest` and `SourceCloseFocusTest` do that, and that is correct
for them, because they test focus and not the key route.

- On the household TCL, API 34, `BACK` is only a key event. A Compose key handler that consumes
  it stops it, and no `BackHandler` runs.
- On this emulator, API 36, the key event still reaches Compose. But the system also runs
  `BackHandler` on the key down, by a second route that does not depend on what Compose did
  with the key. A test there can pass while the household is stuck.
- `KeyEventBackActivity` leaves only the key-event route, so the emulator behaves like the TCL.
  Press `BACK` with `Instrumentation.sendKeyDownUpSync`. A key sent with `performKeyPress` stops
  inside Compose and never reaches `Activity.onKeyUp`.
- Assert `keyRouteBacks` on the Activity as well. It counts the presses that took the key-event
  route, so a later Android that ignored the attribute would fail the test instead of passing it.

**Instrumentation tests never touch the household television.** They install, clear and drive an
app, and the household's Watch log is on that set and cannot be fetched again.

**And they never call the YouTube Data API.** Both screens take their whole state as a
parameter, so `app/src/androidTest/kotlin/local/youtubetv/ui/TestScreens.kt` hands them made-up
Sources and videos. The `search.list` quota is 100 calls a day and is the tightest limit in the
project.

## Robolectric was considered and not added

Build ticket 28 weighed it, because it runs on the JVM and so would run in a plain `./gradlew`
run, which matters for a project with no CI. It is not worth it here:

- **It would not have caught the defect that made the case for this layer.** Robolectric draws
  with a stub font unless native graphics are turned on, so what text measures under it is not
  what it measures on the set. The one test in this suite that found something new,
  `RailCardFontScaleTest`, is a measurement test.
- **The emulator is already up.** `googletv1080p` runs for the whole of a working session beside
  the television, and the whole instrumentation suite costs about 30 seconds on it. Robolectric's
  saving is a device this project already has.
- **It is a third runtime to keep working.** Android's focus search, `BackHandler` and the lazy
  lists' key handling would each have to behave under it, and where it differed nobody would know
  which of the two was telling the truth.

Reopen this if the emulator stops being available, or if a CI machine ever appears.
