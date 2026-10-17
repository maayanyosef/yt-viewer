#!/usr/bin/env bash
# Where the television scripts keep their files, and who is allowed to press a
# button. Sourced by tv.sh, tv_lock.sh, keepalive.sh, shot.sh, burst.sh and
# patrol.sh. It is not a command and running it on its own does nothing.
#
# TWO DIRECTORIES, NOT ONE
#
#   SHOT_WORK     one per caller. Screenshots, and nothing else.
#   SHARED_WORK   one for the whole repository. The television lock, and the
#                 keep-alive's pid and log.
#
# They used to be the same directory: `work`, beside the script. Inside a git
# worktree "beside the script" is a private directory, so three agents in three
# worktrees took three private locks and excluded nobody. On 2026-09-21 that let
# one agent's key press land on another agent's open confirmation and confirm a
# Bulk mark on the household's set. ADR-0001 makes the Watch log append-only, so
# there was no way back. See ticket 34.
#
# SHARED_WORK is the MAIN checkout's `tools/tv/work`, found through
# `git rev-parse --git-common-dir`. That resolves to the one shared `.git` from
# inside any worktree, so every worktree computes the same path.
#
# Screenshots stay per caller on purpose. Two agents both writing
# `work/menu-full.png` into one directory would read each other's pictures. That
# is a quieter and nastier failure than the one this file fixes.
#
# The keep-alive is shared because there is exactly one of it per session, for
# one television. An agent in any directory must be able to see the one that is
# already running, or it would start a second.
#
# OVERRIDES
#
#   TV_WORK          moves BOTH directories. A second television or an emulator
#                    session driven with TV_WORK set is entirely self-contained.
#   TV_SHARED_WORK   moves only the shared directory. It wins over TV_WORK. The
#                    lock test uses it to give two make-believe callers one
#                    shared lock without touching the real one.
#   TV_TICKET        names the caller, and overrides everything below. Set it
#                    when two agents share one checkout AND one session.
#
# WHO OWNS THE LOCK
#
# The lock directory holds four files:
#
#   owner       one line, TICKET EPOCH SHOT_WORK. Human readable, and the
#               record a `status` prints.
#   caller      the owner's CALLER_ID, below. This is what ownership is decided
#               on. It is a separate file so a path with a space in it cannot
#               be mis-split.
#   heartbeat   touched by acquire and by every command the owner runs. Its
#               mtime is the only proof the owner is still there.
#
# CALLER_ID is `TV_TICKET` when that is set. Otherwise it is the caller's own
# scratch directory and its Claude session id together.
#
#   - The directory catches the worktree case, because each worktree has its
#     own and only one of them is written into the lock.
#   - The session id catches two agents inside ONE checkout, which is the
#     shape this run actually uses now that worktrees are banned. The directory
#     alone cannot tell those apart.
#
# It is best effort, not a guarantee. Two agents that share a checkout AND a
# session id are still indistinguishable, and `TV_TICKET` is the way out.

TV_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The main checkout, from any worktree. `--git-common-dir` resolves to the one
# shared `.git`, and its parent is the main checkout. Outside git, the
# repository that holds this script, and TV_IN_GIT stays empty.
#
# `--git-common-dir` prints a path relative to git's own directory, so it is
# resolved by cd-ing to it rather than by string work. From a worktree it is
# already absolute and points at the main checkout's `.git`.
TV_IN_GIT=""
_tv_common="$(cd "$TV_TOOLS" && git rev-parse --git-common-dir 2>/dev/null)"
if [ -n "$_tv_common" ] && _tv_common="$(cd "$TV_TOOLS" && cd "$_tv_common" 2>/dev/null && pwd)"; then
    MAIN_CHECKOUT="$(dirname "$_tv_common")"
    TV_IN_GIT=1
else
    MAIN_CHECKOUT="$(cd "$TV_TOOLS/../.." && pwd)"
fi
unset _tv_common

# Per caller. Unchanged from before ticket 34, on purpose.
SHOT_WORK="${TV_WORK:-$TV_TOOLS/work}"
mkdir -p "$SHOT_WORK"

# One for the whole repository.
if [ -n "${TV_SHARED_WORK:-}" ]; then
    SHARED_WORK="$TV_SHARED_WORK"
elif [ -n "${TV_WORK:-}" ]; then
    SHARED_WORK="$TV_WORK"
elif [ -n "$TV_IN_GIT" ]; then
    # The main checkout found above, so every worktree computes the same path.
    SHARED_WORK="$MAIN_CHECKOUT/tools/tv/work"
else
    # Not a git checkout at all. Fall back to the old behaviour rather than
    # refusing: a copy of these scripts outside git should still work.
    SHARED_WORK="$SHOT_WORK"
fi
mkdir -p "$SHARED_WORK"

LOCK="$SHARED_WORK/tv.lock"
# One keep-alive per session, so one pid and one log for the whole repository.
KEEPALIVE_PID="$SHARED_WORK/keepalive.pid"
KEEPALIVE_LOG="$SHARED_WORK/keepalive.log"
# Written by `keepalive.sh stop` and removed by `start`. It is how the patrol
# tells a deliberate stop from a loop that died.
KEEPALIVE_STOPPED="$SHARED_WORK/keepalive.stopped"
# Touched by acquire and by every command the owner runs. It is the only proof
# that the owner is still there, now that the keep-alive is a session-wide
# thing and says nothing about any one agent.
LOCK_BEAT="$LOCK/heartbeat"
LOCK_CALLER="$LOCK/caller"

# Who this shell is. See the header. Read once, so every script in one command
# agrees with itself.
CALLER_ID="${TV_TICKET:-$SHOT_WORK|${CLAUDE_CODE_SESSION_ID:-no-session}}"

lock_owner_ticket() { awk 'NR==1{print $1}' "$LOCK/owner" 2>/dev/null; }
lock_owner_started() { awk 'NR==1{print $2}' "$LOCK/owner" 2>/dev/null; }

# Field 3 to the end, because a Windows path can hold a space.
lock_owner_work() { cut -d' ' -f3- < "$LOCK/owner" 2>/dev/null | head -1; }

lock_owner_held_for() {
    local began
    began="$(lock_owner_started)"
    case "$began" in
    '' | *[!0-9]*) echo "an unknown time" ;;
    *) echo "$(( ($(date +%s) - began) / 60 )) minutes" ;;
    esac
}

# Seconds since the owner last ran a command.
#
# It falls back rather than giving up, and the order matters. `acquire` creates
# the lock directory before it can write anything into it, so for a moment a
# real, freshly taken lock has no heartbeat. Answering "999999" there would let
# a waiter break a lock taken a second earlier. The same fallback is what keeps
# a pre-ticket-34 lock, which has no heartbeat at all, from being instantly
# stale.
lock_silent_for() {
    local when f
    for f in "$LOCK_BEAT" "$LOCK/owner" "$LOCK"; do
        when=$(stat -c %Y "$f" 2>/dev/null) || continue
        [ -n "$when" ] || continue
        echo $(( $(date +%s) - when ))
        return
    done
    echo 999999
}

# True when this shell may touch the set.
caller_holds_lock() {
    [ -d "$LOCK" ] || return 1

    local owner recorded
    owner="$(lock_owner_ticket)"

    # TV_TICKET, when set, has to agree whatever else matches.
    if [ -n "${TV_TICKET:-}" ] && [ "$TV_TICKET" != "$owner" ]; then
        return 1
    fi

    if [ -f "$LOCK_CALLER" ]; then
        recorded="$(cat "$LOCK_CALLER" 2>/dev/null)"
        [ -n "$recorded" ] && [ "$recorded" = "$CALLER_ID" ]
        return
    fi

    # A lock written before ticket 34 records no caller, so there is nothing to
    # match. Only an explicit TV_TICKET can claim it. Refusing outright would
    # wedge, so `release` still takes it by name and the refusal below says so.
    [ -n "${TV_TICKET:-}" ] && [ "$TV_TICKET" = "$owner" ]
}

# Touch the heartbeat, but only for the owner. Every subcommand calls this,
# including the ones that need no lock, because a lease is broken by the
# owner's silence and reading logcat for an hour is not being absent. Ticket
# 08's two-hour unattended stop is checked exactly that way.
beat() {
    if caller_holds_lock; then
        touch "$LOCK_BEAT" 2>/dev/null
    fi
    return 0
}

# Refuse, and say enough that the reader knows whether to wait or to act.
# Every caller of this must not have sent anything to the set yet.
require_lock() {
    local what="${1:-this command}"
    if caller_holds_lock; then
        # The owner is alive and using the set. This is what stops the lease
        # being broken under it.
        touch "$LOCK_BEAT" 2>/dev/null
        return 0
    fi

    if [ ! -d "$LOCK" ]; then
        echo "REFUSED: nobody holds the television, so $what would be a stray press."
        echo "Take it first:  bash tools/tv/tv.sh lock <ticket>"
    elif [ ! -f "$LOCK_CALLER" ]; then
        echo "REFUSED: the television is held by '$(lock_owner_ticket)', for $(lock_owner_held_for)."
        echo "That lock was written by the old per-worktree code and names no caller,"
        echo "so it cannot be matched. If it is yours, run:"
        echo "  bash tools/tv/tv.sh unlock $(lock_owner_ticket) --force"
    else
        echo "REFUSED: the television is held by '$(lock_owner_ticket)', for $(lock_owner_held_for)."
        echo "  the owner locked it from : $(lock_owner_work)"
        echo "  this shell's scratch is  : $SHOT_WORK"
        echo "  the owner's caller id    : $(cat "$LOCK_CALLER" 2>/dev/null)"
        echo "  this shell's caller id   : $CALLER_ID"
        echo "Wait for '$(lock_owner_ticket)' to run unlock. Do not retry in a loop."
    fi
    echo "Nothing was sent to the set."
    exit 1
}

# ---------------------------------------------------------------- the device
#
# WHICH TELEVISION
#
# The address is never written in a script. It comes from
# tools/tv/device.properties, which is in .gitignore, so every developer keeps
# their own. tools/tv/device.properties.example shows the format:
#
#   address=192.168.1.20
#   port=5555            optional, 5555 when left out
#
# TV_DEVICE, when set, wins over the file. It is a whole adb serial, such as
# emulator-5554, and it is how the emulator is driven instead of the set.
#
# The file is read beside this script first, then in the main checkout, so a
# worktree that has no copy of its own still finds it.
tv_property() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$2" 2>/dev/null | tail -1 | tr -d '\r' |
        sed 's/[[:space:]]*$//'
}

if [ -z "${TV_DEVICE:-}" ]; then
    for _tv_file in "$TV_TOOLS/device.properties" "$MAIN_CHECKOUT/tools/tv/device.properties"; do
        [ -f "$_tv_file" ] || continue
        _tv_address="$(tv_property address "$_tv_file")"
        [ -n "$_tv_address" ] || continue
        case "$_tv_address" in
        *:*) TV_DEVICE="$_tv_address" ;;
        *) TV_DEVICE="$_tv_address:$(tv_property port "$_tv_file" | grep . || echo 5555)" ;;
        esac
        break
    done
    unset _tv_file _tv_address
fi
if [ -z "${TV_DEVICE:-}" ]; then
    # Not an exit: `status`, `unlock` and `keepalive.sh stop` need no device.
    # Anything that does reach adb fails on this serial with adb's own message.
    echo "tools/tv: no television is configured. Copy tools/tv/device.properties.example" \
        "to tools/tv/device.properties and set address=, or set TV_DEVICE." >&2
    TV_DEVICE="no-television-configured"
fi

# adb from the Android SDK, when it is not on PATH already. ANDROID_HOME first,
# then ANDROID_SDK_ROOT, then sdk.dir in the main checkout's local.properties.
if ! command -v adb >/dev/null 2>&1; then
    for _tv_sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
        "$(tv_property sdk.dir "$MAIN_CHECKOUT/local.properties" | sed 's/\\\(.\)/\1/g')"; do
        [ -n "$_tv_sdk" ] || continue
        _tv_sdk="$(cygpath -u "$_tv_sdk" 2>/dev/null || echo "$_tv_sdk")"
        if [ -d "$_tv_sdk/platform-tools" ]; then
            export PATH="$PATH:$_tv_sdk/platform-tools"
            break
        fi
    done
    unset _tv_sdk
fi

# Where Claude Code keeps this repository's transcripts. Its folder name is the
# main checkout's path with every character that is not a letter or a digit
# replaced by `-`, so C:\work\youtube-tv becomes C--work-youtube-tv.
claude_project_dir() {
    local path
    path="$(cygpath -w "$MAIN_CHECKOUT" 2>/dev/null || echo "$MAIN_CHECKOUT")"
    echo "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects/$(printf '%s' "$path" | sed 's/[^A-Za-z0-9]/-/g')"
}
