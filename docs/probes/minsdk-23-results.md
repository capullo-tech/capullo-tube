# minSdk 23 probe: results

Recorded against the criteria in `minsdk-23-criteria.md`, which was written
before the runs. Worktree `../capullo-tube-minsdk23`, branch
`chore/min-sdk-23`; commit f49048c carries the change, b6d9b12 the failure
evidence channel. Runs on 2026-11-13/14.

## Q1 - Build and offline checks at minSdk 23: PASS

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` (full suite;
  the network probes stay env-gated by `YT_SMOKE`), `spotlessCheck`: green.
- `lintDebug`: runs clean of **NewApi** - zero findings. Its 49 errors are the
  pre-existing `UnsafeOptInUsageError` set in `PlaybackService.kt` (media3
  opt-in annotations). Proven pre-existing: untouched `main` at minSdk 26
  lints with the identical 49 errors and the same first failure. The 14
  warnings are deliberate choices with manifest comments (portrait lock, no
  autoVerify) plus dependency-version nags. No baseline added; CI does not
  gate on lint.

## Q2 - Does API 23 load and play?

### Local rig (home IP): PASS

API 23 guest, fresh wipe-data boot, host KVM
(`~/android-dev/smoke23-playback.sh`, 2026-11-14 00:42): `OK (2 tests)` in
26.5 s. Launch, trending cards, card tap, Pause transport, framework media
session `state=3`, playhead advancing. Guest logcat has no FATAL EXCEPTION and
no NoSuchMethodError / NoClassDefFoundError - the desugar backports hold at the
floor. `device SDK: 23` confirmed before install.

### CI legs (GitHub-hosted IPs): BLOCKED, root-caused

Every leg red at the 120 s playing bar; launch and content green. The
assertion-message evidence (b6d9b12) carries the guest state:

- texts: the Player screen with the seed song, `0:00 | -3:42` (duration is the
  metadata fallback - the player never prepared), `Radio` badge;
- session: `PlaybackState {state=0, position=0, buffered position=0,
  speed=0.0, updated=83642 ...}` - set once, never progressed.

The failure artifact's logcat names the cause in the app's own words, once per
queue entry:

```
W TubeStation: resolve id=Sh4pJYJOT7M index=0 result=skipped
  error="YouTube asked for a bot check. Playback needs a proof-of-origin token."
```

YouTube bot-checks GitHub's datacenter IPs at the innertube player endpoint.
The extractor throws `SignInConfirmNotBotException`; the app maps it to
`YouTubeError.BotCheck` and the station loop skips the whole queue - the
skip-not-silent design (CONTRIBUTING §4) working as written. The player never
receives an item, so the session sits at state NONE.

Not a minSdk regression: the same build plays on API 23 from a residential IP,
and trending/search JSON (a different endpoint class) succeeds from the same
CI IPs.

## Q3 - Regression canary, API 28-37: content PASS, playback blocked as above

`MainSmokeTest` passed on all ten legs in both CI runs (43464811367,
43468165256). Zero NoSuchMethodError anywhere: no desugar regression on any
level. Only the playback bar is red, for the Q2 reason.

## Open: the CI playback gate needs a decision

The seam exists: extractor v0.26.5 ships the `PoTokenProvider` interface, but
the app calls the provider-less `NewPipe.init` overload, and
`YouTubeModels.kt` documents BotCheck as "nothing will play until a PoToken
provider exists". Generating tokens needs Google's BotGuard run in a real
WebView (the NewPipe-app approach; Rhino will not satisfy the integrity
checks). Options: wire that provider in the engine; keep the playback gate on
hardware and let CI prove load; or run the matrix on a self-hosted runner with
a residential IP.

## Decision (owner, 2026-11-14)

CI proves load; the playback gate stays on hardware. The CI matrix runs
MainSmokeTest only; PlaybackSmokeTest joins the matrix invocation when a
PoTokenProvider lands. The API 23 floor leg lands in the same change.
