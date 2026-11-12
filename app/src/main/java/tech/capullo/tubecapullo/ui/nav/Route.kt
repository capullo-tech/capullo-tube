package tech.capullo.tubecapullo.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination in the app. Each key is @Serializable because [TubeNavigator]'s back stacks
 * are saved through rememberNavBackStack, which needs a serializer per key to survive process
 * death (RadioCapullo's convention).
 */
sealed interface Route : NavKey {
    /** The three bottom-navigation roots. Each one owns its own back stack. */
    sealed interface TabRoot : Route

    @Serializable
    data object Home : TabRoot

    @Serializable
    data object Search : TabRoot

    @Serializable
    data object Library : TabRoot

    /** Full-screen destinations: pushed on the current tab's stack, drawn without the shell. */
    @Serializable
    data object Player : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object StationDebug : Route
}

/** Bottom-navigation tabs in display order. */
enum class Tab(val root: Route.TabRoot) {
    HOME(Route.Home),
    SEARCH(Route.Search),
    LIBRARY(Route.Library),
}
