# Radio: pass criteria (PLAN section "Radio", approved 2026-11-11)

Written BEFORE any code change for the new radio and BEFORE the first listening test
(CONTRIBUTING §10). Updated 2026-11-11 BEFORE the code change for "Songs from each Mix" (owner
decisions of that date: one number N drives Blend in and Liked radio; a like acts in lists too;
Liked radio is likes plus N from each like's Mix). Updated 2026-11-12 BEFORE the code change for
Previous (section 13; owner decision of that date: Previous goes back for real, across station
changes). Updated 2026-11-12 BEFORE the code change for Pairs (sections 2, 14 to 18; owner
decisions of that date: every fetch from a song takes its Mix and its top related videos, in
pairs; a gate for related videos; one copy of each song title; N becomes "Songs added per like",
0, 2, 4 or 6; views and year on cards and rows; the radio title names its origin). Updated
2026-11-12 BEFORE the code change for Home v2 (owner decision of that date: each fetch takes
every related video NewPipe returns, about 20, not the top 8). No listening
result exists when this file is written. The numbers come from GUESS constants, the setting's
default and one probe run (`related-quality-results.md`). Nothing here is measured by listening.

## Tools

- `adb logcat -s TubeStation`: one `key=value` line per event. The lines for this test are
  `start`, `advance`, `chain`, `related gate`, `like`, `liked-radio`, `shuffle`, `skip`,
  `remove` and `previous`.
- `adb shell dumpsys activity service tech.capullo.tubecapullo/.player.PlaybackService`: the
  source, the current Mix seed, the upcoming list with provenance, shuffle, like mode, Songs
  added per like (`songs-per-like=`), the last chain seed, the Previous state (`back:` line), the
  pair rule (`pairs:` line) and the constants.
- `adb logcat -s TubeHome`: one `moreLike` line per "More like" row.
- The Up next sheet: Earlier, Now, Up next. Each row says where it came from.

## Constants and settings under test

| Value | Name | Value used here |
|---|---|---|
| Chain when fewer than this many tracks are upcoming | `CHAIN_BELOW` | 5 (GUESS) |
| Songs added per like (N): a Blend in like, and each like in Liked radio | setting `AppSettings.songsPerLike` | default 2 (owner decision), 0, 2, 4 or `MAX_SONGS_PER_LIKE` = 6 (GUESS) |
| Track length window | `MIN_TRACK_SECONDS..MAX_TRACK_SECONDS` | 60 s .. 15 min (GUESS) |
| Upcoming tracks Liked radio keeps queued | `LIKED_RADIO_LOOKAHEAD` | 6 (GUESS) |
| Previous restarts the track above this playhead | `PREVIOUS_RESTART_MS` | 3 s (GUESS) |
| Stations that Previous can go back to | `STATION_BACK_STACK` | 5 (GUESS) |
| Related videos taken from each fetch, in YouTube's order | (no cap) | all NewPipe returns, about 20 (owner decision 2026-11-12) |
| Related gate: views floor | `RELATED_MIN_VIEWS` | 10_000 (GUESS; the probe was inconclusive) |
| Related gate: share of the seed's views | `RELATED_MIN_SEED_FRACTION` | 0.01 (GUESS; the probe was inconclusive) |
| Cards in a "More like" row | `CARDS_PER_ROW` | 10 (GUESS) |

## Words in this file

- **Fetch:** the Mix and the related videos of one song, fetched together.
- **Related list:** every related video of the song that NewPipe returns (about 20), in
  YouTube's order, after the related gate.
- **Pair:** one Mix track and one related track of the same fetch. Which of the two comes first
  is random per pair. The Mix tracks keep the Mix order and the related tracks keep the related
  order.
- **Merge:** the pairs of a fetch, one after the other. When one list runs out, the rest of the
  other list follows.
- **Chain:** a `chain` log line. It appends the merge of the last song played.
- **Seed:** the song a fetch is for (`seed=`).
- **N:** the value of Settings > "Songs added per like". The dump shows it as `songs-per-like=`.
- **Title key:** the cleaned title (TitleCleaner), compared without case, spaces or
  punctuation.
- **Track k after an action:** k = 1 is the first track that starts after the action.
- **Untouched:** no tap, no like, no skip and no Up next change during the run.
- **Station change:** a `start` line that replaces the station: a song tap, Go to radio, the
  channel line, a card, a list. A roll-over and a Switch now like are not station changes.

## 1. An hour untouched (song radio)

Procedure: start a song radio from a song tap. Let it play for 60 minutes untouched.

- **Pass:** all of these are true.
  - No video id is in two `advance` lines.
  - Each `chain` line has a `seed=`, a `mix=+a` and a `related=+b`. Each chain seed is the id of
    the `advance` line before it, or the start seed for `reason=start`.
  - A `chain reason=low` line comes when the upcoming count goes below 5. The dump never shows 0
    upcoming tracks while a chain is not in flight.
  - Each `advance` line shows `dur=` from 60 to 900 seconds.
  - Each `advance` line before the first `reason=low` chain shows `prov=SEED`, or `prov=MIX` or
    `prov=RELATED` with `from=<start seed>`. The order follows section 14.
- **Fail:** a repeated id, a chain without a seed, a track outside the window, or a `loop waiting`
  line that does not end within one chain fetch.
- **Inconclusive:** a `resolve ... result=skipped` or `recover ... action=skip` line. A skipped
  stream changes the count, but it is not a radio defect. Also inconclusive: a bot check from
  YouTube.

## 2. One list empty

Procedure: start song radios until a `chain` line shows `empty=related` (the probe run makes
this common: in the length window, 7 of 12 seeds had no related track in the top 8). If a song
without a Mix is at hand (for example a fresh upload with few views), start a radio from it too
and look for `empty=mix`. If neither shows, mark the case as not run.

- **Pass (`empty=related`):** the line shows `mix=+a related=+0 empty=related` with a > 0. The
  upcoming tracks of that chain are all `prov=MIX from=<seed>`, in Mix order.
- **Pass (`empty=mix`):** the line shows `mix=+0 related=+b empty=mix` with b > 0. The tracks are
  all `prov=RELATED from=<seed>`. Up next says "Related to <song>".
- **Fail:** an `empty=` field on a line where both counts are above 0, or a missing `empty=`
  field where one count is 0 and the other is not.
- **Inconclusive:** both lists come back empty (`mix=+0 related=+0`).

## 3. Skip

Procedure: in a song radio, press Next 5 times in a row.

- **Pass:** each Next logs `skip id=<id>` and starts the next upcoming track. The upcoming list
  after the skips is the list before the skips minus the skipped tracks. No other line changes the
  queue (no `chain` unless the count went below 5).
- **Fail:** a skip removes or reorders other upcoming tracks, or a skipped id plays again in this
  session.

## 4. Like, mode Blend in (default), in a radio

Procedure: Settings > "When you like a song" > Blend in. Settings > "Songs added per like" = 2.
In a song radio, write down the upcoming list. Like the current song S. Then set 4 and do it
again with the next song.

- **Pass:** the log shows `like mode=blend source=song seed=<S> mix=+1 related=+1`. The chip says
  "Added 2 songs like <S>". Tracks 1 and 2 after the current song are one `prov=MIX from=<S>
  blended` and one `prov=RELATED from=<S> blended`, in either order. The Mix track is the first
  unplayed track of the Mix of S. Tracks 3 and up are the upcoming list written down before the
  like, in the same order, minus any of the 2 that were already in it.
- **Pass (N = 4):** `mix=+2 related=+2`. Tracks 1 and 2 are one pair, tracks 3 and 4 the next.
- **Pass (one list short):** `mix=+2 related=+0 empty=related` for N = 2: the Mix gives both.
- **Pass (several likes):** a like of track 1 while it plays puts the 2 new tracks first, with
  the 1 remaining track of the first like between them (new, old, new).
- **Fail:** more or fewer than N in total while the two lists of S had N or more unplayed tracks
  in the window, a pair with two tracks of one list while the other list still had tracks, or
  the old upcoming list lost or reordered a track.
- **Inconclusive:** the two lists of S together had fewer than N such tracks.

## 5. Like, mode Blend in, in a playlist or a channel

Procedure: Blend in, N = 2. Play a playlist of at least 5 tracks, in order, repeat off. While
list track 2 plays, write down the dump's `order:` line and the upcoming list. Like the current
song S.

- **Pass:** the log shows `like mode=blend source=playlist seed=<S> mix=+1 related=+1` (or
  `mix=+2 related=+0 empty=related`). Tracks 1 and 2 after S show `from=<S> blended`. Track 3 is
  list track 3. While the 2 blended tracks play, the dump's `order: ... round=2/<M>` does not
  change, and the source stays the playlist. Under repeat all, the next round still starts after
  the list's last track. Repeat the check in a channel (`source=channel`).
- **Fail:** a list track is lost, moved or played twice in the round, the `round=` position moves
  while a blended track plays, or the source changes.
- **Inconclusive:** the two lists of S had fewer than 2 unplayed tracks that are not already in
  the upcoming list.

## 6. Songs added per like = 0

Procedure: Blend in, set Songs added per like to 0. Like the current song in a song radio and in
a playlist.

- **Pass:** the log shows `like mode=blend source=<song|playlist> seed=<S> +0
  reason=songs-per-like-0`. No chip shows. The upcoming list does not change. No fetch for S
  (no `like seed=<S> mix-error`, `related gate seed=<S>` or `result=dropped` line either).
- **Fail:** any change to the upcoming list caused by the like.

## 7. Like, mode Save only

Procedure: set Save only. Write down the upcoming list. Like the current song.

- **Pass:** the log shows `like mode=save-only ... +0`. No chip shows. The upcoming list is the same
  as before the like. The heart is filled and Library > Songs has the song.
- **Fail:** any change to the upcoming list caused by the like.

## 8. Like, mode Switch now

Procedure: set Switch now. Like the current song S in a song radio. Then do it again in a
playlist.

- **Pass (radio):** the log shows `like mode=switch-now source=song seed=<S> mix=+a related=+b`.
  The chip says "Switching to songs like <S>". S plays on to its end. Tracks 1 and up are the
  merge of S (section 14). The dump shows `chain-seed=<S>`. Play next pins stay first. The Player
  title does not change.
- **Pass (playlist):** the log shows `like mode=switch-now source=playlist seed=<S> mix=+a
  related=+b`. After it the dump shows `source: source=song seed=<S>`, the Player title is
  "Radio · <S>" and the repeat button is gone. S plays on. Tracks 1 and up are the merge of S. No
  list track plays again. A `chain reason=low` line comes when fewer than 5 tracks are upcoming.
- **Fail:** S stops, a track from the old upcoming list plays before the merge of S runs low,
  or in a playlist the source stays the playlist after a switch that added tracks.

## 9. Likes in Liked

Procedure: play Liked. Unlike an upcoming song. Unlike the current song and like it again.

- **Pass:** the like logs `like mode=none source=liked +0`. The unlike of the upcoming song
  removes that track from the list (owner decision). Nothing else changes in the upcoming list.
- **Fail:** any other change to the upcoming list.

## 10. Shuffle in a radio

Procedure: in a song radio, write down the upcoming list. Turn shuffle on. Let a chain happen.

- **Pass:** the log shows `shuffle on=true rest=n`. The current song plays on. The upcoming list
  has the same tracks in a new order. The next chain line shows `shuffled=true` and its tracks are
  not in merge order. The Player shows shuffle and does not show repeat.
- **Fail:** the current song changes, a track leaves or joins the upcoming list, or repeat shows.

## 11. Liked radio

Procedure: like at least 10 songs. Set Songs added per like to 2. Start Liked radio from Library
> Liked > Radio and look at the first 12 `advance` lines and the `liked-radio` lines. Then set 0
and start again. Then, with N = 2, turn shuffle off and look at the next 2 rounds.

- **Pass:** all of these are true.
  - Shuffle shows on at the start. The Player title is "Radio · Liked songs".
  - N = 2: the `advance` lines follow like, new, new, like, new, new, ... Each like logs
    `liked-radio like=<L> ... mix=+1 related=+1` (or `mix=+2 related=+0 empty=related`). The 2
    new tracks after like L are a pair of L: `prov=MIX from=<L>` and `prov=RELATED from=<L>`.
    Over the rounds, the Mix tracks of L follow the Mix order in the dump. No new track is a
    liked song.
  - N = 0: every `advance` line is a like (`liked-radio like=<L> ... +0`), and the station does
    not stop.
  - No like plays twice before every like played once (`round=` counts up to the like count,
    then starts at 1).
  - Shuffle off: the log shows `shuffle on=false`. The likes after the current unit play in the
    order of Library > Songs (newest first), skipping likes already played in this round. The
    next round starts with the newest like.
- **Fail:** a like with fewer than N new tracks while the dump showed that the two lists of
  that like had N unplayed tracks in the window, a new track that is a liked song, a like
  repeated inside a round, or a shuffle-off round not newest first.
- **Inconclusive:** a like with fewer than N new tracks and no more unplayed tracks in the window
  in either list.

## 12. A like in Liked radio

Procedure: Blend in, N = 2. In Liked radio, like a new (not liked) song S while it plays.

- **Pass:** the log shows `like mode=blend source=liked-radio seed=<S> mix=+1 related=+1`.
  Tracks 1 and 2 after S are a pair of S, then the Liked radio queue goes on as before the like.
- **Pass (Switch now):** the log shows `like mode=switch-now source=liked-radio seed=<S>`. The
  dump then shows `source: source=song seed=<S>`, and the Player title is "Radio · <S>".
- **Fail:** the like changes the queue in any other way.

## 13. Previous

Procedure: start a song radio from a song R. Let 3 tracks start (R1, R2, R3). Write down the
upcoming list U while R3 plays. Tap the channel line. When the channel's first track C1 plays,
press Previous 4 times, each within 3 s of the track start. Then let a track play past 3 s and
press Previous. Repeat the procedure with the Player button, the notification, the lock screen
and a Bluetooth device.

- **Pass:** all of these are true.
  - Press 1 logs `previous action=restore-station from=channel to=song id=<R3>`. R3 plays from
    the start. The Player title is "Radio · <R>". The upcoming list starts with U, in the same
    order.
  - Press 2 logs `previous action=song id=<R2>`. R2 plays. Up next starts with R3, then U.
  - Press 3 logs `previous action=song id=<R1>`. Up next starts with R2, R3, then U.
  - Press 4 logs `previous action=restart`. R1 plays from the start.
  - Previous past 3 s logs `previous action=restart` and the same track plays from the start.
  - In a second run, press Next after press 2. R3 plays. The next Next plays the first track
    of U.
  - The Player button shows the Previous icon. TalkBack reads "Previous".
- **Pass (list):** play a playlist in order, repeat all. Let list tracks 1 to 3 start. Go to
  radio from track 3. In the radio, press Previous within 3 s. The log shows
  `previous action=restore-station from=song to=playlist`. Track 3 plays from the start. The dump
  shows `order: ... round=3/<M>`, `repeat=ALL` and the shuffle state from before. The next
  tracks are list tracks 4, 5 and up.
- **Pass (Liked radio):** in Liked radio, write down the dump's `liked radio: round=` line and
  the upcoming list. Go to radio from a song, then press Previous within 3 s. The log shows
  `to=liked-radio`. The dump shows the same `round=`. The upcoming list starts with the list
  written down, in the same order.
- **Pass (back stack):** start a station, then make 6 station changes, each while the first
  track of the station plays. Then press Previous within 3 s of each track start. The log shows
  5 `action=restore-station` lines, newest station first, then `action=restart`. The first
  station does not come back.
- **Fail:** a Previous that skips forward, a restored station with a different upcoming list,
  list place, repeat or shuffle, a song lost from Up next after a step back, or a Previous that
  shows no `previous` log line.
- **Inconclusive:** a `resolve ... result=skipped` line during the procedure. The loop then
  plays the track after the one Previous chose.

## 14. Pairs in a chain

Procedure: start a song radio from a song S. Before the first track ends, read the `chain
reason=start` line and the first 12 upcoming rows in the dump. Let the radio play to the first
`chain reason=low` line and read it the same way.

- **Pass:** all of these are true.
  - The `chain` line shows `mix=+a related=+b` with b at most 20 (all related videos).
  - While both lists last, upcoming rows 1 and 2, 3 and 4, and so on, each hold one
    `prov=MIX from=<S>` and one `prov=RELATED from=<S>`.
  - Within those pairs, the Mix track comes first in some pairs and second in others (with 3
    or more pairs; a 1 in 4 chance that 3 pairs all start with the same list).
  - The `prov=MIX` rows keep the Mix order of S. The `prov=RELATED` rows keep YouTube's order.
  - After the last related track, the rest of the Mix follows in Mix order.
- **Fail:** two tracks of one list in a pair while the other list still has tracks, a list out
  of its own order, or more than 20 related tracks from one fetch.
- **Inconclusive:** `empty=related` or `empty=mix` (see section 2), or b < 2.

## 15. Related gate and one copy of each title

Procedure: during sections 1 and 14, read every `related gate` line and the dump's upcoming
list.

- **Pass:** all of these are true.
  - Each fetch logs `related gate seed=<id> kept=a dropped=b` with a + b at most 20, before its
    `chain`, `like` or `liked-radio` line.
  - Each `prov=RELATED` row is from a verified channel, or shows at least 10K views on its
    views line, and at least 1 % of the views of its seed.
  - No `prov=RELATED` row has the title key of its seed.
  - No two upcoming rows of one fetch have the same title key. A `dupes=n` field on the
    `chain`, `like` or `liked-radio` line counts the copies the rule removed. Of two copies,
    the one with more views stays.
- **Fail:** a related row from an unverified channel under 10K views or under 1 % of its
  seed's views, a related row with its seed's title key, or two rows of one fetch with one
  title key.
- **Inconclusive:** no `dupes=` field in the run. The probe run found 15 copies in 781 items
  (2 %), so a short run can show none.

## 16. More like rows

Procedure: like at least 2 songs. Open Home and read the "More like" rows and the `moreLike`
lines in `adb logcat -s TubeHome`.

- **Pass:** each `moreLike` line shows `mix=a related=b cards=c` with c at most 10. While both
  lists last, cards 1 and 2, 3 and 4, and so on, each hold one track of the liked song's Mix and
  one of its related videos. No card is a liked song, no card is outside the length window, and
  no two cards of a row have the same title key.
- **Fail:** more than 10 cards, a liked song or a title key twice in a row, or two cards of one
  list in a pair while the other list still has cards.
- **Inconclusive:** a row whose related list was empty after the gate (all cards from the Mix).

## 17. The views line

Procedure: open Home, Search (Songs) and the Up next sheet of a radio.

- **Pass:** a card or row with a known view count or year shows a line after the channel line:
  "1.2M views · 2019", "34K views", or the year alone. Related rows and search results show it.
  Mix rows show no such line (the probe found no views and no date on Mix items).
- **Fail:** a line with a wrong number format ("1200000 views", "1.0M"), a "-1", or an empty
  line.

## 18. Settings and the Player title

Procedure: open Settings. Step "Songs added per like" from 0 to its top and back. Start a song
radio, a search radio and Liked radio, and let each chain once.

- **Pass:** the stepper shows 0, 2, 4, 6 and stops at 0 and 6. The line under it says "After a
  liked song, and between songs in Liked radio". The Player title reads "Radio · <seed>" for a
  song radio, "Radio · "<query>"" for a search radio and "Radio · Liked songs" for Liked radio.
  A chain does not change the title.
- **Fail:** an odd value, a step of 1, or a title that follows the chain seed.

## What the results change

- A fail in 1 to 8 or 11 to 18 is a code defect. Fix it before any tuning.
- A pass with a bad listening feel is a tuning result. Record which constant or setting felt
  wrong and in which direction before you change a GUESS.
- An inconclusive result changes nothing. Run again with a different seed.
