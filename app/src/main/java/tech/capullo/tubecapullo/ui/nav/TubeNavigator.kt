package tech.capullo.tubecapullo.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

@Stable
class TubeNavigator(
    private val stacks: Map<Tab, MutableList<NavKey>>,
    currentTab: MutableState<Tab>,
) {
    var currentTab by currentTab
        private set

    val visibleTabs: List<Tab>
        get() = if (currentTab == Tab.HOME) listOf(Tab.HOME) else listOf(Tab.HOME, currentTab)

    val top: NavKey get() = stack(currentTab).last()

    fun stack(tab: Tab): List<NavKey> = stacks.getValue(tab)

    fun selectTab(tab: Tab) {
        val stack = stacks.getValue(currentTab)
        while (stack.size > 1) stack.removeLastOrNull()
        currentTab = tab
    }

    fun open(route: Route) {
        if (route is Route.TabRoot) {
            selectTab(Tab.entries.first { it.root == route })
            return
        }
        val stack = stacks.getValue(currentTab)
        val existing = stack.indexOf(route)
        if (existing >= 0) {
            while (stack.size > existing + 1) stack.removeLastOrNull()
        } else {
            stack.add(route)
        }
    }

    fun back(): Boolean {
        val stack = stacks.getValue(currentTab)
        return when {
            stack.size > 1 -> {
                stack.removeLastOrNull()
                true
            }
            currentTab != Tab.HOME -> {
                currentTab = Tab.HOME
                true
            }
            else -> false
        }
    }
}

@Composable
fun rememberTubeNavigator(): TubeNavigator {
    val home = rememberNavBackStack(Route.Home)
    val search = rememberNavBackStack(Route.Search)
    val library = rememberNavBackStack(Route.Library)
    val currentTab = rememberSaveable { mutableStateOf(Tab.HOME) }
    return remember(home, search, library, currentTab) {
        TubeNavigator(
            stacks = mapOf(Tab.HOME to home, Tab.SEARCH to search, Tab.LIBRARY to library),
            currentTab = currentTab,
        )
    }
}
