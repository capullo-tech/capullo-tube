# HANDOFF: youtube_capullo bootstrap

Status: **M5 done (2026-08-18). All five bootstrap milestones complete.** On-device: liking a
Tame Impala track and pressing Next produced "Let It Happen" (Tame Impala) as the immediate next
pick - the like audibly steered selection, which is M5's done-criterion on real data. The owner
tests through the day on 2026-08-19 and returns with comments; expect the §5-labelled GUESS
constants to be the first things their feedback tunes. Still pending: the M4 hour-long soak
(passive), a manual swipe-from-recents check, and the first commit (owner gate). See "Testing
guide" at the end of this file.

**Post-M4 fix (2026-08-18): playback died mid-track after a few minutes backgrounded.** Owner
report, reproduced and root-caused with dumpsys: `startForegroundCount=0` while audibly playing -
the service NEVER became a foreground service, so Samsung killed the backgrounded process (and a
task swipe would kill it too, per the exit-info record). Root cause: `MediaSessionService` only
learns about a session when a MediaController connects (`onGetSession`) or via explicit
`addSession()`. Telecloud's UI binds a MediaController; TubeCapullo's talks through
PlaybackCoordinator, so nothing ever connected and the notification manager never attached. The
system's legacy media-button path made KEYCODE_MEDIA_NEXT work regardless, which masked it.
Fix: explicit `addSession(mediaSession)` in the service + runtime POST_NOTIFICATIONS request in
MainActivity (API 33+; without the grant the app's notifications sit at importance=NONE).
Verified: startForegroundCount=1, notification posted, and a 6-minute screen-off soak stayed
`playing` INCLUDING an unattended track transition (the exact moment it used to die). Task-swipe
survival is expected with FGS but was not adb-verified (the scripted swipe missed the card) -
one manual swipe-while-playing confirms it.

Read `idea` first, then `CONTRIBUTING.md`. `CONTRIBUTING.md` is binding: catalog-and-pins
dependencies, Compose/Material3 only, the section 6 rule on constants, the section 10 rule about
writing pass criteria before an empirical run. Nothing here overrides it.

## 1. What we are building

An Android app where one phone plays YouTube audio and broadcasts it in sync to other phones,
Raspberry Pis and browsers over the LAN via Snapcast. The playlist is not hand-built: the user picks
one song, and the app extends it into an endless station. A like steers the station toward that
track's neighbourhood, a skip steers away, a search jumps somewhere new.

Two decisions the owner has already made, do not relitigate them:

1. **App only.** No `capullo-source-youtube` library yet. The station logic goes in this app, behind
   the contracts SPI, so extracting it later is a file move. `CONTRIBUTING.md` section 10 says do not
   build for a caller that does not exist, and RadioCapullo is not consuming this yet.
2. **Hybrid station.** Seed the candidate pool from *both* YouTube Mix (`RD<videoId>`) and per-video
   related streams, then reweight locally from like/skip/play-through. Not one or the other.

## 2. Environment facts that will waste your time otherwise

`CONTRIBUTING.md` section 1 tells you to clone every capullo repo as siblings under `~/capullo-tech/`.
**That directory does not exist on this machine.** The checkouts are scattered:

| Repo | Path here |
|---|---|
| capullo-audio | `~/Documents/Proyectos/capullo-audio-src/` |
| telecloud-radio | `~/Documents/Proyectos/tele_cloud/` |
| RadioCapullo | `~/Documents/Proyectos/capullo_oscar/RadioCapullo/` |
| this app | `~/Documents/Proyectos/youtube_capullo/` |

Not checked out at all: `capullo-audio-contracts`, `capullo-source-telegram`,
`capullo-source-radiobrowser`, `build-conventions`, `quantumcast`, and the `lib-*` native mirrors.
Those resolve from jitpack.

Consequence: the composite-build substitution in `settings.gradle.kts` only fires on the literal
relative path `../capullo-audio`, so with this layout **no local library edit reaches an APK** — every
build resolves the pinned jitpack commit. If you need to co-develop a library, clone it to the
sibling path the app's `settings.gradle.kts` tests for. Do not "fix" this by editing pins.

## 3. Architecture: read this before you write a line

### 3.1 The finding that shapes everything

**`CapulloAudioEngine` cannot yet drive a multiroom app. Do not build on it.**

Evidence, in `capullo-audio-src/capullo-audio/src/main/kotlin/tech/capullo/audio/`:

- `CapulloAudioEngine.kt` exposes exactly four public members: `startBroadcast(parentJob)`, `play(id)`,
  `playQueue()`, `stop()`. Its `SnapserverProcess` and `SnapcontrolPlugin` are private with no
  accessor. So there is no way to read the resolved `SnapserverPorts`, build a "listen here"
  URL or QR, register NSD, run a local snapclient, or call `notifyPropertiesChanged()`.
- `QueuePlaybackLoop` is `internal`. Not reachable from an app at all.
- The engine's own README says as much: snapclient / listen-in mode is listed as still to wire.

telecloud-radio bypasses the facade for exactly this reason. Grep confirms it: it imports
`SnapserverProcess`, `SnapcontrolPlugin`, `SnapclientProcess`, `SnapcastControlClient`,
`SnapserverDiscoveryManager`, `SnapserverNsdRegistrar`, `SnapserverPorts`, `FifoAudioBufferSink` —
and never `CapulloAudioEngine`.

So we wire the snapcast primitives directly, the same way telecloud does. When the engine grows a
public port/listen surface, the broadcast half can move onto it; the station will not have to change.

### 3.2 The shape

```
  ui/  NowPlayingScreen (♥ like  ✕ skip  ⌕ search)   SearchScreen   ListenersSheet
        │ state down, events up
  station/  StationEngine  ── implements MediaSourceProvider ──┐
        │   candidate pool · taste signal · appends next id    │
        │                                                      │
  player/  StationLoop  ── our own advance loop ───────────────┘
        │   (QueuePlaybackLoop is internal; we mirror its contract, see 3.3)
        ▼
  ExoPlayer + FifoAudioBufferSink ──► PCM 44100/16/stereo ──► snapfifo
        ▼
  snapcast/  SnapcastManager ── SnapserverProcess · SnapcontrolPlugin · SnapclientProcess
                                SnapserverDiscoveryManager · NSD · SnapcastControlClient
        ▼
  LAN: snapclients on other phones/Pis + the bundled browser web player
```

`data/youtube/` sits under `station/` and is almost entirely ported (section 4).

### 3.3 Contract obligations we must honour

Mirror what `QueuePlaybackLoop` does, because that is what the engine will expect if it ever takes
over, and it is the behaviour the contracts are documented against:

1. Call `mediaRequestFor(idAt(i))` **before** `onQueueAdvanced(i)`. The source enriches now-playing
   during resolution, and prefetch decisions depend on knowing the track resolved.
2. A throw from `mediaRequestFor` is the source's only "skip me" signal. Catch it, skip forward,
   and bound the walk by queue size so an all-dead queue cannot spin forever.
3. Overlay the engine-side clock (`positionMs`, `isPlaying`) onto the source's `NowPlaying` before
   publishing. The source only sets `isPlaying = true` / `positionMs = 0`.

### 3.4 `isRotating` must be `false`

This is a trap and I walked into it first. `isRotating` does **not** mean "endless station". Read
`QueuePlaybackLoop.kt:55-59`: it means a live stream that never really ends (RadioBrowser-style), and
the loop deliberately **does not auto-advance** those on `STATE_ENDED`.

Our station is a **finite queue that grows**. Report `isRotating = false` so auto-advance works, and
make `queue()` return a fresh view each call — the loop re-reads it on every advance, so a track
appended during `onQueueAdvanced(i)` is visible when the current one ends. Getting this backwards
gives you a station that plays exactly one song and stops.

## 4. Reuse map: what to port from telecloud

Source: `tele_cloud/app/src/main/java/tech/capullo/telecloudradio/`.
Both apps are GPLv3, so this is a straight port. Keep the KDoc — those comments carry the reasoning
that section 6 demands, and re-deriving it costs more than moving it.

**Port nearly as-is** (`data/youtube/`):

| File | Why it survives unchanged |
|---|---|
| `YouTubeExtractorClient.kt` | search, playlist expansion, single video, stream resolution + the stream cache. It is the only file importing `org.schabi.newpipe.*` — keep that property, it is what bounds the blast radius when YouTube rotates its player JS. |
| `YouTubeUrls.kt` | pure ID/URL parsing, no Android or extractor types. Already lists `RD` in its playlist prefixes, so Mix URLs parse today. |
| `AudioStreamSelector.kt` | picks which audio stream to actually play |
| `YouTubeAudioCache.kt` | writes a resolved stream to the download buffer |
| `TelecloudDownloader.kt` | NewPipe's blocking `Downloader` SPI over OkHttp. Rename to match this app. Keep the comment explaining why it is OkHttp and not ktor. |
| `YouTubeModels.kt` | `YtVideo` / `YtStream` / `YouTubeError` |
| `ui/youtube/YouTubeSearchScreen.kt` + its ViewModel | the search UI, adapted to feed a seed rather than a station-add |

**Do not port:**

- `YouTubeIds.kt` and the `MediaMessageEntity` mapping in `YouTubeStationRepository.kt`. Both exist
  only to squeeze YouTube rows into Telegram's schema — synthetic `Long` chat IDs, epoch-seconds
  dates as strings, a `GROUP_TYPE = "YOUTUBE"` label filling Telegram's slot. We get a clean schema.
- Anything under `data/telegram/`, `auth/`, `data/credentials/`.

**Port and adapt** (`snapcast/SnapcastManager.kt`, ~30 KB): this is the multiroom feature set —
`prepareBroadcast`, `startBroadcast(nowPlaying, controller)`, `connectListen` / `disconnectListen`,
per-client volume and latency, channel switching, `notifyPropertiesChanged`, web config. It is
largely source-agnostic. `player/PlaybackService.kt` is more Telegram-tangled; take its Service
shell, watchdog and notification shape, not its body.

**New, no equivalent exists:** everything in section 5.

## 5. The new part: StationEngine

Nothing in telecloud calls related-streams or Mix. This is the whole idea, and it is the only part
being invented.

```
  seed (user picks / searches)
        ├── mix(videoId)            → RD<videoId>, YouTube's own algorithm, snapshot per fetch
        └── relatedStreams(videoId) → per-video neighbours, cheap, always available
                    ↓
        ┌───────────────────────┐   ♥ like  → boost this track's neighbours, re-seed a mix FROM it
        │    CANDIDATE POOL     │   ✕ skip  → damp it and what resembles it
        │  scored · deduped ·   │   ⌕ search→ hard jump: new seed, keep history
        │  played never returns │
        └───────────┬───────────┘
                    │ highest-scoring unplayed
                    ▼
            append to queue → resolve → play
                    │
                    └─ onQueueAdvanced(i) → top the pool back up, prefetch N+1
```

`relatedStreams(videoId)` is nearly free to add: `YouTubeExtractorClient.video()` already calls
`StreamInfo.getInfo(...)` (line ~100), and that same `StreamInfo` carries `getRelatedItems()`. Map it
with the existing private `toYtVideo()`. One new public method, no new network shape.

`mix(videoId)` is the unknown — see section 7.

Scoring: every weight here is a **guess until measured**. `CONTRIBUTING.md` section 6 is explicit
that a guess must say so in its comment, because an unlabelled guess is indistinguishable from a
measurement and that is how a placeholder becomes folklore. Label them.

## 6. Milestones

Each one ends in something you can run. Do not start the next until the current one is demonstrated.

**M1 — the skeleton builds. ✅ DONE 2026-08-18.** Gradle wiring copied from telecloud's shape: `settings.gradle.kts`
with the shared `libs` catalog from build-conventions plus a local `pins.versions.toml`, root
`build.gradle.kts` with spotless, `app/build.gradle.kts` with the named-APK copy task. Toolchain per
`CONTRIBUTING.md`: **minSdk 26**, compile/target 36, JVM target 17. Empty Compose app, Hilt wired.
Done when `./gradlew :app:assembleDebug spotlessCheck` is green.

Resolved: no desugaring shipped. TC's own pin comment states its only reason - `java.nio.file`
is API 26+ and TC is minSdk 24. We are minSdk 26. M2's on-device extraction check is the
confirmation that nothing else needed it.

**M2 — YouTube plays locally. ✅ DONE 2026-08-18.** Ported `data/youtube/` (extractor client,
urls, selector, downloader, models + the three JVM test files), added `relatedStreams` + `mix`,
built `player/LocalPlayer` (deliberately dumb - see its KDoc for what M3/M4 replace) and
`ui/search/`. Retry policy lives in the ViewModel, one retry per track on stream error.
Probe findings (full data: docs/probes/rd-mix-results.md): mix ≈ 50-60 unique tracks/fetch and
**pagination cycles** past that (dedup is mandatory); semi-stable across fetches (0.62 overlap);
mix vs related nearly disjoint (0.05-0.11) → the hybrid's two feeds are genuinely complementary;
seed is item #1 of its own mix (pool must drop it); bare `playlist?list=RD…` works too.
Deviation from the §4 port list: `YouTubeAudioCache` was NOT ported - it depends on telecloud's
DownloadManager infra and nothing here calls it yet (§10: no callers, no port). Revisit at M4 with
the prefetch design. `preferM4a` now defaults to false (streaming-only; the smoke test keeps the
M4A door watched for the M4 cache).

**M3 — it broadcasts. ✅ DONE 2026-08-18.** Ported `SnapcastManager` (trimmed: no listen-in,
channel tags, vol/lat persistence, stream lock, web config - telecloud is the reference when those
return), built `PlaybackService` (MediaSessionService; mixer→48k-resampler→FIFO-tee sink chain,
volume-0 focus-blind ExoPlayer, browser UA on the Media3 HTTP factory) and `PlaybackCoordinator`
(UI↔service bridge, replay=1 covers the cold-start race; replaced M2's LocalPlayer as its KDoc
promised). Verified from the desktop: web player HTTP 200, stream "playing", metadata over
JSON-RPC, phone snapclient connected.
Two traps hit and recorded in code comments: (1) `useLegacyPackaging = true` is REQUIRED - the
snapcast .so binaries are ProcessBuilder-exec'd, and without it AGP leaves them inside the APK →
exec ENOENT; (2) plain `startService`, not `startForegroundService` - the FGS 5s deadline can't be
met when stream resolution precedes playback, and MediaSessionService promotes itself once playing.

**M4 — the station. ✅ DONE 2026-08-18** (hour-soak pending, see Status). `station/StationEngine`
implements the contracts SPI directly (already the shape a capullo-source-youtube would export):
finite-and-GROWING queue, `isRotating = false` (§3.4), pool fed by mix + related with the probe's
findings coded in (seed dropped from own mix, dedup, 60s-15min duration window - all constants
labelled GUESS where they are guesses). `station/StationLoop` is the documented copy of the
engine's internal QueuePlaybackLoop (KDoc names the master; DELETE it when the engine facade opens)
- its three obligations are what StationLoopTest verifies. Service owns the loop's player seam;
retry policy (one re-resolve on stream error, then skip) moved from the ViewModel into the service.
Engine takes an injected CoroutineScope so tests drive refill/prefetch deterministically.
Tap-a-row now seeds a station; Next lives in the bar, lock screen and BT (ForwardingPlayer forces
SEEK_TO_NEXT available - ExoPlayer holds one item at a time and would disable it, telecloud's fix).

**M5 — taste. ✅ DONE 2026-08-18.** Session-scoped (nothing persists across app restarts yet -
deliberate; revisit only if day-testing shows restarts are common):

- **♥ Like** (bar heart): boosts every pool candidate from the liked track's neighbourhood
  (`LIKE_NEIGHBOURHOOD_BOOST = 1.0`, sized to deterministically beat neutral + jitter) AND
  immediately re-seeds a mix FROM the liked track. Unlike removes the boost only.
- **Next = weak skip**: every user-initiated next (bar, lock screen/BT, web player - all three
  funnel through the service's `skipCurrent()`) damps the skipped track's neighbourhood
  (`SKIP_NEIGHBOURHOOD_DAMP = 0.7`: below-neutral until nothing neutral remains, recoverable
  because variety-skips exist). **Natural track end is neutral on purpose** - no signal.
- **Uploader nudge** (±0.2, deliberately under the jitter): shades toward/away from an artist
  without overriding per-track signals.
- **Search jump keeps history**: `startStation` no longer clears `playedIds` or taste - a jump
  never replays this session's tracks and likes keep steering across seeds.
- **Queue re-planning** - the piece that makes taste AUDIBLE: on any taste change the unplayed
  queue tail returns to the pool (provenance preserved via `queuedProvenance`) and is re-picked
  under the new scores. Without it a like sat behind ~2 stale lookahead tracks (~7 minutes); the
  taste test caught exactly that before the fix (red → feature → green).

All taste behaviour is JVM-tested (StationTasteTest): like-shifts-next-picks, skip-damps-vs-
neutral (two-provenance setup), unlike-removes-boost, history-across-jump, like-triggers-refetch.

## Testing guide (owner day-testing, 2026-08-19)

What to listen for, mapped to the constants your feedback would tune:

1. **Does a like steer too hard?** After a ♥, the next several tracks should lean toward the liked
   song's neighbourhood but not become a one-artist channel. If it railroads:
   `LIKE_NEIGHBOURHOOD_BOOST` (1.0) shrinks toward the jitter (0.3).
2. **Does a skip over-punish?** Skipping one song for variety shouldn't banish a whole style. If
   it does: `SKIP_NEIGHBOURHOOD_DAMP` (0.7) shrinks. If skips feel ignored: raise it.
3. **Station monotony without input** - if an untouched station circles one neighbourhood too
   long, `REFILL_BELOW` (10) or `LOOKAHEAD` (2) may need air; the refill-from-current drift is the
   variety mechanism.
4. **Track-length window** (60s-15min): note any song wrongly excluded (long prog/DJ tracks?) or
   junk that slipped in (shorts, teasers).
5. **Stability**: background it, screen off, whole album lengths - the FGS fix soaked 6 min; a day
   of real use is the actual test. Also do the one manual check adb couldn't: swipe the app from
   recents WHILE playing - it should keep playing.
6. **Multiroom**: a browser on the LAN at the "Broadcasting" URL should stay in sync all day and
   its Next button should carry the skip signal like the phone's.

Report with track names when something feels wrong - "after liking X it played Y,Z" tunes a
constant; "it felt off" doesn't.

## 7. Resolve these before M4, they change the design

**What does NewPipeExtractor actually return for `RD<videoId>`?** Unknown. `YouTubeUrls.kt` parses
the ID, but nothing has ever fetched one. It may return the full mix, a truncated window, or throw.
The hybrid weighting depends on the answer.

Write the pass criteria to a file **before** you run the probe (`CONTRIBUTING.md` section 10) —
including what an inconclusive result looks like. Then write a network-gated test that fetches an
`RD` playlist for a few known video IDs and records: item count, whether the seed video is included,
whether two fetches of the same mix agree, and how the items relate to `relatedItems` for the same
seed. If Mix comes back thin or unstable, related-streams carries more of the weight and the plan
still stands.

**The contracts SPI signatures - RESOLVED 2026-08-18.** Read via `javap` from the exact jar the
M1 build resolved (`~/.gradle/caches/modules-2/files-2.1/com.github.capullo-tech/`
`capullo-audio-contracts/main-SNAPSHOT/.../capullo-audio-contracts-main-SNAPSHOT.jar` - note the
coordinate is `main-SNAPSHOT`, so re-check after any capulloAudio pin bump):

- `MediaSourceProvider`: `suspend fun mediaRequestFor(id: String): MediaRequest`,
  `fun queue(): PlaybackQueue`, `fun onQueueAdvanced(index: Int)` **with a default impl** (optional).
- `PlaybackQueue`: `size: Int`, `currentIndex: Int`, `isRotating: Boolean`, `idAt(Int): String?`.
- `NowPlayingSource`: `val nowPlaying: StateFlow<NowPlaying>`.
- `MediaRequest`: data class `(String, String, Map<String, String>)`.
- `NowPlaying`: data class with an `EMPTY` companion; `PlaybackController` starts with `play()`.
  Run `javap -p` on the cached jar for the full member list before M4 - the fields matter for the
  clock overlay.

## 8. Do not

- Do not use `CapulloAudioEngine` (section 3.1) or try to reach `QueuePlaybackLoop`.
- Do not add a second HTTP stack. ktor is the app's client; OkHttp exists only because NewPipe's
  `Downloader` SPI is blocking.
- Do not pin `lib-snapcast-android` directly. It arrives transitively via capullo-audio, which owns
  the pin. telecloud's `pins.versions.toml` documents what a direct pin cost them: a lexically-higher
  hash won the conflict and packaged a stale `.so` — green build, dead control plane, invisible to CI.
- Do not write a colour literal in a composable, or a bare tuned number without the section 6 comment.
- Do not commit, push or tag without the owner's explicit go-ahead. Local builds and tests are fine.
- No AI trailers in commits or PRs.
