# Related-quality probe: results

Run 2026-11-12, desktop JVM, home network, `newpipeExtractor v0.26.5`, one run. The criteria
were written first in `related-quality-criteria.md`; read the two files together. The raw
`PROBE` lines are in the test XML
(`app/build/test-results/testDebugUnitTest/TEST-...RelatedQualityProbeTest.xml`); the numbers
below are copied from that run. Percentiles are nearest-rank.

## Answer first

- **Q3 is inconclusive.** The reference set R (verified top-8 items) held 15 items, under the 20
  the criteria require, and its small half held 6, under 8. By the rule written before the run,
  the GUESSES stay: `RELATED_MIN_VIEWS = 10_000`, `RELATED_MIN_SEED_FRACTION = 0.01`. The rule
  would have given 200_000 and 0.0005 from this thin set; those numbers are not used.
- **Most of YouTube's top related items are not songs.** 88 of the 96 top-8 items (92 %) run
  longer than 15 minutes: playlists, "best of" compilations, full concerts, mood mixes. The
  station's length window (60 s to 15 min) removes them before the gate matters.
- **Inside the length window, both gates act the same on this run.** 8 of 96 top-8 items are in
  the window: 6 verified (kept by both gates) and 2 unverified with 79 and 2_800 views (dropped
  by both).
- **Related adds few tracks.** In the window, the top 8 give 0.67 tracks per seed; 7 of 12 seeds
  give none. The pair rule will often fall back to "the Mix fills the related share".
- **No related item repeated the seed's song** (0 of 96, either key). Duplicate titles in the
  Mix plus the top 8: 15 of 781 (2 %).
- **Mix items carry no views and no date** (0 of 300). Related items carry both (96 of 96), and
  90 of 96 dates are approximate.

## Seeds

| # | Query or id | Seed id | Uploader | Seed views | Verified |
|---|---|---|---|---|---|
| 1 | `dQw4w9WgXcQ` | dQw4w9WgXcQ | Rick Astley | 1_836_258_921 | yes |
| 2 | `kJQP7kiw5Fk` | kJQP7kiw5Fk | LuisFonsiVEVO | 9_170_884_003 | yes |
| 3 | `9bZkp7q19f0` | 9bZkp7q19f0 | officialpsy | 6_124_658_019 | yes |
| 4 | Burna Boy Last Last | 421w1j87fEM | Burna Boy | 369_847_875 | yes |
| 5 | Aya Nakamura Djadja | iPGgnzc34tY | Aya Nakamura | 1_071_082_350 | yes |
| 6 | Miles Davis So What | zqNTltOGh5c | MilesDavisVEVO | 33_518_052 | yes |
| 7 | Rammstein Du Hast | W3q8Od5qJio | Rammstein Official | 734_735_802 | yes |
| 8 | Arijit Singh Tum Hi Ho | Umqb9KENgmk | T-Series | 511_922_326 | yes |
| 9 | Buena Vista Social Club Chan Chan | tGbRZ73NvlY | World Circuit Records | 64_363_641 | no |
| 10 | Fairuz Kifak Inta | nvr4lPoKqzI | UMMENAVEVO | 136_233_002 | yes |
| 11 | Mdou Moctar Afrique Victime | q0lPeQA2hu4 | KEXP (live session) | 536_545 | yes |
| 12 | Ichiko Aoba Asleep Among Endives | 9aED02XuLwo | ICHIKO AOBA | 14_250_682 | yes |

Median seed views: 369_847_875 (seed 4). The search picked a KEXP live upload for seed 11, the
only seed under 1 M views.

## Q1: PASS

Every seed resolved. Every seed listed 15 to 20 related video items (8 or more required).

## Q2: The view distribution (top 8, pooled)

96 items, 0 with unknown views, 15 verified (0.16).

| Group | n | Views min | p10 | p25 | median | Ratio to seed p10 | p25 | median |
|---|---|---|---|---|---|---|---|---|
| all | 96 | 66 | 7_600 | 98_000 | 820_000 | 0.00001 | 0.00012 | 0.00256 |
| verified | 15 | 297_000 | 317_000 | 1_500_000 | 3_300_000 | 0.00087 | 0.00406 | 0.11228 |
| unverified | 81 | 66 | 7_600 | 65_000 | 566_000 | 0.00001 | 0.00009 | 0.00153 |

Per seed (top 8):

| Seed | Verified | Views min | median | In window | Kept, rule gate | Kept, GUESS gate |
|---|---|---|---|---|---|---|
| 1 | 2/8 | 154 | 4_500_000 | 1 | 6 | 2 |
| 2 | 1/8 | 7_600 | 280_000 | 0 | 1 | 1 |
| 3 | 3/8 | 754 | 98_000 | 2 | 4 | 3 |
| 4 | 1/8 | 7_600 | 566_000 | 0 | 5 | 3 |
| 5 | 2/8 | 98_000 | 1_000_000 | 2 | 5 | 3 |
| 6 | 1/8 | 141 | 504_000 | 0 | 5 | 5 |
| 7 | 1/8 | 4_900 | 922_000 | 1 | 5 | 2 |
| 8 | 0/8 | 82 | 98_000 | 0 | 4 | 2 |
| 9 | 0/8 | 66 | 257_000 | 0 | 7 | 3 |
| 10 | 0/8 | 79 | 172_000 | 1 | 4 | 3 |
| 11 | 2/8 | 2_800 | 335_000 | 1 | 6 | 7 |
| 12 | 2/8 | 155_000 | 671_000 | 0 | 7 | 8 |

"In window": items from 60 s to 15 min, the station's length window.

## Q3: The gate constants: INCONCLUSIVE, the GUESSES stay

- R: 15 verified top-8 items with known views (20 required).
- Small half (seeds at or below the median seed's views, seeds 4, 6, 9, 10, 11, 12): 6 verified
  items (8 required).
- What the rule would have given: ratio p10 0.00087, so a fraction of 0.0005; small-half views
  p10 297_000, so a floor of 200_000.

`RELATED_MIN_VIEWS` stays 10_000 and `RELATED_MIN_SEED_FRACTION` stays 0.01, both GUESSES. The
code comments cite this file.

## Q4: The gates on this run's own data

| Gate | Unverified top-8 dropped | Top-8 kept | Ranks 9 to 20 kept |
|---|---|---|---|
| Rule (200_000, 0.0005) | 37/81 | 0.61 | 0.70 (n = 119) |
| GUESS (10_000, 0.01) | 54/81 | 0.44 | 0.44 (n = 119) |

The GUESS gate drops more than half of the unverified top-8 items, which is the criteria's flag.
The flag matters less than it reads: nearly all of those items are compilations over 15 minutes,
which the length window drops anyway. The dropped lists (`PROBE q4 ... dropped` lines) are mood
mixes, "greatest hits" compilations, a drama short, a Roblox clip and a 1-hour loop of the seed
song. The GUESS gate drops 17 more unverified items than the rule gate. Most are the 1 M to
8 M view "80s hits", "R&B throwback" and "chill" mixes next to the billion-view seeds, where
0.01 of the seed asks for 10 M views or more.

### Post-hoc, inside the length window (not used to set the constants)

This split was not in the criteria. It is reported because the window removes 92 % of the
population that Q3 and Q4 describe.

| Ranks | Items | In window | Verified in window | Kept, GUESS | Kept, rule |
|---|---|---|---|---|---|
| 1 to 8 | 96 | 8 | 6 | 6 | 6 |
| 9 to 20 | 119 | 11 | 8 | 10 | 11 |

The 5 unverified in-window items over all ranks:

| Seed | Rank | Views | Ratio | Uploader, title | GUESS | Rule |
|---|---|---|---|---|---|---|
| 10 | 3 | 79 | 0.000001 | Fede robloxx, "@Cristofer_videos12" | drop | drop |
| 11 | 5 | 2_800 | 0.0052 | Heartwood Soundstage, "Mdou Moctar" | drop | drop |
| 5 | 14 | 108_000_000 | 0.10 | VOVI Music, "Baby Calm Down (FULL VIDEO SONG)" | keep | keep |
| 8 | 15 | 643_000 | 0.0013 | SR Music, "Agar Tum Saath Ho" | drop | keep |
| 11 | 12 | 4_900_000 | 9.1 | KEXP, "Mdou Moctar - Tarhatazed (Live on KEXP)" | keep | keep |

The only in-window disagreement between the two gates is a real song (seed 8, rank 15), outside
the top 8. [INFERENCE: with one disagreement in 19 in-window items, this run cannot rank the two
gates.]

`RELATED_TOP_K` stays 8 (a GUESS). For the owner: in the window, ranks 1 to 8 give 8 tracks over
12 seeds, and ranks 9 to 20 give 11 more.

## Q5: Same-song rules

- Seed title filter: 0 of 96 top-8 items share the seed's title key (0 of 96 with the loose key
  too). On this run the filter changes nothing; the related list holds compilations, not other
  uploads of the seed song.
- Duplicate titles in the Mix plus the top 8: 15 of 781 items (0.02) share the title key of an
  item before them. The probe did not print those 15 items one by one, so this file cannot
  list them. At least one of the 15 is not a real match: two titles in seed 12's top 8 are
  "𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭" in mathematical-bold letters, which the key's letter test does not see, so both
  keys were empty and matched. The code's key (`TitleCleaner.key`, written after this run) is
  null for such a title and matches nothing. The probe now uses that key and prints every
  duplicate (`PROBE q5 duplicate`), for the next run.
- The loose key finds 55 of 781 (0.07). Its 40 extra matches are false: titles in the "Song -
  Artist" order ("... - Fairuz", "... – Linkin Park") all collapse to the artist's name. The
  loose key is worse than the title key; there is nothing to report to the owner beyond that.

## Q6: The display line

- Top-8 related items: views known 96 of 96, a date 96 of 96, approximate 90 of 96 ("2 yr ago";
  the 6 exact ones are "11 hr ago"-type texts).
- Mix items (first page of each Mix, 300 items): views 0 of 300, a date 0 of 300. Up next rows
  of Mix tracks show no views line, and the duplicate rule compares their views as unknown, so a
  related upload of the same title with known views wins over the Mix copy.
- Seeds (`StreamInfo`): views and an exact date for all 12.

## Caveats

One network, one day, 12 seeds, one fetch per seed: a shape, not a distribution of all music.
YouTube's related list changes between fetches, and a logged-out desktop client may get a
different list than a phone; this run measures neither. The verified set is small (15), which is
why Q3 is inconclusive. A second run with more seeds, or with the top 20, can settle Q3; the
criteria then apply unchanged.
