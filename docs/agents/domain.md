# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the
codebase.

This repo is **single-context**: one `CONTEXT.md` and one `docs/adr/`, both at the root. There
is no `CONTEXT-MAP.md` and no per-package context, because this is a single Android app, not a
monorepo.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root. It is the glossary.
- **`docs/adr/`**: read the ADRs that touch the area you are about to work in. Today that is
  `docs/adr/0001-watch-model.md`, which is the data model and the hardest-to-reverse decision
  in the project.

If a file does not exist, **proceed silently**. Do not flag its absence and do not suggest
creating one upfront. `/domain-modeling` creates them lazily, when a term or a decision
actually gets resolved.

## File structure

```
/
├── CONTEXT.md
├── CLAUDE.md
├── docs/
│   ├── adr/
│   │   └── 0001-watch-model.md
│   └── agents/
└── app/src/main/kotlin/local/youtubetv/
```

## Use the glossary's vocabulary, and respect its avoid-lists

`CONTEXT.md` here is stricter than most. **Every term carries an `_Avoid_` line** naming the
synonyms that must not be used. Those are not suggestions.

When your output names a domain concept, in a ticket title, a type name, a test name, a
comment, or anything shown on the television, use the term exactly as `CONTEXT.md` defines it.

- **Source**, not subscription, feed or list.
- **Direction**, not sort order or ascending/descending.
- **Period**, not season, era, chapter or group.
- **Watch**, not view, history entry or play.
- **Last played**, not resume point, bookmark or current position.
- **Next unwatched**, not up next or continue.
- **Bulk mark**, not import history or backfill.
- **Source search**, not search or YouTube search.

Read `CONTEXT.md` for the full list and the definitions; the eight above are only the ones
easiest to slip on.

If a concept you need is not in the glossary, that is a signal. Either you are inventing
language the project does not use, which is worth reconsidering, or there is a real gap worth
noting for `/domain-modeling`.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it rather than silently overriding it:

> _Contradicts ADR-0001 (the Watch model is an append-only typed log), but worth reopening
> because…_

ADR 0001 is the one to be careful with. The whole data model is built to be replayable on a
second device later, which is why the log is append-only and why videos are disposable cache.
A change that makes the log editable is a much bigger decision than it looks.
