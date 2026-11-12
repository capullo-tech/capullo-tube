# Lyrics: pass criteria (owner decisions 2026-11-12)

Written 2026-11-12, BEFORE the lyrics code and BEFORE the second probe run (CONTRIBUTING §10).
Section 1 is a copy of the criteria that were written before the first run, the same day. The
first run is in `lyrics-results.md`. Sections 2 and 3 are new. They are fixed here, before the
parser exists and before the second run, so the data cannot choose its own rule.

The second run is `LyricsProbeTest`. It is network-gated behind `YT_SMOKE=1`, the same as the
other probes:

```
YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*LyricsProbeTest*'
```

## Words in this file

- **Songs:** the owner's own songs, read from the OPPO's database on 2026-11-12: 10 liked
  songs and 113 played songs (123 rows, 113 videos; a liked song can also be a played song). The
  probe keeps this exact list (id, title, channel, duration), so the two runs compare.
- **Hit:** LRCLIB gives lyrics, and the duration of the LRCLIB entry is within 5 s of the
  video's duration (`LYRICS_DURATION_TOLERANCE_S`, GUESS from the first run). Videos often have
  an intro, so a match from 5 to 30 s off is "loose" and a match more than 30 s off is "far".
  Loose and far are not hits.
- **Synced:** LRC lyrics with a time for each line. **Plain:** lyrics without times.
- **Parse:** the artist and the song title that the app reads from the video title and the
  channel name. LRCLIB gets them.
- **Description block:** the lines of a video description that the description detector accepts
  as lyrics.

## 1. Is the feature worth adding (first run; copied, not changed)

Question: for the owner's own songs, how often do we get lyrics, and are they the right song?

Sources, in order: LRCLIB `/api/get` (artist, track, duration), then LRCLIB `/api/search` with
the cleaned title, then the YouTube description (lyrics pasted in). No captions.

Worth adding if:

- liked songs: 6 or more of 10 are hits, and
- all songs: 50 % or more are hits, and
- spot check of 10 random hits: 9 or more are the right song.

Not worth it if all songs are under 30 %. From 30 % to 50 %: inconclusive; the owner decides.
Also report: the synced and plain shares, and which languages miss.

## 2. The parser (second run)

The first run read the artist and the title with a short probe script. Most of its misses were
parse failures, not songs that LRCLIB does not have. The app's parser must read these shapes:

| Shape | Example from the songs |
|---|---|
| `Artist【Title】` | `周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video` |
| `Artist [ Title ]` | `張信哲 Jeff Chang [ 過火 ] 官方完整版 Official MV` |
| `artist: title` | `twenty one pilots: Stressed Out [OFFICIAL VIDEO]` |
| `Artist EnglishName - Title` | `庾澄慶 Harlem Yu - 春泥` |
| `歌手 歌名` (a Chinese name, a space, the song) | `張學友 一千個傷心的理由` |
| other separators | `張學友 ‧ 聽海`, `@AmrDiab - Tamally Maak`, `江蕙 -夢中的情話` |
| title only, the artist is the channel | `吻別`, channel `張學友 Jacky Cheung` |
| `Artist《Title》` (the first run had it) | `范逸臣 Van Fan《放生》官方MV` |

The first run had 33 misses. The parser targets the 17 rows whose title has one of the shapes
above (the liked song `張學友 一千個傷心的理由` is also a played song, so it is 2 rows). The
other 16 rows are not a parse problem: two concert recordings, an AI remix album, a medley, a
translation video, a tribute, an extended version, a remix, a "full video song" of twice the
song's length, a lyrics video of 14 minutes, an instrumental, two videos that name the artist
after the song or behind an honorific (`真的爱你 Beyond`, `天王刘德华`), two titles in two scripts
with translated names, and the one loose match.

The parser passes if all of these are true:

- **Coverage:** all songs: 80 % or more are hits (99 or more of 123). Liked songs: 6 or more of
  10 (the first-run rule).
- **Gain:** 9 or more of the 17 target rows are hits.
- **No loss:** 2 or fewer first-run hits are now misses, and the results file explains each one.
- **Right song:** of the new hits, 9 or more of the first 10 are the right song (artist and
  title read against the video title). With fewer than 10 new hits, at most 1 is wrong.

The parser fails if the gain is under 5 rows, or if 3 or more first-run hits are lost, or if 2 or
more new hits are the wrong song. Between pass and fail: inconclusive. The parser then ships, and
the results file lists each shape that still misses.

The probe also reports the time of each LRCLIB lookup (median and maximum), because the
LRCLIB timeouts are GUESSES.

## 3. The description detector (second run)

The detector is strict by design. A non-blank line counts as a lyric line only when it:

- is 60 characters or shorter (`MAX_LINE_CHARS`, the first run's "short line"),
- has no link (`http`, `www.`), no hashtag, no `@` handle and no timestamp (`1:23`, `01:23:45`),
- is not a credits line: no `詞`, `曲`, `編曲`, `作詞`, `作曲`, `製作`, `Producer`, `Composer`,
  `Lyricist`, `Director`, `Mixed` or `Mastered` label,
- is not a label line that starts with a short label and a colon (`Label: value`).

A block is a run of lyric lines. Blank lines between stanzas do not break it, and neither does a
section label: a line in brackets (`[Chorus]`) or a label with nothing after its colon
(`Chorus:`, `歌詞：`). A section label does not count as a lyric line. Any other line breaks the
block. The detector accepts the longest block only when it has 12 or more lyric lines AND at
least one line that repeats inside the block (a chorus). It returns that block as plain lyrics.

Population: every video of the songs. The probe fetches each description once (NewPipe
`StreamInfo`) and runs the detector on it.

- **Precision (the gate):** every accepted block is read. With LRCLIB lyrics for the same
  video, the probe prints the share of block lines that LRCLIB's lyrics also contain. A block with
  a share of 0.5 or more is right. Without LRCLIB lyrics, the block is read by hand and printed in
  full.
  - **Pass:** 9 or more of 10 accepted blocks are lyrics of the song. With fewer than 10
    accepted blocks, at most 1 is wrong.
  - **Fail:** 2 or more accepted blocks are credits, a tracklist, links, prose or another song.
    The description source then does not ship until the rule is tightened and measured again.
  - **Inconclusive:** fewer than 3 blocks are accepted. The source ships, because a strict rule
    can show a wrong card only rarely, and the results file says that the precision is
    unmeasured.
- **Yield (a measurement, not a gate):** how many LRCLIB misses the description fills, next to
  the first run's 7 of 15 with 12 or more short lines.

## 4. The app (unit tests; device check by the owner)

- **Pass (unit tests):** the parser shapes above and the first-run hit shapes; LRC parse
  (`[mm:ss.xx]`, `[mm:ss]`, `[mm:ss.xxx]`, several stamps on one line, tags and text without
  a stamp); the duration tolerance at its boundary; the description detector accepts a lyrics
  block and refuses a credits block and a link list; the current line at, before and after each
  stamp; the cache retry rule ("none" retries after `LYRICS_RETRY_DAYS`, lyrics never); the 5 to
  6 migration keeps every row.
- **Device (owner):** a song with lyrics shows the card under the controls, and the card does
  not overlap the control rows on a 360 dp and a 393 dp wide screen. The current line follows
  playback. A tap or a drag up opens the drawer, which has the Queue sheet's two steps and height
  cap. A tap on a synced line seeks there. A song without lyrics shows no card. `adb logcat -s
  TubeLyrics` shows one line per song.

## Caveats

One person's songs, one network and one day. The song list leans to Mandarin pop and to rock in
English. LRCLIB is a public database that changes, so a first-run hit can become a miss without
a code change; the probe reruns the first run's lookup for each lost hit to tell the two apart.
