package tech.capullo.tubecapullo.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    sealed interface TabRoot : Route

    @Serializable
    data object Home : TabRoot

    @Serializable
    data object Search : TabRoot

    @Serializable
    data object Library : TabRoot

    @Serializable
    data object Player : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object StationDebug : Route
}

enum class Tab(val root: Route.TabRoot) {
    HOME(Route.Home),
    SEARCH(Route.Search),
    LIBRARY(Route.Library),
}
