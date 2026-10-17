"""Compute the wave plan for a build effort's tickets.

Usage:
    python .claude/skills/drive/frontier.py .scratch/<effort>

The effort directory is required. Prints, in order:

  - which tickets are already finished enough to unblock others,
  - one line per wave, listing the tickets that can run at the same time,
  - anything stuck, which means a blocking edge points at a ticket that does not exist.

Parsing is deliberate rather than clever, so a malformed ticket fails loudly here
instead of silently dropping out of the plan.

Both heading styles are accepted: `**Status:** x` and `Status: x`. Matching only one of
them silently treats a whole effort as unstarted.
"""

import glob
import os
import re
import sys

# A ticket is finished enough to unblock its dependants when its code is complete.
# `needs-device` and `needs-decision` both mean exactly that: the code landed, and what is
# left is a television check or a decision for the project owner. Neither blocks downstream work.
UNBLOCKING = ("resolved", "needs-device", "needs-decision")

# These are decisions not to build something. They unblock dependants and must never be
# offered as work. Reported on their own line so a deferred ticket stays visible rather
# than quietly joining the finished pile.
CLOSED = ("deferred", "wontfix")


def load(effort_dir):
    tickets = {}
    paths = sorted(glob.glob(os.path.join(effort_dir, "issues", "*.md")))
    if not paths:
        sys.exit(f"no tickets found under {effort_dir}/issues/")
    for path in paths:
        name = os.path.basename(path)
        match = re.match(r"(\d+)-", name)
        if not match:
            sys.exit(f"{name}: filename must start with a two-digit number")
        text = open(path, encoding="utf-8").read()

        blocked = re.search(r"^\**Blocked by:\**\s*(.*)$", text, re.M)
        # Only the digits before the first full stop are edges. A `Blocked by:` line often
        # carries a trailing sentence of prose explaining the edge.
        edges = re.findall(r"\d+", blocked.group(1).split(".")[0]) if blocked else []

        status = re.search(r"^\**Status:\**\s*(\S+)", text, re.M)
        tickets[int(match.group(1))] = {
            "path": path,
            "title": text.split("\n")[0].lstrip("# ").strip(),
            "edges": [int(e) for e in edges],
            "status": status.group(1) if status else "unknown",
        }
    return tickets


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: python .claude/skills/drive/frontier.py .scratch/<effort>")
    effort_dir = sys.argv[1]
    tickets = load(effort_dir)

    missing = {e for t in tickets.values() for e in t["edges"] if e not in tickets}
    if missing:
        sys.exit(f"blocking edges point at tickets that do not exist: {sorted(missing)}")

    finished = {n for n, t in tickets.items() if t["status"].startswith(UNBLOCKING)}
    closed = {n for n, t in tickets.items() if t["status"].startswith(CLOSED)}
    claimed = sorted(n for n, t in tickets.items() if t["status"].startswith("claimed"))
    done = finished | closed

    print(f"effort: {effort_dir}")
    print(f"finished enough to unblock: {sorted(finished)}")
    if closed:
        print(f"closed, never offered as work: {sorted(closed)}")
    if claimed:
        print(f"CLAIMED by another session, left alone: {claimed}")
    print()

    remaining = set(tickets) - done - set(claimed)
    remaining_at_start = set(remaining)
    wave = 0
    while remaining:
        wave += 1
        ready = sorted(n for n in remaining if all(e in done for e in tickets[n]["edges"]))
        if not ready:
            print(f"STUCK, a cycle or a claimed blocker: {sorted(remaining)}")
            sys.exit(1)
        print(f"wave {wave} ({len(ready)} in parallel):")
        for n in ready:
            print(f"    {n:02d}  {tickets[n]['title']}")
        done |= set(ready)
        remaining -= set(ready)

    print()
    print(f"{len(remaining_at_start)} tickets to run, {wave} waves")


if __name__ == "__main__":
    main()
