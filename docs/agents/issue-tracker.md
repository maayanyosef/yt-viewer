# Issue tracker: local markdown

Tickets and specs for this repository live as markdown files under `.scratch/`.
There is no GitHub or GitLab tracker for them. Never run `gh` or `glab` for tickets.

`.scratch/` is not part of the shared repository. Each developer keeps their own.
Create it the first time you raise a ticket.

## Efforts

Tickets are grouped by **effort**: one folder per body of work.

| Path | What it holds |
| --- | --- |
| `.scratch/<effort>/issues/NN-<slug>.md` | One ticket per file, numbered from `01` |
| `.scratch/<effort>/map.md` | Every ticket of the effort, with a one-line state each |
| `.scratch/<effort>/handover.md` | What the next session reads first. Optional |
| `.scratch/<effort>/spec.md` | The behaviour spec, when the effort has one |

**When an effort has a spec, the spec is the source of truth for behaviour.**
A ticket says what to build and in what order.
When a ticket and the spec disagree, the spec wins.
When the spec does not settle the disagreement, stop and report it.

## Conventions

- One file per ticket. Never one combined tickets file.
- Blocking is a **`Blocked by:`** line near the top, with ticket numbers. A ticket is unblocked when every ticket it names is finished.
- State is a **`Status:`** line near the top. See `triage-labels.md`.
- Keep `map.md` in step with the ticket files.
- Rewrite `handover.md` at the end of a session that changed what is unblocked.

## One ticket per agent context

**Never resolve more than one ticket in one agent context.**
The rule protects clean context per ticket and a written record per ticket.

- Finishing your own ticket is not a second ticket. That includes verification, acting on a `/code-review`, and the closing notes.
- Picking up a neighbouring ticket is a second ticket, even when it looks cheap. Say that it looks cheap, and stop.
- `/drive` (`.claude/skills/drive/`) runs many tickets in one sitting and still honours the rule, because every ticket gets its own subagent and its own closing notes.

## When a skill says "publish to the issue tracker"

Create a file under `.scratch/<effort>/issues/`, and add a line for it to that effort's `map.md`.

## When a skill says "fetch the relevant ticket"

Read the file at the given path.

## Closing a ticket well

The closing notes matter as much as the code, because the project is rebuilt from its written records.
A finished ticket carries:

- Each acceptance criterion, **ticked only if it was verified**, with the evidence written under it. An unticked criterion says plainly why, and what would close it.
- A **"Things found while building"** section: the traps that cost time.
- A **"Decisions taken while building"** section, with the reason for each.
- A **"Known gaps"** section, naming the ticket that inherits each one.

**Every ticket gets a `/code-review` before it closes.**
A ticket may not reach `resolved` or `needs-device` until every finding is either fixed or raised as its own ticket.

**Never tick a criterion the session could not verify.**
Set `Status:` to `needs-device` or `needs-decision` and list what is unchecked.
An emulator screenshot is not the television.
