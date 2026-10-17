# The ticket subagent brief

The template every ticket subagent is given, in every wave, including a wave of one.

Write the brief from this file and never from memory.
A brief rebuilt from memory loses the television rule first, and that rule is the one whose failure is invisible.

Copy the whole of **The brief** below into the subagent prompt.
Replace `NN` with the ticket number and `PATH` with the ticket's file path.
Add anything the ticket needs on top. Never remove a line.

---

## The brief

You own exactly one ticket: **`PATH`**. Work only that ticket.

### Read first, in this order

1. `CLAUDE.md` at the repository root.
2. `docs/agents/issue-tracker.md`. It owns the ticket conventions, the statuses, and what a closing note must contain.
3. `docs/agents/domain.md` and `CONTEXT.md`. The glossary's `_Avoid_` lines are binding.
4. `docs/agents/triage-labels.md`.
5. The effort's `spec.md`, if it has one.
6. Your own ticket, `PATH`.

**The spec wins over the ticket.** If the two disagree in a way the spec does not settle, stop and report it.

### Where you work

The dispatch tells you: the main checkout, or your own git worktree.

- **One ticket at a time: the main checkout.** A worktree buys nothing when nobody else is writing, and it costs a merge.
- **Two or more tickets at once: one worktree each.** Two agents editing one checkout collide.

The television lock is shared by every worktree, so either is safe from the television's side.
The lock lives in the main checkout's `tools/tv/work/`, found through `git rev-parse --git-common-dir`.

**One case is left, and it is yours to close when it applies.**
Two agents that share a checkout **and** a `CLAUDE_CODE_SESSION_ID` look the same to the lock.
If you are one of two agents in the same checkout, put `TV_TICKET=NN` in front of every `tv.sh` call:

```
TV_TICKET=NN bash tools/tv/tv.sh keys "22"
```

The working tree is clean when you start. Leave it clean when you finish.

### How to build it

- Set `Status: claimed` in your ticket and commit that before you start.
- Commit each coherent step as you go, with explicit paths. Do not save the work for one commit at the end.
- Put each rule in a pure function or a model class with no Android import, and unit-test it on the JVM. Match the shape already in the code. `HomeModel` and `SourceScreenModel` are the pattern: no Android import, so the whole flow is a JVM test with no device and no quota.
- **That is the default layer and not the only one.** `app/src/androidTest/`, run with `./gradlew emulatorTest`, is for the four things Compose does that no pure function can describe: focus, keys, measurement and composition identity. It runs on the `googletv1080p` emulator and never on a television. Read `docs/agents/testing.md` before you decide a screen defect cannot be tested.
- **Run `/code-review` when the code is done, and act on what it finds.** It is not optional. A ticket may not close as `resolved` or `needs-device` until the review has run and every finding is fixed or turned into its own ticket. Verify each finding against the real code and data before acting on it. Not all of them hold.

Build and test, with `JAVA_HOME` exported in the same command, because a tool shell may not inherit it:

```
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleDebug testDebugUnitTest
```

### Driving the television

**`bash tools/tv/tv.sh` is the only way to press a key on the television.**
The address comes from `tools/tv/device.properties`. `CLAUDE.md` section 7 says how to create it.

The set goes into standby when it is left alone, and **nothing over the network can undo standby**: `adbd` stops listening and only the physical remote brings it back.
A key sent to a set on its way into standby can be swallowed to wake it.
That is not a visible failure. The screenshot afterwards is bright and shows the wrong state.
`tv.sh` wakes the set before every interactive subcommand.

**Before your first press, check the keepalive and start it if it is not running:**

```
bash tools/tv/keepalive.sh status
bash tools/tv/keepalive.sh start
```

`start` is idempotent. The keepalive is not tied to the lock: `lock` does not start it and `unlock` does not stop it.

**A live pid is not evidence that the keepalive works.** A loop stuck in a hung `adb` call looks healthy to `kill -0`.
Measure it: `bash tools/tv/tv.sh shell "dumpsys power | grep lastUserActivityTime="` falls back to a small number after a poke.

```
bash tools/tv/tv.sh lock NN           take the set
bash tools/tv/tv.sh install PATH.apk  install
bash tools/tv/tv.sh launch            wake, then start the app
bash tools/tv/tv.sh keys "20 20 23"   wake, then send those keyevents in ONE adb call
bash tools/tv/tv.sh hold 23           wake, then one long press
bash tools/tv/tv.sh shot NAME         wake, screenshot, and shrink it for reading
bash tools/tv/tv.sh burst NAME 12     wake once, then 12 pictures back to back
bash tools/tv/tv.sh shell "..."       passthrough, no wake. For logcat and database reads
bash tools/tv/tv.sh unlock NN         give it back. The keepalive keeps running
```

Rules that cost time when they were learned:

- **Take the lock before the first press and release it when you are done.**
- **Do not pipe `tv.sh lock`, `tv.sh unlock` or `keepalive.sh start` into anything.** A background child holds the pipe open and the command hangs.
- **`tv.sh hold` is two deliveries, not one.** `input keyevent --longpress` sends the press, then one repeat about 450 ms later. An action with a repeat gate does its thing twice. Nothing that grows with hold time leaves its first step.
- **Read a screenshot from the `-small.png` copy**, 960 px wide.
- **The first launch after an install is slow**, because the system compiles the app. A screenshot before the first frame looks exactly like a crash.
- **`tv.sh burst` cannot catch anything shorter than a couple of seconds.** A picture costs about 1.5 s over adb. For a short window, take one `screencap` at a measured delay, or record with `screenrecord` and cut the video with `ffmpeg`.
- **The television logs when a key landed, and no screenshot can.** `tv.sh shell "logcat -d -v time" | grep publishKeyEvent` prints the target window and a `deliveryTime`.
- **Prefer a logged number to a number read off a picture.** The app log tag is `YouTubeTv`. Read it with `tv.sh shell "logcat -d -s YouTubeTv:*"`, after `tv.sh shell "logcat -G 32M"`, because a busy system log overflows its buffer.
- **Never turn the television's wifi or airplane mode on or off.** `adb` reaches the set over that wifi. Test offline behaviour on the emulator.
- **When an emulator is also running, every raw `adb` call needs `-s`.** `tv.sh` does this for you.

### Verifying

**Verify your own ticket yourself.** Install, launch, press, screenshot, and read the screenshot. Judge the result.

- **Tick only what you verified.** An emulator screenshot is not the television.
- **Verify a data change by diffing, not by looking.** Copy the database before and after and compare row by row.
- **`watch_state` is not materialised.** A Bulk mark range is applied at query time, so a query that reads `watch_state` alone undercounts the watched videos.
- If the television is unreachable, verify on the `googletv1080p` emulator, set `Status: needs-device`, and name exactly which criterion is left and why.

### Finishing

- Write the closing notes `docs/agents/issue-tracker.md` describes.
- Set the final `Status:` and commit.
- Update the ticket's line in the effort's `map.md` to match.
- **Release the television lock.**

You may edit another ticket's markdown when your ticket teaches something that ticket needs: a finding, a changed assumption, a new blocker. Say so in your report.
Writing another ticket's **code** is taking a second ticket and is not allowed.

### Report back

- What landed, and the commit hashes.
- Every criterion left unticked, each with its reason.
- Every other ticket's file you edited, and why.
- Anything that needs a person: a genuine choice, or a physical act such as holding the remote. Give exact numbered steps for a physical act. **Do not hand back a check you could have made over `adb`.**

### Stop and report instead of guessing

- The build or the tests fail and you cannot fix it.
- A `/code-review` correctness finding you cannot fix.
- The ticket and the spec disagree and the spec does not settle it.
- The YouTube Data API returns `quotaExceeded`.
- Anything needing a decision only the project owner can make.

### The API quota

The `search.list` allowance is 100 calls a day, and it is the tightest limit in the project.
Spend it only when the ticket needs it. Say in your report how many calls you spent.
