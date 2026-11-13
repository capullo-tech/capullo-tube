# Desugaring probe: results

Run 2026-11-12 against `fix/desugar-streams-api28-32`, criteria in
`desugar-api28-32-criteria.md` (written first). Rig: same host-emulator smoke
harness as the matrix run (cold boot, install, launch, UI dump after settle).

## Q1 - kiosk on API 28-32 after desugaring: PASS (5/5)

| API | RESULT | duration cards | `No static method` |
|-----|--------|----------------|--------------------|
| 28  | PASS   | 1 (2 cards visible) | 0 |
| 29  | PASS   | 1              | 0 |
| 30  | PASS   | 1              | 0 |
| 31  | PASS   | 1              | 0 |
| 32  | PASS   | 1              | 0 |

Every level shows the same trending cards as API 36 (e.g. "Top Uh It ·
2:27", "Mr. Do Something · 2:51"), no error banner, app-level smoke
unchanged (MainActivity resumed, process alive, no crash-buffer entries).
Evidence: `~/android-dev/results/api*/{ui.xml,home.png}` on the rig host.

Static check: the dex carries `Lj$/com/android/tools/r8/…` markers and the
desugared `j$.util.stream` implementation; APK grew ~23 KB.

## Q2 - regression canary on API 36: PASS

Unchanged vs the matrix run - smoke pass, trending cards load, no crash.

## Conclusion

Ship `isCoreLibraryDesugaringEnabled` + `coreLibraryDesugaring(pins.desugar.jdk.libs)`
(desugar_jdk_libs 2.1.5; the `_nio` variant stays out - java.nio.file is
native on minSdk 26+). The old "minSdk 26 lets us skip TC's desugaring"
rationale was wrong for java.util.stream and is rewritten in the build file.
