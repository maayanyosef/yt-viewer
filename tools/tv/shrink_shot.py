"""Shrink or crop an adb screenshot before an agent reads it.

A 1920x1080 screenshot costs about 2,800 tokens of context. At 960 wide it costs
about 700, and TV layout is still perfectly judgeable. Crop instead of shrinking
when you need to read small text.

Usage:
    python shrink_shot.py IN.png OUT.png [WIDTH]
    python shrink_shot.py IN.png OUT.png --crop LEFT TOP RIGHT BOTTOM [WIDTH]

WIDTH defaults to 960. Coordinates are in the source image's pixels.
"""

import sys

from PIL import Image


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1

    src, dst = argv[1], argv[2]
    rest = argv[3:]
    box = None

    if rest and rest[0] == "--crop":
        if len(rest) < 5:
            print("--crop needs LEFT TOP RIGHT BOTTOM")
            return 1
        box = tuple(int(v) for v in rest[1:5])
        rest = rest[5:]

    width = int(rest[0]) if rest else 960

    image = Image.open(src)
    if box:
        image = image.crop(box)
    if image.width > width:
        height = round(image.height * width / image.width)
        image = image.resize((width, height), Image.LANCZOS)

    image = image.convert("RGB")
    image.save(dst)
    print(f"{dst} {image.width}x{image.height}")

    # The panel blanks after 10 minutes with no input, and a shot of a blank
    # panel is black. Say so loudly, so nobody reads a black image and calls a
    # criterion failed. `tv_lock.sh wake` fixes it.
    grey = image.convert("L")
    mean = sum(v * c for v, c in enumerate(grey.histogram())) / (grey.width * grey.height)
    if mean < 6:
        print(f"WARNING: almost black (mean brightness {mean:.1f}). The screen was probably "
              f"asleep. Run `tv_lock.sh wake` and take the screenshot again.")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
