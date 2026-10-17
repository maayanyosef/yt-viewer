#!/usr/bin/env bash
# The one way to drive the television.
#
# The panel blanks after 10 minutes with no input. A key press sent to a
# sleeping panel can be swallowed to wake it instead of doing its job, which
# silently corrupts a verification: the screenshot afterwards looks fine and
# shows the wrong state. So every interactive subcommand here wakes the set
# first and refuses to run if it cannot.
#
# Every interactive subcommand also REFUSES TO RUN unless this shell holds the
# television lock, which is one lock for the whole repository. See tv_paths.sh
# and ticket 34: before that, three agents in three worktrees held three
# private locks, and one agent's press confirmed a Bulk mark on the household's
# Watch log, which cannot be undone.
#
# THE KEEP-ALIVE IS NOT PART OF THE LOCK. `lock` does not start it and `unlock`
# does not stop it. It is started once at the beginning of a session and left
# running for the whole session:
#
#   bash tools/tv/keepalive.sh start
#
# The only thing that stops it is `bash tools/tv/keepalive.sh stop`. Nothing
# over the network undoes standby, because `adbd` stops listening, so a set that
# reaches it needs a person with the physical remote. Tying the keep-alive to
# the lock left the set unprotected in every gap between one agent unlocking and
# the next one locking.
#
#   bash tv.sh lock TICKET            take the set (waits if another agent has it)
#   bash tv.sh unlock TICKET          give it back (--force if it is not yours)
#   bash tv.sh install PATH.apk       install (lock needed, no wake sent)
#   bash tv.sh launch                 wake, then start the app
#   bash tv.sh keys "20 20 23"        wake, then send those keyevents, one adb call
#   bash tv.sh hold 23                wake, then one long press (the card menu)
#   bash tv.sh shot NAME [--crop ...] wake, screenshot, shrink for reading
#   bash tv.sh burst NAME 12          wake once, then 12 pictures back to back
#   bash tv.sh shell "..."            passthrough, no wake (logcat, db reads)
#   bash tv.sh wake                   wake on its own
#   bash tv.sh status                 who holds the set
#   bash tv.sh paths                  where the lock and the screenshots live
#   bash tv.sh keepalive start        start the anti-standby timer, once a session
#   bash tv.sh keepalive status       is the anti-standby timer running
#   bash tv.sh keepalive stop         the only thing that stops it
#
# Key presses go in ONE adb call. Separate calls start a JVM per press on the
# television, which makes any timing measurement meaningless.

. "$(cd "$(dirname "$0")" && pwd)/tv_paths.sh"

S="$TV_TOOLS"
# The television in tools/tv/device.properties. Set TV_DEVICE to an adb serial,
# such as emulator-5554, to drive the emulator instead. See tv_paths.sh, which
# also puts the SDK's adb on PATH.
DEV="-s $TV_DEVICE"
PKG="local.youtubetv"

require_awake() {
    state=$(bash "$S/tv_lock.sh" wake)
    if [ "$state" != "AWAKE" ]; then
        echo "$state"
        echo "REFUSING: the panel is not awake, so this would give a false result."
        exit 1
    fi
}

action="$1"
shift 2>/dev/null

# Every subcommand, not only the guarded ones. A lease is broken by the OWNER's
# silence, and an owner reading logcat through `tv.sh shell` for an hour is not
# absent. Ticket 08's two-hour unattended stop is verified exactly that way,
# with no key sent at all, and 90 minutes of it would otherwise lose the set.
# `beat` does nothing unless this shell holds the lock.
beat

case "$action" in
lock)
    # Nothing here touches the keep-alive, on purpose. See the header.
    bash "$S/tv_lock.sh" acquire "$1"
    ;;
unlock)
    # `--force` takes the lock from an agent that is gone. Naming the ticket is
    # not proof of ownership, so without it a stranger is refused.
    bash "$S/tv_lock.sh" release "$1" "${2:-}"
    ;;
wake)
    require_lock "a wake"
    bash "$S/tv_lock.sh" wake
    ;;
install)
    # Installing needs no display, so this deliberately sends no wake. It does
    # need the lock: replacing the app under another agent's feet is exactly
    # what cost tickets 05 and 07 a session each.
    require_lock "an install"
    adb $DEV install -r "$1"
    ;;
launch)
    require_lock "a launch"
    require_awake
    adb $DEV shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1
    ;;
keys)
    require_lock "a key press"
    require_awake
    cmd=""
    for k in $1; do
        cmd="$cmd input keyevent $k;"
    done
    adb $DEV shell "$cmd"
    ;;
hold)
    # One long press. `--longpress` delivers a repeat count of 1, which is what
    # `isHeld` in ui/RemoteKeys.kt calls a hold, so the card menu and the grid's
    # held Up are both reachable from here. Its own subcommand rather than
    # `tv.sh shell "input keyevent --longpress 23"`, because `shell` sends no
    # wake and a hold swallowed to light the panel opens no menu at all.
    require_lock "a long press"
    require_awake
    adb $DEV shell "input keyevent --longpress ${1:-23}"
    ;;
shot)
    # shot.sh checks the lock itself, because it is also run directly.
    bash "$S/shot.sh" "$@"
    ;;
burst)
    # Many pictures back to back, for a moment too short for one screenshot.
    # burst.sh checks the lock itself, for the same reason.
    bash "$S/burst.sh" "$@"
    ;;
shell)
    # Passthrough, and the one subcommand that stays open without the lock. It
    # is for reading: logcat, dumpsys and database reads, which the orchestrator
    # needs while a ticket agent holds the set, and which change nothing.
    #
    # A command that INJECTS is refused without the lock. That is a net under
    # an honest mistake, not a fence: anything can be spelled around it, and
    # the rule is still that a press goes through `keys`, `hold` or `launch`.
    #
    # It matches the injecting verbs only, not the bare word `input`, so
    # `dumpsys input` and `logcat | grep input` still read freely.
    # `monkey` and `am start` have to sit in command position, after nothing or
    # after a separator. `logcat -d | grep -i monkey` is a read and must pass.
    if printf '%s' "$*" | grep -Eq '(^|[;&|[:space:]])input[[:space:]]+(keyevent|tap|text|swipe|press|roll|motionevent|draganddrop)([[:space:]]|$)' ||
        printf '%s' "$*" | grep -Eq '(^|[;&|][[:space:]]*)(monkey|am[[:space:]]+start)([[:space:]]|$)'; then
        require_lock "a shell command that presses keys"
    fi
    adb $DEV shell "$@"
    ;;
status)
    bash "$S/tv_lock.sh" status
    ;;
paths)
    bash "$S/tv_lock.sh" paths
    ;;
keepalive)
    bash "$S/keepalive.sh" "$@"
    ;;
*)
    sed -n '28,45p' "$0"
    exit 2
    ;;
esac
