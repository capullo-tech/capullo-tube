# Region probe: pass criteria

Written BEFORE the first probe run (CONTRIBUTING §10, PLAN item 14). No run
exists when this file is written. The probe is `RegionMixProbeTest`. It is
network-gated behind `YT_SMOKE=1`, the same as `RdMixProbeTest`.

## The question

Does the content country (`gl`) change Mix and related results for the same
seed?

The answer decides one design point. The chart region in Settings applies to
the trending chart only (`YouTubeExtractorClient.trendingMusic`). Today the
station's feeds (`mix`, `relatedStreams`) use the device-locale country that
`ensureInitialized` pins globally. If the country changes those feeds, the
device locale steers the station without the user knowing. A station region
would then be a deliberate, separate feature. If the country does not change
them, the region stays a chart-only setting and nothing more is needed.

## Method

- Countries: `US` and `MX`. Both are in the extractor's supported list.
  Spanish-language music gives `MX` the best chance to show an effect.
- Language: fixed at `en-US` for both countries, so only `gl` changes.
- Seeds, three:
  - `dQw4w9WgXcQ`: English, global catalogue. It is also a seed of the RD-mix
    probe, so the numbers can be compared.
  - `kJQP7kiw5Fk`: Spanish-language hit. This is the seed most likely to show
    an `MX` effect.
  - `JGwWNGJdvx8`: English pop, a more recent catalogue.
- Per seed and per feed: four fetches, in the order US-A, MX-A, US-B, MX-B.
  The order spreads time drift evenly over both countries.
- Overlap of two fetches: shared IDs divided by the size of the smaller set.
  The RD-mix probe uses the same metric, so its 0.62 Mix stability is
  comparable.
- `within`: the mean of overlap(US-A, US-B) and overlap(MX-A, MX-B). This is
  the noise floor: the same seed and the same country, fetched twice.
- `cross`: the mean of the four US-versus-MX pairs.
- `gap = within - cross`. A real country effect makes `gap` positive. No effect
  keeps `gap` near zero.

The probe prints every pair value, not only the means. A mean without its
spread is not evidence.

## Q1: Do the fetches work, and is the country applied? (pass/fail, asserted)

- **Pass:** for each seed and country, every Mix fetch returns 10 or more
  unique IDs, and every related fetch returns 5 or more. Before each fetch, the
  service reports the requested country.
- **Fail:** a fetch throws, a fetch returns fewer items, or the country did not
  apply. Then Q2 to Q4 are not results. Record the failure and do not interpret
  the overlaps.

## Q2: Does the country change Mix? (measurement, judged by these bands)

- **Effect (yes):** `gap >= 0.20` on at least 2 of the 3 seeds.
- **No effect (no):** `|gap| < 0.10` on all 3 seeds.
- **Inconclusive:** any other shape. Examples: one seed shows an effect and two
  do not; a `gap` falls between 0.10 and 0.20.
- **Inconclusive also when** the noise floor is too high to see an effect: the
  mean `within` for Mix across seeds is below 0.40. The RD-mix probe measured
  0.62 for one seed. A floor below 0.40 leaves no room for a 0.20 gap to be
  distinct from noise.

## Q3: Does the country change related? (measurement, same bands as Q2)

Use the same bands as Q2, applied to the related feed. Related has never been
measured for stability. The `within` value from this run is the first
measurement of it. Record that value whatever the answer is.

## Q4: Control: does `gl` reach YouTube from this client? (measurement)

Search for `musica` under each country, two fetches each, in the same order.
Compute `within`, `cross` and `gap` the same way.

- Control `gap >= 0.20`: the client sends `gl` and YouTube uses it for search.
  A Q2 or Q3 "no effect" then means "Mix (or related) ignores `gl`".
- Control `|gap| < 0.10`: `gl` changes nothing that is visible, even for
  search. A Q2 or Q3 "no effect" then becomes **Inconclusive**. Possible
  causes: the IP location overrides `gl`, or the client does not send `gl`.
  The run cannot tell these apart.
- Any other control value: report it next to Q2 and Q3, and add no
  interpretation.

## What the answers change

- **Q2 or Q3 "effect":** the station follows the device locale, and the user
  cannot see this. Record it in the HANDOFF. Do not move the chart region into
  the station without an owner decision. A region-steered station is a product
  change, not a fix.
- **Q2 and Q3 "no effect", with a control effect:** the chart region stays
  chart-only. No station change is needed.
- **Inconclusive:** change nothing. Run again from a second network, or with
  two more seeds from a regional genre, before any conclusion.

## What failure looks like

A `BotCheck` or a rate limit (`ReCaptchaException`) is not a probe result. It
is the source refusing this IP. Record it, change nothing in the design, and
run again from another network.

This probe changes `gl` from one IP. It does not test what a phone physically in
Mexico receives. That is a different question, and the app cannot control it.
