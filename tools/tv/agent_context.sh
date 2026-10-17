#!/usr/bin/env bash
# Print the current context size of every live subagent of this session.
#
# Context size = input_tokens + cache_creation_input_tokens + cache_read_input_tokens
# of the most recent assistant turn. Reads only the tail of each transcript, so it
# costs nothing to run often.
#
# Usage: agent_context.sh [THRESHOLD]
# Exits 10 if any agent is at or above THRESHOLD (default 450000), else 0.

HERE="$(cd "$(dirname "$0")" && pwd)"
# For claude_project_dir, which finds this repository's transcripts.
. "$HERE/tv_paths.sh"
# Point CLAUDE_SUBAGENTS at this session's directory. Without it the newest
# one under the project wins, which is right whenever only one run is live.
DIR="${CLAUDE_SUBAGENTS:-$(ls -dt "$(claude_project_dir)"/*/subagents 2>/dev/null | head -1)}"
THRESHOLD="${1:-450000}"
over=0

for f in "$DIR"/agent-*.jsonl; do
    [ -e "$f" ] || continue
    id=$(basename "$f" .jsonl)
    id=${id#agent-}

    read -r inp cre rea <<<"$(tail -c 200000 "$f" \
        | grep -o -E '"(input_tokens|cache_creation_input_tokens|cache_read_input_tokens)":[0-9]+' \
        | tail -3 \
        | awk -F: '{v[$1]=$2} END {printf "%d %d %d", v["\"input_tokens\""], v["\"cache_creation_input_tokens\""], v["\"cache_read_input_tokens\""]}')"

    total=$((inp + cre + rea))
    age=$(( ($(date +%s) - $(stat -c %Y "$f")) ))

    # A transcript quiet for 10 minutes belongs to a finished agent. Report it,
    # but never alarm on it: its context is frozen and nobody is spending it.
    mark=""
    if [ "$age" -ge 600 ]; then
        mark=" (finished)"
    elif [ "$total" -ge "$THRESHOLD" ]; then
        mark=" OVER"
        over=1
    fi

    printf '%s %d tokens, last write %ds ago%s\n' "$id" "$total" "$age" "$mark"
done

[ "$over" -eq 1 ] && exit 10
exit 0
