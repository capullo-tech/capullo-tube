package tech.capullo.tubecapullo

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    @Test
    fun tapSongCardLoadsAndPlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

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

        device.findObjects(By.pkg(PKG).desc("music_card")).first().click()

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

        val sessionDump = shell("dumpsys media_session")
        val state = mediaSessionState(sessionDump)
        check(state == STATE_PLAYING) {
            "framework media session is not PLAYING (state=$state) while the UI says playing" +
                " | ${mediaSessionLine(sessionDump) ?: "no PlaybackState line in the dump"}"
        }

        val first = playheadSeconds(device)
        check(first != null) { "no playhead text (m:ss) on the player screen" }
        Thread.sleep(PROGRESS_WINDOW_MS)
        val second = playheadSeconds(device)
        println("playhead: $first -> $second")
        check(second != null && second > first) {
            "playhead did not advance across ${PROGRESS_WINDOW_MS}ms: $first -> $second"
        }
        shell("screencap -p /sdcard/smoke-playback.png")

        val windowVanished = device.wait(Until.gone(By.pkg(PKG)), 2_000)
        check(!windowVanished) { "app window vanished after playback started" }
    }

    private fun playheadSeconds(device: UiDevice): Long? = device.findObjects(By.pkg(PKG))
        .mapNotNull { it.text }
        .firstOrNull { it.matches(PLAYHEAD_REGEX) }
        ?.split(":")
        ?.let { (minutes, seconds) -> minutes.toLong() * 60 + seconds.toLong() }

    private fun mediaSessionState(dump: String): Int? {
        val fromPackage = dump.substringAfter(PKG, "")
        if (fromPackage.isEmpty()) return null
        return Regex("""state=(\d+)""").find(fromPackage)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun mediaSessionLine(dump: String): String? = dump.lineSequence().firstOrNull { "PlaybackState" in it }

    private fun diagnosticLogcat(): String = shell("logcat -d")
        .lineSequence()
        .filter { DIAGNOSTIC_PATTERN.containsMatchIn(it) && !it.contains("UiDevice") }
        .toList()
        .takeLast(60)
        .joinToString("\n")

    private fun visibleAppTexts(device: UiDevice): List<String> = device.findObjects(By.pkg(PKG))
        .mapNotNull { it.text }
        .filter { it.isNotBlank() }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }

    private companion object {
        const val PKG = "tech.capullo.tubecapullo"

        const val STATE_PLAYING = 3

        const val HOME_RENDER_TIMEOUT_MS = 30_000L

        const val CONTENT_TIMEOUT_MS = 90_000L

        const val CARD_TIMEOUT_MS = 30_000L

        const val PLAYING_TIMEOUT_MS = 120_000L

        const val PROGRESS_WINDOW_MS = 5_000L

        val PLAYHEAD_REGEX = Regex("^\\d+:\\d{2}$")

        val DIAGNOSTIC_PATTERN = Regex(
            "(?i)tubecapullo|newpipe|extractor|exoplayer|httpdatasource|nosuchmethod|noclassdef",
        )
    }
}
