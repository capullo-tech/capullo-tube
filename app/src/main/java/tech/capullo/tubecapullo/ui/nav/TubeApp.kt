package tech.capullo.tubecapullo.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import tech.capullo.tubecapullo.links.LinksViewModel
import tech.capullo.tubecapullo.ui.debug.StationDebugScreen
import tech.capullo.tubecapullo.ui.home.HomeScreen
import tech.capullo.tubecapullo.ui.library.LibraryScreen
import tech.capullo.tubecapullo.ui.player.PlayerScreen
import tech.capullo.tubecapullo.ui.search.SearchScreen
import tech.capullo.tubecapullo.ui.settings.SettingsScreen
import tech.capullo.tubecapullo.ui.shared.MultiroomSheet
import tech.capullo.tubecapullo.ui.theme.Dimens

/**
 * Blur radius on the app behind the Multiroom sheet, matching telecloud and the web player's
 * backdrop blur (value carried over from the Player's own sheet host; chosen by eye there).
 */
private val MULTIROOM_BACKDROP_BLUR = 12.dp

/**
 * The app root: tab back stacks ([TubeNavigator]) in a NavDisplay, and around the tab roots the
 * shell (now-playing mini-bar, bottom navigation). Full-screen routes draw without
 * the shell. [navRequests] carries routes from outside the composition: notification taps and
 * `--es dbg` commands, forwarded by MainActivity. [links] is the incoming-link handler that
 * MainActivity feeds shares and tapped links to; its progress, dialog and messages show here, over
 * whichever screen is current.
 */
@Composable
fun TubeApp(
    navRequests: Flow<Route>,
    links: LinksViewModel,
    shell: ShellViewModel = hiltViewModel(),
) {
    val navigator = rememberTubeNavigator()
    LaunchedEffect(navigator, navRequests) { navRequests.collect { navigator.open(it) } }

    val snackbarHostState = remember { SnackbarHostState() }
    val resolvingLink by links.resolving.collectAsStateWithLifecycle()
    LinkEffects(links, navigator, snackbarHostState)

    // The Multiroom sheet is a ModalBottomSheet with its own window, so it is hosted here over
    // whichever screen opened it rather than as a back-stack entry; it handles back itself.
    var multiroomOpen by rememberSaveable { mutableStateOf(false) }
    val openMultiroom = { multiroomOpen = true }
    val backdropBlur by animateDpAsState(
        if (multiroomOpen) MULTIROOM_BACKDROP_BLUR else 0.dp,
        label = "multiroomBackdropBlur",
    )
    if (multiroomOpen) MultiroomSheet(onDismiss = { multiroomOpen = false })

    val openPlayer = { navigator.open(Route.Player) }
    val provider = entryProvider<NavKey> {
        entry<Route.Home> {
            HomeScreen(
                onOpenPlayer = openPlayer,
                onOpenSettings = { navigator.open(Route.Settings) },
            )
        }
        entry<Route.Search> { SearchScreen(onOpenPlayer = openPlayer) }
        entry<Route.Library> { LibraryScreen(onOpenPlayer = openPlayer) }
        entry<Route.Player> {
            PlayerScreen(
                onBack = { navigator.back() },
                onOpenMultiroom = openMultiroom,
            )
        }
        entry<Route.Settings> {
            SettingsScreen(
                onBack = { navigator.back() },
                onOpenStationDebug = { navigator.open(Route.StationDebug) },
            )
        }
        entry<Route.StationDebug> { StationDebugScreen(onBack = { navigator.back() }) }
    }
    val entries = rememberTabEntries(navigator, provider)

    Scaffold(
        modifier = Modifier.blur(backdropBlur),
        // Insets belong to the screens' own Scaffolds; this one only reserves the shell's height.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (navigator.top is Route.TabRoot) {
                ShellBottomBar(
                    shell = shell,
                    currentTab = navigator.currentTab,
                    onSelectTab = navigator::selectTab,
                    onOpenPlayer = openPlayer,
                )
            }
        },
        snackbarHost = {
            // Full-screen routes have no shell bar to sit on, and this Scaffold reserves no
            // insets: lift the messages above the system navigation bar there.
            val lift = if (navigator.top is Route.TabRoot) {
                Modifier
            } else {
                Modifier.navigationBarsPadding()
            }
            Column(lift) {
                if (resolvingLink) LinkProgress()
                SnackbarHost(snackbarHostState)
            }
        },
    ) { padding ->
        NavDisplay(
            entries = entries,
            onBack = { navigator.back() },
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        )
    }
}

/**
 * Decorates each tab's stack separately, so a tab's saved state and ViewModels are cleared only
 * when its own entries are popped, never when another tab is selected; then concatenates the
 * stacks that are drawn ([TubeNavigator.visibleTabs]).
 */
@Composable
private fun rememberTabEntries(
    navigator: TubeNavigator,
    provider: (NavKey) -> NavEntry<NavKey>,
): List<NavEntry<NavKey>> {
    val perTab = Tab.entries.associateWith { tab ->
        // key(): distinct composite keys, so each tab's rememberSaveable state is saved apart.
        key(tab) {
            val saveableState = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
            val viewModels = rememberViewModelStoreNavEntryDecorator<NavKey>()
            val decorators = remember(saveableState, viewModels) {
                listOf<NavEntryDecorator<NavKey>>(saveableState, viewModels)
            }
            rememberDecoratedNavEntries(navigator.stack(tab), decorators, provider)
        }
    }
    return navigator.visibleTabs.flatMap { perTab.getValue(it) }
}

@Composable
private fun ShellBottomBar(
    shell: ShellViewModel,
    currentTab: Tab,
    onSelectTab: (Tab) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val playerState by shell.playerState.collectAsStateWithLifecycle()
    val likedIds by shell.likedIds.collectAsStateWithLifecycle()
    val likedAlbumIds by shell.likedAlbumIds.collectAsStateWithLifecycle()
    val station by shell.station.collectAsStateWithLifecycle()
    Column {
        playerState.video?.let { video ->
            NowPlayingBar(
                video = video,
                state = playerState,
                isLiked = video.videoId in (if (station.albumMode) likedAlbumIds else likedIds),
                likeEffects = shell.likeEffects,
                rollOvers = shell.rollOvers,
                onToggleLike = shell::toggleLike,
                onTogglePlayPause = shell::togglePlayPause,
                onNext = shell::next,
                onOpen = onOpenPlayer,
            )
        }
        // NavigationBar has no height parameter: its 80 dp floor gives way to a fixed height,
        // which must also hold the system inset the bar pads itself by.
        val insets = NavigationBarDefaults.windowInsets
        val inset = insets.asPaddingValues().calculateBottomPadding()
        NavigationBar(
            windowInsets = insets,
            modifier = Modifier.height(Dimens.NAV_BAR_HEIGHT + inset),
        ) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == currentTab,
                    onClick = { onSelectTab(tab) },
                    // The label names the tab; the icon would only repeat it to a screen reader.
                    icon = {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.NAV_BAR_ICON_SIZE),
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

private val Tab.label: String
    get() = when (this) {
        Tab.HOME -> "Home"
        Tab.SEARCH -> "Search"
        Tab.LIBRARY -> "Library"
    }

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.HOME -> Icons.Default.Home
        Tab.SEARCH -> Icons.Default.Search
        Tab.LIBRARY -> Icons.Default.LibraryMusic
    }
