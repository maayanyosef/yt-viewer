---
name: drive
description: "Drive the build tickets wave by wave, unattended, and stop with one consolidated report."
disable-model-invocation: true
---

Drive this repo's build tickets from the **frontier** forward, one fresh agent context per
ticket, until there is nothing left to do without the user.

Read `docs/agents/issue-tracker.md` first. It owns the ticket conventions, the statuses, and
what a closing note must contain. This skill owns only the driving.

## Why this does not break "one ticket per agent context"

That rule exists so each ticket gets clean context and its own written record. Both survive
here: every ticket still runs in its own subagent and still writes its own closing notes. What goes away is the user starting each one by hand.

The rule still binds inside a ticket. A subagent **implements only the ticket it was given**.

It may still **edit another ticket's file**, and should, when building one ticket teaches
something a later ticket needs: a finding, a changed assumption, a newly discovered blocker.
Write that into the other ticket's markdown and say so in the report. Editing a ticket's
description is keeping the record straight; writing its code is taking a second ticket.

## The shape of a run

A **wave** is the set of tickets whose blockers are all finished, so they can run at once.
Waves are computed, never guessed:

```
python .claude/skills/drive/frontier.py .scratch/<effort>
```

A dependency chain is usually thinner than it looks, so many waves hold a single ticket. A
one-ticket wave is a normal result, not a sign the plan is wrong, and never a reason to widen
a wave by ignoring an edge.

An argument limits how many waves to run: `/drive 1` runs the first wave and stops. Use it to
cap the blast radius when something about the run is unproven. With no argument, run every
wave.

## Steps

### 0. Start the keepalive, before anything else

```
bash tools/tv/keepalive.sh start
```

Then leave it running for the whole run. It is **not** tied to the lock: `tv.sh lock` does not
start it and `tv.sh unlock` does not stop it. The separation exists because a run of one agent at a time would leave the set unprotected in the gap at every handover, and
nothing over the network can undo standby. Never stop it mid-run.

Check it by measurement, not by pid. A loop stuck in a hung `adb` call looks healthy to
`kill -0`.

### 1. Plan, and show the plan

Run `frontier.py`. Report the waves, how many tickets the run covers, and which criteria will
need a decision from the user rather than a check the run can make itself.

Then ask the user to approve the plan. **This is the only approval in the run.** It replaces one
approval per ticket, which is the point of the skill.

### 2. Run each wave

Dispatch one subagent per ticket in the wave, with the brief below.

- A wave of one: dispatch it in the working directory.
- A wave of more than one: give **each** subagent its own git worktree.

Two agents editing the same file collide. Use a worktree whenever a wave is wider than one
ticket.

**A worktree is safe from the television's side since ticket 34, and was not before.** The lock
used to live in the caller's own directory, so agents in three worktrees held three private locks
and excluded nobody, and that once destroyed a household's watch history. It now lives in the main
checkout's directory, found through `git rev-parse --git-common-dir`, and ownership is a caller
id rather than a directory. So the rule above is a rule about merge collisions only.

**The one case the lock still cannot separate** is two agents sharing a checkout *and* a
`CLAUDE_CODE_SESSION_ID`. If a wave ever runs two agents in one checkout, tell each to prefix
every call with `TV_TICKET=NN`.

Wait for the whole wave before starting the next. A later wave's blockers are only finished
once the earlier wave has merged and its tests pass.

**A wave is not cut by `frontier.py` alone.** That script reads `Blocked by:` lines, so it
cannot see two tickets that will write the same file. Look at what each ticket's "What to
change" section names and split the wave further when two would collide. Say so when reporting
the plan, because the split will not match what `frontier.py` printed.

### 2b. Patrol the wave, every ten minutes, until it lands

**Start `tools/tv/patrol.sh` in the background the moment the wave is dispatched.** Dispatching
agents and not arming the patrol is an incomplete dispatch.

```
bash tools/tv/patrol.sh 120 380000 600
```

It polls every two minutes and wakes this session **every ten minutes whether or not a rule
tripped**. The heartbeat is not optional and it is not a reason, it is a schedule.

**Start it in a command of its own, with nothing else in that command.** Use the harness's own
background mechanism, the one that notifies you when the process exits. **Never a bare `&` inside
a command that also does something else.**

That is not tidiness. A bare `&` child dies with the foreground command that spawned it, and
because it was never a tracked background task **no completion notice ever arrives**. The only
thing that would tell you the patrol has stopped is the patrol. It is the one failure here that
is silent, and it is silent by construction.

It has happened: a restart was folded into a command that also read the database.
The patrol was dead for over three hours before anybody noticed, and four tickets ran unwatched.

**After starting it, confirm you were told it is running in the background. If you did not see
that confirmation, it is not running.**

Every wake is a full round, in this order:

1. **Restart `patrol.sh`.** First action, before judging the snapshot, before messaging an
   agent, before answering anything. Reading a snapshot and not restarting it is the same as
   turning the monitoring off. It has lapsed that way when the notice arrived bundled with other tool results.
2. **Go through every live agent, not only the one the snapshot named.** For each: is it still
   making progress or is it hanging, has it committed, has it ticked what it verified, is it
   repeating the same failing command.
3. Act on what that shows. One message to a stuck agent costs seconds. Not sending it once cost an hour, when an agent ran with six modified files uncommitted, zero of thirteen
   criteria ticked, and four screenshots whose verdicts existed only in its context.

A heartbeat with nothing tripped is a normal result, not a wasted wake. A rule only catches
what somebody thought to check for; the heartbeat catches the rest.

`tools/tv/README.md` describes what the snapshot holds and how to silence finished agents.

### 3. Land each ticket

When a subagent reports back:

1. Merge its worktree branch, if it had one.
2. Run the project's test and build commands. Read them from the repo rather than from memory,
   and export any environment variable the build needs in the same command.
3. Record anything still open, and why.

Resolve a merge conflict by **intent**, tracing each side to why it changed, never by picking
lines. If the intent is unclear, that ticket stops.

Stop after the wave limit the argument set, if it set one.

### 4. Stop, and report once

Finish with the report described under **The report**, below.

## The subagent brief

**`docs/agents/subagent-brief.md` is the brief. Copy it whole into every subagent prompt.**

Write it from that file, never from memory. A brief rebuilt from memory loses the television rule first, and that rule is the one
whose failure is invisible: a press sent to a set going into standby is swallowed, and the
screenshot afterwards is bright and shows the wrong state.

The file also carries what this section used to say, plus everything later runs learned. What
follows is the summary, not the brief:

- Read first: `docs/agents/issue-tracker.md`, `docs/agents/domain.md`, `CLAUDE.md`,
  `CONTEXT.md`, the behaviour spec named in `docs/agents/issue-tracker.md`, and its own
  ticket. **The spec wins over the ticket.**
- Set `Status: claimed` and commit that before starting.
- Build it test-first with `/tdd`. Put each rule in a pure function or a model class with no
  Android import, and unit-test it on the JVM. Find that shape in the codebase and match it rather than inventing a new
  shape.
- Run `/code-review` when the code is done, and act on what it finds.
- **Verify it yourself.** Drive the television over `adb`, or the `googletv1080p` emulator when the set is unreachable. Install, launch, inject the key presses,
  screenshot, and read the screenshot. Judge the result rather than describing it for someone
  else to judge.
- Write the closing notes `docs/agents/issue-tracker.md` describes.
- Set the final `Status:` and commit.
- Report: what landed, every criterion left unticked with its reason, every later ticket whose
  file it edited and why, and every merge risk.

## Stop conditions

Two kinds. This distinction decides whether the run continues.

**Record and carry on.** The ticket is finished; one criterion is still open.

- **The television is unreachable.** Verify on the emulator, set `Status: needs-device`, and
  name the criterion. The next run clears it once the set is on. This needs nothing from the user beyond turning the television on.
- **The criterion is a matter of taste**, such as whether the household likes a layout. That is
  a real choice, so it goes to the user under **Decide this**, with a screenshot and a
  recommendation.

**Tick only what was verified.** An emulator screenshot is not the television.

**Stop this ticket and say so.** Carry on with the rest of the wave; do not start a later wave
that depends on the stopped ticket.

- The test or build command fails after the merge.
- A `/code-review` correctness finding the subagent cannot fix.
- A merge conflict whose intent is unclear.
- The ticket and the spec disagree in a way the spec does not settle.
- Anything needing a decision only the user can make.

**Stop the whole run.** Report immediately and wait.

- The working tree is dirty before a wave starts.
- Two waves in a row stopped.
- The YouTube Data API returns `quotaExceeded`.

## The report

**Ask the user only where there are options to choose between.** Everything checkable over
`adb` is already checked by the time the report is written.

Handing back a check the run could have made is a defect. When something is genuinely theirs,
a choice or a physical act such as pressing a remote, give exact numbered steps. They should never have to work out what to do.

Three sections. Leave out any that is empty.

### What landed

One line per ticket: number, title, final `Status:`, commit hash.

### Decide this

Only genuine choices. One block each:

- The question, in one sentence.
- The options, and what each costs.
- **A recommendation**, and why.
- What is blocked until it is decided.

Attach the screenshot for anything about appearance.

### Then run this

The single next command, ready to paste. Say which decisions above have to be made first, and
which can wait.

## Quota

The YouTube Data API has a daily pool that resets at midnight Pacific Time, and `search.list`
draws on a separate, much smaller bucket. Both numbers, and the cost of a full walk, are in
the spec's metadata section, if the effort has a spec. Read them there rather than from memory.

Clearing the app's data forces a fresh walk and spends that cost again, so clear it only when
a ticket genuinely needs one. Report every `search.list` call, because that bucket is the
tightest limit in the project.

## Recovering a half-finished run

Every ticket commits its own work, and a claimed ticket carries `Status: claimed`, so a run
that dies part-way leaves the repo consistent. Re-run `frontier.py`: it reports claimed tickets
and leaves them alone.

Clear a stale claim by setting that ticket's status back to `ready-for-agent`, once you have
checked no other session is working it.
