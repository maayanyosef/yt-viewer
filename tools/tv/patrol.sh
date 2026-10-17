#!/usr/bin/env bash
# Watch the live ticket agents and wake the orchestrator.
#
# Two reasons to wake. A rule trips, or ten minutes pass. The heartbeat matters
# as much as the rules: a rule only catches what I thought to check for, and
# judgement catches the rest. Both were asked for on 2026-09-20.
#
# ---------------------------------------------------------------------------
# HOW TO START IT. Written 2026-09-21, after it died for three hours.
#
# START IT IN A COMMAND OF ITS OWN. Nothing else in that command. It must be
# the harness's own background mechanism, the thing that notifies you when the
# process exits, and never a bare `&` inside a command doing something else.
#
#   RIGHT:  one background call, containing only this script.
#   WRONG:  bash tools/tv/patrol.sh ... &   ; some_other_command
#
# Why a bare `&` is fatal rather than merely untidy. The child dies with the
# foreground command that spawned it, so no process survives. And because it was
# never a tracked background task, NO COMPLETION NOTICE EVER ARRIVES. The one
# thing that would have told you the patrol stopped is the patrol. It is the
# only failure here that is silent, and it is silent by construction.
#
# It happened on 2026-09-21 at 07:56: a restart was folded into a command that
# also read the database, and the patrol was dead for three hours sixteen
# minutes before anybody asked about it. Four tickets ran unwatched.
#
# So: after every wake, restart it as its OWN call, and confirm you were told it
# is running in the background. If you did not see that confirmation, it is not
# running.
# ---------------------------------------------------------------------------
#
# WHAT THE ORCHESTRATOR MUST DO ON EVERY WAKE. Written 2026-09-21.
#
# The heartbeat is not optional and it is not a reason, it is a schedule. This
# script must be started with a HEARTBEAT so it wakes the orchestrator every ten
# minutes WHETHER OR NOT A RULE TRIPPED.
#
# Every wake is a full round over EVERY live agent, not a glance at whichever
# one tripped:
#
#   1. Restart this script. First action, before judging the snapshot, before
#      messaging an agent, before answering anything. Reading a snapshot and not
#      restarting is the same as turning the monitoring off. It lapsed twice on
#      2026-09-20 that way.
#   2. For each live agent ask: is it still making progress, or is it hanging?
#      Has it committed? Has it ticked what it verified? Is it repeating the
#      same failing command?
#   3. Act on what that shows. One message to a stuck agent costs seconds; not
#      sending it cost an hour on 2026-09-20.
#
# A heartbeat with nothing tripped is a NORMAL result, not a wasted wake. It is
# the check that proves nothing is stuck. A rule only catches what somebody
# thought to check for; the heartbeat catches the rest.
# ---------------------------------------------------------------------------
#
# Usage: patrol.sh [POLL_SECONDS] [TOKENS_HIGH] [HEARTBEAT_SECONDS]
#
# Exits 10 when a rule trips, 0 on a heartbeat or when every agent has finished.
# It always prints a full snapshot, so the wake-up needs no follow-up commands.

HERE="$(cd "$(dirname "$0")" && pwd)"
# LOCK comes from here, and it is the one repository-wide lock. The patrol used
# to compute it itself and so watched only its own worktree's private copy.
. "$HERE/tv_paths.sh"
REPO="${TV_REPO:-$(cd "$HERE/../.." && pwd)}"
SUBS="${CLAUDE_SUBAGENTS:-$(ls -dt "$(claude_project_dir)"/*/subagents 2>/dev/null | head -1)}"

POLL="${1:-120}"
TOKENS_HIGH="${2:-380000}"     # raise it for an agent already in its endgame
HEARTBEAT="${3:-600}"

STALLED=1200                   # 20 min with no transcript write
DIRTY_AGE=1500                 # 25 min of uncommitted work
LOCK_AGE=5400                  # 90 min holding the television
FINISHED=900                   # quiet this long means the agent is done

# Agents from earlier waves. Their transcripts stay on disk forever, and a big
# one goes on tripping the token rule every time the patrol restarts, because a
# restart resets the clock before FINISHED can retire it. Listing the id here is
# explicit, which is better than guessing from a timestamp.
IGNORE="$HERE/patrol.ignore"

started=$(date +%s)
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

snapshot() {
    live=0
    reasons=""

    echo "-- agents"
    for f in "$SUBS"/agent-*.jsonl; do
        [ -e "$f" ] || continue
        id=$(basename "$f" .jsonl); id=${id#agent-}
        age=$(( $(date +%s) - $(stat -c %Y "$f") ))
        [ "$age" -ge "$FINISHED" ] && continue
        [ -f "$IGNORE" ] && grep -qx "$id" "$IGNORE" && continue
        live=$((live + 1))

        read -r inp cre rea <<<"$(tail -c 200000 "$f" \
            | grep -o -E '"(input_tokens|cache_creation_input_tokens|cache_read_input_tokens)":[0-9]+' \
            | tail -3 \
            | awk -F: '{v[$1]=$2} END {printf "%d %d %d", v["\"input_tokens\""], v["\"cache_creation_input_tokens\""], v["\"cache_read_input_tokens\""]}')"
        total=$((inp + cre + rea))

        printf '   %s  %d tokens, last write %ds ago\n' "$id" "$total" "$age"
        [ "$total" -ge "$TOKENS_HIGH" ] && \
            reasons="$reasons\n  $id is at $total tokens: consider a handover to a fresh agent"
        [ "$age" -ge "$STALLED" ] && \
            reasons="$reasons\n  $id has not written for ${age}s: check it is not stuck"
    done
    [ "$live" -eq 0 ] && echo "   none live"

    echo "-- worktrees"
    # The main checkout as well as the worktrees. A wave of one ticket runs in the
    # checkout itself, and a patrol that watched only worktrees saw nothing at all.
    for w in "$REPO" "$REPO"/.claude/worktrees/agent-*; do
        [ -d "$w" ] || continue
        name=$(basename "$w")
        dirty=$(git -C "$w" status --short 2>/dev/null | wc -l)
        last=$(git -C "$w" log -1 --format=%ct 2>/dev/null)
        since=$([ -n "$last" ] && echo $(( $(date +%s) - last )) || echo 0)

        # How much of the ticket is actually written down, which is the thing a
        # token count cannot see.
        # The most recently changed claimed ticket, in any effort, is the live one.
        tf=$(ls -t "$w"/.scratch/*/issues/*.md 2>/dev/null \
             | xargs -r grep -l '^\*\*Status:\*\* claimed' 2>/dev/null | head -1)
        if [ -n "$tf" ]; then
            done_n=$(grep -c '^- \[x\]' "$tf")
            open_n=$(grep -c '^- \[ \]' "$tf")
            ticks="$done_n/$((done_n + open_n)) ticked"
        else
            ticks="no claimed ticket"
        fi

        printf '   %s  %d uncommitted, last commit %ds ago, %s\n' "$name" "$dirty" "$since" "$ticks"
        [ "$dirty" -gt 0 ] && [ "$since" -ge "$DIRTY_AGE" ] && \
            reasons="$reasons\n  $name has $dirty uncommitted files and has not committed for ${since}s"
    done

    echo "-- television"
    if [ -d "$LOCK" ]; then
        owner=$(lock_owner_ticket)
        begun=$(lock_owner_started)
        held=$([ -n "$begun" ] && echo $(( $(date +%s) - begun )) || echo 0)
        silent=$(lock_silent_for)
        printf '   held by %s for %ds, last used %ss ago, from %s\n' \
            "$owner" "$held" "$silent" "$(lock_owner_work)"
        [ "$held" -ge "$LOCK_AGE" ] && \
            reasons="$reasons\n  $owner has held the television for ${held}s"
    else
        echo "   free"
    fi

    echo "-- keep-alive"
    # One loop per session, not per lock. A set with no keep-alive drifts into
    # standby, and only the physical remote undoes that, so a stopped one is
    # worth waking the orchestrator for.
    ka=$(bash "$HERE/keepalive.sh" status | head -1)
    printf '   %s\n' "$ka"
    #
    # PATROL_KEEPALIVE_OPTIONAL=1 turns off this one rule and nothing else. It is
    # for the run where the household is watching the television itself and has
    # said not to touch it. The keep-alive injects a key every four minutes, so
    # leaving it on would be driving the set while the family watches. Asked
    # for on 2026-09-21. Unset, which is the default, the rule trips
    # exactly as it always did.
    #
    # Only a keep-alive stopped ON PURPOSE is ever exempt. A wedged one still
    # trips, because a wedged loop is the silent failure this script exists for.
    case "$ka" in
    running*) ;;
    "not running: stopped on purpose"*)
        [ "${PATROL_KEEPALIVE_OPTIONAL:-0}" = 1 ] || \
            reasons="$reasons\n  the keep-alive is not running ($ka): bash tools/tv/keepalive.sh start"
        ;;
    *) reasons="$reasons\n  the keep-alive is not running ($ka): bash tools/tv/keepalive.sh start" ;;
    esac
}

while :; do
    snapshot > "$TMP"
    out=$(cat "$TMP")

    if [ -n "$reasons" ]; then
        echo "PATROL: a rule tripped"
        printf "%b\n" "$reasons"
        echo "$out"
        exit 10
    fi

    if [ "$live" -eq 0 ]; then
        echo "QUIET: no live agent"
        echo "$out"
        exit 0
    fi

    if [ $(( $(date +%s) - started )) -ge "$HEARTBEAT" ]; then
        echo "HEARTBEAT: nothing tripped; judge whether anything needs doing"
        echo "$out"
        exit 0
    fi

    sleep "$POLL"
done
