# YT Viewer

YT Viewer is a Google TV app for one household that browses YouTube playlists and channels sequentially, remembers what was watched and when, and jumps back to where the household left off.
Build ticket 67 gave the app this name on 2026-10-17.
These names keep the old spelling on purpose, because a person never sees them:

- the package name `local.youtubetv`,
- the log tag `YouTubeTv`,
- the database file `youtube-tv.db`.

## Language

**Source**:
A YouTube playlist or channel the household has added to the app. The unit within which videos are browsed and watches are tracked.
_Avoid_: Subscription, feed, list

**Direction**:
The per-Source browse order: newest-first or oldest-first. Ordering is always by the video's publish date, never by playlist position.
_Avoid_: Sort order, ascending/descending

**Period**:
A block of a Source's videos that share a calendar year or a calendar month, used to jump around a long Source. The app picks year or month per Source from how densely that Source posts. A Period is never a television season and never a grouping the channel owner made.
_Avoid_: Season, era, chapter, group

**Watch**:
A household-level record that a video was played: when it started, how far it got, and whether it completed. A Watch created by bulk-marking has no date.
_Avoid_: View, history entry, play

**In progress**:
A video whose latest Watch has a position but is not completed.
_Avoid_: Partially watched, paused

**Last played**:
The video the household most recently actually played within a Source. "Jump to last played" lands here. A Bulk mark never moves it.
_Avoid_: Resume point, bookmark, current position

**Next unwatched**:
The first gap after the high-water mark, in the Source's Direction. A gap is a video with no Watch; the high-water mark is the highest index in Direction carrying one, so a gap the household skipped past is never offered again while anything is still ahead. When nothing is ahead it falls back to the first gap in the whole Source. A Newly listed video is the one exception, and only in a newest-first Source. Independent of Last played, so a detour never loses the household's place, and unlike Last played it never moves backwards.
_Avoid_: Up next, continue

**High-water mark**:
The furthest the household has worked along a Source: the highest index, in that Source's Direction, carrying a Watch that is not a Shuffle play. It is what Next unwatched starts from. It follows position, never time, so playing one old video does not move it back. A Shuffle play leaves a Watch but never moves it, so a random pick far ahead does not lose the household's place. A Source with no Watch has none.
_Avoid_: Last watched, watermark, progress point, furthest point

**Newly listed**:
A video a walk listed for the first time, with no ordinary play at or past it in that Source since. It is about when the app first saw the video, never about when YouTube published it. In a newest-first Source it is offered as Next unwatched even though it sits above the high-water mark, because the household never had the chance to skip it. An ordinary play of a video at or past it ends this, and it becomes an ordinary skipped video. A Shuffle play never ends it, so a Newly listed video stays offered until it is watched or an ordinary play reaches or passes it. An oldest-first Source needs no exception: a new video already arrives after the mark.
_Avoid_: New, unseen, fresh, recent, newly published

**Bulk mark**:
Marking every video older or newer than a chosen one in a Source as watched, or as unwatched, in one action. Used to declare a backlog already seen, and to set a Source up for a re-watch from a chosen point.
_Avoid_: Import history, backfill

**Bulk mark range**:
The record a Bulk mark leaves: every video in a Source older, or newer, than a chosen video, set watched or unwatched. A Source may carry several. For any video, the newest range covering it wins. A video discovered later falls inside an existing range without a second Bulk mark. A video with exactly the same publish time as the chosen video, not merely the same day, is covered whichever side the range names, wherever that video sits on the grid (spec section 4.2).
_Avoid_: Bulk mark boundary, watermark, cutoff, watched-through date

**View count**:
YouTube's own public count of how many times a video has been viewed, by anyone. The video card shows it rounded, after the date. It is not a Watch and says nothing about what the household watched.
_Avoid_: Watch count, plays, views on its own

**Shuffle**:
A per-Source setting that makes next, previous and autoplay-next pick the Source's videos in random order instead of in Direction. The grid stays in Direction, and Next unwatched, the High-water mark and Periods are unchanged. A new Source starts with it off.
_Avoid_: Random order, random Direction, shuffle mode

**Shuffle run**:
One pass of Shuffle through a Source, in which every playable video plays once before any plays again. It belongs to the Source and survives the app closing, the days passing and Shuffle being turned off. A video listed during the run joins it, a Bulk mark never changes it, and when it is finished the next one starts at once. The household can also start a new run early, which forgets what the old run played.
_Avoid_: Session, shuffle session, playlist, queue

**Shuffle play**:
A play that Shuffle chose, rather than a card the household pressed. It leaves a Watch and moves Last played like any play, but never moves the High-water mark and never ends Newly listed, so Next unwatched keeps the household's place. A card pressed by hand while Shuffle is on is an ordinary play.
_Avoid_: Random play, shuffled watch

**API key**:
The user's own YouTube Data API key. The app needs it to read Sources from YouTube. Each household creates its own, and no copy of the app ships with one.
_Avoid_: Token, secret, credentials

**Key screen**:
The television screen that asks for an API key. It shows a QR code and an address that open the Key page.
_Avoid_: Setup screen, onboarding, login

**Key page**:
The web page the television serves to a phone or computer on the same home network while the Key screen is open, where the user pastes the API key.
_Avoid_: Phone page, web page, setup page

**Source search**:
Searching YouTube by name for a channel or playlist in order to add it as a Source. Not searching for videos.
_Avoid_: Search, YouTube search
