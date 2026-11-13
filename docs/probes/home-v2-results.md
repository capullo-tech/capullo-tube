# Home v2 and albums probe: results

Run 2026-11-12, desktop JVM, home network, `newpipeExtractor v0.26.5`, one run of
`HomeV2AlbumsProbeTest` (254 s). The criteria were written first in `home-v2-criteria.md`; read
the two files together. The raw `PROBE` lines are in the test XML
(`app/build/test-results/testDebugUnitTest/TEST-...HomeV2AlbumsProbeTest.xml`); the numbers below
are copied from that run. Percentiles are nearest-rank. Counts are pooled over the related and
fallback lists, so a video listed next to two seeds counts twice.

## Answer first

- **Q1 PASS.** Every seed resolved and listed 18 to 20 related videos. No chapter fetch failed.
- **Half of all related videos are long.** 99 of 192 (0.52) run 25 to 120 minutes, the same
  share in ranks 1 to 8 (41 of 80) as in ranks 9 to 20 (58 of 112).
- **Q3: the window stays 25 to 120 minutes.** 104 of the 108 album-titled items from 15 to 150
  minutes (0.96) lie inside it, above the 0.80 the rule asks.
- **Q4: the chapter test stays as the PLAN states it.** 20 of the 48 "chapters only" items (0.42)
  carry compilation words, not more than half.
- **Q5: `FULL_ALBUM_SEEDS = 4`.** The related lists give a median of 1 album candidate per seed
  (min 0, max 8, mean 2.6), so 4 seeds are needed to expect 4 cards. The fallback search gives a
  median of 7 per artist (min 2, max 14, mean 7.8).
- **Q4b: `FULL_ALBUM_CHAPTER_CHECKS = 8`** (the cap). The median seed has 3 long, gated items
  without an album title; 3 times 4 seeds is 12. 29 of the 104 candidates (0.28) qualify by
  chapters alone, above the 0.10 that would skip the checks.
- **Q6: about a third of albums have chapters.** 38 of 104 album-titled long items (0.37), and
  86 of 234 long items (0.37). A chaptered item has 5 to 40 chapters (median 13); its shortest
  chapter is 17 s at least and 144 s at the median.

## Seeds

| # | Query | Seed id | Uploader | Related | Long | Long, gated | Candidates | Fallback candidates |
|---|---|---|---|---|---|---|---|---|
| 1 | Radiohead Karma Police | 1uYWYWPc9HU | Radiohead | 19 | 8 | 4 | 3 | 6 |
| 2 | Fleetwood Mac Dreams | Y3ywicffOj4 | Fleetwood Mac | 20 | 9 | 5 | 2 | 14 |
| 3 | Miles Davis So What | zqNTltOGh5c | Miles Davis | 19 | 15 | 11 | 8 | 13 |
| 4 | Daft Punk Get Lucky | 5NV6Rdv1a3I | Daft Punk | 20 | 7 | 2 | 1 | 9 |
| 5 | Rammstein Du Hast | W3q8Od5qJio | Rammstein Official | 18 | 8 | 0 | 0 | 6 |
| 6 | Burna Boy Last Last | 421w1j87fEM | Burna Boy | 18 | 8 | 1 | 1 | 3 |
| 7 | Buena Vista Social Club Chan Chan | tGbRZ73NvlY | World Circuit Records | 20 | 9 | 5 | 2 | 8 |
| 8 | Fairuz Kifak Inta | nvr4lPoKqzI | UMMENAVEVO | 20 | 16 | 5 | 1 | 2 |
| 9 | Ichiko Aoba Asleep Among Endives | 9aED02XuLwo | ICHIKO AOBA | 18 | 10 | 10 | 7 | 10 |
| 10 | Tame Impala The Less I Know The Better | 2SUwOgmvzK4 | Tame Impala | 20 | 9 | 3 | 1 | 7 |

"Long": 25 to 120 minutes. "Candidates": long, through the related gate, and an album title or
chapters. "Fallback candidates": the same rules on the first 20 results of "<artist> full album"
(the gate without the seed share). The fallback queries used the uploader as `artistName` gives
it, so seed 5 searched "Rammstein Official full album" and seed 8 "UMMENA full album" (a label,
not the artist); both still found albums.

## Q3: The duration window

Population A: 108 album-titled items from 15 to 150 minutes. Inside 25 to 120: 104 (0.96). p10
37.9 min, p90 88.5 min. The 4 outside are 21.9, 22.1, 24.1 minutes (EP-length) and one over 120.
By the rule the window stays 25 to 120 minutes; the constants are now measured, not GUESSES.

## Q4: Album-ness

Of 234 long items: 104 have an album title, 86 have chapters, 38 have both, and 48 have chapters
and no album title. Of those 48, 20 carry a compilation word. By the rule the chapter test stays:
a long item with chapters counts as an album whatever its title says.

For the owner: the "chapters only" list (`PROBE q4 chaptersOnly` lines) holds real albums and
concerts ("Dave Brubeck Quartet - Live In Juan-les-Pins (1967)", "Ichiko Aoba - covers iii
(Bootleg, 2025)", "Tame Impala at Parque de Quetzalcóatl, Mexico, for Cercle") next to mood
playlists ("Radiohead Late Night Drive Songs", "TAME IMPALA, a playlist."). At least 4 playlist
titles escape the compilation words because they write "𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭" in mathematical-bold letters
(the same Unicode trap as the related-quality run). So the 0.42 is a lower bound, and the true
share may be over half. The rule fixed before the run decides, so the chapter test stays;
[INFERENCE] a later run that folds those letters to ASCII could flip it.

## Q5: Hit rate and the fallback

Related candidates per seed: 3, 2, 8, 1, 0, 1, 2, 1, 7, 1 (median 1). Fallback candidates per
artist query: 6, 14, 13, 9, 6, 3, 8, 2, 10, 7 (median 7). The related gate dropped 23 album-like
long items: most are unverified re-uploads ("Fleetwood Mac Greatest Hits Full Album 2026") next
to seeds with hundreds of millions of views, where 0.01 of the seed asks for millions of views.

`FULL_ALBUM_SEEDS = 4` by the rule (4 × 1 reaches 4). With a median of 1, the row will often
need the fallback; one fallback query reaches 4 cards for 8 of 10 artists.

## Q6: Chapter coverage

- Album-titled long items with chapters: 38 of 104 (0.37). Two of three albums play as one
  track, without chapter Next and Previous.
- Long items with chapters: 86 of 234 (0.37).
- Chapters per chaptered item: min 5, median 13, max 40. Shortest chapter: min 17 s, median
  144 s. No chaptered item had fewer than `MIN_CHAPTERS` (3).

## Caveats

One network, one day, 10 seeds and one fetch per seed: a shape, not a distribution of all
music. YouTube's related list changes between fetches and between clients; this run measures
neither. The candidate counts are small (median 1 per seed), so `FULL_ALBUM_SEEDS` can move by
one in either direction on another run.
