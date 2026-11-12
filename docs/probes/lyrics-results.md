# Lyrics probe: results

The criteria are in `lyrics-criteria.md`; read the two files together. Two runs: the first run
(a probe script, before any app code) decided that the feature is worth adding. The second run
(`LyricsProbeTest`, the app's own parser and LRCLIB code) measures the parser and the
description detector.

## First run (2026-11-12, probe script, desktop, home network)

The songs: the owner's 10 liked songs and 113 played songs, read from the OPPO's database. Per
song: LRCLIB `/api/get` with artist, title and duration, then `/api/search` with "artist title"
and the result nearest in duration. The script read the artist and the title with a few rules:
`Artist - Title`, `Artist《Title》`, else the channel name and the title.

### Answer first

- **Worth adding: PASS on all three criteria.**
- Liked songs: **9 of 10** are hits (criterion: 6 or more).
- All songs: **90 of 123 (0.73)** are hits (criterion: 50 % or more). Played songs alone: 81 of
  113 (0.72).
- Spot check: **12 of 12** hits read by hand are the right song (criterion: 9 of 10).
- Every hit is synced: 90 of 90 have LRC lyrics. No hit was plain only.

### Detail

| | Rows | Hits | Via get | Via search | Loose (5-30 s) | Far (> 30 s) | None |
|---|---|---|---|---|---|---|---|
| Liked | 10 | 9 | 8 | 1 | 0 | 0 | 1 |
| Played | 113 | 81 | 75 | 6 | 1 | 1 | 30 |
| All | 123 | 90 | 83 | 7 | 1 | 1 | 31 |

Duration difference of the 90 hits: median 1.0 s, maximum 3.7 s (one search hit). 89 of 90 are
within 2 s. The one loose match (13.1 s) is the wrong song: "Pre-Production - Part 2" matched an
Alain Bashung track. The one far match (525 s) is a 13-minute medley. This supports a 5 s
tolerance: no right song was outside 5 s, and the only match beyond it was wrong.

By script of the title:

| Title | Rows | Hits | Share |
|---|---|---|---|
| Latin | 81 | 69 | 0.85 |
| Chinese | 33 | 15 | 0.45 |
| Arabic (or an Arabic artist in Latin letters) | 9 | 6 | 0.67 |

### The misses

Most of the 33 misses are parse failures: the script sent LRCLIB the wrong artist or title.
These shapes failed:

- `Artist【Title】`: `周杰倫 Jay Chou【晴天 Sunny Day】`, `光良 Michael Wong【童話】`.
- `Artist [ Title ]`: `張信哲 Jeff Chang [ 過火 ]`, `動力火車 Power Station [ 忠孝東路走九遍 ]`.
- `artist: title`: `twenty one pilots: Stressed Out`.
- `Artist EnglishName - Title`: `庾澄慶 Harlem Yu - 春泥`, `張宇 Phil Chang - 曲終人散 The
  Curtain Falls`.
- A Chinese title with the artist before it and only a space between: `張學友 一千個傷心的理由`.
- Other separators: `張學友 ‧ 聽海`, `@AmrDiab - Tamally Maak`, `江蕙 -夢中的情話`.

The other misses are not songs that LRCLIB can match: a concert, an AI remix album, a medley, a
translation video, a tribute, an extended version, a "full video song" twice the song's length.

### The description (a sample, unverified)

A second script read the YouTube description of the first 40 songs. Of the 15 LRCLIB misses in
that sample, 7 have 12 or more short lines (60 characters or less) with no link, hashtag, `@` or
timestamp. Nobody read them, so some can be credits lists or tracklists, not lyrics. The second
run measures the precision of a strict detector (`lyrics-criteria.md` section 3).

Captions are not a source (owner decision 2026-11-12).
