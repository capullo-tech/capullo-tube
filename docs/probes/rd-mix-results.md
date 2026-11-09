# RD-mix probe: results

Run 2026-08-18, desktop JVM, home network, `newpipeExtractor v0.26.5`. Criteria
were written first in `rd-mix-criteria.md`; read the two files together. Raw
output lines are in the test XML (`PROBE q…`); the numbers below are copied from
that run.

## Q1 - PASS

Both seeds returned 100 raw items with valid IDs, seed video first.

## Q2 - overlap 0.62 → semi-stable

Two back-to-back fetches of `dQw4w9WgXcQ`'s mix: 53 vs 52 unique IDs, overlap
0.615. Middle band of the criteria: one fetch is a usable session snapshot, but
a refetch on like/re-seed will bring ~40% new material. Dedup by ID is required.

## Q3 - overlap 0.05 / 0.11 → complementary

| seed | mix unique | related | shared | overlap |
|---|---|---|---|---|
| dQw4w9WgXcQ | 60 | 20 | 1 | 0.05 |
| 9bZkp7q19f0 | 64 | 19 | 2 | 0.11 |

Below the 0.2 line: the two feeds see almost entirely different neighbourhoods.
The hybrid design keeps both feeds first-class, as planned.

## Q4 - bookkeeping

- The seed IS the first item of its own mix → the pool must drop it or the
  station opens by replaying the seed.
- The bare `playlist?list=RD<seed>` form ALSO resolves (200 raw items through
  `playlist()`). `mixUrl`'s original assumption that the watch form is required
  was wrong; its KDoc now records that both work.

## Unasked-for finding: mix pagination cycles

Q1 counted 100 raw items; Q2's de-duplicated sets hold ~53. The "endless" mix
playlist repeats after its real depth of ~50-60 unique tracks. Consequences,
already applied in `YouTubeExtractorClient.mix()`:

- dedup by videoId before returning;
- `MIX_RAW_FETCH_LIMIT = 100`: paging further buys repeats, not tracks.

## Caveats

One network, one day, two mainstream seeds. The bands in the criteria doc were
chosen before the run, but n=2 seeds is a shape check, not a distribution. If a
niche-genre seed behaves differently, re-run with those seeds before tuning
pool weights around these numbers.
