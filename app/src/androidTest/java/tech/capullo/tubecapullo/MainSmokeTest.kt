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

        val trendingLanded = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").textStartsWith("Trending in")),
            90_000,
        )
        if (!trendingLanded) {
            println("visible app texts: " + visibleAppTexts(device).joinToString(" | "))
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(trendingLanded) { "home rendered but the trending section never landed in 90s" }
        val cardsLanded = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").desc("music_card")),
            30_000,
        )
        if (!cardsLanded) {
            println("visible app texts: " + visibleAppTexts(device).joinToString(" | "))
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(cardsLanded) { "trending section landed but no song card appeared in 30s" }
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
