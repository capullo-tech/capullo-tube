# RD-mix probe: pass criteria

Written BEFORE the first probe run (CONTRIBUTING §10). The probe is
`RdMixProbeTest`, network-gated behind `YT_SMOKE=1`. It answers the questions the
hybrid station design (HANDOFF-bootstrap §5) depends on. Seeds: two mainstream
music videos old enough to have deep mixes - `dQw4w9WgXcQ` and `9bZkp7q19f0`.

## Q1 - Does the mix fetch work at all? (pass/fail)

Fetch `watch?v=<seed>&list=RD<seed>` through `PlaylistInfo`.

- **Pass:** both seeds return ≥ 10 items with valid 11-char video IDs.
- **Fail:** either seed throws, or returns < 10 items. The station then leans on
  related-streams alone and `mix()` is parked until the extractor pin moves.
- **Inconclusive:** one seed works and the other doesn't → run two more seeds
  before concluding anything; a single video can be RD-poor for its own reasons.

## Q2 - Snapshot stability (measurement, not pass/fail)

Fetch the same seed's mix twice, back to back. Record the Jaccard-ish overlap
(shared IDs / smaller list).

- ≥ 0.8: a stable snapshot → the pool can trust one fetch per seed for a session.
- 0.3 - 0.8: semi-stable → refetch on like/re-seed, dedup by ID matters.
- < 0.3: effectively fresh each fetch → the mix is a sampler, weight it low and
  refetch freely.

## Q3 - Overlap between mix and relatedStreams for the same seed (measurement)

- > 0.6: largely redundant → related-streams (cheaper, no session) carries the
  pool; mix adds little beyond ordering.
- 0.2 - 0.6: partial overlap → hybrid as planned.
- < 0.2: complementary → hybrid earns its keep; keep both feeds first-class.

## Q4 - Bookkeeping facts (record, they shape dedup)

- Is the seed video itself in its own mix?
- Item count per fetch (before our limit).
- Does the bare `playlist?list=RD<seed>` form also resolve? (`mixUrl` currently
  assumes the watch-form is required - that assumption is labelled as such in
  its KDoc and this is what verifies or kills it.)

## What failure looks like

A probe that throws `BotCheck` is not a probe result - it is the whole source
saying no from this IP. Record it, change nothing in the design, re-run from
another network before drawing conclusions.
