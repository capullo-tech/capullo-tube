# PLAN: CapulloTube v0.2 (updated 2026-11-12)

All work is local on branch `links-export`. Nothing is pushed until the owner says so. The
section "Delivery" says how the local history becomes a short, direct branch tree before the push.

## Status

| State | Item |
|---|---|
| Done, device-checked | navigation shell, Library, Settings, Queue, share, links in and out, liked export and import, playlists and channels with shuffle and repeat, search filters, Home rows, stall watchdog, CapulloTube name |
| In progress | density pass (smaller type and tighter rows, Spotify-like mini-bar) |
| Built, unit-tested, not device-checked | Home v2 and albums (section "Home v2 and albums") |
| Next | simpler radio (section "Radio") |
| Owner, on device | hour-long soak; swipe-from-recents check |

## Owner decisions

- Three tabs: Home, Search, Library. Settings opens from the Home top bar.
- A tap on a song is "Go to radio" and opens the Player.
- Names say what things are: the uploader is a Channel, a YouTube list is a Playlist, the app
  is CapulloTube. Playlist names are YouTube's own.
- Every Player title is Radio, Playlist or Channel, then a name (2026-11-12). A radio names its
  origin: the seed song, the search ("<query>" in quotes) or Liked songs. The name stays while
  the radio chains, because the base list comes from the seed (as on Spotify). Liked is a
  playlist: "Playlist · Liked songs".
- Liked: unlike removes the track from the list, and that is all. The end of Liked rolls into
  Liked radio.
- A shared or tapped link: nothing playing -> radio and Player; something playing -> plays next.
  "Ask what to do" is an option. Link opening can be switched off.
- Share sends only the `https://youtu.be/<id>` link.
- Portrait only (2026-11-12). Android 16+ may ignore the lock on large screens (tablets,
  foldables); that is accepted.
- Previous goes back for real, across station changes (2026-11-12; section "Previous").
- Pairs (2026-11-12): every fetch from a song takes its Mix and its related videos, in pairs
  (section "Radio", "Pairs").

## Navigation

```
Tabs (Home | Search | Library), each keeps its own state; mini-bar above the tabs
  -> Player        mini-bar tap, song tap, notification tap, --es dbg player
  -> Settings      gear on the Home top bar
Player
  -> Queue         queue button, or swipe up on the cover
  -> Multiroom     multiroom icon
  -> song menu     tap the song title: Go to radio (outside a radio)
  -> Channel       tap the channel line
```

## Play sources

| Source | Started from | Plays | Title |
|---|---|---|---|
| Radio | song tap; Go to radio; Search "Radio from results"; a shared link | endless (see "Radio") | Radio · <song> or Radio · "<query>" |
| Liked radio | Library > Songs > Radio; end of Liked | your likes, each followed by N songs like it | Radio · Liked songs |
| Playlist | Search playlists or albums; a pasted or shared playlist or Mix link | the list | Playlist · <name> |
| Channel | tap the channel line; Search channels | the channel's uploads, newest first | Channel · <name> |
| Liked | Library > Songs > Play or Shuffle | your likes | Playlist · Liked songs |
| Album | Home > Full albums; Library > Albums; Listen again | one long video; its chapters are its tracks | Album · <title> |

Lists (Playlist, Channel, Liked) have shuffle and repeat (off, all, one). Repeat off rolls into a
radio from the last song (Liked rolls into Liked radio). An album's end rolls into a radio from
the album. When a playlist, a channel or an album ends, a chip says "<Playlist, Channel or
Album> ended · continuing as Radio".

## Radio (approved 2026-11-11; "Songs from each Mix" added the same day; Pairs 2026-11-12)

The radio today scores a pool of candidates with about ten terms. Most of that work repeats
YouTube's own Mix ordering, and the skip damp rarely touches the queue. The new radio keeps
YouTube's ordering and adds only what a Mix cannot do.

Every playback is a starting queue plus the same rules. One number N, Settings > "Songs added
per like" (0, 2, 4 or 6, default 2), sets how many songs follow a liked song everywhere.

1. Start: play the song's pairs (see "Pairs"). Leave out songs played this session and songs
   under 60 s or over 15 min.
2. Low queue (about 5 left): chain the pairs of the last song played: all of its Mix and all
   of its related videos.
3. Skip: goes to the next song. No signal.
4. One list empty: when a song has no Mix, or no related video passes the rules, the other list
   fills every place.
5. Shuffle (radios too): shuffles the rest of the current chain; later chains follow the
   setting. Radios have no repeat.
6. A like, by Settings > "When you like a song". It applies in a radio, Liked radio, a playlist
   and a channel. It does not apply in Liked: there every song is already liked, and the heart
   only unlikes.
   - Blend in (default): the first N songs of the liked song's pairs (N/2 of its Mix, N/2
     related) go right after the current song, then the radio or the list goes on. In a list
     they are extras: the list's place and its repeat round do not change. Several likes
     interleave. N = 0 works as Save only.
   - Save only: the like does not change the queue.
   - Switch now: the upcoming queue becomes the liked song's pairs; the current song plays on.
     A playlist, a channel or Liked radio then becomes a radio from the liked song (title
     "Radio · <song>"), and the radio rules apply from then on.
   "Go to radio" is different: it starts a new radio from the chosen song at once.
7. Liked radio: your likes in rounds. No like plays again before all likes played. After each
   like come the first N songs of that like's pairs (not played, in the length window, not
   liked). N = 0 plays likes only. Shuffle is on at the start (likes in random order per
   round); shuffle off plays the likes newest first. A like there follows item 6.
8. Library > Songs (the liked songs) keeps three buttons: Play (in order), Shuffle and Radio
   (Liked radio).

### Pairs (owner decision 2026-11-12)

- Every fetch from a song takes both lists: its Mix (YouTube's order) and all of its related
  videos that NewPipe returns, about 20 (YouTube's order; the top 8 until the Home v2 decision).
  A unit is a pair: 1 Mix song and 1 related song, in random order.
  Each list keeps its own order. When one list runs out, the rest of the other follows.
- Related gate (related videos only): a related video stays when its channel is verified, or
  when it has at least 10K views and at least 1 % of the seed's views. Unknown views fail.
- A related upload of the seed's own song (the same cleaned title) leaves.
- One copy per title: of songs with the same cleaned title, only the most viewed is queued.
- The probe of 2026-11-12 (`docs/probes/related-quality-results.md`) could not set the gate
  numbers (too few verified items), so 10K and 1 % stay GUESSES. It also found that most top
  related videos are compilations over 15 minutes, so the Mix often fills the related share.
- Cards and rows show "<views> views · <year>" when known. Mix songs carry neither.

Pass criteria: `docs/probes/radio-criteria.md` sections 2 and 14 to 18.

No dislike and no blacklist (owner call 2026-11-11: keep it simple). Skip is the only way past a
song.

Removed with it: score terms and jitter, skip weight and fade, "less like this" (the heart's
long-press, the `less` debug command), history taste (boost, channel affinity, fatigue, cold
start), "Go to mix" (a radio is already that Mix, continued), the "Use listening history"
switch. Play history stays for Listen again and Library > History.
Pass criteria go to `docs/probes/radio-criteria.md` before the first listening test.

## Queue

- The Player's queue button opens the Queue sheet: a drawer, at most about 85% of the screen.
  Title "Queue", with the station's source under it ("Radio · Thunder").
- Two steps: it opens at half the screen with the first upcoming song at the top; drag up for
  the capped full height. Pull down inside the list to see the current and played songs.
- One list, no section headers: played songs (dimmed), the current song (highlighted, with a
  playing badge), then the upcoming songs. A queue too short to scroll that far gets exactly the
  missing space at the bottom of the list, below the screen edge while half open.
- A row tap plays it now (not a skip); a played row plays again. Swipe an upcoming row to remove
  it. Row buttons: heart, share, and a menu with Go to radio.
- Each row says where it came from ("From the Mix of X", "Related to X").

## Previous (owner decision 2026-11-12)

The Player button, the cover swipe, the notification, the lock screen, Bluetooth and the web
player all do the same thing:

1. Past 3 s into the track: restart the track.
2. Else, the station's previous song plays again. The current song goes to the front of Up
   next, so Next returns to it.
3. On the first song of a station: the station left by the last station change comes back as it
   was. Its source, its upcoming list, its list place and repeat round, its Liked radio round,
   shuffle and repeat return. The song that was playing then plays again from the start.
4. Nothing earlier: restart.

A station change is a song tap, Go to radio, the channel line, a card or a list. A roll-over and
a Switch now like are the same station going on. Up to 5 stations are kept, so repeated Previous
walks back through several changes. Room keeps them (section "Home v2 and albums"), so Previous
also reaches a station from before an app restart. In an album with chapters, rules 1 and 2 act
on chapters first: past 3 s into a chapter, Previous restarts the chapter; else it goes to the
chapter before; on the first chapter it acts as on a song. Pass criteria:
`docs/probes/radio-criteria.md` section 13 and `docs/probes/home-v2-criteria.md` section 4.

## Home, Search, Library, Settings

- Home: Continue, Listen again, "More like <song>" rows (0 to 5, default 1; up to 10 cards: the
  song's Mix and related videos in pairs), Full albums, one "Trending in <country>" row per
  chosen chart (up to 5, default the device country). Taiwan, Hong Kong and China have no
  YouTube chart. Section "Home v2 and albums" has the rules.
- Search: Songs (default), Playlists, Albums, Channels.
- Long-press any song card or row: Play next (only while something plays), Go to radio.
- Library: Songs (liked songs: Play, Shuffle, Radio), Albums (liked albums), History. Export and
  import (Songs and Albums tabs) carry both.
- Settings: Trending charts, Continue cards, More like rows, Full albums, When you like a song,
  Songs added per like, Links (open YouTube links, ask what to do), Developer (debug builds).

## Debugging

- `adb logcat -s TubeStation`: one line per station and player event (also `snapshot saved`,
  `snapshot restored`, `album chapter-next`, `album chapter-previous`, `album chapter at=`,
  `roll-chip`).
- `adb logcat -s TubeHome`: one line per Home row fetch (`moreLike`, `fullAlbums`, `reload`).
- `adb shell dumpsys activity service tech.capullo.tubecapullo/.player.PlaybackService`: station
  state and a `player:` line (state, buffer, error, loop index).
- `adb shell am start -n tech.capullo.tubecapullo/.MainActivity --es dbg <name>`, where `<name>`
  is `station`, `player`, `dump`, `like`, `skip`, `liked` or `likedradio`.
- Settings > Developer > Station debug: the dump, live.
- Screens: the agent reads layout through `uiautomator dump` bounds; it cannot view screenshots
  in this environment. Visual review is the owner's.

## Delivery

The local history has 20 commits on 7 stacked branches, and several of them add code that a
later commit removes (artist radio, the "steered by" line, the playlist third line, our Mix
labels, and, if the radio proposal goes ahead, the whole taste-history work). Pushing that
history would show reviewers work that no longer exists.

When the code is final (owner call: three stacked PRs, version v0.2, versionCode 2):

1. Keep the current history as a local tag `backup/local-2026-11-11`. It is not pushed.
2. Rebuild three branches from `main` that contain only the final code, each a short series of
   one-theme commits:
   1. `tube/engine`: sources, radio, lists, history, settings store, player service, watchdog,
      logging, capullo-audio pin, version v0.2 (vc2). Includes the call-site changes the old UI
      needs to compile.
   2. `tube/screens` (on 1): navigation shell, Settings, Library, Home, Search, Player, Queue,
      debug screen, density.
   3. `tube/links` (on 2): links in and out, liked export and import, docs.
3. Each branch builds and passes its tests on its own.
4. Delete the old local branches.

## Not in this plan

- Tune the GUESS constants from listening (ongoing).
- Telecloud features that are not ported (separate plan).

## Ecosystem boundaries (audit 2026-11-11)

capullo-tube is one app of the capullo-tech family. Code falls into three groups.

**Use what already exists (no copy):**

| Need | Library | How the app uses it |
|---|---|---|
| Source SPI (`MediaSourceProvider`, `PlaybackQueue`, `NowPlaying`, `MediaRequest`) | capullo-audio-contracts | `StationEngine` implements it |
| Snapcast processes, control client, NSD, ports | capullo-audio (`tech.capullo.audio.snapcast.*`) | `SnapcastManager` composes them |
| PCM FIFO sink, audio focus | capullo-audio (`player.FifoAudioBufferSink`, `AudioFocusController`) | `PlaybackService` |
| Multiroom sheet, listen QR, share | capullo-audio-ui (`SnapcastControlSheet`, `ListenQrDialog`) | `ui/shared/MultiroomSheet.kt` |
| Versions and toolchain | build-conventions catalog | `settings.gradle.kts` |

**Copies that exist only because a library does not expose the piece yet:**

| App file | Master | Remove when |
|---|---|---|
| `station/StationLoop.kt` | capullo-audio `QueuePlaybackLoop` (`internal`) | the engine exposes its loop |
| `snapcast/SnapcastManager.kt` (trimmed) | telecloud-radio `SnapcastManager.kt` (841 lines); quantumcast composes the same primitives in its own service | capullo-audio grows a public broadcast manager. Three apps now carry this glue, which meets the "revisit at three" trigger in CONTRIBUTING section 11. |
| Player chrome: `MultiroomIcon`, `SeekBar`, control bar, mini-bar | telecloud-radio `ui/player/PlayerScreen.kt`, `AppNavHost.MiniPlayer`; quantumcast `NowPlayingBar` | a shared player-chrome module exists (same section 11 trigger) |

**App-only (CapulloTube or YouTube specific, no second caller today):**
`data/youtube/*` (telecloud-radio main no longer carries YouTube code, so this is the only copy),
`station/*` taste engine, Queue sheet, Library export/import, link handling, `TitleCleaner`. Each is
generic in shape, but no other app calls it. Extraction waits for that caller (CONTRIBUTING
section 10). `StationEngine` already implements the contracts SPI, so a future
`capullo-source-youtube` is a file move.

**Actions:**
1. Done: the capullo-audio pin is 1c866cf, the same commit telecloud-radio and quantumcast pin.
2. Propose to the capullo-audio owner: a public broadcast manager (snapserver, control plugin,
   NSD, local snapclient, focus) and a public queue loop. Both remove app copies in three apps.
3. Shared player chrome in capullo-audio-ui (multiroom icon, seek bar, transport, mini-bar):
   decide after the broadcast manager, since the chrome binds to its state.

## Home v2 and albums (owner decisions 2026-11-12; built 2026-11-12, device check open)

- Home rows, top to bottom; empty rows hide: Continue, Listen again, More like <song> (default 1
  row), Full albums, Trending in <country>.
- Continue: stations you left unfinished (radio, playlist, channel, album), resumed exactly
  where you left them (song, position, queue, list place, shuffle and repeat; stale links
  re-resolved). The last 6 (GUESS), persisted in Room, which also backs Previous's station stack
  across app restarts. A finished list (Repeat off end) leaves Continue.
- Listen again: older items only, picked at random on each load: liked songs and liked albums
  not played in 30+ days, and songs played 3+ times that you have not played in 30+ days
  (GUESSES). Never the playing song or a Continue station.
- Full albums: discovery only. Long videos (25-120 min, GUESS) found in the related lists of
  your liked songs (the compilations the radio's length window drops), kept when the title says
  album/full album or YouTube has chapters, through the related quality gate, liked albums
  excluded; when that finds too few, search "<artist> full album" for the top liked artists.
- Album playback: `Album · <title>`. Chapters drive Next and Previous and list in the Queue; no
  chapters plays as one track. The end rolls into a radio from the album, with the chip
  "Album ended · continuing as Radio" (the same chip for playlists and channels).
- Album likes: the heart saves or removes the album, never blends. Library tabs: Songs (liked
  songs), Albums (liked albums), History. Liked songs list and radio never include albums.
  Export and import carry both.
- Related videos: use all that NewPipe returns per seed (about 20) instead of the top 8.
- Radio chaining stays free drift (from the last song played, as v0.1 did).
- Screen-off service start: fix only if the soak test shows it.

As built:

- Pass criteria: `docs/probes/home-v2-criteria.md`, written before the code and the probe. The
  probe run of 2026-11-12 is in `docs/probes/home-v2-results.md`. It kept the 25 to 120 minute
  window (0.96 of album-titled items inside), kept the chapter test (0.42 of "chapters only"
  items look like compilations, under half), and set 4 seeds and 8 chapter checks per load.
  About a third of albums have chapters; the others play as one track.
- Continue and Previous read one Room table, `station_snapshot` (the station as JSON). A station
  is written when it is left, when a track starts and every 10 s of playhead (GUESS). Room keeps
  7 rows: up to 6 Continue cards and the playing station.
- Liked albums have their own table, `liked_album`, so every reader of the liked songs stays
  album-free with no filter. The heart is the album's only while the album's own video plays.
- The likes file is version 2: `tracks` (songs) and `albums`. Version 1 files import as songs.
  A version 1 build refuses a version 2 file and asks for an update, so albums are never lost
  without a message.
- Listen again: liked songs and albums liked and not played for 30 days, and unliked songs in
  the song length window played 3 or more times and not in 30 days (GUESSES). A random order per
  load.
