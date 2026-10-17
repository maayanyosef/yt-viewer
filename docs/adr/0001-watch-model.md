---
status: accepted
date: 2026-09-19
ticket: charting ticket 10, the Watch model
---

# The Watch model is an append-only typed log with a disposable derived table

Watches are stored on the TV in v1, but cloud sync will replace that local storage in a later
phase, so the model has to be replayable on a second device. We therefore make the log of the
household's own actions the single source of truth, keep a derived per-video table purely for
fast reads, and never delete anything.

This is the hard-to-reverse part of the app. Everything else in v1 is a screen.

## The log

One table. Every row is an entry. Entries are never edited, with exactly one narrow exception
(see "Position", below).

Every entry carries:

- `id`: random, unique.
- `deviceId`: which device wrote the entry.
- `seq`: a per-device monotonic sequence number.
- `at`: wall-clock time.
- `kind`: see below.

Ordering rule: within one device, order by `seq`. Across devices, order by `at`.

We store `seq` as well as `at` because an Android TV commonly boots with a wrong clock and
corrects it over the network seconds later. Wall-clock time can therefore jump backwards on one
device. Last played is derived purely from ordering, so a clock jump would silently send the
household to the wrong video. One integer per row removes that failure entirely. Time-ordered
ids such as ULID were considered and rejected for the same reason: they encode the same clock.

### Entry kinds

- `played`: one play of one video.
  - Carries `videoId`, `sourceId`, `position`, `completed`.
- `unwatched`: the household marked one video as not watched.
  - Carries `videoId`, `sourceId`.
- `range`: a Bulk mark range. One entry covers many videos.
  - Carries `sourceId`, `pivotPublishedAt`, `side` (`older` or `newer`), `watched`
    (`true` or `false`), and `clearsLastPlayed` (`true` or `false`).
  - `clearsLastPlayed` is set only by the Source-wide `Mark everything unwatched` action. See
    "Bulk mark ranges" below.
- `sourceAdded` and `sourceRemoved`: the lifecycle of a Source, keyed by its playlist id.

An entry whose `kind` this build does not recognise is preserved, ignored during derivation, and
written back untouched. A mixed-version household must not lose data, and the dangerous failure
is the silent one: an older TV dropping what a newer phone wrote, with neither device reporting
anything.

## What a Watch is

A Watch is an event, not a state. Every play appends a new `played` entry; a re-watch never
edits an older one.

A Watch is household-level, which is what `CONTEXT.md` already says. `sourceId` is stored on the
entry, because Last played is defined per Source and the history is worth keeping, but the
question "has this video been watched?" is answered by `videoId` alone, across every Source. The
same video can sit in a channel's uploads and in a curated playlist; the household watched it
once. The cost is that Next unwatched in a borrowed playlist can skip a video for a reason that
is not visible on that screen, so a video card shows its watched check no matter which Source
earned it.

## Position

Exactly one entry is mutable, and only while the play it belongs to is on screen.

Ticket 01 established that the IFrame Player has no progress event, so position is polled and
written every few seconds. Appending an entry per write would make the log mostly noise and
useless for sync. Instead, a play appends one `played` entry when it starts, that entry's
`position` and `completed` change until the play ends, and then it is frozen forever.

So the log is append-only per play, not per position tick. This is a deliberate softening of
"append-only" and is stated here so nobody later mistakes it for a bug.

When completion flips is **not** decided here. Ticket 11 decides it, using what the ticket 05
device run proves about `ENDED`.

## Bulk mark ranges

A Bulk mark writes exactly one `range` entry, never one entry per video.

This solves a problem ticket 09 found: a full walk can discover a video that was never listed
before but has an old publish date, for example an upload made public months late. With per-video
Watches that video would have no Watch, would become a gap, and would drag Next unwatched
backwards into a range the household had already declared watched. A range entry covers it
automatically, with no fix-up code at walk time.

`watched: false` gives the household two things that per-video entries made awkward:

- Mark an entire Source as unwatched.
- Re-watch a Source from a chosen point, which is one entry: pivot at that video, side `newer`,
  `watched = false`.

A range carries only a pivot date and a side, so it covers **every** video in that date range,
including Shorts that the Shorts filter is currently hiding. This is deliberate. A hidden Short
is a video the household chose not to see, so declaring it watched costs nothing, while the
opposite would mean turning Shorts on one day and finding hundreds of unwatched Shorts inside a
range already marked. Giving the range an `includesShorts` field was rejected: it would freeze
the state of a UI toggle into a record that cloud sync replays forever.

`clearsLastPlayed` exists for one action only. `Mark everything unwatched` is an explicit "I am
starting this Source over", so leaving the Jump to last played chip pointing into the middle of
that Source contradicts what the household just asked for. A range with `clearsLastPlayed = true`
makes Last played derive as nothing for that Source until the next `played` entry. No other range
touches Last played.

### Amended 2026-09-21 by build ticket 42: the `newer` side ends at the entry's `at`

A range with `side = newer` covers a video when **both** of these hold:

1. The video's publish date is not **before** `pivotPublishedAt`. This is the original rule.
2. The video's publish date is not **after** the entry's own `at`.

So a `newer` Bulk mark means "everything from this video up to now", and not "everything from
this video onwards, for ever".

**What was wrong.** With no far end, a `newer` range covered every video YouTube would ever
publish in that Source. A new upload has a later publish date than any range already written, so
it arrived already watched, was never a gap, and was never offered as Next unwatched. On
`ערוץ לדוגמה - כל הסרטונים`, the one Source the household really watches, one Bulk mark made on
2026-09-20 would have silenced the Source for ever.

**The `older` side is not touched, and needs no far end.** Its closed end is the pivot, and the
pivot faces the direction new videos arrive from. A future upload is newer than the pivot, so it
can never fall inside an `older` range. The open end of an `older` range points into the past,
and the only thing that can appear there is a video published long ago and listed late. Covering
that video is what this section already asks for.

**Nothing is written, nothing is migrated, and the log's shape does not change.** The bound is
the entry's own `at`, a field every entry has always carried. No payload field was added. A
range already in the household's log therefore gained its far end the moment this build shipped,
and a replay on a second device reads the same answer. This is why ADR 0001's "entries are never
edited" rule is kept: the Bulk mark the owner asked to amend is not amended, only read with the
field it already had.

The central case above still works on the `newer` side. A video published **before** the Bulk
mark and listed weeks later is still covered, because its publish date is before the entry's
`at`.

**The bound is a wall clock, and that was accepted.** `at` comes from a television that can boot
with the wrong clock, which is why `seq` exists. The failure here is mild and one-sided: a clock
set in the past writes a bound that is too early, so the Bulk mark covers fewer videos than the
household meant, and the household can make it again. The alternative, bounding by the newest
video the Source had listed at the time, is not stored and would need a new field on every
range.

`clearsLastPlayed` is **not** date-tested, before this amendment or after it. Such a range clears
Last played for the whole Source, and its side, its pivot and now its far end are all not
consulted.

`DERIVATION_VERSION` moved from 3 to 4, which rebuilds the derived table on the next launch.

## Deriving watched state

For one video:

1. Take the newest entry that names the video directly, if any: a `played` or an `unwatched`.
2. Take the newest `range` entry in that Source that covers the video. What "covers" means is
   the whole of "Bulk mark ranges" above, including the 2026-09-21 amendment: on the `newer`
   side the entry's own `at` is a second end.
3. Whichever of the two is newer wins.
4. A `played` or a `range` with `watched = true` means watched. An `unwatched` or a `range` with
   `watched = false` means not watched.

So a later `unwatched` beats an earlier bulk mark, and a later bulk mark beats an earlier
`unwatched`. One rule, stated once.

**Next unwatched** is the first video in the Source's Direction that has no Watch, is not hidden
by the Shorts filter, is not unavailable, and is not an upcoming premiere or scheduled stream.
Any Watch closes the gap, complete or not: a video the household started and abandoned is not
Next unwatched. That video is reached through Last played, which is what that chip is for.

**Last played** is the video of the newest `played` entry in the Source. A `range` never moves
it, with the single exception of a range carrying `clearsLastPlayed = true`, which makes Last
played derive as nothing until the next `played` entry. A freshly added Source that was only
bulk-marked has no Last played at all and its chip is disabled, because nobody ever sat and
watched anything there.

## One display rule that does not follow "newest wins"

Derivation decides *whether* a video is watched. It does not decide what the card says.

When a range written today covers a video the household actually played in June, newest-wins
makes the video watched, which is correct. But labelling the card "Watched before September"
would replace a fact with a vaguer fact. So: if any `played` entry exists for a video, the card
shows that entry's date, even when a newer `range` is what makes the video watched.

This is a display rule only. It never changes watched state, Next unwatched, or Last played.

## Skipping is a rule, not a stored flag

Three separate facts make a video skippable, and they change at different rates:

- `isShort`, from ticket 02. Immutable, classified once, defaults to not-Short on failure.
- Unavailable, from ticket 03. A private or deleted video still appears in `playlistItems.list`
  with no `videoPublishedAt` and is missing from `videos.list`. It can come back.
- `liveBroadcastContent = upcoming`. It becomes `live` and then `none` on its own.

Only the raw fields are stored. Whether to skip is decided at query time by one rule in one
place. Two of the three facts change with no household action, and the rule itself will move —
the Shorts definition alone already changed in October 2024. A query-time rule is a code change;
a stored flag is a migration.

## The derived table

One row per video: watched, latest position, completed, the newest `played` entry's time. It
exists so Next unwatched stays cheap on a 3,000-video Source. It can be thrown away and rebuilt
from the log at any moment, which means a bug in the derivation is a rebuild, not lost history.

A `derivationVersion` is stored beside it and bumped in code whenever the rule changes. On start,
a mismatch triggers a rebuild. A manual rebuild is also exposed. Rebuilding on every launch was
rejected: ticket 09 worked to keep the launch path clear, and this would put work back on it.

## Videos are cache, not history

Every field of a video comes from the API and can be fetched again, so the video list is not
synced and is rebuilt by re-walking. Syncing it would mean copying tens of thousands of rows that
any device can fetch for one unit per fifty.

One exception: the moment a video gets its first Watch, its title and publish date are copied
somewhere durable. Ticket 09 decided a vanished video is kept if it has a Watch, and a pure cache
cannot keep what the API no longer returns. Without this the household's history would show a
bare video id.

## Sources

Source lifecycle goes in the log as `sourceAdded` and `sourceRemoved`, keyed by the playlist id,
which ticket 08 fixed as the natural key. Removal is a tombstone: the Source is hidden, its
entries are kept, and re-adding the same playlist id restores every Watch and every range.

Source settings — Direction, the Shorts toggle, the layout choice — are ordinary mutable state
with `updatedAt`. They are not history. Putting them in the log would make it grow every time
somebody flips a chip to look at something, which is noise in the thing cloud sync has to replay.

## The log is never compacted in v1

A household watching ten videos a day for ten years writes about 36,000 entries of a few dozen
bytes each. Bulk marks are one entry each, not one per video. A few megabytes after a decade is
not a problem worth solving, and compaction is the most dangerous operation possible on an
append-only log.

## What was rejected

- **Mutable rows with `updatedAt` and last-writer-wins.** The obvious design. It cannot replay,
  so cloud sync would have to invent conflict resolution per field.
- **A log with no derived table.** Fine at ten entries, miserable at ten thousand.
- **Per-video Bulk mark entries.** Three thousand rows per Bulk mark, and still wrong for videos
  discovered later. This was ticket 09's provisional choice, which ticket 09 explicitly left to
  this ADR; the objection it recorded — that unwatch would need a tombstone — disappeared once
  `unwatched` became an ordinary entry kind.
- **A tombstone naming the single Watch it cancels.** The household's intent is "this video is
  not watched", not "cancel that particular play from last Tuesday". A video with three plays
  would need three tombstones.
