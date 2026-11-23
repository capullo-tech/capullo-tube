# PLAN: smart playlist generator (2026-11-18, DRAFT)

Branch `tube/smart-playlist`. Not approved. Sources: `../music-discovery-sites-research.md`,
the engine on `main` (5144a07), the unmerged `station-sources` PLAN and `taste-history`.

## Goal

The radio should play songs you would not have found on YouTube alone, and you should be able to
steer it with two buttons: ♥ Like (more like this) and Less like this. A Depth setting decides
whether the new songs are well known or deep cuts.

## Today

The radio takes one song and plays its YouTube Mix and its related videos, one of each in turn.
♥ Like adds 2 more of the liked song's Mix and related after the current song. Next does nothing
to the radio. There is no "don't like" button.

Why this is the ceiling: both lists come from YouTube, so the radio can only play what YouTube
would have played. We tried scoring YouTube's candidates with ten terms (commit aa40704) and the
result repeated YouTube's own order. More scoring will not help. New songs have to come from
somewhere else.

## Behaviour after this plan

1. Every song the radio expands gets a third list: songs that Last.fm says are similar. Last.fm
   knows "Radiohead - Creep" is close to "Pixies - Where Is My Mind?" from decades of listening
   data, independent of YouTube's view counts. Each similar song is looked up on YouTube by
   name and only kept if the title and artist really match. Songs that YouTube's Mix already
   offered are thrown away, because the third list exists to add what YouTube misses.
2. The radio plays the three lists in turn: Mix, related, similar. ♥ Like adds one of each
   (3 instead of 2; the "Songs added per like" setting keeps working).
3. Settings gets a Depth choice. Familiar: the top 10 similar songs. Deep: songs ranked 11 to 40.
   Last.fm returns a match score per song, so the dial costs nothing extra.
4. Up next shows why a song is there: "Similar to Creep".
5. Later PR: Less like this on any row and in the Player. It removes the song, drops every queued
   song that came from it, and keeps that artist out of the radio for a while. ♥ Like also makes
   the liked song the point the radio continues from, and the Player says "steered by ♥ Creep".

Next stays "not a signal", as decided for v0.2.

## What is new for the user

| Now | After |
|---|---|
| Songs only from YouTube Mix and related | Plus songs Last.fm considers similar |
| No way to go deeper than YouTube's popular picks | Depth: Familiar or Deep |
| ♥ Like adds 2 YouTube picks | ♥ Like adds 3 picks, one per source, and steers the radio |
| No negative signal | Less like this (PR 3) |
| "Because you liked X" rows from YouTube only | Later: genre rows from Last.fm tags |

## Cost

**Network.** One Last.fm call per expanded song (free). One YouTube search per similar song we
have not seen before. YouTube searches are the expensive part: YouTube bot-checks us when we
call too much. So we look up only the next 2 similar songs ahead, and we remember every lookup
in the database forever, so a song is searched once per phone.

**Privacy.** Artist and title of each expanded song go to Last.fm. Same as the lyrics lookup to
LRCLIB today: on by default, off switch in Settings ("Find similar songs online").

**The Last.fm API key.** Every Last.fm request needs an `api_key`. Keys are free, issued at
once at last.fm/api/account/create, for non-commercial use, about 1 request per second. The
methods we use (`track.getSimilar`, `artist.getSimilar`, `artist.getTopTracks`) are read-only
and need only the key, not the secret, so the key can live in the app:

- The app ships a project key, injected into `BuildConfig` from a CI secret so it is not in git.
  Anyone can extract it from the APK; that is accepted for a read-only key (Rabbit Hole Sounds
  does the same). All installs share its rate budget.
- Settings > "Last.fm API key (optional)": a personal key overrides the project key. A user who
  gets rate limited, or does not want to share a key, pastes their own.
- Last.fm terms ask for attribution; an "Similar songs by Last.fm" line in Settings. (b: confirm
  wording in the terms before release.)

**Code.** Engine: a third list in `Feed`, `Provenance.SIMILAR`, `pairUp` becomes a round robin
over three lists. New: `SimilarSource` (Last.fm client), `TrackResolver` (name to YouTube video,
with the cache table `resolved_track`, Room v8), two settings, one why-line case. Tests:
`PairsTest`, `StationEngineTest`, `UpNextTextTest` grow cases.

**Unknowns.** Last.fm coverage of Spanish and regional music (my guess: thinner than for
English rock and pop). Title parsing on uploads that are not "Artist - Title". Wrong YouTube
matches (covers, live versions). All three are measured by the probe in PR 1 before the
engine changes.

## Options we looked at and dropped or deferred

| From the research | Decision | Reason |
|---|---|---|
| Deezer (art, 30 s previews) | Drop | Deezer closed new developer access in 2025. YouTube already gives us art and audio |
| Every Noise at Once (precomputed genre map) | Drop | No API, frozen since Spotify withdrew its data. We take one lesson: cache what we fetch so a station costs no repeat calls |
| Gnoosic crowd voting | Drop the crowd, keep the buttons | We have no server and one user per phone. The like / don't like / neutral schema becomes Less like this |
| Music-Map force-directed graph | Defer | A nice Explore screen later, fed from the cache; not a generator improvement |
| ListenBrainz + MusicBrainz (open data, no key) | Defer | Works today for artist similarity, but needs 3 to 4 calls per expansion at a strict 1 per second, and its convenient endpoints now require a user token. Can replace Last.fm later behind the same `SimilarSource` interface |
| Skip weighting (old PLAN items 2, 3) | Keep closed | Reopened only if explicit buttons prove not enough |

## PR stack

| # | Branch | Content | Gate |
|---|---|---|---|
| 1 | `tube/similar-probe` | `LastfmSimilarProbeTest`, runs only with `LASTFM_PROBE=1` and a key from the environment; same seeds as `RelatedQualityProbeTest` plus Spanish and regional seeds | pass criteria below |
| 2 | `tube/smart-playlist` | Last.fm client, resolver and cache, third list, Depth and online switch, personal key field, why line | listening test, criteria written first |
| 3 | `tube/steering` | Less like this, like steers the chain, "steered by ♥" line | listening test |
| later | | Artist radio from the Last.fm graph, genre rows on Home, Explore map, ListenBrainz provider | |

## Pass criteria for PR 1 (GUESS, written before any measurement)

| Q | Measure | Pass |
|---|---|---|
| q1 | seeds whose title parses into artist and title (`LyricsQuery.of`) | >= 80 % |
| q2 | parsed seeds where Last.fm returns 10 or more similar songs | >= 70 % |
| q3 | similar songs found on YouTube with a matching title and artist in the top 5 results | >= 80 % |
| q4 | found songs that were not in the seed's Mix or related (new to the radio) | >= 50 % |
| q5 | median time per found song on Wi-Fi | <= 2 s |
| q6 | q2 and q4 on the Spanish and regional seeds alone | within 15 points of the whole set |

q1 or q3 failing means our parsing or matching is weak, fix that. q4 failing means Last.fm and
YouTube mostly agree, and the whole option adds nothing: stop and reconsider.

## Engine sketch (PR 2)

```kotlin
interface SimilarSource {
    suspend fun similarTracks(artist: String, title: String, limit: Int): List<SimilarTrack>
    suspend fun similarArtists(artist: String, limit: Int): List<SimilarArtist>
    suspend fun topTracks(artist: String, limit: Int): List<SimilarTrack>
}

data class SimilarTrack(val artist: String, val title: String, val match: Double, val playCount: Long?)

class TrackResolver(extractor: YouTubeExtractorClient, dao: ResolvedTrackDao) {
    suspend fun resolve(artist: String, title: String): YtVideo?   // cache first, else search(limit = 5)
}
```

Constants, all GUESS: `SIMILAR_NAMES = 40`, `SIMILAR_RESOLVE_AHEAD = 2`, `DEPTH_FAMILIAR = 1..10`,
`DEPTH_DEEP = 11..40`, `AVOID_ARTIST_DAYS = 30`. Log line:
`similar seed=<id> names=<n> resolved=<n> novel=<n> depth=<d>`.

## PR 1 results (2026-11-18, `LastfmSimilarProbeTest`, 24 seeds, 128 sampled candidates)

| Q | all | regional (10) | other (14) | Pass? |
|---|---|---|---|---|
| q1 parsed | 1.00 | 1.00 | 1.00 | yes, but the measure is too lenient (see below) |
| q2 >= 10 similar | 0.67 | 0.70 | 0.64 | **no** (needs 0.70) |
| q3 found on YouTube | 0.94 (120/128) | 0.96 | 0.92 | yes |
| q4 new to the radio | 0.65 | 0.78 | 0.55 | yes |
| q5 median per found song | 385 ms | 388 ms | 383 ms | yes |
| q6 regional vs whole | +3 / +13 pts | | | yes, regional is better |

All 8 q2 misses are naming problems on our side, not missing Last.fm data: `ft. Daddy Yankee` in
the title, `M/V` and Korean in brackets, `(Live on KEXP)`, a Japanese subtitle in brackets,
`Grupo Frontera x Bad Bunny` as artist, and three uploads with no "Artist - Title" shape
(T-Series, DEL Records, a re-upload channel). Fix for PR 2: retry `track.getSimilar` with
featured artists and bracketed text stripped and with only the first artist of an `x` / `&`
credit; if it still fails, fall back to `artist.getSimilar` + `artist.getTopTracks`; skip
label uploaders (T-Series, DEL Records) as artist sources.

Other observations: ranks 1-2 are usually the seed artist's own songs and mostly already in the
Mix (novel ~25 %); ranks 3+ are other artists and mostly new. Deep (11-14) sounded as relevant
as Familiar in the printed names (Tems, Wizkid after Burna Boy; Sonny Rollins after Miles Davis).
Misses in q3: `Ckay - love nwantiti [feat. ...] [Remix]`, a few Arabic titles, one obscure jazz
track.

Verdict: go for PR 2, with the parsing retry and artist fallback above as part of its scope.

## PR 2 as built (`tube/smart-playlist`)

- `data/similar/`: `LastfmClient` (track.getSimilar, artist.getSimilar, artist.getTopTracks),
  `SeedNames` (parsed names, then bare names: no brackets, no featured artist, first artist of a
  joint credit; Topic channels), `depthOrder`, `pickResolution` (studio over live/cover/remix),
  `TrackResolver` (Room `resolved_track`, v8; hits kept 180 days, misses 30), and
  `LastfmSimilarFinder` (key choice, retry, artist fallback, novelty filter, bounded searches).
- Engine: `fetchSong` asks Last.fm in parallel with the Mix and related, then resolves up to
  `SIMILAR_PER_FEED = 4` new songs in one parallel batch (at most 2 searches per wanted song).
  `interleave` replaces `pairUp`; with no Last.fm songs the order is exactly the old pairing.
  New log line: `similar seed=.. via=track|retry|artist|off|no-key|unparsed|none|error names=..
  known=.. tried=.. searched=.. found=..`.
- Change from the draft: a like still adds "Songs added per like" (N); one of the N is a Last.fm
  song whenever Last.fm found one, instead of N + 1.
- Deep = ranks 11-40, then ranks 1-10. (A 0.1 match floor was tried and removed: the score is
  relative to each list; Redonditos' list is below 0.05 after rank 1, so Deep fell back to rank 1.)
- Settings > Similar songs: on/off (default on), Depth, Last.fm API key (optional; empty means
  the built-in key from `LASTFM_API_KEY` / `local.properties` `lastfm.apiKey`).
- Home "More like" rows stay YouTube-only.
- Device check: owner's razr, once the feature is mature.

## PR 2b: Last.fm limits (as built)

Facts (2026-11-18): the API terms (4.4) set limits "in our sole discretion" with no number; the
widely cited figure is 5 requests a second per originating IP, averaged over 5 minutes. Error 29
names the IP, so the limit is per phone, not per key; a personal key is not uncapped. Error 26
suspends a key for every install using it. No rate headers. The terms ask for caching.

- `LastfmGate` (both keys): calls at least 250 ms apart; error 29 pauses all calls 10 min;
  error 10 or 26 stops that key for the session. Settings shows the reason under the key.
- `lastfm_cache` (Room v9): every answer and every "not found" for 30 days, keyed by method and
  normalized names, not by key. Liked radio cycling the same likes now costs no calls.
- The finder logs `via=paused` while the gate holds Last.fm back.
- Device: razr 40 ultra, Android 15: 8 -> 9 kept the cached YouTube lookups; Creep's answer
  landed in `lastfm_cache`.

## PR 2c: review fixes (as built)

- A radio start or chain lands YouTube's lists at once; Last.fm songs join when they arrive,
  one after every 2 of the seed's YouTube songs (log line ends in `late`). A like waits for
  Last.fm at most 3 s, then lands YouTube-only. Razr: Mix landed 1.1 s before the Last.fm songs.
- Last.fm songs asked per expansion: radio 4, a like N/2 (at least 1), Liked radio 1.
- Without a built-in key the "Find similar songs online" switch starts off.
- Settings: "Powered by AudioScrobbler" credit row linking to last.fm (API terms 2.7); the
  pause countdown ticks every 30 s.
- Test: a radio with Last.fm songs resumes from Room with SIMILAR provenance.

- Attribution: only the credit row in Settings > Similar songs. Up next says "Similar to <seed>"
  with no Last.fm mention.

## Listening test: pass criteria (written 2026-11-19, before the test)

Device: razr 40 ultra, Wi-Fi, build from the PR head. Settings: Find similar songs online on,
Depth Familiar for sessions 1-2, Deep for session 3. Capture with
`adb logcat -s TubeStation` for the whole session. Every number below is a GUESS.

Sessions (about 20 minutes each, skip freely):
1. Radio from an English pop/rock seed (e.g. "Radiohead - Creep").
2. Radio from a Spanish/Latin seed (e.g. "Soda Stereo - De Música Ligera"), with 3 likes spread
   over the session.
3. Liked radio for 15 minutes, Depth Deep.

| # | Check | Evidence | Pass |
|---|---|---|---|
| L1 | Last.fm songs actually play | `advance ... prov=SIMILAR` lines | at least 1 in every 4 songs in sessions 1-2 |
| L2 | They fit the station | owner's call per SIMILAR song: fits / neutral / wrong | wrong <= 1 in 5 |
| L3 | They are new | SIMILAR songs the owner would not have expected from YouTube's radio | at least half |
| L4 | No duplicates | the same recording twice, any upload of it; a marked different version (live, acoustic, remix, cover) is fine (owner, 2026-11-20) | none |
| L5 | Wrong version | live/cover/karaoke/sped-up played as a SIMILAR song | <= 1 per session |
| L6 | Start stays fast | `start` to first `chain` line | no slower than before (<= 6 s on Wi-Fi) |
| L7 | Likes stay responsive | `like id=` to `like mode=blend` | <= YouTube fetch + 3 s, every like |
| L8 | Every like brings a Last.fm song when Last.fm has one | `like mode=blend ... similar=+1` when `similar ... names>0` | 3 of 3 likes in session 2 |
| L9 | Now Playing stays correct | title in Player/notification matches what is heard | always |
| L10 | Last.fm limits never hit | no `via=paused`, no Settings warning | none |
| L11 | Deep sounds deeper | session 3 SIMILAR songs less obvious than Familiar's (Deep = less similar, not less popular; owner, 2026-11-23) | yes |

Fail on L4, L9 or L10: fix before merging. Fail on L2, L3, L5 or L11: tune `DEPTH_FAMILIAR_RANKS`,
or the version penalties in a follow-up; not a merge blocker on its own.
Inconclusive: Last.fm answered nothing for the seed (`via=none` / `unparsed`); pick another seed.

## Listening results (OPPO PFFM10, 2026-11-20 to 2026-11-23)

| # | Session 1: Creep, Familiar | Session 2: Soda Stereo, Familiar, likes | Session 3: Liked radio, Deep |
|---|---|---|---|
| L1 Last.fm songs play | 5 of 16 | 2 of 6 | 9 of 23 |
| L2 fit | all fit | Estallando fits | ADMO, Charly García, La parte de adelante, Canción para Naufragios, Nos Siguen Pegando Abajo, Crimen fit; Psychosocial off (came from a like, not counted) |
| L4 same recording twice | none | Prófugos re-upload: fixed (534b6cb) | none (the two repeats are Continue resuming the song it left) |
| L6 start | Mix 4 s, Last.fm +1.3 s | Mix 4 s, Last.fm +1.6 s | Mix 4 s, Last.fm +1 s |
| L8 a like brings a Last.fm song | 3 of 3 | 2 of 2 | 6 of 6 likes (6 per like now gives 3, c59e345) |
| L9 Now Playing matches | always | always | always; one silent stall on Crimen, now recovered (watchdog) |
| L10 Last.fm limits | none | none | none |
| L11 Deep less obvious | | | yes, once the 0.1 floor was removed (Redonditos fell back to rank 1) |

Liked radio likes whose Last.fm search found nothing (two instrumental / slowed uploads) add
YouTube songs only, as designed. Blocking checks L4, L9, L10 pass.
