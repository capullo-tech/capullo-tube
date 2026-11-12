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
 * Device-level smoke test - the payload of the CI emulator matrix
 * (.github/workflows/EmulatorSmoke.yml, one leg per API level 28-37).
 *
 * Two bars, deliberately:
 * 1. LAUNCH - the app cold-starts and the home scaffold composes (top bar, stable window).
 * 2. CONTENT - the trending kiosk actually lands: the section header plus at least one song
 *    card. This is network-gated (YouTube extraction from the guest) - a red here means the
 *    app cannot load its main content in that environment, which is exactly what a smoke
 *    matrix exists to say.
 *
 * UiAutomator rather than a Compose test rule, deliberately: home spends its first seconds
 * under an infinite CircularProgressIndicator while trending loads, and a Compose rule's idle
 * synchronization never settles under an infinite animation - every matrix leg would die in
 * waitForIdle instead of testing anything. UiAutomator polls the window hierarchy directly.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {
    @Test
    fun coldStartLoadsHomePageWithContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        // Cold start through the shell's am - the exact resolution path the adb CLI uses. NOT
        // Context.startActivity from the app's own package: on a fresh API 37 guest, in-process
        // resolution fails two different ways (getLaunchIntentForPackage null; explicit
        // component ActivityNotFoundException) for a package whose process is demonstrably
        // alive and running this very test, while the same launch via am works. MainActivity
        // is the manifest's launcher activity, so this is still the real entry point, just
        // resolved system-side. No pressHome first: on a fresh boot the launcher is already
        // foreground, the app has never run (nothing to clear), and re-foregrounding the
        // launcher is what re-triggered its early-boot crash loop on the 37.0 google_apis
        // image.
        shell("am start -W -n tech.capullo.tubecapullo/.MainActivity").also(::println)

        // Bar 1, launch: the home page's stable identity is the top bar title IN THE APP'S OWN
        // WINDOW - a bare text match can hit the launcher's icon label during the open
        // transition (seen on API 36, where the text matched before the activity was even
        // Displayed). Present in every home state - spinner, cards, or the retry banner.
        // 30s, not 5: cold Hilt graph + Room open on a swiftshader-rendered emulator is slow.
        val homeRendered = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").text("TubeCapullo")),
            30_000,
        )
        check(homeRendered) { "home never rendered: TubeCapullo top bar missing after 30s" }
        shell("screencap -p /sdcard/smoke-home.png")

        // Bar 2, content: the trending section header, then at least one card. The header
        // precedes the card row in the same LazyColumn, so by the time a card is composed the
        // section has landed for real. 90s: extraction from a cold guest means first-time
        // Rhino setup + network on an emulated CPU; the fetch itself is ~1s warm.
        val trendingLanded = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").text("Trending music")),
            90_000,
        )
        if (!trendingLanded) {
            // Preserve what the page DID show (error banner, empty content) before failing -
            // "content never loaded" without a view dump is a debugging dead end.
            shell("uiautomator dump /sdcard/smoke-content-dump.xml")
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(trendingLanded) { "home rendered but the trending section never landed in 90s" }
        val cardsLanded = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").desc("music_card")),
            30_000,
        )
        if (!cardsLanded) {
            // The header can land while the fetch failed (error banner + empty row) - the
            // dump says WHICH error the user would have seen.
            shell("uiautomator dump /sdcard/smoke-content-dump.xml")
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(cardsLanded) { "trending section landed but no song card appeared in 30s" }
        shell("screencap -p /sdcard/smoke-content.png")

        // And the window must STAY there - a window that vanishes right after first frame
        // (activity finished, task cleared) is not a successful launch. Until.gone instead of
        // a one-shot hasObject: an instantaneous probe can lose the race with first-frame
        // composition (API 36); polling for disappearance cannot false-positive. (A process
        // crash needs no assertion here - it takes the instrumentation down with it and fails
        // the run the loud way.)
        val windowVanished = device.wait(Until.gone(By.pkg("tech.capullo.tubecapullo")), 2_000)
        check(!windowVanished) { "app window vanished after first frame - launch was not stable" }
    }

    /**
     * Runs a shell command as the adb CLI would, drained and returned (its output lands in
     * the transcript via the callers' println).
     */
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }
}
