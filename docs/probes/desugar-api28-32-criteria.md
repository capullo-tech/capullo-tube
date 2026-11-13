# Desugaring probe: pass criteria

Written BEFORE the fix run (CONTRIBUTING §10). Context: the SDK 28-37 smoke
matrix (2026-11-11, `results/api*`) showed the trending kiosk failing on API
28-32 with `NoSuchMethodError: Collectors.toUnmodifiableList` — a Java 10+
API that is only native on Android from API 33. NewPipeExtractor calls it on
the trending path. The build deliberately skipped core-library desugaring on
the belief that `java.nio.file` being native on API 26+ covered the extractor's
needs (see the old minSdk comment in `app/build.gradle.kts`); that belief was
wrong for `java.util.stream` additions.

Fix under test: enable `isCoreLibraryDesugaringEnabled` +
`coreLibraryDesugaring(desugar_jdk_libs)` and re-run the failing levels.

## Q1 - Does the kiosk load on API 28-32 after desugaring? (pass/fail)

Fresh cold boot, install, launch on each of API 28, 29, 30, 31, 32.

- **Pass:** every level shows ≥ 1 trending card with a duration string
  (pattern `· M:SS`) in the UI dump, and no "No static method" text anywhere.
  App-level smoke (spawn, MainActivity resumed, process alive, no crash)
  unchanged from the matrix run.
- **Fail:** any level still shows the NoSuchMethodError banner or zero cards
  *with* the method error present → the desugared library does not cover the
  call site; bump the `desugar_jdk_libs` pin one minor and re-run that level.
- **Inconclusive:** zero cards but NO method error (network/bot-check flake,
  as seen once on API 33 in the matrix run) → retry that level once; a retry
  that loads cards is a pass, but both runs are recorded.

## Q2 - Regression canary on API 36 (pass/fail)

Desugaring rewrites the dex for every API level, so one ≥ 33 level is
re-checked to guard a silent regression.

- **Pass:** API 36 unchanged — smoke pass, trending cards load, no crash.
- **Fail:** anything worse than the matrix run → revert the change; the fix
  is not shippable as-is.
