package tech.capullo.tubecapullo.ui.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TubeNavigatorTest {
    private fun navigator() = TubeNavigator(
        stacks = Tab.entries.associateWith { mutableListOf<NavKey>(it.root) },
        currentTab = mutableStateOf(Tab.HOME),
    )

    @Test
    fun `back from a non-Home tab root goes to Home, then leaves the app`() {
        val nav = navigator()
        nav.selectTab(Tab.LIBRARY)
        assertEquals(listOf(Tab.HOME, Tab.LIBRARY), nav.visibleTabs)

        assertTrue(nav.back())
        assertEquals(Route.Home, nav.top)
        assertEquals(listOf(Tab.HOME), nav.visibleTabs)

        assertFalse("Home root must let the system exit", nav.back())
    }

    @Test
    fun `a second open of the Player does not stack a second Player`() {
        val nav = navigator()
        nav.selectTab(Tab.SEARCH)
        nav.open(Route.Player)
        nav.open(Route.Player)
        assertEquals(listOf(Route.Search, Route.Player), nav.stack(Tab.SEARCH))

        nav.back()
        assertEquals(Route.Search, nav.top)
    }

    @Test
    fun `an open route below the top is brought back by popping what is above it`() {
        val nav = navigator()
        nav.open(Route.Player)
        nav.open(Route.Settings)
        nav.open(Route.Player)
        assertEquals(listOf(Route.Home, Route.Player), nav.stack(Tab.HOME))
    }

    @Test
    fun `Player search button leaves no Player behind on the tab it came from`() {
        val nav = navigator()
        nav.open(Route.Player)
        nav.open(Route.Search)
        assertEquals(Tab.SEARCH, nav.currentTab)
        assertEquals(listOf(Route.Home), nav.stack(Tab.HOME))

        nav.back()
        assertEquals("back from Search must land on Home's root", Route.Home, nav.top)
    }

    @Test
    fun `full-screen routes pop back in order on the tab that opened them`() {
        val nav = navigator()
        nav.open(Route.Settings)
        nav.open(Route.StationDebug)
        assertEquals(Route.StationDebug, nav.top)

        assertTrue(nav.back())
        assertEquals(Route.Settings, nav.top)
        assertTrue(nav.back())
        assertEquals(Route.Home, nav.top)
    }
}
