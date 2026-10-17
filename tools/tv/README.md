# Driving the television from an agent

These scripts exist because a Compose for TV screen can only be judged on the set, and an
agent that talks to `adb` directly gets confident wrong answers rather than visible failures.

`tv.sh` is the only entry point. Never call `adb` directly from a ticket agent.

**Which television.** The scripts read its address from `tools/tv/device.properties`, which is
in `.gitignore`, so every developer keeps their own. Create it once:

    cp tools/tv/device.properties.example tools/tv/device.properties

Then set `address=` in it to the television's address. `port=` is optional and defaults to
5555. `TV_DEVICE=emulator-5554` in front of a command drives the emulator instead. The scripts
find `adb` on `PATH`, or in the SDK that `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `sdk.dir` in
`local.properties` names.

**Start the keep-alive once, at the beginning of the session, before anything else.** It is
not part of the lock and nothing else starts it.

    bash tools/tv/keepalive.sh start         # once a session, then leave it alone

    bash tools/tv/tv.sh lock ticket08        # take the set; waits if another agent holds it
    bash tools/tv/tv.sh install app/build/outputs/apk/debug/app-debug.apk
    bash tools/tv/tv.sh launch               # wakes the panel first
    bash tools/tv/tv.sh keys "20 20 23"      # wakes first, all presses in one adb call
    bash tools/tv/tv.sh hold 23              # wakes first, one long press: the card menu
    bash tools/tv/tv.sh shot grid            # wakes first, shrinks to 960 wide for reading
    bash tools/tv/tv.sh shot counts --crop 0 0 1920 400
    bash tools/tv/tv.sh burst handover 12    # wakes once, then 12 pictures back to back
    bash tools/tv/tv.sh shell "logcat -d -s YouTubeTv | tail -20"   # passive, no wake
    bash tools/tv/tv.sh unlock ticket08      # the keep-alive keeps running

    bash tools/tv/tv.sh status               # who holds the set
    bash tools/tv/tv.sh paths                # where the lock and the screenshots live

## The two problems it solves

**The panel blanks after 10 minutes with no input.** A D-pad press sent to a sleeping panel
can be consumed to wake it instead of doing its job. Send eight presses after a pause and you
may get seven, or none. Nothing looks broken: the screenshot afterwards is bright and simply
shows the wrong state, and an agent then judges a criterion against it. Every interactive
subcommand wakes the set first and refuses to run if it cannot.

`install` sends no wake on purpose, because the package manager does not need the display.
`shell` sends no wake either, so logcat does not light up the television at night.

**Two agents share one television.** `lock` is a `mkdir` on `tv.lock`, so it is atomic.

## The lock is repository-wide, and the interactive subcommands enforce it

Both halves of that sentence were untrue until ticket 34, and the cost was household data. On
2026-09-21 three agents in three git worktrees held three private locks on one set, and one
agent's key press landed on another agent's open `Mark everything unwatched?` confirmation and
confirmed it. ADR-0001 makes the Watch log append-only, so there is no way back.

- **One lock for the whole repository.** `tv_paths.sh` puts it in the **main checkout's**
  `tools/tv/work/tv.lock`, found through `git rev-parse --git-common-dir`, which resolves to
  the one shared `.git` from inside any worktree. `tv.sh paths` prints where it landed.
- **Ownership is a caller id, not a directory.** The lock records `CALLER_ID`, which is the
  caller's scratch directory and its Claude session id together. The directory catches two
  agents in two worktrees; the session id catches two agents in **one** checkout, which is the
  shape this run uses now that worktrees are banned. Two agents sharing a checkout **and** a
  session id are still indistinguishable, and `TV_TICKET` is the way out.
- **Screenshots stay per caller**, in the calling checkout's own `tools/tv/work`. Two agents
  writing `menu-full.png` into one directory would read each other's pictures, which is a
  quieter failure than the one above.
- **`keys`, `hold`, `shot`, `burst`, `launch`, `install` and `wake` all refuse** unless this
  shell holds the lock. The refusal names the owner, how long it has held the set and the
  directory it locked from, and **sends nothing**.
- **`shell` stays open**, because reading logcat while another agent works is useful and
  changes nothing. It does refuse a command that injects: `input keyevent` and its siblings,
  `monkey`, and `am start`. It matches the injecting verbs and not the bare word, so
  `dumpsys input` still reads freely. That is a net under an honest mistake, not a fence.
- **A lease is broken by the owner's silence, not by its age.** `acquire` writes a heartbeat
  inside the lock and **every** `tv.sh` subcommand the owner runs touches it again, `shell`
  included. Reading logcat for an hour is not being absent, and ticket 08's two-hour unattended
  stop is verified with no key sent at all. Nothing is broken until `TV_LOCK_STALE` seconds
  have passed with the owner running nothing. An agent that is working keeps its set however
  long the ticket takes, which is what tickets 05 and 07 lost to the old fixed-age rule.
  - The break is a `mv` and not a delete, so when two waiters both decide to break, exactly one
    wins and the loser waits instead of deleting the winner's brand new lock.
- **`unlock` needs more than the ticket's name.** Two agents can carry the same ticket number,
  for instance when a stuck one is retired and replaced, so a caller that is not the owner is
  refused. `bash tools/tv/tv.sh unlock <owner> --force` is the documented way to take the set
  from an agent that is gone, and it says `FORCED` when it does.
- **`TV_WORK` still redirects everything**, lock and screenshots together, so an emulator
  session or a second set is entirely self-contained. `TV_SHARED_WORK` moves only the lock.

`bash tools/tv/test_tv_lock.sh` proves all of it without touching the set: 122 checks over two
callers, one lock, a real throwaway git worktree, and a fake `adb` that records what would have
been sent. Run it whenever anything in `tools/tv` changes.

## Standby, and the keep-alive

**The set has one idle state and it is standby.** The device suspends and `adbd` stops
listening, so nothing over the network can bring it back. Port 5555 is closed and there is not
even an ARP entry to aim a wake-on-LAN packet at. **Only the physical remote undoes standby.**

So `keepalive.sh` sends one bare modifier key every 240 seconds.

**It is started once, at the beginning of a session, and left running for the whole session.**
It is not tied to the lock in either direction: `tv.sh lock` does not start it and `tv.sh
unlock` does not stop it.

```
bash tools/tv/keepalive.sh start     # once a session. Safe to run again.
bash tools/tv/keepalive.sh status    # running, WEDGED, or not running
bash tools/tv/keepalive.sh stop      # the ONLY thing that stops it
```

`keepalive.sh stop` also leaves a marker, so `status` says `stopped on purpose`. `start` removes
the marker again.

`patrol.sh` still trips on a keep-alive that was stopped on purpose, unless it is started with
`PATROL_KEEPALIVE_OPTIONAL=1`. Commit `2fd5cfa` made that choice on 2026-09-21. Set the variable
whenever the keep-alive is stopped on purpose, for instance for ticket 08's two-hour stop or while
the household is watching the set:

```
PATROL_KEEPALIVE_OPTIONAL=1 bash tools/tv/patrol.sh 60 400000 600
```

A keep-alive that is not running for any other reason, including a wedged one, still trips.

It used to be started by `lock` and stopped by `unlock`. That left the set unprotected in
every gap between one agent unlocking and the next one locking, and a queue of a dozen agents
has a dozen such gaps. One of them let the set reach 653,798 ms of idle on 2026-09-21. The two were fully decoupled the same day.

**`keepalive.sh stop` is the only thing that stops it.** There is one reason to: the app's own
two-hour unattended stop (ticket 08) is reset by any key, so the keep-alive has to be stopped
before verifying that rule, and started again afterwards.

The pid and the log live in the **repository-wide** directory beside the lock, so an agent in
any checkout sees the loop that is already running instead of starting a second.

**The loop has nothing supervising it.** It is one backgrounded subshell. If it is killed, if
its parent shell is reaped, or if it wedges inside an `adb` call that never returns, it stops
poking and nothing notices. The first sign would be the set in standby, needing a person with
the remote. Three things answer that.

- **The poke has a 30 second timeout.** Without one, a hung `adb` call parks the loop for
  ever. The pid stays alive, so every check says "running", and the set drifts into standby
  anyway. That is the worst failure of the three because it looks healthy.
- **`start` is idempotent and self-healing.** It never starts a second loop alongside a
  healthy one. It kills and replaces one that is dead or wedged, and says which it was.
- **Health is judged by the log's mtime, never by the pid.** The loop appends a line on every
  pass whether the poke worked or not, so no line for two and a half intervals means it is not
  poking, whatever `kill -0` says.

`keepalive status` has three answers: `running`, `not running`, and `WEDGED`, which prints how
long it has been silent and the command that repairs it. `patrol.sh` reports it too, and trips
a rule when it is not running.

The loop's stdin, stdout and stderr are detached when it is spawned. Without that the child
holds the caller's stdout open, so piping `keepalive.sh start` into anything, or capturing it
in `$(...)`, hangs until the loop dies.

## Screenshots and context

A 1920x1080 screenshot costs an agent about 2,800 tokens. `shot` writes both sizes and an
agent reads only the small one, at about 700.

- `work/NAME-full.png`, for a person to look at, and for keeping as evidence. An agent must never read it.
- `work/NAME-small.png`, 960 wide, which is what the agent reads.

`shrink_shot.py` also warns when a picture is almost black, which means the panel was asleep
and the verification is worthless.

### When one screenshot is not enough

`burst` takes many pictures back to back, waking the panel once instead of before every one.
It exists for a moment too short to aim at: ticket 08's video handover, where YouTube's
related-video wall is painted when a video ends and the app replaces it.

It is about one picture every half second to a second and a half over the network, not a video
recording. **A wall it catches proves a defect. A burst that catches none is evidence and not a
proof**, which is why ticket 08's wall criterion was finally settled by a person watching the panel on 2026-09-20, and the answer was that the wall does flash.

**Read the file sizes before you read the pictures.** Ticket 17's burst of 26 was 21 pictures
of nothing in particular and 5 of the moment. A flat screen compresses to a fraction of a
screen with video on it, so `ls -l` finds the window for free:

| What is on the panel | Size of the full-size PNG |
| --- | --- |
| The opaque handover cover, or a card menu over a dimmed grid | 22 to 36 KB |
| A video frame, or the grid with its thumbnails | 1.1 to 2.4 MB |

Read the small copies of the few pictures that matter and leave the rest. Twenty-six pictures
read in full would cost an agent about 18,000 tokens of context and say nothing new.

**A big picture's mtime is not its capture moment.** A 2 MB screencap takes seconds to come
over the network and the mtime is when the transfer finished; a 33 KB one arrives at once. So
a large frame whose mtime falls inside the window was very likely captured before it. Ticket
17 nearly misread one that way, and what settled it was the app's own transport strip inside
the picture, reading `1:09 / 1:29` on a video that ends at `1:29`.

## Watching the agents

`patrol.sh [POLL] [TOKENS_HIGH] [HEARTBEAT]` prints one snapshot and exits, so a harness that
re-invokes on exit turns it into a monitor. It exits 10 when a rule trips and 0 on the
heartbeat. It reports every live agent's token count and silence, every worktree's
uncommitted count and how much of its ticket is ticked, who holds the television and how long
ago they last used it, and whether the keep-alive is running.

**Always pass a heartbeat, and treat every wake as a full round.** This was set on 2026-09-21. `patrol.sh 120 380000 600` is the shape: poll every two minutes, token ceiling
380,000, wake every ten minutes **whether or not a rule tripped**.

On every wake, in this order:

1. **Restart `patrol.sh`.** First action, before judging the snapshot, before messaging an
   agent, before answering anything. Reading a snapshot and not restarting it is the same as
   turning the monitoring off. It lapsed twice that way on 2026-09-20, both times because the
   notice arrived bundled with other tool results.
2. **Go through every live agent, not only the one the snapshot named.** For each: is it still
   making progress or is it hanging, has it committed, has it ticked what it verified, is it
   repeating the same failing command.
3. Act on what that shows.

A heartbeat with nothing tripped is a normal result, not a wasted wake. A rule only catches
what somebody thought to check for; the heartbeat catches the rest.

Put the ids of agents from earlier waves in `patrol.ignore`, one per line. Their transcripts
stay on disk and a large finished one otherwise trips the token rule on every restart.

`agent_context.sh` is the same token report on its own. `tail_agent.py` prints an agent's
recent messages without inlining its screenshots.

## Settings

- `TV_WORK` moves both directories: the screenshots and the shared lock. It defaults to
  `tools/tv/work`, which is not in git. Set it to drive a second set or the emulator in a
  session entirely of its own.
- `TV_SHARED_WORK` moves only the shared directory, the one holding the lock and the
  keep-alive. It wins over `TV_WORK`. `test_tv_lock.sh` uses it to stay off the real lock.
- `TV_TICKET` names the caller, and when it is set it must match the lock's owner. It is the
  only way to tell apart two callers that share both a checkout and a session id. Optional.
- `TV_LOCK_STALE` is how many seconds of silence from the owner break a lease. Default 5400.
- `CLAUDE_CODE_SESSION_ID` is not set by these scripts. It is part of the caller id when the
  harness provides it, which is what tells two agents in one checkout apart.
- `TV_REPO` tells `patrol.sh` which checkout to watch. It defaults to this repo.
- `CLAUDE_SUBAGENTS` names the session's subagent transcript directory. Without it the newest
  one under the project wins, which is right whenever only one run is live.
- `DEV` in `tv_lock.sh` is the television's address.

## Keys worth knowing

- **`66` is the keyboard's own action key.** On the add screen, `Find it` cannot be reached
  with `Down`, because the text field keeps the highlight. The path is
  `keyboardActions(onSearch)` in `ui/AddSourceScreen.kt`, which over `adb` is keycode 66.
  Two presses of `Down` and then `OK` do nothing at all and look like a broken build.
  Ticket 25 lost several minutes to this.
- **`tv.sh shell` will inject for a lock holder, so `input text` is how you fill a field.**
  The filter calls `require_lock` for an injecting command rather than refusing it outright,
  and only `monkey` and `am start` are refused on their own. So while you hold the set,
  `tv.sh shell "input text '@NASA'"` types into the add screen's field, which is the only
  practical way to fill it: there is no `text` subcommand and spelling a handle out in
  keyevents is not one. `tv.sh shell "am force-stop local.youtubetv"` is allowed for the same
  reason, and ticket 31 used it to stop the app during a Source's first walk. Both were found
  on 2026-09-21.
- **`23` is the centre button, and `tv.sh hold 23` is the card menu.** Ticket 19 opened it
  that way every time.
- **`tv.sh hold` is not a hold.** `input keyevent --longpress` delivers a repeat count of
  exactly 1, arriving with the same `eventTime` as the press. That is enough for
  `isHeld` in `ui/RemoteKeys.kt`, which is all it was written for. It is **not** a key held
  down for a measurable time, and nothing that repeats can be measured with it.
  - **A real hold cannot be injected on this television at all.** The only other way is
    `sendevent`, which lets Android's own repeat generator run, and it is refused: the `adb`
    shell user is not in the `input` group on this TCL.

        $ tv.sh shell "sendevent /dev/input/event6 1 106 1; ..."
        sendevent: /dev/input/event6: Permission denied

    `/dev/input/event6` is `virtual-remote` and does carry `KEY_LEFT` and `KEY_RIGHT`, so the
    device is right and only the permission is missing.
  - A burst of separate presses is not a hold either. Both `HeldKeyGate` and the grid treat a
    repeat count of zero as a fresh press, on purpose.
  - Ticket 32 lost a television session to this. Anything whose behaviour changes with how
    long a key is held needs a person with the remote.

## Things that must never happen

- `adb shell pm clear local.youtubetv` deletes every walked Source and the household's Watch log. Nothing can fetch the Watch log again.
- Turning the television's wifi or airplane mode on or off.
- Changing `screen_off_timeout`, `sleep_timeout` or any `tcl_*` setting. It was decided on 2026-09-20 to leave the panel blanking as it is and let the scripts wake it.
