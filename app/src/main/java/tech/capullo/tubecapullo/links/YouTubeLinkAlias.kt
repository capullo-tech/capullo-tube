package tech.capullo.tubecapullo.links

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The manifest's `<activity-alias>` that receives tapped YouTube links (ACTION_VIEW). Settings >
 * "Open YouTube links in CapulloTube" enables and disables it. The alias is a separate component
 * from MainActivity, so the switch never touches the share target.
 *
 * The component state is the setting: PackageManager persists it across restarts and updates,
 * so there is no second copy in the prefs file to drift from it.
 */
@Singleton
class YouTubeLinkAlias @Inject constructor(@ApplicationContext context: Context) {

    private val packageManager = context.packageManager
    private val component = ComponentName(context.packageName, CLASS_NAME)

    private val _enabled = MutableStateFlow(readEnabled())

    /** Whether Android may route tapped YouTube links to the app. Default on (manifest). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // DONT_KILL_APP: the switch sits in Settings, inside the running app.
        packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        _enabled.value = enabled
        Log.i(TAG, "alias enabled=$enabled")
    }

    // DEFAULT means "as the manifest says", and the manifest leaves the alias enabled.
    private fun readEnabled(): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> true
    }

    private companion object {
        const val TAG = "TubeLinks"

        /** The alias's full name. Mirrors `android:name=".links.OpenYouTubeLink"` in
         *  AndroidManifest.xml (resolved against the namespace, not the applicationId); a rename
         *  there must change this too. */
        const val CLASS_NAME = "tech.capullo.tubecapullo.links.OpenYouTubeLink"
    }
}
