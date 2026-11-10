# Trending-music kiosk probe: pass criteria

Written BEFORE the first probe run (CONTRIBUTING §10). The probe is
`YouTubeTrendingMusicProbeTest`, network-gated behind `YT_SMOKE=1` (same gate as
the smoke test and the RD-mix probe). It answers the questions the home
recommendation cards depend on: whether the `trending_music` kiosk
(`charts.youtube.com/charts/TrendingVideos/RightNow`, registered by the pinned
extractor v0.26.5) can feed a launch screen.

## Q1 - Does the kiosk fetch work at all? (pass/fail)

Fetch the kiosk through `KioskInfo.getInfo(ServiceList.YouTube, url)`.

- **Pass:** ≥ 10 items with valid 11-char video IDs.
- **Fail:** throws, or returns < 10 items. The home cards then have no
  cold-start source; M-A stops there for an owner decision (a search-based
  fallback is a different design and must not be silently substituted).
- **Inconclusive:** `BotCheck` - that is the whole source saying no from this
  IP, not a kiosk verdict. Record it, re-run from another network before
  concluding anything (same rule as the RD probe).

## Q2 - Card art availability (pass/fail for art cards)

Share of items whose thumbnails list yields a `thumbnailUrl`.

- **≥ 0.9:** art cards as designed (Coil + tonal placeholder for the rest).
- **0.5 - 0.9:** mixed; keep art cards but the placeholder is a common sight -
  acceptable, record the number.
- **< 0.5:** art cards are mostly placeholders; the owner decides between
  list-rows and art cards before Coil lands.

## Q3 - Duration shape (measurement, not pass/fail)

Share of items inside the station's playback window (60 s - 15 min,
`StationEngine.MIN/MAX_TRACK_SECONDS`). Cards display whatever the kiosk
returns; this number tells us how often a tapped card seeds a station whose
first pool skips long-form or sub-minute entries. No display filtering is
planned regardless - record, don't act.

## Q4 - Snapshot stability + bookkeeping (measurement)

Two back-to-back fetches: item count each time, overlap (shared IDs / smaller
list), and fetch latency. The home feed is fetched fresh per launch by design
(Jetispot's model), so stability only matters as a fact, not a gate. Also
record: does the list look like music (titles), and the host locale (the
client pins `ContentCountry` to the device locale, so the kiosk's region
follows it).
