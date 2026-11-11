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

        // The home page's stable identity is the top bar title: present in every home state -
        // spinner, cards, or the network-error retry banner - so the check carries no network
        // dependency (an offline runner must not fail a launch test). 30s, not 5: cold Hilt
        // graph + Room open on a swiftshader-rendered emulator is slow, and trending never has
        // to arrive for this to pass.
        val homeRendered = device.wait(Until.hasObject(By.text("TubeCapullo")), 30_000)
        check(homeRendered) { "home never rendered: TubeCapullo top bar missing after 30s" }

        // The window must still be there a beat later - a process that died just after first
        // frame (Hilt, Room open, snapcast probe) would otherwise pass as a green smoke run.
        // (If it dies before this line instead, the instrumentation itself crashes - "Process
        // crashed" - which fails the run the loud way.)
        device.waitForIdle(2_000)
        check(device.hasObject(By.pkg("tech.capullo.tubecapullo"))) {
            "app window vanished after first frame - process crashed?"
        }
    }
}
