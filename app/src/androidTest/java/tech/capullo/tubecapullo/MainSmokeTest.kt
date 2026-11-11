package tech.capullo.tubecapullo

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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
 * the check is exactly what a per-API-level matrix is for: the launcher intent surfaces a live
 * process with the home scaffold composed.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {
    @Test
    fun coldStartShowsHomePage() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val app = ApplicationProvider.getApplicationContext<Context>()

        // Cold start through the real launcher intent - the same path a tap on the launcher
        // icon takes - from a cleared task: re-resuming an already-open activity proves nothing.
        val launch =
            app.packageManager.getLaunchIntentForPackage("tech.capullo.tubecapullo")
                ?: error("no launcher intent - wrong applicationId?")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        device.pressHome()
        app.startActivity(launch)

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
