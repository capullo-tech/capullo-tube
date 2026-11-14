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

@Singleton
class YouTubeLinkAlias @Inject constructor(@ApplicationContext context: Context) {
    private val packageManager = context.packageManager
    private val component = ComponentName(context.packageName, CLASS_NAME)

    private val _enabled = MutableStateFlow(readEnabled())

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        _enabled.value = enabled
        Log.i(TAG, "alias enabled=$enabled")
    }

    private fun readEnabled(): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> true
    }

    private companion object {
        const val TAG = "TubeLinks"

        const val CLASS_NAME = "tech.capullo.tubecapullo.links.OpenYouTubeLink"
    }
}
