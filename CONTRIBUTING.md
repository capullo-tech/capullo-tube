# Contributing to capullo-tech

One document. It covers how we build, how we test, how we write, and the rules that exist because
breaking them cost us real time.

Machine-specific setup for a given build box (paths, device IPs, keystore locations) stays in that
box's `CLAUDE.md`. Feature state lives in the `HANDOFF-*.md` files. Everything else is here.

**Contents**

1. [Getting started](#1-getting-started)
2. [Approved libraries](#2-approved-libraries)
3. [UI](#3-ui)
4. [UX](#4-ux)
5. [Strings and localization](#5-strings-and-localization)
6. [Hardcoded values in code](#6-hardcoded-values-in-code)
7. [Code style](#7-code-style)
8. [Git and review](#8-git-and-review)
9. [How we write](#9-how-we-write)
10. [Working method: empirical and hardware work](#10-working-method-empirical-and-hardware-work)
11. [Open, not decided](#11-open-not-decided)

---

## 1. Getting started

Clone every capullo-tech repo as siblings in one directory. The builds expect that layout.

```
~/capullo-tech/
  quantumcast/  telecloud-radio/  capullo-audio/  capullo-audio-contracts/
  capullo-source-radiobrowser/  capullo-source-telegram/  build-conventions/
```

Build an app:

```bash
cd ~/capullo-tech/quantumcast && ./gradlew :app:assembleDebug
```

Each assemble also drops a self-identifying copy at
`app/build/outputs/apk-named/<variant>/<app>-v<versionName>-vc<versionCode>-<variant>.apk`.

Three facts that surprise people:

**Sibling repos are composite builds.** An app that finds `../capullo-audio` on disk builds against
your local copy, not the published artifact. Your library change is in the APK immediately.

**Published library changes need the jitpack cascade.** Push, warm jitpack, then bump the pin in
each consumer. There is no shortcut.

**The prebuilt native mirrors are never built locally.** `lib-snapcast-android`,
`lib-tdlib-android` and `lib-media3-ffmpeg-android` come from jitpack only.

Run the tests:

```bash
cd ~/capullo-tech/capullo-audio && ./gradlew :capullo-audio:testDebugUnitTest
```

Format before you commit:

```bash
./gradlew spotlessApply
```

Toolchain: minSdk 23, compile/target 36, JVM target 17.

## 2. Approved libraries

The shared version catalog is the single source of truth. `build-conventions` publishes it and
every repo imports it:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") }
    }
}
```

Bump a version there once, then re-pin the tag per repo. That is the anti-drift mechanism. Do not
work around it.

| Kind of dependency | Lives in |
|---|---|
| Org toolchain (AGP, Kotlin, KSP, Hilt, Spotless/ktlint) | shared catalog |
| Any library used by two or more repos | shared catalog |
| Inter-repo capullo pins (capullo-audio, contracts, source-*, lib-*) | each repo's local `pins` |
| Single-repo deps (coil, newpipe, reorderable, mockk, tdlib) | local `pins` |

`from()` cannot be combined with inline entries. That is why each repo keeps a small second
catalog. Unused entries in a shared catalog cost nothing, so put any shared version there.

### The approved set

- **UI:** Compose (BOM-versioned), Material3, material-icons core and extended, Navigation3.
- **Async:** kotlinx-coroutines. Flow for streams, `suspend` for one-shot calls.
- **Serialization:** kotlinx-serialization-json.
- **Network and RPC:** Ktor client (okhttp engine, websockets, kotlinx-json). Snapcast JSON-RPC
  control uses this. Do not add a second HTTP stack.
- **Media:** Media3 and ExoPlayer.
- **Persistence:** Room, processed by KSP.
- **DI:** Hilt.
- **Test:** JUnit4, kotlinx-coroutines-test, androidx junit and espresso for instrumented
  tests; UiAutomator for device-level smoke tests (EmulatorSmoke.yml's matrix).

### To add a dependency

1. Look in the catalog first. An equivalent is usually approved already.
2. Prefer a Compose, AndroidX or Kotlin-official option to a third-party one.
3. If a second repo needs it, promote it to the shared catalog. Do not duplicate the version.
4. Keep Compose artifacts versionless. The BOM sets the version. An explicit version defeats it.

AGP 9 ships built-in Kotlin. Android modules do not apply `kotlin-android`.

## 3. UI

Compose only, Material3 only. No XML layouts. No Material2. New screens are composables in
`ui/screens/`. The theme lives in `ui/theme/`.

**Dynamic color is the default, with a brand fallback.** API 31 and above take
`dynamicLightColorScheme` or `dynamicDarkColorScheme`. Below that, the hand-built brand schemes
apply. Both light and dark schemes are complete. Dark is not an afterthought.

**Never write a color literal in a composable.** Use `MaterialTheme.colorScheme.*`. The palette
lives in `Theme.kt` and nowhere else. That constraint is what makes dynamic color work.

**State flows down. Events flow up.** ViewModels expose `StateFlow`. Screens collect it. Callbacks
go back up. Business logic does not live in a composable.

**Use `remember` for UI-lifetime state. Use `rememberSaveable` when the state must survive rotation
or process death.** A scroll position that resets on rotation is a bug.

**Give every actionable icon a `contentDescription`.** Give decorative images `null` explicitly, so
a reader can see that someone decided. This is the standard for new code. Existing screens are not
fully audited, so do not read current code as the reference (see section 11).

**Hide role-specific UI. Do not disable it.** The calibration mic button is the model: it is absent
when the app runs as a client.

## 4. UX

**Report progress during long work.** The calibration runs for 3 to 8 minutes. It publishes
"measuring baseline", "probe capture 2/6", "verifying". Silence during a multi-minute operation
reads as a hang.

**Make environment changes reversible, and journal them.** The volume balance writes an undo record
before it writes volumes. Pre-run state goes to a journal, so a process death is recovered at the
next start. The same rule applies to device volume, latency and playback state.

**Say why, in words the user can act on.** "Not detected by the mic: too far, too quiet, or masked"
and "heard fine, but the reading was disqualified" send the user to different actions. They are
different messages. A feature that declines silently is a bug.

**Prefer the least intrusive control that works.** Server-side gain before the client's OS volume.
A modest boost before a loud one. A calibration that shouts is a calibration people switch off.

**Bound the blast radius of an automatic change.** One run can move a client at most 6 dB, and
applies 70 percent of the computed step. Being wrong then costs one visible action, not a
re-levelled room.

**Put debug affordances behind `--es dbg <name>` intent extras.** Never behind hidden gestures in a
release build.

## 5. Strings and localization

Hardcoded strings and string resources can both appear. Every repo ships one `values/` directory
today, so a hardcoded `Text("Settings")` costs nothing and reads better where it is used. Enforcing
`stringResource()` in a single-locale app is ceremony with no payoff.

Two triggers change this:

1. **Someone adds a second locale.** Every user-visible string then moves to `values/strings.xml`
   in the same change. A half-localized app is worse than an unlocalized one.
2. **The same string appears in three or more places.** A resource is better factoring.

Log messages, exception text and debug output stay hardcoded. They are for us.

## 6. Hardcoded values in code

This is a different question from strings, and here there is a rule.

A bare number in an expression is a future bug. A named constant with a comment that explains its
origin is documentation. The rule is not "no magic numbers". The rule is that a value which encodes
a judgement must record whose judgement, and on what evidence.

**Self-evident values stay inline.** `0`, `1`, `% 2`, `.take(1)`. If no reader can wonder "why that
number?", leave it alone.

**Every tuned, measured or chosen value gets a named constant and a comment.** Thresholds,
tolerances, timeouts, retry counts, buffer sizes, gain limits. The comment must give:

- the meaning, with units;
- the evidence: a rig measurement and its date, an upstream constraint, or a calculation;
- what breaks if the value moves, in both directions when that is known.

```kotlin
/** Corrections below this are within measurement noise AND ~the audible-sync floor: leave the
 *  client where it is rather than chase noise. Raised from 15 ms once the rig showed a single
 *  capture resolves the residual to only ~±20 ms — at 15 ms the SAME unchanged setup reported
 *  "trim -60ms" and "already aligned" on consecutive runs, which are one measurement drawn
 *  twice. Sized at ~2x the post-median noise. */
private const val DEADBAND_MS = 25
```

That comment is longer than the code. It is also the reason nobody has simplified the value back
into the bug it fixes.

**A value that is a guess must say so.** This is the rule most worth keeping. `SETTLE_MS = 7_000`
has never been measured and its comment says exactly that. The label is why nobody has tuned it by
eye: a wrong value there breaks correctness in a way the tests do not catch. An unlabelled guess
looks identical to a measurement, and that is how a placeholder becomes folklore.

**Scope a constant as tightly as its meaning allows.** Make it private to the class that uses it.
Make it `internal` only when a test must assert against it. A test that asserts `3` instead of
`MIN_LEVEL_SAMPLES` stops testing the real thing as soon as the constant changes.

**Never hardcode:** colors in composables, dependency versions outside the catalog, device
addresses or ports outside config, credentials or keystore paths in any form.

**Two copies of one number is the real defect.** Copies drift, and the drift is silent. When a
constant must be mirrored, as `SOURCE_SPREAD_MS` is to keep the DSP layer independent of the
matching layer, each comment must name the other copy.

## 7. Code style

Spotless drives ktlint. Run `./gradlew spotlessApply` before you commit. CI runs `spotlessCheck`.

- Maximum line length is 100.
- `ktlint_standard_annotation` is disabled.
- `ktlint_function_naming_ignore_when_annotated_with` is set to `Composable`, so `PascalCase`
  composables pass.
- QC also relaxes `no-wildcard-imports` and `max-line-length` for pre-existing Compose code. New
  code still respects 100 columns. The relaxation covers legacy code, not new work.

## 8. Git and review

**Branch from the feature you are working on, never commit to the default branch directly.**

**Write commit messages that explain why.** The subject line says what changed. The body says what
was wrong before, what the fix does, and what evidence supports it. Our commit bodies are long
because the reasoning is the valuable part. A future reader can see the diff; they cannot see the
rig run that justified it.

**No AI trailers in commits or pull requests.** No `Co-Authored-By` lines, no "generated with"
footers. The message describes the change, not the tool that typed it.

**A green branch is not a finished feature.** Push when the feature is done, not when the tests
pass.

**Committing, pushing, tagging and releasing need an explicit go-ahead from the repo owner.** Local
builds and local test runs do not.

**Look at the target before you overwrite or delete it.** When you change something a person set by
hand, record the previous state first.

## 9. How we write

This applies to documentation, commit messages, code comments, and issue and PR text.

**Lead with the answer.** Context and reasoning follow it. Skip preamble and filler.

**Use prose by default.** Use a list when the content is genuinely list-shaped. Use headers when
navigation genuinely helps.

**Prefer active voice. Avoid hedging when you are not uncertain.**

**Label your epistemic status when it matters:** established fact, widely held view, your
inference, or genuine uncertainty. This rule has caught real errors here. A grading tool once
reported a room "6.8 dB lopsided" as though it were fact. It was an unvalidated inference, and it
was wrong.

**Say it once when you disagree, then do the work.** If a request has a better framing, give the
framing, then carry out the request. Never agree with something incorrect to avoid friction.

**Correct errors plainly and move on.** Do not defend a framing because you already stated it.

### Documentation follows ASD-STE100

**Scope: user-facing and contributor-facing documentation.** READMEs, runbooks, procedures, error
messages, release notes, API guides, and this file. It does not apply to code comments, commit
bodies, or handoff documents. Those carry evidence and reasoning, and section 6 requires exactly
the long causal sentences that STE would cut. Do not shorten a comment that explains why a constant
has its value.

Write documentation with Simplified Technical English. It is the controlled language that aerospace
manufacturers use for maintenance manuals. The rules exist so a tired reader who is not a native
English speaker cannot misread an instruction. They also remove the usual signs of machine-written
text: long sentences, rotating synonyms, hedges and filler.

The rules that matter most:

- **20 words per sentence in procedures. 25 in descriptions.**
- **One instruction per sentence. One topic per paragraph.**
- **One word, one meaning.** Choose one verb for the check/verify/confirm idea and one noun for the
  config/settings idea. Use no other word for that idea in the document.
- **Condition before command:** "Before you run a calibration, start playback." Not the reverse.
- **Simple tenses. Active voice. No noun clusters longer than three words.**
- Never change code, identifiers, commands or quoted error text to satisfy a rule.

The full standard is free at asd-ste100.org.

## 10. Working method: empirical and hardware work

This section matters most for the calibration work, where the hardware is the source of truth.

**Unit tests prove orchestration. They cannot prove acoustics.** A feature that passes 172 tests
and has never run on hardware is unproven. Say which of these a claim rests on: proven on hardware
with a log to point at, proven in tests only, or inferred from arithmetic.

**Write the pass criteria before the run.** A rig run takes 5 to 9 minutes and produces a wall of
numbers. If you decide what counts as success afterwards, a disappointing result quietly becomes a
passing one. Write the criteria to a file first, including the failure and inconclusive shapes.
Being wrong in a documented way is a result. Being vaguely right is not.

**Suspect the newest tool when measurements disagree.** A grader once reported a room 6.8 dB
lopsided while the calibrator, the commanded gains and the owner's ear all put it within about 1 dB
of even. The grader was wrong: it compared raw peak levels across captures whose noise floors were
8.5 dB apart, so it measured the floor. Three sources agreeing and one disagreeing points at the
one.

**Never report a number without its spread.** A grade whose scatter is larger than the effect it
claims to measure is not evidence. The tool should say so itself.

**Take enough samples, or make no claim.** Consecutive captures of one speaker at a fixed gain have
measured 8 to 13 dB apart. Grade with at least 5 capture pairs. Two pairs carry about ±3 dB, which
is worse than the effects under measurement.

**Leave shared hardware in a known state.** The rig is in someone's living room, not a CI rack.
Record the previous state in a file before you change volumes or latencies. Restore it afterwards,
and verify the restore. Between runs, leave the speakers playing, because Bluetooth sinks power off
when idle and the client then falls back to the phone speaker. Run `rig-precheck.sh` before every
run.

**Prove that a test can fail.** After you write a test, break the thing it covers and watch it go
red. A test that passes against broken code is worse than no test, because people trust it. This
check also finds phantom coverage: a branch we believed was untested turned out to be covered, and
we found that by making the branch throw.

**Model the physics in a fake, not the schedule.** A fake that reproduces a failure by capture
index can be satisfied by code that cheats. A fake that reproduces it by cause, such as "this
speaker is inaudible below 60 percent volume", cannot.

**Declining is a feature. Silence is a bug.** An empirical feature will sometimes fail to measure
the room. Every decline must write nothing, restore what it touched, and explain why in words the
user can act on.

**Optimize the count, not the constant.** Find which part of a slow operation is a design choice
and which is physics. Capture length sets correlation signal-to-noise ratio, and 3-second blocks
measured a 4 dB spread with one block in four failing. Capture count was a yield estimate that
healthy rooms beat every time. Cutting the count saved a minute at no cost to accuracy. Cutting the
length would have traded away the accuracy that makes the feature useful. Do not tune a constant
you have not measured.

**Extract shared logic. Do not copy it.** A second copy of a delicate calculation drifts from the
first.

**Delete what nothing reads.** A variable only ever written, a constant nothing references, a
parameter every caller passes the same value for: remove it in the change that orphans it. Both
appeared in one session's work and both were caught by hand, not by the compiler. Kotlin does not
warn on an unused private constant, so nothing catches these for you.

**Do not build for a caller that does not exist.** Add the abstraction when the second caller
arrives. A generic seam with one user is harder to change than the specific code it replaced,
because the seam looks load-bearing.

## 11. Open, not decided

These are accidents of history, not decisions. Do not treat them as precedent.

- **Navigation3** is in the catalog and RadioCapullo uses it. QC and TC have not migrated.
- **There is no design-system module.** Shared composables are copied between apps. That is
  acceptable at two apps. Revisit at three.
- **There are no screenshot or Compose UI tests** in the calibration path. Logic is unit-tested.
  The UI is verified by hand on the rig.
- **Accessibility beyond `contentDescription`** has never been audited: TalkBack traversal,
  minimum touch targets and font scaling are all unknown.
- **`SETTLE_MS` is a guess**, not a measurement. It could be wrong in either direction.
