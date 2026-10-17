"""Show the last N entries of a subagent transcript, compactly.

Raw entries carry whole file reads and base64 screenshots, so this prints a
summary per entry instead: who spoke, what tool ran, and a truncated result.
Images are named, never inlined.

Usage: python tail_agent.py TRANSCRIPT.jsonl [N] [CHARS_PER_BLOCK]
"""

import json
import sys


def clip(text, limit):
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[:limit] + f" ...[+{len(text) - limit} chars]"


def blocks(content, limit):
    if isinstance(content, str):
        return [clip(content, limit)]

    out = []
    for block in content or []:
        if not isinstance(block, dict):
            out.append(clip(block, limit))
            continue

        kind = block.get("type")
        if kind == "text":
            out.append(clip(block.get("text", ""), limit))
        elif kind == "thinking":
            out.append("[thinking] " + clip(block.get("thinking", ""), 200))
        elif kind == "tool_use":
            out.append(f"[tool_use {block.get('name')}] " + clip(json.dumps(block.get("input", {}), ensure_ascii=False), limit))
        elif kind == "tool_result":
            out.append("[tool_result] " + " | ".join(blocks(block.get("content"), limit)))
        elif kind == "image":
            source = block.get("source", {})
            size = len(source.get("data", "") or "")
            out.append(f"[image omitted, {size} base64 chars]")
        else:
            out.append(f"[{kind}]")
    return out


def main(argv):
    path = argv[1]
    count = int(argv[2]) if len(argv) > 2 else 20
    limit = int(argv[3]) if len(argv) > 3 else 400

    with open(path, encoding="utf-8") as handle:
        lines = handle.readlines()

    for line in lines[-count:]:
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue

        message = entry.get("message") or {}
        role = message.get("role") or entry.get("type") or "?"
        stamp = (entry.get("timestamp") or "")[11:19]

        for part in blocks(message.get("content"), limit):
            print(f"{stamp} {role}: {part}")


if __name__ == "__main__":
    main(sys.argv)
