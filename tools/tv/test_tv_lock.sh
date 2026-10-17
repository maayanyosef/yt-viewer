#!/usr/bin/env bash
# Prove that the television lock excludes, and that the keep-alive is not tied
# to it. Run it whenever tools/tv changes.
#
#   bash tools/tv/test_tv_lock.sh
#
# Exits 0 when every check passes, 1 otherwise.
#
# WHY THIS FILE EXISTS
#
# On 2026-09-21 three agents held three private locks on one television and
# excluded nobody, because the lock lived in a directory that a git worktree
# gets a private copy of. One agent's key press confirmed a Bulk mark on the
# household's Watch log, which ADR-0001 makes impossible to undo. Ticket 34.
#
# "I changed the line" is not evidence. Exclusion is a property of two callers
# running at once, so this test creates two callers and watches the second one
# be refused.
#
# IT NEVER TOUCHES THE TELEVISION
#
# Three things keep it away from the set:
#
#   - A fake `adb` is put first on PATH. It writes its arguments to a log file
#     instead of talking to anything. That log is also the evidence: a refused
#     command must leave it untouched.
#   - TV_SHARED_WORK points the lock and the keep-alive at a temporary
#     directory, so the session's real keep-alive is neither read nor stopped.
#   - TV_DEVICE names a serial that does not exist.
#
# The one part that uses the real repository is the worktree check, which only
# reads paths and takes no lock.

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

pass=0
fail=0

ok() {
    pass=$((pass + 1))
    echo "  ok   $1"
}

no() {
    fail=$((fail + 1))
    echo "  FAIL $1"
    if [ $# -gt 1 ]; then printf '       %s\n' "$2"; fi
}

check() {
    # check DESCRIPTION EXPECTED ACTUAL
    if [ "$2" = "$3" ]; then
        ok "$1"
    else
        no "$1" "expected [$2], got [$3]"
    fi
}

contains() {
    # contains DESCRIPTION NEEDLE HAYSTACK
    case "$3" in
    *"$2"*) ok "$1" ;;
    *) no "$1" "[$2] is not in: $(echo "$3" | tr '\n' '|')" ;;
    esac
}

lacks() {
    case "$3" in
    *"$2"*) no "$1" "[$2] should not be in: $(echo "$3" | tr '\n' '|')" ;;
    *) ok "$1" ;;
    esac
}

TMP="$(mktemp -d)"
WT="$TMP/wt"
SHARED="$TMP/shared"
A="$TMP/caller-a"
B="$TMP/caller-b"
E="$TMP/caller-e"
ADBLOG="$TMP/adb.log"
mkdir -p "$SHARED" "$A" "$B" "$E" "$TMP/bin"
: > "$ADBLOG"

cleanup() {
    for p in "$SHARED/keepalive.pid" "$E/keepalive.pid"; do
        if [ -f "$p" ]; then kill "$(cat "$p")" 2>/dev/null; fi
    done
    # Only this test's own worktree. Never a blanket `git worktree prune`: a
    # test must not tidy administrative entries it did not create.
    git -C "$REPO" worktree remove --force "$WT" 2>/dev/null
    rm -rf "$TMP"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# A fake adb, first on PATH. tv.sh appends the real platform-tools to PATH, so
# a directory prepended here still wins.
# ---------------------------------------------------------------------------
# python here is a Windows build, so it cannot write to a /tmp/... path. Same
# reason shot.sh keeps a WORK_WIN.
TMP_WIN="$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")"
if ! python -c "from PIL import Image; Image.new('RGB', (64, 64), (200, 200, 200)).save(r'$TMP_WIN/frame.png')"; then
    echo "  FAIL could not write the make-believe screenshot; is Pillow installed?"
    exit 1
fi

{
    echo '#!/usr/bin/env bash'
    echo "echo \"\$*\" >> \"$ADBLOG\""
    echo 'case "$*" in'
    echo "*screencap*) cat \"$TMP/frame.png\" ;;"
    echo '*dumpsys*) echo "  mWakefulness=Awake" ;;'
    echo 'esac'
    echo 'exit 0'
} > "$TMP/bin/adb"
chmod +x "$TMP/bin/adb"

export PATH="$TMP/bin:$PATH"
export TV_DEVICE="fake-device-for-the-lock-test"
# Long enough that the keep-alive loop pokes once and then sleeps for the whole
# test. A poke arriving in the middle of a count would make "it sent nothing"
# flaky. Section 5 backdates the log by more than INTERVAL * 5 / 2 anyway.
export TV_KEEPALIVE_SECONDS=600

# Calls the test made, not counting the keep-alive's own poke, which arrives on
# its own schedule and is not evidence of anything a subcommand did.
adb_lines() { grep -v "KEYCODE_SHIFT_LEFT" "$ADBLOG" 2>/dev/null | wc -l | tr -d ' '; }

# Every run of tv.sh in this test goes through one of these, so no call can
# reach the real lock or the real keep-alive.
as_a() { ( export TV_WORK="$A" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-A"; bash "$HERE/$@" ) 2>&1; }
as_b() { ( export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; bash "$HERE/$@" ) 2>&1; }

ka_pid() { cat "$SHARED/keepalive.pid" 2>/dev/null || echo none; }

echo
echo "== 1. The lock directory is shared, the screenshot directory is not =="

main_paths="$(bash "$HERE/tv_lock.sh" paths)"
main_lock="$(echo "$main_paths" | sed -n 's/^LOCK=//p')"
main_shot="$(echo "$main_paths" | sed -n 's/^SHOT_WORK=//p')"
check "the main checkout's lock is in its own tools/tv/work" \
    "$REPO/tools/tv/work/tv.lock" "$main_lock"

if git -C "$REPO" worktree add --detach --quiet "$WT" HEAD 2>"$TMP/wt.err"; then
    ok "a throwaway git worktree was created"

    common="$(cd "$WT/tools/tv" && cd "$(git rev-parse --git-common-dir)" && pwd)"
    check "git rev-parse --git-common-dir inside the worktree resolves to the main .git" \
        "$REPO/.git" "$common"

    wt_paths="$(bash "$WT/tools/tv/tv_lock.sh" paths)"
    wt_lock="$(echo "$wt_paths" | sed -n 's/^LOCK=//p')"
    wt_shot="$(echo "$wt_paths" | sed -n 's/^SHOT_WORK=//p')"

    check "the worktree computes the SAME lock path as the main checkout" \
        "$main_lock" "$wt_lock"
    if [ "$wt_shot" = "$main_shot" ]; then
        no "the worktree keeps its OWN screenshot directory" "both are $wt_shot"
    else
        ok "the worktree keeps its OWN screenshot directory"
    fi

    # A lock left at the old per-worktree path by the pre-ticket-34 code.
    mkdir -p "$WT/tools/tv/work/tv.lock"
    echo "ticket31 1758400000" > "$WT/tools/tv/work/tv.lock/owner"
    after="$(bash "$WT/tools/tv/tv_lock.sh" paths | sed -n 's/^LOCK=//p')"
    check "a stale lock at the OLD per-worktree path changes nothing" "$main_lock" "$after"
    out="$(cd "$WT" && TV_SHARED_WORK="$SHARED" bash tools/tv/tv_lock.sh status)"
    contains "and the new code reads the shared lock as FREE, not that one" "FREE" "$out"

    git -C "$REPO" worktree remove --force "$WT" 2>/dev/null
    ok "the throwaway worktree was removed"
else
    no "a throwaway git worktree could not be created" "$(cat "$TMP/wt.err")"
fi

echo
echo "== 2. Two callers, one lock =="

out="$(as_a tv.sh lock t-a)"
contains "caller A takes the lock" "ACQUIRED by t-a" "$out"

out="$(export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; timeout 8 bash "$HERE/tv_lock.sh" acquire t-b 2>&1)"
rc=$?
check "caller B waits instead of winning (timed out)" "124" "$rc"
lacks "caller B never printed ACQUIRED" "ACQUIRED" "$out"

owner="$(cat "$SHARED/tv.lock/owner")"
contains "the owner file records caller A's ticket" "t-a" "$owner"
contains "the owner file records caller A's directory" "$A" "$owner"

echo
echo "== 3. Every interactive subcommand refuses a caller without the lock =="

for c in "keys 20" "hold 23" "launch" "install /nowhere/app.apk" "wake" "shot probe" "burst probe 3"; do
    before="$(adb_lines)"
    out="$(as_b tv.sh $c)"
    rc=$?
    name="$(echo "$c" | awk '{print $1}')"
    check "tv.sh $name refuses (exit 1)" "1" "$rc"
    contains "tv.sh $name names the owner" "t-a" "$out"
    check "tv.sh $name sent nothing to the set" "$before" "$(adb_lines)"
done

before="$(adb_lines)"
out="$(as_b tv.sh shell "input keyevent 23")"
rc=$?
check "tv.sh shell refuses a key press without the lock (exit 1)" "1" "$rc"
contains "and names the owner" "t-a" "$out"
check "and sent nothing to the set" "$before" "$(adb_lines)"

before="$(adb_lines)"
out="$(as_b tv.sh shell "monkey -p local.youtubetv 1")"
rc=$?
check "tv.sh shell refuses monkey without the lock (exit 1)" "1" "$rc"
check "and sent nothing either" "$before" "$(adb_lines)"

# The guard matches the injecting verbs, not the bare word. A read that merely
# mentions `input` must still go through, or the orchestrator loses logcat.
for c in "logcat -d" "dumpsys input" "logcat -d | grep input" "getprop ro.product.model"; do
    before="$(adb_lines)"
    as_b tv.sh shell "$c" >/dev/null
    check "tv.sh shell still READS without the lock: $c" "$((before + 1))" "$(adb_lines)"
done

echo
echo "== 4. The owner is not blocked =="

before="$(adb_lines)"
out="$(as_a tv.sh keys "20 20")"
if [ "$(adb_lines)" -gt "$before" ]; then
    ok "caller A's keys reach the set"
else
    no "caller A's keys reach the set" "the adb log did not grow: $out"
fi
contains "and they go in one adb call" "input keyevent 20; input keyevent 20;" "$(tail -1 "$ADBLOG")"

as_a tv.sh shot owner-shot >/dev/null
if [ -s "$A/owner-shot-full.png" ]; then
    ok "caller A's screenshot lands in caller A's own directory"
else
    no "caller A's screenshot lands in caller A's own directory" "no $A/owner-shot-full.png"
fi
if ls "$SHARED"/*.png >/dev/null 2>&1; then
    no "no picture is written into the shared directory" "$(ls "$SHARED")"
else
    ok "no picture is written into the shared directory"
fi

echo
echo "== 5. The keep-alive is not tied to the lock, in either direction =="

check "taking the lock did NOT start a keep-alive" "none" "$(ka_pid)"
out="$(as_a tv.sh keepalive status)"
contains "and status says so plainly" "not running" "$out"

out="$(as_a tv.sh keepalive start)"
contains "start begins one" "keepalive started as pid" "$out"
first="$(ka_pid)"
if [ "$first" = "none" ]; then
    no "the pid is written to the shared directory" "no $SHARED/keepalive.pid"
else
    ok "the pid is written to the shared directory, not the caller's"
fi
if [ -f "$A/keepalive.pid" ]; then
    no "nothing is written into the caller's own directory" "$A/keepalive.pid exists"
else
    ok "nothing is written into the caller's own directory"
fi

out="$(as_a tv.sh keepalive start)"
contains "start again refuses to begin a second one" "already running as pid" "$out"
check "and the pid did not change" "$first" "$(ka_pid)"

out="$(as_b tv.sh keepalive status)"
contains "a caller that does NOT hold the lock still sees it" "running as pid $first" "$out"

# A live pid is not evidence. A loop wedged inside a hung adb call passes
# `kill -0` and pokes nothing, so health is judged by the log's mtime.
sleep 300 &
wedged=$!
kill "$first" 2>/dev/null
echo "$wedged" > "$SHARED/keepalive.pid"
touch -d "@$(( $(date +%s) - 3000 ))" "$SHARED/keepalive.log"
out="$(as_a tv.sh keepalive status)"
contains "a live pid that is not poking reads as WEDGED, not running" "WEDGED as pid $wedged" "$out"
out="$(as_a tv.sh keepalive start)"
contains "and start replaces it" "RESTARTED" "$out"
contains "saying why" "wedged: no poke for" "$out"
kill "$wedged" 2>/dev/null

echo
echo "== 6. unlock releases the lock and leaves the keep-alive running =="

before="$(ka_pid)"
out="$(as_a tv.sh unlock t-a)"
contains "caller A releases" "RELEASED by t-a" "$out"
lacks "and unlock says nothing about stopping the keep-alive" "keepalive stopped" "$out"
if [ -d "$SHARED/tv.lock" ]; then
    no "the lock directory is gone" "$SHARED/tv.lock is still there"
else
    ok "the lock directory is gone"
fi
check "the keep-alive is the same pid it was before the unlock" "$before" "$(ka_pid)"
out="$(as_a tv.sh keepalive status)"
contains "and it is still running with the lock FREE" "running as pid" "$out"

out="$(as_a tv.sh keepalive stop)"
contains "keepalive stop is what stops it" "keepalive stopped" "$out"
check "and the pid file is gone" "none" "$(ka_pid)"
contains "and it says the set is now unprotected" "unprotected" "$out"

out="$(as_a tv.sh lock t-a)"
contains "taking the lock again still does not start one" "ACQUIRED by t-a" "$out"
check "confirmed: no keep-alive after lock" "none" "$(ka_pid)"
as_a tv.sh keys 20 >/dev/null
check "and an interactive subcommand does not start one either" "none" "$(ka_pid)"
as_a tv.sh unlock t-a >/dev/null

echo
echo "== 7. With the lock free, a press is still refused =="

before="$(adb_lines)"
out="$(as_b tv.sh keys 20)"
rc=$?
check "a key press with nobody holding the set refuses (exit 1)" "1" "$rc"
contains "and says nobody holds the set" "nobody holds the television" "$out"
check "and sent nothing" "$before" "$(adb_lines)"

echo
echo "== 8. A lock left by the old code does not wedge the new code =="

mkdir -p "$SHARED/tv.lock"
echo "ticket31 1758400000" > "$SHARED/tv.lock/owner"
before="$(adb_lines)"
out="$(as_b tv.sh keys 20)"
rc=$?
check "an old-format lock refuses rather than letting a press through" "1" "$rc"
contains "and names the owner it found" "ticket31" "$out"
contains "and says how to clear it" "unlock ticket31 --force" "$out"
check "and sent nothing" "$before" "$(adb_lines)"
out="$(as_b tv.sh unlock ticket31)"
contains "the name alone does not clear it" "not proof of ownership" "$out"
out="$(as_b tv.sh unlock ticket31 --force)"
contains "and --force does" "RELEASED by ticket31" "$out"

echo
echo "== 9. TV_WORK still redirects everything =="

out="$(TV_WORK="$E" bash "$HERE/tv_lock.sh" paths)"
check "TV_WORK moves the lock too" "$E/tv.lock" "$(echo "$out" | sed -n 's/^LOCK=//p')"
check "TV_WORK moves the screenshots too" "$E" "$(echo "$out" | sed -n 's/^SHOT_WORK=//p')"

out="$(TV_WORK="$E" bash "$HERE/tv.sh" lock t-emulator 2>&1)"
contains "a TV_WORK session takes its own lock" "ACQUIRED by t-emulator" "$out"
before="$(adb_lines)"
TV_WORK="$E" bash "$HERE/tv.sh" keys 20 >/dev/null 2>&1
if [ "$(adb_lines)" -gt "$before" ]; then
    ok "a TV_WORK session can press keys"
else
    no "a TV_WORK session can press keys" "the adb log did not grow"
fi
out="$(TV_WORK="$E" bash "$HERE/tv.sh" unlock t-emulator 2>&1)"
contains "a TV_WORK session releases" "RELEASED by t-emulator" "$out"

echo
echo "== 10. A lease is broken by the owner's silence, not by its age =="

out="$(as_a tv.sh lock t-a)"
contains "caller A takes the lock" "ACQUIRED by t-a" "$out"
# Backdate the lease far past STALE, but keep the heartbeat fresh: an agent that
# has held the set for hours and is still pressing keys must not lose it.
echo "t-a $(( $(date +%s) - 99999 )) $A" > "$SHARED/tv.lock/owner"
touch "$SHARED/tv.lock/heartbeat"
out="$(export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; timeout 8 bash "$HERE/tv_lock.sh" acquire t-b 2>&1)"
lacks "an old lease whose owner is still working is NOT broken" "BREAKING" "$out"
contains "and the waiter is told how long ago the owner used it" "last used" "$out"

# An interactive subcommand is what keeps the heartbeat fresh.
touch -d "@$(( $(date +%s) - 99999 ))" "$SHARED/tv.lock/heartbeat"
as_a tv.sh keys 20 >/dev/null
silent_after="$(( $(date +%s) - $(stat -c %Y "$SHARED/tv.lock/heartbeat") ))"
if [ "$silent_after" -lt 10 ]; then
    ok "a key press from the owner refreshes the heartbeat"
else
    no "a key press from the owner refreshes the heartbeat" "still ${silent_after}s old"
fi

# Now let the owner fall silent. That, and only that, breaks the lease.
touch -d "@$(( $(date +%s) - 99999 ))" "$SHARED/tv.lock/heartbeat"
out="$(export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; timeout 20 bash "$HERE/tv_lock.sh" acquire t-b 2>&1)"
contains "a silent owner's lease IS broken" "BREAKING" "$out"
contains "and caller B then wins it" "ACQUIRED by t-b" "$out"
( export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; bash "$HERE/tv.sh" unlock t-b ) >/dev/null 2>&1

echo
echo "== 11. Two callers inside ONE checkout =="

# The shape this run actually uses, now that worktrees are banned. The
# directory is the same for both, so the Claude session id is what tells them
# apart. Caller A locks; caller C is the same directory and a different session.
as_c() { ( export TV_WORK="$A" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-C"; bash "$HERE/$@" ) 2>&1; }

out="$(export TV_WORK="$A" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-A"; bash "$HERE/tv.sh" lock t-a 2>&1)"
contains "caller A locks from the shared checkout" "ACQUIRED by t-a" "$out"

before="$(adb_lines)"
out="$(as_c tv.sh keys 20)"
rc=$?
check "a second caller in the SAME directory is refused (exit 1)" "1" "$rc"
contains "and the refusal shows both caller ids" "this shell's caller id" "$out"
check "and it sent nothing" "$before" "$(adb_lines)"

before="$(adb_lines)"
out="$(export TV_WORK="$A" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-A"; bash "$HERE/tv.sh" keys 20 2>&1)"
if [ "$(adb_lines)" -gt "$before" ]; then
    ok "and caller A itself is still let through"
else
    no "and caller A itself is still let through" "$out"
fi

echo
echo "== 12. release needs more than the ticket's name =="

# Two agents can carry the same ticket number, for instance when a stuck one is
# retired and replaced. The replacement must not drop the lock the first one is
# still using.
out="$(as_c tv.sh unlock t-a)"
rc=$?
check "unlock by a stranger who guesses the ticket name is refused (exit 1)" "1" "$rc"
contains "and it says naming the ticket is not proof" "not proof of ownership" "$out"
if [ -d "$SHARED/tv.lock" ]; then
    ok "and the lock is still there"
else
    no "and the lock is still there" "it was dropped"
fi

out="$(as_c tv.sh unlock t-a --force)"
contains "--force is the documented way to take it from an agent that is gone" "FORCED" "$out"
contains "and it releases" "RELEASED by t-a" "$out"

out="$(as_a tv.sh unlock t-a)"
contains "releasing a free lock says so instead of claiming a release" "NOTHING TO RELEASE" "$out"

echo
echo "== 13. A lock with no heartbeat yet is not instantly stale =="

# `acquire` cannot write into the lock until after `mkdir`, so a real lock taken
# a moment ago can be sampled with nothing in it. Answering "abandoned" there
# would let a waiter delete a lock somebody had just won.
mkdir -p "$SHARED/tv.lock"
silent="$(export TV_SHARED_WORK="$SHARED"; bash -c '. "'"$HERE"'/tv_paths.sh"; lock_silent_for')"
if [ "$silent" -lt 60 ]; then
    ok "an empty, freshly made lock reads as seconds old, not abandoned"
else
    no "an empty, freshly made lock reads as seconds old, not abandoned" "got ${silent}s"
fi

echo "ticket31 1758400000" > "$SHARED/tv.lock/owner"
out="$(export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"; timeout 8 bash "$HERE/tv_lock.sh" acquire t-b 2>&1)"
lacks "a pre-ticket-34 lock is NOT broken on sight" "BREAKING" "$out"
lacks "so nobody takes the set from under it" "ACQUIRED" "$out"
out="$(as_b tv.sh unlock ticket31 --force)"
contains "and --force is how it is cleared" "RELEASED by ticket31" "$out"

echo
echo "== 14. Breaking a lease is a move, so only one waiter can win =="

mkdir -p "$SHARED/tv.lock"
echo "t-gone $(( $(date +%s) - 99999 )) $A" > "$SHARED/tv.lock/owner"
printf 'someone-else' > "$SHARED/tv.lock/caller"
touch -d "@$(( $(date +%s) - 99999 ))" "$SHARED/tv.lock/heartbeat"
(
    export TV_WORK="$B" TV_SHARED_WORK="$SHARED" CLAUDE_CODE_SESSION_ID="session-B"
    timeout 25 bash "$HERE/tv_lock.sh" acquire t-b1 > "$TMP/b1.out" 2>&1 &
    timeout 25 bash "$HERE/tv_lock.sh" acquire t-b2 > "$TMP/b2.out" 2>&1 &
    wait
)
won=$(grep -l "ACQUIRED" "$TMP/b1.out" "$TMP/b2.out" 2>/dev/null | wc -l | tr -d ' ')
check "exactly one of two simultaneous waiters wins the broken lease" "1" "$won"
if [ -f "$SHARED/tv.lock/caller" ]; then
    ok "and the winner's own caller file is the one on disk"
else
    no "and the winner's own caller file is the one on disk" "no caller file"
fi
if ls -d "$SHARED"/tv.lock.dead.* >/dev/null 2>&1; then
    no "the moved-aside copy is cleaned up" "$(ls -d "$SHARED"/tv.lock.dead.*)"
else
    ok "the moved-aside copy is cleaned up"
fi
rm -rf "$SHARED/tv.lock"

echo
echo "== 15. Reading keeps the lease alive =="

out="$(as_a tv.sh lock t-a)"
contains "caller A takes the lock" "ACQUIRED by t-a" "$out"
touch -d "@$(( $(date +%s) - 99999 ))" "$SHARED/tv.lock/heartbeat"
# Ticket 08's two-hour unattended stop is verified by watching logcat and
# sending no key at all. STALE is 90 minutes, so a read has to count as life.
as_a tv.sh shell "logcat -d" >/dev/null
age="$(( $(date +%s) - $(stat -c %Y "$SHARED/tv.lock/heartbeat") ))"
if [ "$age" -lt 10 ]; then
    ok "tv.sh shell refreshes the owner's heartbeat"
else
    no "tv.sh shell refreshes the owner's heartbeat" "still ${age}s old"
fi

touch -d "@$(( $(date +%s) - 99999 ))" "$SHARED/tv.lock/heartbeat"
as_b tv.sh shell "logcat -d" >/dev/null
age="$(( $(date +%s) - $(stat -c %Y "$SHARED/tv.lock/heartbeat") ))"
if [ "$age" -gt 1000 ]; then
    ok "but a stranger's read does not"
else
    no "but a stranger's read does not" "it refreshed to ${age}s"
fi
as_a tv.sh unlock t-a >/dev/null

echo
echo "== 16. The shell guard lets a read that mentions a verb through =="

for c in "logcat -d | grep -i monkey" "logcat -d | grep 'am start'" "dumpsys activity | grep monkey"; do
    before="$(adb_lines)"
    as_b tv.sh shell "$c" >/dev/null
    check "read allowed: $c" "$((before + 1))" "$(adb_lines)"
done
for c in "monkey -p local.youtubetv 1" "am start -n local.youtubetv/.MainActivity" "sleep 1; input keyevent 23"; do
    before="$(adb_lines)"
    as_b tv.sh shell "$c" >/dev/null 2>&1
    rc=$?
    check "inject refused: $c" "1" "$rc"
    check "and it sent nothing: $c" "$before" "$(adb_lines)"
done

echo
echo "== 17. A deliberate keep-alive stop does not trip the patrol =="

as_a tv.sh keepalive start >/dev/null
out="$(as_a tv.sh keepalive stop)"
contains "stop says so" "keepalive stopped" "$out"
out="$(as_a tv.sh keepalive status)"
contains "status calls it a stop on purpose" "stopped on purpose" "$out"
as_a tv.sh keepalive start >/dev/null
out="$(as_a tv.sh keepalive status)"
lacks "and start clears the marker again" "on purpose" "$out"
as_a tv.sh keepalive stop >/dev/null

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
