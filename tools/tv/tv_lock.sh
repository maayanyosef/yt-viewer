#!/usr/bin/env bash
# One television, several agents. Take this lock before touching it.
#
#   bash tv_lock.sh acquire TICKET          # waits, then prints ACQUIRED
#   bash tv_lock.sh release TICKET          # prints RELEASED
#   bash tv_lock.sh release TICKET --force  # take it from an agent that is gone
#   bash tv_lock.sh status
#   bash tv_lock.sh paths                   # where the lock and the shots live
#
# `mkdir` is atomic, so two agents cannot both win.
#
# THE LOCK IS REPOSITORY-WIDE. It used to sit in `work` beside this script,
# which inside a git worktree is a private directory, so three agents in three
# worktrees held three private locks and excluded nobody. `tv_paths.sh` now puts
# it in the main checkout's `tools/tv/work`. See ticket 34 and that file.
#
# WHEN A LEASE IS BROKEN. Not by its age. A lease is broken only when the OWNER
# has been silent for STALE: `acquire` creates a heartbeat file inside the lock
# and every interactive subcommand the owner runs touches it again. Taking the
# set from a live agent is what cost tickets 05 and 07 a session each, and age
# alone cannot tell a working agent from a dead one.
#
# The keep-alive cannot answer that question any more. It is one loop per
# session now, started once and left running whoever holds the lock, so it says
# nothing about any one agent. See keepalive.sh.

. "$(cd "$(dirname "$0")" && pwd)/tv_paths.sh"

# 90 minutes of SILENCE from the owner, not 90 minutes of holding. The first
# value was 25 minutes of holding, which broke ticket 07's lease while it was
# legitimately watching a video: playback verification is quiet for long
# stretches. Ticket 05 then took the set mid-session and each agent installed
# its build over the other's. Measuring silence instead of age means an agent
# that is working keeps its set however long the ticket takes.
STALE="${TV_LOCK_STALE:-5400}"
# The television in tools/tv/device.properties. Set TV_DEVICE to an adb serial,
# such as emulator-5554, to drive the emulator instead. See tv_paths.sh, which
# also puts the SDK's adb on PATH.
DEV="-s $TV_DEVICE"
action="$1"
who="${2:-unknown}"
force="${3:-}"

# The panel blanks after 10 minutes with no input, and a screenshot of a blank
# panel is black. KEYCODE_WAKEUP only wakes; unlike POWER it can never put the
# set to sleep, so it is safe to send when the screen is already on.
wake_screen() {
    local tries=0
    while [ "$tries" -lt 5 ]; do
        state=$(adb $DEV shell "dumpsys power | grep -m1 mWakefulness=" 2>/dev/null | tr -d ' \r')
        case "$state" in
        *Awake*)
            echo "AWAKE"
            return 0
            ;;
        esac
        adb $DEV shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
        sleep 2
        tries=$((tries + 1))
    done
    echo "NOT AWAKE after 5 tries: ${state:-no answer from the set}"
    return 1
}

case "$action" in
acquire)
    waited=0
    while :; do
        if mkdir "$LOCK" 2>/dev/null; then
            # The heartbeat FIRST. Between the mkdir and this touch the lock
            # exists with nothing in it, and a waiter sampling that moment must
            # not read it as abandoned. `lock_silent_for` falls back to the
            # lock directory's own mtime for exactly that window.
            touch "$LOCK_BEAT"
            # What ownership is decided on. See tv_paths.sh.
            printf '%s' "$CALLER_ID" > "$LOCK_CALLER"
            # TICKET EPOCH SHOT_WORK, for a human reading `status`.
            echo "$who $(date +%s) $SHOT_WORK" > "$LOCK/owner"
            echo "ACQUIRED by $who after ${waited}s"
            echo "  lock: $LOCK"
            wake_screen
            exit 0
        fi

        owner=$(lock_owner_ticket)
        started=$(lock_owner_started)
        silent=$(lock_silent_for)
        age=0
        case "$started" in
        '' | *[!0-9]*) ;;
        *) age=$(( $(date +%s) - started )) ;;
        esac

        if [ "$silent" -gt "$STALE" ]; then
            # A move, not a delete. `mv` of a directory onto a fresh name is
            # atomic, so when two waiters both decide to break, exactly one
            # wins and the loser falls through to the sleep instead of deleting
            # the winner's brand new lock a moment later.
            dead="$LOCK.dead.$$.$(date +%s)"
            if mv "$LOCK" "$dead" 2>/dev/null; then
                echo "BREAKING a lease held by '$owner' for ${age}s: it has sent nothing to the set for ${silent}s"
                rm -rf "$dead"
            fi
            # No `continue`. Falling through keeps `waited` moving, so a move
            # that keeps failing, which on Windows means somebody holds a
            # handle inside the directory, still reaches the timeout instead of
            # spinning for ever.
        elif [ "$waited" -eq 0 ]; then
            echo "waiting: '$owner' has held it for ${age}s and last used it ${silent}s ago"
        fi

        if [ "$waited" -ge 2400 ]; then
            echo "TIMEOUT waiting for '$owner', held for ${age}s, last used ${silent}s ago"
            echo "If you are certain that agent is gone, take it with:"
            echo "  bash tools/tv/tv.sh unlock $owner --force"
            exit 1
        fi

        # A broken lease is retried at once; a live one is waited out.
        if [ -d "$LOCK" ]; then
            sleep 20
            waited=$((waited + 20))
        else
            waited=$((waited + 1))
        fi
    done
    ;;
release)
    if [ ! -d "$LOCK" ]; then
        echo "NOTHING TO RELEASE: the television was already free"
        exit 0
    fi
    owner=$(lock_owner_ticket)
    if ! caller_holds_lock; then
        # The ticket name alone is not proof. Two agents can carry the same
        # ticket number, for instance when a stuck one is retired and replaced,
        # and the replacement must not drop the lock the first one is still
        # using.
        if [ "$force" != "--force" ]; then
            echo "REFUSED: the lock is held by '$owner', and this shell is not its owner."
            echo "  the owner locked it from : $(lock_owner_work)"
            echo "  this shell's scratch is  : $SHOT_WORK"
            if [ "$owner" = "$who" ]; then
                echo "The ticket name matches, but the caller does not. Naming the ticket is"
                echo "not proof of ownership."
            fi
            echo "If you are certain that agent is gone, take it with:"
            echo "  bash tools/tv/tv.sh unlock $owner --force"
            exit 1
        fi
        echo "FORCED: taking the lock from '$owner', who locked it from $(lock_owner_work)"
    fi
    rm -rf "$LOCK"
    echo "RELEASED by $who"
    ;;
wake)
    # Every interactive path comes through here: `tv.sh` before keys and
    # launch, and `shot.sh` and `burst.sh` before a picture. Its whole job is to
    # make sure the panel is lit before a press that would otherwise be
    # swallowed to light it.
    #
    # It does NOT touch the keep-alive. The keep-alive is one loop per session,
    # started by hand and left running, and nothing on the lock path reaches it
    # in either direction. See keepalive.sh.
    #
    # This subcommand itself does not require the lock. It is the mechanism the
    # guarded subcommands call after they have already checked; `tv.sh wake` is
    # the guarded way in for a person.
    wake_screen
    ;;
status)
    if [ -d "$LOCK" ]; then
        echo "HELD: $(lock_owner_ticket), for $(lock_owner_held_for), last used $(lock_silent_for)s ago"
        echo "  from: $(lock_owner_work)"
        echo "  lock: $LOCK"
    else
        echo "FREE"
        echo "  lock: $LOCK"
    fi
    ;;
paths)
    echo "SHOT_WORK=$SHOT_WORK"
    echo "SHARED_WORK=$SHARED_WORK"
    echo "LOCK=$LOCK"
    ;;
*)
    echo "usage: tv_lock.sh {acquire|release|wake|status|paths} TICKET"
    exit 2
    ;;
esac
