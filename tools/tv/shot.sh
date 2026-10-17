#!/usr/bin/env bash
# Take a screenshot of the television, ready to read.
#
# This is the ONLY way an agent should screenshot the set. It wakes the panel
# first, because the panel blanks after 10 minutes with no input and a shot of
# a blank panel is black. Then it shrinks the image, because a 1920x1080 shot
# costs about 2,800 tokens of context and a 960-wide one costs about 700.
#
#   bash shot.sh NAME                       # -> scratchpad/NAME-small.png
#   bash shot.sh NAME --crop L T R B        # crop the full-size shot first
#   bash shot.sh NAME --keep                # also keep the full-size PNG
#
# It prints the path to read, and warns if the image is almost black.
# The full-size PNG is always written to scratchpad/NAME-full.png, so it can be
# kept as evidence when a criterion needs it.

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/tv_paths.sh"
# Pictures are written per caller, never into the shared lock directory, so two
# agents can never read each other's screenshots. See tv_paths.sh.
WORK="$SHOT_WORK"
# python here is a Windows build, so it cannot read a /c/... path.
WORK_WIN="$(cygpath -m "$WORK" 2>/dev/null || echo "$WORK")"

# The television in tools/tv/device.properties. Set TV_DEVICE to an adb serial,
# such as emulator-5554, to drive the emulator instead. See tv_paths.sh, which
# also puts the SDK's adb on PATH.
DEV="-s $TV_DEVICE"

# A screenshot is a press in disguise: it wakes the panel, and a panel woken
# under another agent's session changes what that agent is looking at.
require_lock "a screenshot"

name="${1:-shot}"
shift 2>/dev/null

crop=()
if [ "$1" = "--crop" ]; then
    crop=(--crop "$2" "$3" "$4" "$5")
    shift 5
fi

# Wake only if it is actually asleep. tv_lock.sh prints AWAKE or a failure line.
state=$(bash "$HERE/tv_lock.sh" wake)
if [ "$state" != "AWAKE" ]; then
    echo "$state"
    echo "REFUSING to screenshot a sleeping panel."
    exit 1
fi

adb $DEV exec-out screencap -p > "$WORK/$name-full.png"
if [ ! -s "$WORK/$name-full.png" ]; then
    echo "FAILED: screencap wrote nothing. Is the set still on $TV_DEVICE?"
    exit 1
fi

python "$HERE/shrink_shot.py" "$WORK_WIN/$name-full.png" "$WORK_WIN/$name-small.png" "${crop[@]}" 960
echo "full-size kept at $WORK_WIN/$name-full.png"
