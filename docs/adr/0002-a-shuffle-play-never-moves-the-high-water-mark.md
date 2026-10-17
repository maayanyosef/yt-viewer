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
- **Nothing else ignores the field, except Newly listed since build ticket 72, "A Shuffle play never ends Newly listed".** A Shuffle play moves Last played, puts the date on the card, and orders the home rail like any play. _Superseded on 2026-10-17, see the next section:_ as first built, it also ended a Newly listed video's state, because the glossary's rule for Newly listed was "a play at or past it" and a Shuffle play is a play. The closing notes of build ticket 69, "Shuffle: play a Source in random order", say what that cost a newest-first Source.
- **The derived table holds no new column.** It is read only for the position a play resumes from. Next unwatched is always worked out from the log. `DERIVATION_VERSION` moved from 4 to 5 all the same, because the number means "the rules are these".
- **The Shuffle run is not in the log.** It is on the Source's own row, in `sources.shuffle_run`, beside the setting in `sources.shuffle`. Starting a new run writes that row and never a Watch.

## Changed on 2026-10-17: a Shuffle play never ends Newly listed (build ticket 72, "A Shuffle play never ends Newly listed")

Build ticket 69, "Shuffle: play a Source in random order", read the glossary's "a play at or past it" literally, so a Shuffle play ended a Newly listed video's state. In a newest-first Source a Newly listed video sits at the top, and nearly every Shuffle play is past it. So after the first Shuffle play the new video was no longer offered as Next unwatched, though nobody had watched it. The owner was shown that cost and chose the other answer, for the reason this ADR gives: Shuffle is a detour that never costs the household its place, and a new video it never saw is part of that place.

- **The rule now.** Only an ordinary `played` entry ends the Newly listed state. A Shuffle play leaves the flag alone, in storage and on the screen. A Newly listed video stays offered as Next unwatched until it is watched, or until an ordinary play reaches or passes it.
- **A Shuffle play of the Newly listed video itself** gives it a Watch, so Next unwatched no longer offers it. Its flag stays set and simply stops mattering, because a watched video is not a gap. If the household later uses `Mark as unwatched` on it, it is a Newly listed gap again, which is the same answer as if Shuffle had never played it.
- **Nothing in the log changed.** The flag lives on the video cache, not in the Watch log, so no entry is read differently and `DERIVATION_VERSION` stays at 5. A log from before build ticket 69 has no Shuffle mark, so every play in it is ordinary and it gives the answer it always gave.
- **A flag a Shuffle play already cleared is not restored.** Between the install of build ticket 69 and the install of build ticket 72, a Shuffle play past a Newly listed video set its flag to false. The video cache does not record why a flag is false, so this build cannot tell such a flag from one an ordinary play cleared, and a walk never sets a cleared flag again. A household that made no Shuffle play in that window lost nothing. The Watch log shows whether it did: look for a `played` entry carrying `"shuffle": true`.
