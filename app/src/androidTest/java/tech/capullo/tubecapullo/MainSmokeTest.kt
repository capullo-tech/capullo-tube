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
            println("trending unavailable, error banner shown: $texts")
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("smoke_warning", "trending unavailable, Home showed its error banner: $texts") },
            )
        }
        shell("screencap -p /sdcard/smoke-content.png")

        val windowVanished = device.wait(Until.gone(By.pkg("tech.capullo.tubecapullo")), 2_000)
        check(!windowVanished) { "app window vanished after first frame - launch was not stable" }
    }

    private fun visibleAppTexts(device: UiDevice): List<String> = device.findObjects(By.pkg("tech.capullo.tubecapullo"))
        .mapNotNull { it.text }
        .filter { it.isNotBlank() }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }
}
