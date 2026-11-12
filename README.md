# CapulloTube

An Android app that turns one phone into a YouTube radio station for the whole room.

The phone plays YouTube audio and broadcasts it in sync over the LAN with
[Snapcast](https://github.com/badaix/snapcast). Other phones, Raspberry Pis and web browsers listen
as clients. You pick one song. The app extends it into an endless radio:

- The radio plays the song's YouTube Mix and its top related videos, in pairs: one song of each,
  each list in YouTube's order. When the queue runs low, the radio continues with the pairs of
  the last song played.
- **♥ Like** saves the song. It also blends in 2 songs like it (one of its Mix, one related)
  after the current song, in a radio or a list (Settings can change the count, or switch to
  "Save only" or "Switch now").
- **Next** goes to the next song. A skip is not a signal.
- **⌕ Search** starts somewhere new.

Part of the [capullo-tech](https://github.com/capullo-tech) family (RadioCapullo, telecloud-radio).

## Status

Early (v0.2). Playback, the radio, lists, Snapcast, links and the likes backup work on a real
device. The radio constants are first guesses; `docs/probes/radio-criteria.md` gives the pass
criteria for a listening test. `HANDOFF-bootstrap.md` has the bootstrap record.

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

## Preview releases

Every push to `main` publishes a preview APK. The release `preview` holds one APK. GitHub deletes
the previous release, so only one release exists at all times.

Install previews with [Obtainium](https://github.com/ImranR98/Obtainium):

1. Add the app source `https://github.com/capullo-tech/capullo-tube`.
2. Set version detection to **Release title**.
3. Enable prereleases.

Every preview is signed with the same key as the previous one. Obtainium therefore installs a new
preview over the old one, like an OTA update.

## Listen on another device

1. Start a station on the phone. The app shows a **Broadcasting** URL.
2. Open that URL in a browser on the same Wi-Fi, or point any `snapclient` at the phone.

All listeners stay in sync. The browser player's Next button goes to the next song.

## How it works

```
ui/        Home, Search, Library, Settings, Player, Up next
station/   StationEngine  - radios as chained pairs of YouTube Mix and related videos
                            (Pairs.kt), Liked radio, playlists, channels and Liked
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
