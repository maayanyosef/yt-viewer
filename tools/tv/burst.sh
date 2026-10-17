#!/usr/bin/env bash
# Screenshot the television repeatedly, as fast as adb allows.
#
# `shot.sh` wakes the panel before every picture, which costs two extra adb
# round trips each time. That is right for a screen that is sitting still, and
# wrong for a moment that lasts a fraction of a second.
#
# The moment this exists for is ticket 08's video handover. When one video ends
# the IFrame player paints YouTube's related-video wall, and the app replaces it
# within the same frame. The criterion is that the wall is never seen **at all**,
# so a single screenshot before and a single screenshot after prove nothing.
#
#   bash burst.sh NAME 12          # 12 pictures, back to back
#
# It wakes the panel ONCE, at the start, then loops. Every picture is written to
# WORK/NAME-NN-full.png and shrunk to WORK/NAME-NN-small.png, and the script
# prints how long the whole burst covered, so an agent knows what window it saw.
#
# CAUTION: this is roughly one picture every 0.5 to 1.5 seconds over the
# network, not a video recording. It can still miss a single frame. A wall that
# it does catch is proof of a defect; a burst that catches none is good evidence
# and not a proof.

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/tv_paths.sh"
# Pictures are written per caller, never into the shared lock directory. See
# tv_paths.sh.
WORK="$SHOT_WORK"
# python here is a Windows build, so it cannot read a /c/... path.
WORK_WIN="$(cygpath -m "$WORK" 2>/dev/null || echo "$WORK")"

# The television in tools/tv/device.properties, or TV_DEVICE. See tv_paths.sh.
DEV="-s $TV_DEVICE"

require_lock "a burst of screenshots"

name="${1:-burst}"
count="${2:-10}"

# Wake once. Waking inside the loop is the whole thing this script avoids.
state=$(bash "$HERE/tv_lock.sh" wake)
if [ "$state" != "AWAKE" ]; then
    echo "$state"
    echo "REFUSING to screenshot a sleeping panel."
    exit 1
fi

started=$(date +%s)
n=0
while [ "$n" -lt "$count" ]; do
    n=$((n + 1))
    nn=$(printf "%02d" "$n")
    adb $DEV exec-out screencap -p > "$WORK/$name-$nn-full.png"
    if [ ! -s "$WORK/$name-$nn-full.png" ]; then
        echo "FAILED at picture $nn: screencap wrote nothing."
        exit 1
    fi
done
ended=$(date +%s)

# Shrunk after the burst, never during it. Shrinking takes longer than the
# screencap does, and doing it in the loop would open gaps the wall hides in.
n=0
while [ "$n" -lt "$count" ]; do
    n=$((n + 1))
    nn=$(printf "%02d" "$n")
    python "$HERE/shrink_shot.py" "$WORK_WIN/$name-$nn-full.png" "$WORK_WIN/$name-$nn-small.png" 960 >/dev/null
done

echo "$count pictures over $((ended - started))s, $WORK_WIN/$name-01-small.png .. $name-$(printf "%02d" "$count")-small.png"
