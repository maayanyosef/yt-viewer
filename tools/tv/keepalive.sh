#!/usr/bin/env bash
# Keep the television out of standby, so a long session never needs the remote.
#
# The set has one idle state and it is standby: the device suspends and `adbd`
# stops listening. Once it is there, nothing over the network can bring it back,
# because there is no transport left to send anything over. Only the physical
# remote undoes it. So the only thing that works is never letting it get there.
#
# This sends a bare modifier key on a timer. It moves no focus, presses no
# button and opens no menu, and it does count as user activity, which is what
# resets the idle timer. See the note in the loop: KEYCODE_WAKEUP does not.
#
#   bash keepalive.sh start        start it, or repair it. Safe to run again.
#   bash keepalive.sh stop         end it. The ONLY thing that ends it.
#   bash keepalive.sh status       is it running, and when did it last poke
#
# ONE PER SESSION, AND NOTHING ELSE TOUCHES IT. Decided 2026-09-21.
#
# `start` is run once at the beginning of a session and the loop is left running
# for the whole session. It is **not** tied to the television lock: `tv.sh lock`
# does not start it and `tv.sh unlock` does not stop it, in either direction.
#
# It used to be started by `lock` and stopped by `unlock`. That left the set
# unprotected in the gap between one agent unlocking and the next one locking,
# and in a queue of a dozen agents there are a dozen such gaps. One of them let
# the set reach 653,798 ms of idle on 2026-09-21.
#
# The pid and the log live in the repository-wide directory, not beside the
# calling worktree, so an agent in any directory sees the one that is already
# running instead of starting a second. See tv_paths.sh.
#
# CAUTION: the app's own two-hour unattended stop (ticket 08) is reset by any
# key, so this keeps that timer alive too. That is the one reason to run
# `keepalive.sh stop`: stop it before trying to verify the unattended stop, and
# start it again afterwards.
#
# INTERVAL must stay well under the set's own idle timeout. That timeout was
# never stated and may not be read or changed, so this is deliberately
# frequent rather than tuned. 240 s costs one adb call every four minutes.
#
# WHY `start` REPAIRS RATHER THAN REFUSES
#
# The loop below is one backgrounded subshell and nothing supervises it. If it
# is killed, if its parent shell is reaped, or if it wedges inside an `adb`
# call that never returns, it stops poking and NOTHING NOTICES. The first sign
# would be the set in standby, which only the physical remote can undo.
#
# So `start` is idempotent and self-healing. It never starts a second loop
# alongside a healthy one, and it kills and replaces one that is not poking.

. "$(cd "$(dirname "$0")" && pwd)/tv_paths.sh"

# The television in tools/tv/device.properties, or TV_DEVICE. See tv_paths.sh.
DEV="-s $TV_DEVICE"
INTERVAL="${TV_KEEPALIVE_SECONDS:-240}"
PIDFILE="$KEEPALIVE_PID"
LOGFILE="$KEEPALIVE_LOG"

running() {
    [ -f "$PIDFILE" ] || return 1
    kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null
}

# How many seconds since the loop last wrote a line. The loop appends on every
# pass, whether the poke worked or not, so the log's mtime is when it last ran.
# Prints a very large number when there is no log at all.
since_last_poke() {
    local when
    when=$(stat -c %Y "$LOGFILE" 2>/dev/null) || { echo 999999; return; }
    [ -n "$when" ] || { echo 999999; return; }
    echo $(( $(date +%s) - when ))
}

# Alive AND actually poking. A loop wedged inside an `adb` call that never
# returns still passes `running`, and it is exactly as useless as a dead one,
# so a pid on its own is not enough evidence. Two and a half missed intervals
# is the line.
healthy() {
    running || return 1
    [ "$(since_last_poke)" -lt $(( INTERVAL * 5 / 2 )) ]
}

spawn() {
    # stdin, stdout and stderr all detached. Without this the loop inherits the
    # caller's stdout and holds it open for its whole life, so any caller that
    # pipes `keepalive.sh start` into something, or captures it in `$(...)`,
    # hangs until the loop dies. That trap cost a session on 2026-09-21.
    loop </dev/null >/dev/null 2>&1 &
    echo $! > "$PIDFILE"
}

loop() {
    while :; do
        # KEYCODE_SHIFT_LEFT, and this was measured rather than guessed.
        #
        # KEYCODE_WAKEUP is the obvious choice and it is the wrong one: it wakes a
        # sleeping device and does **not** count as user activity, so it never
        # moves the idle timer and a keep-alive built on it does nothing at all.
        # Every modifier key does move the timer and changes no focus, so a bare
        # modifier is the harmless poke. KEYCODE_POWER would toggle the set off.
        #
        # The timeout matters. Without it a hung adb call parks this loop for
        # ever, the set drifts into standby, and the pid is still alive so
        # nothing looks wrong. 30 s is far longer than a poke ever needs.
        if timeout 30 adb $DEV shell input keyevent KEYCODE_SHIFT_LEFT >/dev/null 2>&1; then
            echo "$(date '+%H:%M:%S') poked" >> "$LOGFILE"
        else
            # Losing the set mid-session is worth saying loudly, because every
            # verification after this point is against a set nobody can reach.
            echo "$(date '+%H:%M:%S') UNREACHABLE: the set did not answer" >> "$LOGFILE"
        fi
        sleep "$INTERVAL"
    done
}

case "${1:-status}" in
start | ensure)
    # `ensure` is the old name for this and still works, because a handful of
    # notes on disk use it. It does exactly what `start` does.
    if healthy; then
        echo "already running as pid $(cat "$PIDFILE"), poking every ${INTERVAL}s"
        exit 0
    fi
    # Either dead or wedged. Kill whatever is left before replacing it, so a
    # wedged loop cannot wake up later and poke alongside the new one.
    if running; then
        was="wedged: no poke for $(since_last_poke)s"
        kill "$(cat "$PIDFILE")" 2>/dev/null
    elif [ -f "$PIDFILE" ]; then
        was="dead"
    else
        # No pidfile at all, so nothing was ever started. This is a cold start,
        # not a repair, and saying "restarted" would send the next reader
        # hunting for a failure that never happened.
        was=""
    fi
    rm -f "$PIDFILE"
    # A running keep-alive is not a stopped one. The patrol reads this marker to
    # tell a deliberate stop from a loop that died.
    rm -f "$KEEPALIVE_STOPPED"
    if [ -z "$was" ]; then
        echo "$(date '+%H:%M:%S') started" >> "$LOGFILE"
        spawn
        echo "keepalive started as pid $(cat "$PIDFILE"), poking every ${INTERVAL}s"
    else
        echo "$(date '+%H:%M:%S') RESTARTED, previous was $was" >> "$LOGFILE"
        spawn
        echo "keepalive RESTARTED as pid $(cat "$PIDFILE"): the previous one was $was"
    fi
    ;;
stop)
    # The marker says the stop was on purpose, so the patrol does not wake the
    # orchestrator once a second for the two hours ticket 08 needs.
    date +%s > "$KEEPALIVE_STOPPED"
    if running; then
        kill "$(cat "$PIDFILE")" 2>/dev/null
        rm -f "$PIDFILE"
        echo "keepalive stopped"
    else
        rm -f "$PIDFILE"
        echo "not running"
    fi
    echo "The set is now unprotected. Start it again as soon as you are done:"
    echo "  bash tools/tv/keepalive.sh start"
    ;;
status)
    if healthy; then
        echo "running as pid $(cat "$PIDFILE"), every ${INTERVAL}s"
        tail -3 "$LOGFILE" 2>/dev/null
    elif running; then
        echo "WEDGED as pid $(cat "$PIDFILE"): alive but no poke for $(since_last_poke)s"
        echo "The set is drifting towards standby. Run: bash tools/tv/keepalive.sh start"
        tail -3 "$LOGFILE" 2>/dev/null
    elif [ -f "$KEEPALIVE_STOPPED" ]; then
        echo "not running: stopped on purpose $(( $(date +%s) - $(cat "$KEEPALIVE_STOPPED") ))s ago"
        echo "The set is unprotected. Run: bash tools/tv/keepalive.sh start"
    else
        echo "not running"
        echo "The set is unprotected. Run: bash tools/tv/keepalive.sh start"
    fi
    ;;
*)
    sed -n '13,26p' "$0"
    exit 2
    ;;
esac
