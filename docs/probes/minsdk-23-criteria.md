# minSdk 23 probe: pass criteria

Written BEFORE the verification runs (CONTRIBUTING §10). Context: owner request
(2026-11-14) to lower the floor from 26 to 23 (Android 6). NewPipeExtractor's
`java.nio.file` use is native only from API 26; the `_nio` desugar flavour that
already shipped for `URLDecoder` (2026-11-12) backports it, so the build change
is the floor itself plus a pre-26 launcher icon (the adaptive-icon XML in
`mipmap-anydpi-v26` only resolves on API 26+). New coverage: `PlaybackSmokeTest`
— tap a trending song card and prove the song plays (Pause transport + framework
media session `state=3` + playhead advance), run on every matrix leg.

Change under test: `minSdk 23` + `mipmap-anydpi` fallback icon +
`PlaybackSmokeTest` in the smoke matrix. Once that is green, the matrix gains
the API 23 floor leg (the second commit).

## Q1 - Does the app build and pass its offline checks at minSdk 23? (pass/fail)

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`,
`spotlessCheck` in the worktree.

- **Pass:** all green; lint reports no `NewApi` errors (API 24-26 calls would
  surface here).
- **Fail:** a `NewApi` violation → guard or compat fix at the call site, re-run.
  A manifest-merger minSdk conflict → resolve the offending library's floor;
  do not paper over it with an override.

## Q2 - Does API 23 load and play? (pass/fail)

Fresh cold boot, install, launch — local rig first, then the CI floor leg.

- **Pass:** `MainSmokeTest` OK (launch + trending cards) AND `PlaybackSmokeTest`
  OK (Pause transport, media session `state=3`, playhead advances), screenshot
  evidence pulled, no `FATAL EXCEPTION` in logcat.
- **Fail:** crash or `NoSuchMethodError`/`NoClassDefFoundError` on the extractor
  path → a desugar coverage gap at API 23-25; fix the backport, re-run.
- **Inconclusive:** zero cards or a stream-resolution timeout with NO method
  error (network/bot-check flake, as seen once on API 33 in the 2026-11-11
  matrix) → retry that leg once; a retry that passes is a pass, both runs recorded.

## Q3 - Regression canary, API 28-37 (pass/fail)

Desugaring and the floor change rewrite the dex for every level, and the
playback test is new on every leg.

- **Pass:** every existing leg stays green with the playback test added.
- **Fail:** any leg worse than the 2026-11-12 matrix → the change is not
  shippable as-is; fix or revert.
