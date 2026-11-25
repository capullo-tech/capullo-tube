# CapulloTube

An Android app that turns one phone into a YouTube radio station for the whole room.

The phone plays YouTube audio and broadcasts it in sync over the LAN with
[Snapcast](https://github.com/badaix/snapcast). Other phones, Raspberry Pis and web browsers listen
as clients. You pick one song. The app extends it into an endless radio:

- The radio plays the song's YouTube Mix, its top related videos, and songs
  [Last.fm](https://www.last.fm) finds similar that YouTube did not offer, in rounds: one song of
  each, each list in its own order. When the queue runs low, the radio continues from the last
  song played.
- **♥ Like** saves the song. It also blends in 2 songs like it after the current song, one of
  them from Last.fm when it found any, in a radio or a list (Settings can change the count, or
  switch to "Save only" or "Switch now").
- **Next** goes to the next song. A skip is not a signal.
- **⌕ Search** starts somewhere new.
- **Lyrics** show under the Player's controls when a song has them: synced lyrics from
  [LRCLIB](https://lrclib.net), or lyrics pasted into the video description. LRCLIB receives the
  artist, the title and the duration of each song that plays; Settings > Look up lyrics online
  turns that off. Tap the lyrics for all of them; hold them to switch between a slim tab and a
  card with the current line.
- **Similar songs** come from Last.fm. Last.fm receives the artist and title of each song a
  radio expands; each new suggestion is looked up once on YouTube and remembered. Settings >
  Similar songs turns this off, picks the Depth (Familiar or Deep), and takes an optional
  personal Last.fm API key in place of the app's. Last.fm answers are cached for 30 days, calls
  are spaced, and a rate-limit answer pauses lookups for 10 minutes.

Part of the [capullo-tech](https://github.com/capullo-tech) family (RadioCapullo, telecloud-radio).

## Status

Early (v0.2). Playback, the radio, lists, Snapcast, links and the likes backup work on a real
device. The radio constants are first guesses.

## Build

Requirements: JDK 17, Android SDK 36. Device: Android 6.0+ (minSdk 23).

Point Gradle at your SDK: open the project once in Android Studio (it writes `local.properties`),
or set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

The app's Last.fm key comes from `LASTFM_API_KEY` in the environment or `lastfm.apiKey` in
`local.properties` (both optional; without one, similar songs need a personal key in Settings).

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
./gradlew spotlessApply
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

A phone with CapulloTube can join instead: scan the multiroom QR and tap **Open in CapulloTube**
on the page it opens. The app pauses its own song, plays the scanned station, and shows
**Listening to** its address with a **Leave** button. Playing a song here also leaves. Without
CapulloTube the same button opens the browser player; the page never offers another capullo app.

Off your Wi-Fi: open the multiroom QR while a song plays and pick **Cloudflare**. That creates a temporary public link; anyone with it can listen and control. It stays on until you tap **Turn off** or join another station.

## Debug: cross-device join test

Debuggable builds take commands as `--es dbg <name>` on `MainActivity`; release builds ignore
them. This drives a join between two phones over adb without touching either screen. `A` is the
broadcaster's adb serial, `B` the listener's.

```bash
# A: start a station. Playing anything broadcasts; there is no separate broadcast command.
adb -s "$A" shell am start -a android.intent.action.VIEW -d 'https://youtu.be/dQw4w9WgXcQ' tech.capullo.tubecapullo
# A, optional: the multiroom QR's Cloudflare chip (off is its Turn off button)
adb -s "$A" shell am start -n tech.capullo.tubecapullo/.MainActivity --es dbg publiclink --es value on
# A: log the URLs the multiroom QR shows
adb -s "$A" shell am start -n tech.capullo.tubecapullo/.MainActivity --es dbg share
adb -s "$A" logcat -d -s SnapcastManager:I | grep ' share '
# share lan=http://192.168.1.20:41234/join/ public=https://x.trycloudflare.com/join/ broadcasting=true listening=-

# B: join one of them, as a scan of the QR would (the join intent-filter takes package-targeted VIEW)
adb -s "$B" shell am start -a android.intent.action.VIEW -d 'http://192.168.1.20:41234/join/' tech.capullo.tubecapullo
adb -s "$B" shell am start -n tech.capullo.tubecapullo/.MainActivity --es dbg share   # listening=192.168.1.20
adb -s "$B" logcat -d -s SnapcastManager:I | grep -E ' (join|share) '
# B: leave the station
adb -s "$B" shell am start -n tech.capullo.tubecapullo/.MainActivity --es dbg leave
```

`share` prints `lan=none` while not broadcasting, and `public=off|starting|error:<msg>` until the
tunnel is up.

## How it works

```
ui/        Home, Search, Library, Settings, Player, Up next
station/   StationEngine  - radios as chained rounds of YouTube Mix, related videos and
                            Last.fm similar songs (Pairs.kt), Liked radio, playlists,
                            channels and Liked
           StationLoop    - advances the queue
player/    ExoPlayer → PCM → snapfifo   (PlaybackService = foreground media service)
snapcast/  SnapcastManager - runs snapserver on the phone
data/youtube/  NewPipeExtractor client: search, streams, related, Mix
data/lyrics/   LRCLIB client, the artist and title parser, description lyrics, Room cache
data/similar/  Last.fm client, seed name tries, Depth, YouTube resolver with a Room cache
```

## Contributing

When you report station behaviour, name the tracks: "after liking X it played Y, Z" is useful,
"it felt off" is not.

## License

GPL-3.0. See [`LICENSE`](LICENSE). Same licence as NewPipeExtractor, which this app uses.
