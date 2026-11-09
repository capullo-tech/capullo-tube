# CapulloTube

An Android app that turns one phone into a YouTube radio station for the whole room.

The phone plays YouTube audio and broadcasts it in sync over the LAN with
[Snapcast](https://github.com/badaix/snapcast). Other phones, Raspberry Pis and web browsers listen
as clients. You pick one song. The app extends it into an endless station:

- **♥ Like** steers the station toward that track's neighbourhood.
- **✕ Skip** steers away from it.
- **⌕ Search** jumps somewhere new.

Part of the [capullo-tech](https://github.com/capullo-tech) family (RadioCapullo, telecloud-radio).

## Status

Early. The five bootstrap milestones (skeleton → playback → station → Snapcast → taste) work on a
real device. The taste weights are first guesses and need tuning from real listening.
`HANDOFF-bootstrap.md` has the full record and a testing guide.

## Build

Requirements: JDK 17, Android SDK 36. Device: Android 8.0+ (minSdk 26).

Point Gradle at your SDK: open the project once in Android Studio (it writes `local.properties`),
or set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew spotlessApply             # format before you commit
```

The capullo libraries (`capullo-audio`, `build-conventions`, Snapcast binaries) come from
jitpack. You do not need to clone them. To build `capullo-audio` from source instead, clone it to
`../capullo-audio`; `settings.gradle.kts` then substitutes it automatically.

## Listen on another device

1. Start a station on the phone. The app shows a **Broadcasting** URL.
2. Open that URL in a browser on the same Wi-Fi, or point any `snapclient` at the phone.

All listeners stay in sync. The browser player's Next button counts as a skip.

## How it works

```
ui/        SearchScreen, now playing (like / skip / search)
station/   StationEngine  - candidate pool from YouTube Mix + related videos,
                            reweighted locally by like / skip / play-through
           StationLoop    - advances the queue
player/    ExoPlayer → PCM → snapfifo   (PlaybackService = foreground media service)
snapcast/  SnapcastManager - runs snapserver on the phone
data/youtube/  NewPipeExtractor client: search, streams, related, Mix
```

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. It is binding: approved libraries only,
Compose/Material3, no hardcoded values, and pass criteria written before any empirical test.
Then read [`HANDOFF-bootstrap.md`](HANDOFF-bootstrap.md) for the architecture and open questions.

When you report station behaviour, name the tracks: "after liking X it played Y, Z" is useful,
"it felt off" is not.

## License

GPL-3.0. See [`LICENSE`](LICENSE). Same licence as NewPipeExtractor, which this app uses.
