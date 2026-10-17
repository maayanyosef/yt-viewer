---
status: accepted
date: 2026-10-17
---

# A Shuffle play never moves the High-water mark

A Shuffle play leaves a Watch like any other play, but that Watch carries a mark saying Shuffle chose it, and the High-water mark ignores Watches with that mark. Without this, one random pick far ahead of the household's place would jump the High-water mark there, and Next unwatched would stop offering everything in between. Decided on 2026-10-17 while designing Shuffle.

The scenario that decided it: in an oldest-first Source the household has watched up to video 50. Shuffle plays video 400. Under the rule for ordinary plays, the High-water mark jumps to 400, Next unwatched becomes 401, and videos 51 to 399 are not offered again until the Source runs out. With this decision, Next unwatched stays at 51.

## Considered options

- **A Shuffle play is an ordinary Watch.** Rejected: the household loses its place in the Source after one random pick, which breaks the promise in `CONTEXT.md` that a detour never loses the household's place.
- **A Shuffle play leaves no Watch at all.** Rejected: the video would show as unwatched after the household saw it, and Last played could not follow it.

## Consequences

- The Watch log of ADR 0001 gains one field on a played entry. It is hard to remove later, because households will hold entries with it, and a replay on a second device must honour it.
- The High-water mark is no longer "the highest index carrying a Watch" but "the highest index carrying a Watch that is not a Shuffle play". The glossary entry for High-water mark must say so when Shuffle is built.
- A card pressed by hand while Shuffle is on is an ordinary play and does move the High-water mark.
