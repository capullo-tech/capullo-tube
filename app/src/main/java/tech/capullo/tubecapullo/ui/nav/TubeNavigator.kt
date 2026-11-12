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

/**
 * The app's back stacks: one per [Tab] (the Navigation3 "multiple back stacks" pattern), so each
 * tab keeps its entries, and with them its saved UI state and ViewModels, while another tab is
 * shown. Full-screen routes (Player, Settings, StationDebug) are pushed on the CURRENT tab's stack.
 *
 * What is drawn ([visibleTabs]) is Home's stack, plus the current tab's stack when that tab is not
 * Home: back from a non-Home tab root therefore lands on Home, and back from Home's root has
 * nothing below it, so NavDisplay lets the system finish the activity.
 *
 * Invariant: only the current tab's stack holds more than its root. [selectTab] trims the stack it
 * leaves, so a full-screen route never sits hidden in a background tab (and never appears twice in
 * the drawn entries, which NavDisplay would reject as duplicate keys).
 */
@Stable
class TubeNavigator(
    private val stacks: Map<Tab, MutableList<NavKey>>,
    currentTab: MutableState<Tab>,
) {
    var currentTab by currentTab
        private set

    /** Tabs whose stacks are drawn, bottom first. */
    val visibleTabs: List<Tab>
        get() = if (currentTab == Tab.HOME) listOf(Tab.HOME) else listOf(Tab.HOME, currentTab)

    /** The route on screen. */
    val top: NavKey get() = stack(currentTab).last()

    fun stack(tab: Tab): List<NavKey> = stacks.getValue(tab)

    /** Shows [tab], dropping any full-screen route on the tab being left. */
    fun selectTab(tab: Tab) {
        val stack = stacks.getValue(currentTab)
        while (stack.size > 1) stack.removeLastOrNull()
        currentTab = tab
    }

    /**
     * Opens [route]: a tab root selects that tab; a full-screen route already on the current
     * stack is brought back to the top (a second notification tap must not stack a second
     * Player); anything else is pushed.
     */
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

    /** Pops one level. Returns false at Home's root, where back must leave the app. */
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

/** A [TubeNavigator] whose stacks and current tab survive rotation and process death. */
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
