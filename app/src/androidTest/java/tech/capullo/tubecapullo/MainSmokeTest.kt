package tech.capullo.tubecapullo

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class MainSmokeTest {
    @Test
    fun coldStartLoadsHomePageWithContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        shell("am start -W -n tech.capullo.tubecapullo/.MainActivity").also(::println)

        val homeRendered = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").text("CapulloTube")),
            30_000,
        )
        check(homeRendered) { "home never rendered: CapulloTube top bar missing after 30s" }
        shell("screencap -p /sdcard/smoke-home.png")

        val trendingOrError = device.wait(
            Until.hasObject(
                By.pkg("tech.capullo.tubecapullo").text(Pattern.compile("^(Trending in.*|Retry)$")),
            ),
            150_000,
        )
        if (!trendingOrError) {
            println("visible app texts: " + visibleAppTexts(device).joinToString(" | "))
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(trendingOrError) { "home rendered but neither the trending section nor the error banner appeared in 150s" }
        if (device.hasObject(By.pkg("tech.capullo.tubecapullo").textStartsWith("Trending in"))) {
            val cardsLanded = device.wait(
                Until.hasObject(By.pkg("tech.capullo.tubecapullo").desc("music_card")),
                30_000,
            )
            if (!cardsLanded) {
                println("visible app texts: " + visibleAppTexts(device).joinToString(" | "))
                shell("screencap -p /sdcard/smoke-content.png")
            }
            check(cardsLanded) { "trending section landed but no song card appeared in 30s" }
        } else {
            val texts = visibleAppTexts(device).joinToString(" | ")
            val log = trendingLog()
            val excuse = trendingExcuse(log)
            println("trending unavailable, error banner shown: $texts | $excuse")
            check(log.lineSequence().none { SERVED_EMPTY.containsMatchIn(it) }) {
                "Home showed its trending error banner over a served chart: music.youtube.com answered 2xx and" +
                    " the chart parsed to no songs. That is an app failure, not a blocked runner." +
                    " | texts: [$texts] | TubeHome: [$log]"
            }
            check(excuse != null) {
                "Home showed its trending error banner, but a TubeHome line is neither a refusal by YouTube" +
                    " (http=4xx/5xx, rate limit, bot check, error page) nor a runner that never reached it" +
                    " (http=0 with an IOException): that is an app failure. | texts: [$texts] | TubeHome: [$log]"
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("smoke_warning", "trending unavailable ($excuse), Home showed its error banner: $texts")
                },
            )
        }
        shell("screencap -p /sdcard/smoke-content.png")

        val windowVanished = device.wait(Until.gone(By.pkg("tech.capullo.tubecapullo")), 2_000)
        check(!windowVanished) { "app window vanished after first frame - launch was not stable" }
    }

    /**
     * Why no chart loaded, when every TubeHome trending line has a reason outside the app: YouTube refused
     * the runner, or the runner never reached YouTube. Null when any line lacks one, so a chart that broke
     * in the app fails the leg even beside a refused region.
     */
    private fun trendingExcuse(log: String): String? {
        val lines = log.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.map { excuse(it) ?: return null }.distinct().joinToString("; ")
    }

    private fun excuse(line: String): String? = REFUSED_STATUS.find(line)?.let { "YouTube refused http=${it.groupValues[1]}" }
        ?: UNREACHED.find(line)?.let { "runner never reached YouTube: ${it.groupValues[1]}" }
        ?: REFUSED_ERROR.find(line)?.let { "YouTube refused: ${it.groupValues[1].trim()}" }

    private fun trendingLog(): String = shell("logcat -d -s TubeHome:W")
        .lineSequence()
        .filter { "trending region=" in it }
        .toList()
        .takeLast(LOG_LINES)
        .joinToString("\n")

    private fun visibleAppTexts(device: UiDevice): List<String> = device.findObjects(By.pkg("tech.capullo.tubecapullo"))
        .mapNotNull { it.text }
        .filter { it.isNotBlank() }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }

    private companion object {
        const val LOG_LINES = 10

        val SERVED_EMPTY = Regex("""result=empty http=2\d\d\b""")

        val REFUSED_STATUS = Regex("""result=(?:empty|failed) http=([45]\d\d)\b""")

        val UNREACHED = Regex("""result=failed http=0 io=(\w+)""")

        // Refusals whose status doesn't show it: a 2xx captcha/bot-check, or a chart call sent to
        // www.youtube.com/oops, which answers 200 and surfaces as NewPipe's "Content unavailable".
        val REFUSED_ERROR = Regex(
            """result=failed http=\d+ error=(.*(?i:recaptcha|bot.?check|rate.?limit|blocked|content unavailable).*)$""",
        )
    }
}
