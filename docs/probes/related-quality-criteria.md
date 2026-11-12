# Related-quality probe: criteria

Written 2026-11-12, BEFORE the first probe run (CONTRIBUTING §10). No run exists when this file
is written. The probe is `RelatedQualityProbeTest`. It is network-gated behind `YT_SMOKE=1`, the
same as `RdMixProbeTest`.

## The question

The owner decision of 2026-11-12 ("Pairs") puts YouTube's related videos next to the Mix in
every fetch from a song. Related videos are noisier than a Mix: small uploads, reposts and other
uploads of the same song sit next to official ones. A gate keeps a related item when:

- its uploader is verified, OR
- its views are at least `RELATED_MIN_VIEWS` AND at least `RELATED_MIN_SEED_FRACTION` times the
  views of the seed.

Unknown views (`-1`) fail the view test. A verified uploader still passes. The starting values
are GUESSES: 10_000 views and 0.01. This probe measures what YouTube's top related items look
like, and the rules below turn the measurement into the two constants. The rules are fixed here,
before the data exists, so the data cannot choose its own rule.

The probe also measures the same-song rules (seed title filter, duplicate titles) and what the
display line ("<views> views · <year>") can show.

## Method

- Seeds: 12, across genres, languages and sizes. Three are the ids of earlier probes. The other
  nine are search queries; the first video result is the seed, and the results file records its
  id.
  - `dQw4w9WgXcQ` (English pop, 1987), `kJQP7kiw5Fk` (Latin pop), `9bZkp7q19f0` (K-pop).
  - "Burna Boy Last Last" (Afrobeats), "Aya Nakamura Djadja" (French pop), "Miles Davis So What"
    (jazz), "Rammstein Du Hast" (German metal), "Arijit Singh Tum Hi Ho" (Hindi film music),
    "Buena Vista Social Club Chan Chan" (Cuban son), "Fairuz Kifak Inta" (Arabic).
  - Two smaller seeds: "Mdou Moctar Afrique Victime" (Tuareg rock) and "Ichiko Aoba Asleep Among
    Endives" (Japanese folk).
- Per seed: one `StreamInfo` fetch of the seed, the same fetch `relatedStreams` makes. The seed's
  views come from it. Its related items are the `VIDEO_STREAM` stream items, in YouTube's order,
  the same list `relatedStreams` returns.
- **Top 8** means YouTube's first 8 such items (`RELATED_TOP_K`, a GUESS of 8). Ranks 9 to 20
  are printed too, as a comparison only.
- Per related item, the probe prints: rank, id, views, verified, upload date (year, whether it is
  approximate, and YouTube's text), duration, uploader, cleaned title, and whether its title key
  matches the seed's.
- **Title key:** `TitleCleaner.clean(title, uploader)`, then lowercase letters and digits only.
  This is the key the brief specifies for the seed title filter and the duplicate rule.
- **Loose key**, for comparison only: every bracketed group removed, then the text after the last
  spaced dash, then lowercase letters and digits only. It is not used in code.
- Per seed, the probe also fetches the Mix through `PlaylistInfo`. It prints how many Mix items
  carry views and an upload date.

## Q1: Do the fetches work? (pass/fail, asserted)

- **Pass:** every seed resolves, and every seed's `StreamInfo` lists 8 or more related video
  items.
- **Fail:** a fetch throws, or a seed lists fewer than 8 related items. Record the failure. The
  numbers of the failed seed are not results.

## Q2: The view distribution (measurement)

Pooled over all seeds, for the top 8: item count, the share with unknown views, the verified
share, and the quantiles (minimum, p10, p25, median) of views and of views divided by the seed's
views. The probe prints them for all items, for verified items and for unverified items. Per
seed, it prints the seed's views and the same numbers for its 8 items.

## Q3: The gate constants (rule fixed now)

The reference population **R** is the verified top-8 items with known views. The gate keeps them
whatever their views. The check mark is the only quality signal the extractor gives besides
views. The rule: an unverified item stays when it is not less popular than the least popular
tenth of R, both relative to the seed and in absolute views.

- `RELATED_MIN_SEED_FRACTION`: the largest step of the 1-2-5 series (..., 0.001, 0.002, 0.005,
  0.01, 0.02, ...) that is at or below the p10 of R's views-to-seed-views ratio, pooled.
- `RELATED_MIN_VIEWS`: the largest 1-2-5 step (..., 1_000, 2_000, 5_000, 10_000, 20_000, ...)
  that is at or below the p10 of R's views, over the seeds whose own views are at or below the
  median seed's views. The absolute floor only binds for small seeds: for a large seed, the
  fraction asks for more views than the floor. So the floor comes from the small half of the
  seeds.
- p10 is a choice made here, not a measurement. A lower percentile keeps more unverified items;
  a higher one drops more.

**Inconclusive:** R has fewer than 20 items pooled, or fewer than 8 in the small half. Then the
GUESSES stay (10_000 and 0.01), and the results file and the code comments say so.

**Unknown views:** if more than half of the top-8 items have unknown views, the view test cannot
work. The gate is then verified-only in practice. Record it; the constants still come from the
rule above.

## Q4: The gate on the probe's own data (measurement)

The probe applies the gate with the constants from Q3, and with the GUESSES, to the top 8 of
each seed. It prints kept and dropped per seed and every dropped item (title, uploader, views).
The owner reads the dropped list and judges it by ear and by eye. No pass/fail band applies.

- **Flag:** the Q3 gate drops more than half of all unverified top-8 items. Related then becomes
  verified-only in practice. Report it to the owner; do not change the rule.
- Ranks 9 to 20: the share that the Q3 gate keeps, beside the share for ranks 1 to 8. This is
  information for `RELATED_TOP_K`, which stays a GUESS.

## Q5: Same-song rules (measurement)

- Seed title filter: the share of top-8 items whose title key equals the seed's.
- Duplicate titles: in the Mix plus the top 8 of each seed, the share of items whose title key
  equals the key of an item before it.
- The same two shares under the loose key, and every item the loose key matches that the title
  key misses.

The code uses the title key, as the brief specifies. If the loose key finds many same-song
uploads that the title key misses, report that to the owner. Do not change the key without an
owner decision.

## Q6: The display line (measurement)

- The share of top-8 items with a known view count, and with an upload date.
- The share of those dates that are approximate. YouTube lists related items with a text such
  as "3 years ago"; the extractor turns it into an approximate date, so the year can be off by
  one. [INFERENCE, from how the extractor parses the text; the probe shows the share.]
- The share of Mix items with views and with a date. A Mix item without views shows no line, and
  the duplicate rule compares its views as unknown (`-1`).

The display shows the year whenever a date exists, approximate or not, as the brief specifies.
Record the approximate share in the results file.

## What failure looks like

A `BotCheck` or a rate limit (`ReCaptchaException`) is not a result. It is the source refusing
this IP. Record it, change nothing, and run again from another network.

One network, one day and 12 seeds give a shape, not a distribution of all music. The results
file must say so. YouTube's related list changes from fetch to fetch; this run does not measure
that spread.
