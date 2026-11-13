package tech.capullo.tubecapullo

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level PLAYBACK smoke - the playback payload for hardware runs (the local rig,
 * ~/android-dev/smoke23-playback.sh), NOT the CI matrix for now: YouTube bot-checks
 * datacenter IPs, so CI legs cannot resolve a single stream (every queue entry skips with
 * YouTubeError.BotCheck - docs/probes/minsdk-23-results.md, probe 2026-11-14). When the app
 * gains a PoTokenProvider, add this class back to the invocation in EmulatorSmoke.yml and
 * CI proves play end to end.
 *
 * Three bars:
 * 1. LOAD - cold start, home scaffold, the trending kiosk lands a song card (MainSmokeTest's
 *    bars, repeated here because this test must start from the same real entry point).
 * 2. PLAY - tapping the first card opens the Player and the transport reaches the playing
 *    state (its contentDescription flips Play -> Pause only when State.isPlaying), AND the
 *    framework media session reports PlaybackState state=3 (PLAYING) for our package - the
 *    system's own record of the player, not app UI state.
 * 3. PROGRESS - the Player's playhead text (m:ss, recomposed on the 500 ms position tick)
 *    advances across a 5 s window. A paused or stalled player cannot move it; this is the bar
 *    that separates "buffering forever" from actually playing the song.
 *
 * UiAutomator for the same reason as MainSmokeTest: home's infinite loading spinner never lets
 * a Compose rule's idle sync settle. Network-gated on purpose (YouTube extraction + stream from
 * the guest): a red here means the app cannot load and play in that environment, which is
 * exactly what a smoke matrix exists to say.
 *
 * Failure evidence rides in the assertion messages, not println: `am instrument -w`'s output
 * stream carries only the status dots and the failure blocks (proven 2026-11-13, run
 * 43464811367 - no println line ever reached the job log), so every red carries the visible
 * texts, the media session's PlaybackState line and a filtered logcat tail inside its message.
 * Screenshots still land in the smoke-evidence artifact.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    @Test
    fun tapSongCardLoadsAndPlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        // Same system-side launch as MainSmokeTest (see its kdoc for the API 37 resolution
        // failures that ruled out Context.startActivity).
        shell("am start -W -n tech.capullo.tubecapullo/.MainActivity")

        val homeRendered = device.wait(
            Until.hasObject(By.pkg(PKG).text("CapulloTube")),
            HOME_RENDER_TIMEOUT_MS,
        )
        check(homeRendered) { "home never rendered: CapulloTube top bar missing after 30s" }

        val trendingLanded = device.wait(
            Until.hasObject(By.pkg(PKG).textStartsWith("Trending in")),
            CONTENT_TIMEOUT_MS,
        )
        if (!trendingLanded) {
            shell("screencap -p /sdcard/smoke-playback.png")
        }
        check(trendingLanded) {
            "home rendered but the trending section never landed in 90s" +
                " | texts: [${visibleAppTexts(device).joinToString(" | ")}]"
        }
        val cardsLanded = device.wait(
            Until.hasObject(By.pkg(PKG).desc("music_card")),
            CARD_TIMEOUT_MS,
        )
        if (!cardsLanded) {
            shell("screencap -p /sdcard/smoke-playback.png")
        }
        check(cardsLanded) {
            "trending section landed but no song card appeared in 30s" +
                " | texts: [${visibleAppTexts(device).joinToString(" | ")}]"
        }

        // The card tap is the user's play gesture: HomeScreen wires it to goToRadio, which
        // starts the SongRadio station and opens the Player route.
        device.findObjects(By.pkg(PKG).desc("music_card")).first().click()

        // Stream resolution (extractor + Rhino, cold) plus the first buffer on an emulated CPU.
        val playing = device.wait(Until.hasObject(By.pkg(PKG).desc("Pause")), PLAYING_TIMEOUT_MS)
        if (!playing) {
            shell("screencap -p /sdcard/smoke-playback.png")
        }
        check(playing) {
            "player never reached the playing state in 120s (no Pause transport)" +
                " | texts: [${visibleAppTexts(device).joinToString(" | ")}]" +
                " | session: ${mediaSessionLine(shell("dumpsys media_session")) ?: "none"}" +
                " | logcat: [${diagnosticLogcat()}]"
        }
        shell("screencap -p /sdcard/smoke-playback.png")

        // The framework's record: on a fresh guest our session is the only one, so the first
        // state= after the package line is ours.
        val sessionDump = shell("dumpsys media_session")
        val state = mediaSessionState(sessionDump)
        check(state == STATE_PLAYING) {
            "framework media session is not PLAYING (state=$state) while the UI says playing" +
                " | ${mediaSessionLine(sessionDump) ?: "no PlaybackState line in the dump"}"
        }

        // The playhead must move: two reads of the seek bar's position text across the window.
        val first = playheadSeconds(device)
        check(first != null) { "no playhead text (m:ss) on the player screen" }
        Thread.sleep(PROGRESS_WINDOW_MS)
        val second = playheadSeconds(device)
        println("playhead: $first -> $second")
        check(second != null && second > first) {
            "playhead did not advance across ${PROGRESS_WINDOW_MS}ms: $first -> $second"
        }
        shell("screencap -p /sdcard/smoke-playback.png")

        // Same stability bar as MainSmokeTest: the window must stay up after playing starts.
        val windowVanished = device.wait(Until.gone(By.pkg(PKG)), 2_000)
        check(!windowVanished) { "app window vanished after playback started" }
    }

    /** The seek bar's position text, parsed to seconds; null when no m:ss text is visible.
     *  Anchored on purpose: the remaining-time text is "-m:ss" and card subtitles carry a
     *  "· " prefix, so only the playhead matches. */
    private fun playheadSeconds(device: UiDevice): Long? = device.findObjects(By.pkg(PKG))
        .mapNotNull { it.text }
        .firstOrNull { it.matches(PLAYHEAD_REGEX) }
        ?.split(":")
        ?.let { (minutes, seconds) -> minutes.toLong() * 60 + seconds.toLong() }

    /** PlaybackState.state of the app's media session from `dumpsys media_session`; null when
     *  the package has no session at all. */
    private fun mediaSessionState(dump: String): Int? {
        val fromPackage = dump.substringAfter(PKG, "")
        if (fromPackage.isEmpty()) return null
        return Regex("""state=(\d+)""").find(fromPackage)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The PlaybackState line of a media-session dump, for failure messages. */
    private fun mediaSessionLine(dump: String): String? = dump.lineSequence().firstOrNull { "PlaybackState" in it }

    /** The guest's own record at failure time: app, extractor and player lines, last 60. Raw
     *  logcat is too noisy for an assertion message; the filter names what this player path can
     *  hit - stream HTTP failures, extractor exceptions, dex and desugar errors. */
    private fun diagnosticLogcat(): String = shell("logcat -d")
        .lineSequence()
        .filter { DIAGNOSTIC_PATTERN.containsMatchIn(it) && !it.contains("UiDevice") }
        .toList()
        .takeLast(60)
        .joinToString("\n")

    /** All non-blank texts currently visible in the app's windows, for failure transcripts. */
    private fun visibleAppTexts(device: UiDevice): List<String> = device.findObjects(By.pkg(PKG))
        .mapNotNull { it.text }
        .filter { it.isNotBlank() }

    /**
     * Runs a shell command as the adb CLI would, drained and returned. Output reaches a reader
     * only through the callers' assertion messages or on-device files: println never reaches
     * `am instrument -w`'s stream (see the class kdoc).
     */
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }

    private companion object {
        const val PKG = "tech.capullo.tubecapullo"

        /** android.media.session.PlaybackState.STATE_PLAYING. */
        const val STATE_PLAYING = 3

        /** Cold Hilt graph + Room open on a swiftshader emulator (MainSmokeTest's measured 30s). */
        const val HOME_RENDER_TIMEOUT_MS = 30_000L

        /** Cold-guest extraction: first-time Rhino setup + network (MainSmokeTest's 90s). */
        const val CONTENT_TIMEOUT_MS = 90_000L

        const val CARD_TIMEOUT_MS = 30_000L

        /** A guess, generous on purpose: cold stream resolution (extractor + Rhino) plus the
         *  first ExoPlayer buffer on an emulated CPU. Unplayable seeds are skipped by the
         *  station loop, which this window also covers. Too low turns a slow guest into a
         *  false red; too high only slows a genuinely broken leg's failure. */
        const val PLAYING_TIMEOUT_MS = 120_000L

        /** 10 ticks of the 500 ms position clock - far above the tick jitter, far below the
         *  shortest song's remaining time at the playhead's start. */
        const val PROGRESS_WINDOW_MS = 5_000L

        val PLAYHEAD_REGEX = Regex("^\\d+:\\d{2}$")

        /** Failure-evidence filter for [diagnosticLogcat]: this app's own logs (station loop,
         *  PlaybackService) plus the extractor and ExoPlayer stacks. */
        val DIAGNOSTIC_PATTERN = Regex(
            "(?i)tubecapullo|newpipe|extractor|exoplayer|httpdatasource|nosuchmethod|noclassdef",
        )
    }
}
