# Home v2 and albums: pass criteria (PLAN section "Home v2 and albums", 2026-11-12)

Written 2026-11-12, BEFORE any code change for Home v2 and BEFORE the first probe run
(CONTRIBUTING §10). No run and no device result exists when this file is written. The probe is
`HomeV2AlbumsProbeTest`. It is network-gated behind `YT_SMOKE=1`, the same as the other probes:

```
YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*HomeV2AlbumsProbeTest*'
```

The results go to `home-v2-results.md`. Sections 1, 2 and 5 are checked by unit tests and on a
device. Sections 3 and 4 are measured by the probe, and the rules below turn the measurement into
constants. The rules are fixed here, before the data exists, so the data cannot choose its own
rule.

## Words in this file

- **Station:** what plays, with its source (radio, playlist, channel, album, Liked, Liked radio).
- **Snapshot:** one station as Room stores it: source, the song that played, its position, the
  upcoming list, the list place and round, shuffle and repeat.
- **Long item:** a related video from 25 to 120 minutes (`ALBUM_MIN_SECONDS..ALBUM_MAX_SECONDS`,
  GUESSES of the PLAN).
- **Album title:** a title with the word "album" in it, any case, accents allowed ("álbum",
  "albüm"). "Full album" is a case of it.
- **Chapters:** the video's YouTube chapters (`StreamInfo.streamSegments`). A video "has
  chapters" with 3 or more of them. Three is YouTube's own minimum for a chapter list (YouTube
  Help, "Video chapters"), so it is not a GUESS.
- **Compilation words:** "mix", "playlist", "best of", "greatest hits", "compilation",
  "nonstop", "non-stop", "hour", "hours", "top", "songs". A title with one of them reads as a
  compilation, not an album. The list is a judgement made here.
- **Album candidate:** a long item with an album title or with chapters, that passes the related
  gate (`passesRelatedGate`, the radio's gate, unchanged).

## 1. Continue resume (unit tests and device)

A station that was left by a station change, or that played when the app was closed, comes
back from a Continue card exactly as it was left.

- **Pass:** the same source and title, the same song, the playhead within 1 s of where it was
  left (a station change saves the last position tick, 500 ms old at most), the same upcoming
  list in the same order, the same list place and repeat round, shuffle and repeat as they were.
  A list in its last song with repeat off has already rolled into a radio; the card then resumes
  that radio, never the finished list.
- **Pass (app closed while playing):** the playhead within `SNAPSHOT_SAVE_MS` (10 s, GUESS) of
  where it was when the process died. The position is saved on that interval while a song
  plays.
- **Pass:** stream links are never stored. A resumed song resolves a fresh stream link.
- **Pass:** after an app restart, Previous on the first song of a new station brings back the
  station that played before the restart (Room backs the station stack).
- **Fail:** any other song, a position more than the bound above from the left position, a
  changed or reordered upcoming list, a reset list place, or shuffle or repeat changed.

Unit tests cover each pass line. On a device: play a playlist to its third song, seek to 1:30,
tap a Home card, then tap the playlist's Continue card. Record the `TubeStation` lines
`snapshot saved` and `snapshot restored` and the playhead shown.

## 2. Listen again eligibility (unit tests)

The row shows older items only, picked at random on each load:

- liked songs and liked albums that were neither liked nor played in the last 30 days
  (`LISTEN_AGAIN_AGE_DAYS`, GUESS), and
- songs (in the radio's length window) played 3 or more times (`LISTEN_AGAIN_MIN_PLAYS`, GUESS)
  and not played in the last 30 days.

Never the playing song, and never the song or album of a Continue card.

- **Pass:** the unit tests show each rule at its boundary (29 and 30 days, 2 and 3 plays), and
  the two exclusions.
- **Fail:** any item outside the rules, or the playing song or a Continue item in the row.

## 3. Full albums: hit rate and filters (probe; rules fixed now)

Seeds: 10 songs of the kind a listener likes, across genres and sizes. The first video result of
each query is the seed, and the results file records its id.

1. "Radiohead Karma Police", 2. "Fleetwood Mac Dreams", 3. "Miles Davis So What",
4. "Daft Punk Get Lucky", 5. "Rammstein Du Hast", 6. "Burna Boy Last Last",
7. "Buena Vista Social Club Chan Chan", 8. "Fairuz Kifak Inta", 9. "Ichiko Aoba Asleep Among
Endives", 10. "Tame Impala The Less I Know The Better".

Per seed, the probe takes every related video item NewPipe returns (the full list, about 20)
and prints for each: rank, id, duration, album title (yes or no), compilation words (yes or
no), verified, views, gate (pass or fail). For every item from 15 to 150 minutes it fetches the
item's `StreamInfo` once and prints its chapter count. Per seed it also runs the fallback search
"<seed uploader> full album" (first page, video results) and prints the same fields.

### Q1: Do the fetches work? (pass/fail, asserted)

- **Pass:** every seed resolves and lists 15 or more related video items.
- **Fail:** a fetch throws or lists fewer than 15. A `BotCheck` or a rate limit is not a
  result: record it, change nothing, run again from another network.

### Q2: How many related items are long (measurement)

Pooled and per seed: related items, long items, and the share of long items. Ranks 1 to 8 and
9 to 20 separately.

### Q3: The duration window (rule)

Population **A**: every related or fallback item from 15 to 150 minutes with an album title.

- **Keep 25 to 120 minutes** when 80 % or more of A lies inside it.
- **Else** the window becomes the p10 to p90 of A's durations, rounded outward to 5 minutes.
- **Inconclusive:** A holds fewer than 15 items. The GUESSES stay, and the code comment says so.

### Q4: Album-ness (rule)

Of the long items, the probe counts: album title, chapters, both, and "chapters only" (chapters
and no album title).

- **Chapter test kept as is** when half or fewer of the "chapters only" items carry compilation
  words.
- **Else** a "chapters only" item must also be free of compilation words. An album title alone
  still qualifies.
- **Inconclusive:** fewer than 10 "chapters only" items. The chapter test then stays as the
  PLAN states it.

### Q4b: Chapter checks per load (rule)

The related list does not say whether an item has chapters; each check is one more `StreamInfo`
fetch. The row checks the long, gated items without an album title, at most
`FULL_ALBUM_CHAPTER_CHECKS` per load.

- **Value:** the median per seed of such items, times `FULL_ALBUM_SEEDS` (Q5), rounded up, and
  at most 8. The cap of 8 is a cost choice made here (eight parallel fetches on a Home load, the
  same order as the "More like" rows), not a measurement.
- **Skip the checks** (value 0) when Q4 finds that 10 % or fewer of all album candidates qualify
  by chapters alone: the fetches would buy almost nothing.

### Q5: Hit rate (measurement; sets the fallback trigger)

Per seed: album candidates from the related list (after the window, Q4 and the gate), and from
the fallback search. The mean and the spread (min, median, max) per seed.

- The row needs `FULL_ALBUMS_MIN_CARDS` = 4 cards (GUESS: one screen width of cards) before it
  skips the fallback search.
- **Seeds scanned per load** (`FULL_ALBUM_SEEDS`): the smallest count whose expected candidates
  (count times the median per seed) reach 4, between 2 and 5. Each seed costs one `StreamInfo`
  fetch, so 5 is the cost cap. If the median is 0, the value is 5 and the fallback carries the
  row.

### Q6: Chapter coverage (measurement)

- Of long items with an album title: the share with chapters. An album without chapters plays as
  one track; this share is how often album playback has chapter Next and Previous.
- Of all long items: the share with chapters.
- The chapter count and the shortest chapter, for each item with chapters.

## 4. Chapter playback (unit tests and device)

- **Pass:** in album mode with chapters, Next seeks to the next chapter's start; on the last
  chapter, Next ends the album. Previous past 3 s into a chapter (`PREVIOUS_RESTART_MS`) restarts
  that chapter; at or under 3 s it goes to the previous chapter's start; on the first chapter it
  acts as Previous on a song (the station's previous song, or the station before). The Queue
  sheet lists all the chapters in order: the played ones dimmed, the playing one highlighted.
- **Pass:** an album without chapters plays as one track: Next ends it, and Previous acts as on a
  song.
- **Pass:** the album's end rolls into a radio from the album. The title becomes
  "Radio · <album>", and the chip "Album ended · continuing as Radio" shows. A playlist and a
  channel show the same chip with their own word at their end.
- **Pass:** the heart in album mode saves or removes the album. It never adds songs to the queue,
  whatever "When you like a song" says.

## 5. Library and likes (unit tests)

- **Pass:** Library shows Songs (liked songs), Albums (liked albums), History. The Liked songs
  list, Play, Shuffle, Liked radio and Home's "More like" rows never include an album.
- **Pass:** an export carries songs and albums; an import restores both kinds. A file from an
  earlier build (no kind field) imports every entry as a song.
- **Pass:** the database migration keeps every row of `liked_track` and `played_track`.

## Caveats

One network, one day and 10 seeds give a shape, not a distribution of all music. YouTube's
related list changes between fetches and between clients; this run measures neither.
