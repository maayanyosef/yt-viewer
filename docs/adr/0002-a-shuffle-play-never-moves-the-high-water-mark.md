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

## How it was built (build ticket 69, 2026-10-17)

- **The field.** A `played` entry carries `"shuffle": true` in its payload when Shuffle chose the play. The field is written only when it is true, so an ordinary play's payload is byte for byte what it was before. An entry with no such field reads as an ordinary play, and a replay of an old log gives the High-water mark it always gave.
- **The rule, stated once.** A video can be the High-water mark when the log would make it watched **with every Shuffle play left out of it**, by ADR 0001's own newest-wins rule. So a video played by hand and later by Shuffle is still a mark. An ordinary play undone by `Mark as unwatched` stays undone when Shuffle plays the video again. A Bulk mark that covers a video makes it a mark whatever Shuffle did after.
- **A video only Shuffle played is neither a gap nor a mark.** It carries a Watch, so Next unwatched never offers it. It is not a mark, so the gaps before it stay ahead.
- **Nothing else ignores the field.** A Shuffle play moves Last played, puts the date on the card, and orders the home rail like any play. It also ends a Newly listed video's state, because the glossary's rule for Newly listed is "a play at or past it" and a Shuffle play is a play. Build ticket 69's closing notes say what that costs a newest-first Source.
- **The derived table holds no new column.** It is read only for the position a play resumes from. Next unwatched is always worked out from the log. `DERIVATION_VERSION` moved from 4 to 5 all the same, because the number means "the rules are these".
- **The Shuffle run is not in the log.** It is on the Source's own row, in `sources.shuffle_run`, beside the setting in `sources.shuffle`. Starting a new run writes that row and never a Watch.
