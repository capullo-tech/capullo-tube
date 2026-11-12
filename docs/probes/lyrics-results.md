# Lyrics probe: results

The criteria are in `lyrics-criteria.md`; read the two files together. Three runs and a
rerun: the first run (a probe script, before any app code) decided that the feature is worth
adding. The second run (`LyricsProbeTest`, the app's own parser and LRCLIB code) measured the
parser and the description detector; the detector failed. The third run measured the tightened
detector on the same videos and on held-out videos, and a rerun on the committed code repeated
it.

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

## Second run (2026-11-12, `LyricsProbeTest`, desktop JVM, home network)

The app's own code: `LyricsQuery` (the parser), `LrclibClient` (get, then search, a 5 s
tolerance) and `DescriptionLyrics` (the section 3 rule). The same 123 rows as the first run
(`app/src/test/resources/lyrics/owner-songs.json`). One run, 214 s. The raw `PROBE` lines are in
the test XML of that run; the numbers below are copied from it.

### Answer first

- **Q1 PASS.** LRCLIB answered all 113 lookups.
- **The parser: PASS on every line of section 2.**
  - Coverage: **104 of 123 (0.85)** are hits (criterion: 80 %, 99 rows). First run: 90 (0.73).
    Liked songs: **10 of 10** (first run: 9).
  - Gain: **14 of the 17 target rows** are hits now (criterion: 9).
  - No loss: **0** first-run hits became misses (criterion: 2 or fewer).
  - Right song: all **13 new videos (14 rows)** are the right song. The LRCLIB artist and title
    are the video's artist and song, and the durations are 0 to 3 s apart.
- **The description detector (section 3 rule): FAIL.** 31 blocks accepted, **2 wrong**
  (criterion: fewer than 2): a credits list of names, and the second song of a medley. By
  section 3 the rule was tightened before the source could ship (section 3b; third run below).
- Synced: 103 of 104 hits have LRC lyrics. One is plain only (`伍佰 - 挪威的森林`).
- Sources: 100 hits from `/api/get`, 4 from `/api/search`.
- LRCLIB lookup time: **median 320 ms, maximum 1566 ms** (the first lookup, which opens the
  connection), over 113 lookups. The timeouts (10 s to connect, 15 s per request) are far above
  that. They stay GUESSES: nothing was measured on a phone network.

### The parser: new hits

| Video title | Query (artist, song) | LRCLIB match | Gap |
|---|---|---|---|
| `張學友 一千個傷心的理由` (2 rows) | 張學友, 一千個傷心的理由 | 張學友 - 一千個傷心的理由 | 2.0 s |
| `@AmrDiab - Tamally Maak \| ...` | Amr Diab, Tamally Maak | Amr Diab - Tamally Ma'ak | 0.0 s |
| `庾澄慶 Harlem Yu - 春泥` | 庾澄慶, 春泥 | 庾澄慶 - 春泥 | 2.0 s |
| `張宇 Phil Chang - 曲終人散 The Curtain Falls (官方完整版MV)` | 張宇, 曲終人散 | 張宇 - 曲終人散 | 2.0 s |
| `張宇 Phil Chang - 用心良苦 Well-Intentioned (官方完整版MV)` | 張宇, 用心良苦 | 張宇 - 用心良苦 | 1.4 s |
| `張雨生 Tom Chang - 大海 (official 官方完整版MV)` | 張雨生, 大海 | 張雨生 - 大海 | 0.3 s |
| `twenty one pilots: Stressed Out [OFFICIAL VIDEO]` | twenty one pilots, Stressed Out | Twenty One Pilots - Stressed Out | 2.0 s |
| `twenty one pilots: Heathens (from Suicide Squad: The Album) [...]` | twenty one pilots, Heathens | twenty one pilots - Heathens | 0.0 s |
| `張信哲 Jeff Chang [ 過火 ] 官方完整版 Official MV` | 張信哲, 過火 | 張信哲 - 過火 | 2.0 s |
| `江蕙 -夢中的情話 MENG CHUNG DE CHING HUA(...)` | 江蕙, 夢中的情話 | 江蕙 - 夢中的情話 | 1.0 s |
| `動力火車 Power Station [ 忠孝東路走九遍 ] Official Music Video` | 動力火車, 忠孝東路走九遍 | 動力火車 - 忠孝東路走九遍 (search) | 3.0 s |
| `周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video` | 周杰倫, 晴天 | 周杰倫 - 晴天 | 0.0 s |
| `伍佰 Wu Bai&China Blue【挪威的森林 Norwegian forest】...` | 伍佰, 挪威的森林 | 伍佰 - 挪威的森林 (plain) | 1.0 s |

The 3 target rows that still miss have the right query:

- `張學友 ‧ 聽海` (query 張學友, 聽海): no LRCLIB entry within 5 s. [INFERENCE] a duration
  mismatch or no entry; the probe does not print the nearest entry.
- `盧冠廷 莫文蔚【一生所愛 ...】...原创remix`: a 417 s remix. A match would be another recording.
- `光良 Michael Wong【童話】Official Music Video`: a 457 s video of a song of about 4 minutes
  [INFERENCE: an MV with a long intro or outro]. The tolerance refuses it, as designed.

The other 16 misses are the rows that section 2 calls "not a parse problem".

### The description detector, section 3 rule

All 113 descriptions were fetched. 31 had an accepted block: 26 on videos with LRCLIB lyrics
and 5 on LRCLIB misses. Every block was read:

- **29 are the lyrics of the song.** Some carry one extra line: a "LYRICS" or `Lyrics for "..."`
  heading, a promo line, the song's own title, a "Thank you for watching" line. The share of
  block lines found in LRCLIB's lyrics is low for some right blocks: 0.00 to 0.04 for four Arabic
  songs and 0.07 to 0.21 for three Chinese songs. [INFERENCE] LRCLIB spells those lines
  differently (other diacritics, the other Chinese script); the LRCLIB text was not read. So
  the share alone does not decide, and each block was read.
- **2 are wrong:**
  - `MACKLEMORE & RYAN LEWIS - CAN'T HOLD US`: the video's crew list (names under "Stedicam
    Operators", "Production Assistants"). One name came back under a second role, which was the
    only "repeated line".
  - `王杰 - 《一场游戏一场梦》 ~ 王傑 –《 我是真的愛上你 》~ 江美琪 - ...` (a 13-minute medley):
    the description holds the lyrics of all three songs, and the longest block was the second
    song.

Yield: of the 19 LRCLIB misses, 5 had an accepted block; 4 of them are right (a remix of
`一生所愛`, the bilingual `Something Just Like This`, Black Violin's `Legends in the Making`,
Beyond's `真的爱你`). The first run's sample counted 7 of 15 with 12 or more short lines; the
strict rule accepts fewer, as intended.

## Third run (2026-11-12, `LyricsProbeTest`, desktop JVM, home network)

The tightened detector of criteria section 3b. One run, 287 s. LRCLIB coverage did not change:
104 of 123 hits, liked 10 of 10, 14 of 17 target rows, 0 lost; lookup time median 324 ms,
maximum 1180 ms.

### Answer first

- **The tightened detector: PASS on section 3b.** The description source ships.
- **A (the same 113 videos): 28 blocks accepted, 0 wrong.** The credits list and the medley are
  gone. The fix cost 1 right block: Adele's `Skyfall`, a short excerpt with no two lines
  repeated together. LRCLIB has that song, so the listener loses nothing there.
- **B (held out): 44 related videos, 8 blocks accepted, 8 right, 0 wrong** (criterion: at most
  1 wrong with fewer than 10 blocks).
- Yield: in A, the description fills 4 of the 19 LRCLIB misses (`一生所愛` remix,
  `Something Just Like This` with a Chinese translation, `Legends in the Making`, `真的爱你`). In
  B, 3 of the 8 accepted blocks are on videos where LRCLIB found nothing.

### B, held out

The 10 liked songs gave 44 related videos in the radio's 60 s to 15 min window that are not
among the owner's songs. Every description was fetched. 8 blocks were accepted:

| Video | LRCLIB | Share | Read |
|---|---|---|---|
| Gorillaz - Clint Eastwood | yes | 1.00 | lyrics |
| Bastille - Pompeii | yes | 0.77 | lyrics |
| Linkin Park - Numb | yes | 0.76 | lyrics |
| Calvin Harris - Outside ft. Ellie Goulding | yes | 0.37 | lyrics |
| جورج وسوف - حلف القمر (George Wassouf) | yes | 0.25 | lyrics |
| F.I.R. 飛兒樂團 - 我們的愛 | no | | lyrics |
| TANK [ 非你莫屬 It had to be you ] | no | | lyrics, with a spoken intro line |
| 倪安東 Anthony Neely [散場的擁抱 The Last Embrace] | no | | lyrics |

### Rerun on the committed code

The probe ran once more on the code as committed (same day, 267 s), after formatting changes
and a rewrite of `LrclibClient.bestMatch` with the same rule. LRCLIB: the same 104 of 123, 0
lost, lookup median 322 ms, maximum 1225 ms. A: the same 28 blocks. B changed, because NewPipe
listed other related videos: 29 videos, 8 blocks accepted, all 8 lyrics of the song. 3 of them
are also in the third run's B. The 5 new ones: Limp Bizkit `My Way` (share 0.63, with 4 lines
of track metadata above the lyrics), `En - 我走後` (the video title twice above the lyrics),
Fadl Shaker `Law Ala Albi`, Amr Diab `3awdony` (a copyright line above the lyrics), Assi El
Hallani `Bahebek We Bghar`. The last four have no LRCLIB lyrics and were read.

### Spread and what this does not show

[GENUINE UNCERTAINTY] Over both runs, B gave 13 different held-out blocks and 0 wrong. That puts
the true wrong share below about 21 % at 95 % confidence (one-sided exact binomial bound; 31 %
for the third run's 8 alone). That is a weak bound. The rule is strict by design, and A shows
what it refuses, but the precision on descriptions it has not seen rests on 13 blocks. Several
right blocks carry 1 to 4 extra lines (a title, a copyright line, track metadata) at the top.
A wrong card is the cost; the drawer names its source ("From the video description"), so the
listener can tell where the text came from.

Population A was the data the fix was made from, so its "0 wrong" is not a precision estimate
(criteria section 3b).

One network, one day. LRCLIB and YouTube descriptions change over time, so a rerun can differ
without a code change.
