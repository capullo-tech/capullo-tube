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
 * Device-level launch smoke test - the payload of the CI emulator matrix
 * (.github/workflows/EmulatorSmoke.yml, one leg per API level 28-37).
 *
 * UiAutomator rather than a Compose test rule, deliberately: home spends its first seconds under
 * an infinite CircularProgressIndicator while trending loads, and a Compose rule's idle
 * synchronization never settles under an infinite animation - every matrix leg would die in
 * waitForIdle instead of testing anything. UiAutomator polls the window hierarchy directly, so
 * the check is exactly what a per-API-level matrix is for: the launcher surfaces a live process
 * with the home scaffold composed.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {
    @Test
    fun coldStartShowsHomePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        // Cold start from a clean task, through the shell's am - the exact resolution path the
        // adb CLI uses. NOT Context.startActivity from the app's own package: on a fresh API 37
        // guest, in-process resolution fails two different ways (getLaunchIntentForPackage
        // null; explicit-component ActivityNotFoundException) for a package whose process is
        // demonstrably alive and running this very test, while the same launch via am works.
        // MainActivity is the manifest's launcher activity, so this is still the real entry
        // point, just resolved system-side.
        device.pressHome()
        val amOutput = instrumentation.uiAutomation
            .executeShellCommand("am start -W -n tech.capullo.tubecapullo/.MainActivity")
            .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }
        println("am start said: $amOutput")

        // The home page's stable identity is the top bar title IN THE APP'S OWN WINDOW - a
        // bare text match can hit the launcher's icon label during the open transition (seen
        // on API 36, where the text matched before the activity was even Displayed). Present
        // in every home state - spinner, cards, or the network-error retry banner - so the
        // check carries no network dependency. 30s, not 5: cold Hilt graph + Room open on a
        // swiftshader-rendered emulator is slow, and trending never has to arrive for this
        // to pass.
        val homeRendered = device.wait(
            Until.hasObject(By.pkg("tech.capullo.tubecapullo").text("TubeCapullo")),
            30_000,
        )
        check(homeRendered) { "home never rendered: TubeCapullo top bar missing after 30s" }

        // And it must STAY there - a window that vanishes right after first frame (activity
        // finished, task cleared) is not a successful launch. Until.gone instead of a one-shot
        // hasObject: an instantaneous probe can lose the race with first-frame composition on
        // the newest images (API 36 again); polling for disappearance cannot false-positive.
        // (A process crash needs no assertion here - it takes the instrumentation down with it
        // and fails the run the loud way.)
        val windowVanished = device.wait(Until.gone(By.pkg("tech.capullo.tubecapullo")), 2_000)
        check(!windowVanished) { "app window vanished after first frame - launch was not stable" }
    }
}
