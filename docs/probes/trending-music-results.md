# Trending-music kiosk probe: results

Run 2026-11-10, desktop JVM, home network, `newpipeExtractor v0.26.5`, host
locale `en`. Criteria were written first in `trending-music-criteria.md`; read
the two files together. Raw output lines are in the test XML (`PROBE q…`); the
numbers below are copied from that run.

## Q1 - PASS

The `trending_music` kiosk returned 30 items with valid 11-char IDs. First
five titles are recognisably music ("Top Uh It", "take me home (4 christmas)",
"Mr. Do Something", "I'm The Prize", "POKITO"). 30 is the chart's full depth -
`limit` above 30 buys nothing.

## Q2 - PASS: share 1.0 → art cards as designed

30/30 items carry a `thumbnailUrl` (best-of list, per `toYtVideo`). Every
card can be an art card; the tonal placeholder stays only for load failures.

## Q3 - share 1.0 in the 60 s - 15 min window

All 30 items sit inside `StationEngine.MIN/MAX_TRACK_SECONDS`, zero unknown
durations. A tapped card seeds a station whose whole chart is playable
material - no display filtering needed (as planned: record, don't act).

## Q4 - overlap 1.0, ~0.9 s per fetch

Two back-to-back fetches: 30 vs 30 IDs, overlap 1.0 (it is a chart snapshot,
not a shuffle). Latency 909 ms / 877 ms - fine for a fresh fetch per launch,
the Jetispot home model.

## Consequences

- M-A proceeds: art cards, no display-side duration filter, no feed cache.
- Region: the kiosk's country follows `ContentCountry` pinned from the device
  locale at `ensureInitialized` - emulators and devices with a locale country
  get that country's chart (host run had `en`/no explicit country, i.e. the
  default chart).

## Caveats

One network, one day, one locale. The chart rotates on YouTube's schedule
(daily-ish); overlap 1.0 says nothing about cross-day stability, which the
home does not depend on anyway (fresh per launch).
