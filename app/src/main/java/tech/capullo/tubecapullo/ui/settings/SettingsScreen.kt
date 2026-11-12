package tech.capullo.tubecapullo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.LikeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStationDebug: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val chartRegions by viewModel.chartRegions.collectAsStateWithLifecycle()
    val moreLikeRows by viewModel.moreLikeRows.collectAsStateWithLifecycle()
    val continueCards by viewModel.continueCards.collectAsStateWithLifecycle()
    val fullAlbums by viewModel.fullAlbums.collectAsStateWithLifecycle()
    val likeMode by viewModel.likeMode.collectAsStateWithLifecycle()
    val songsPerLike by viewModel.songsPerLike.collectAsStateWithLifecycle()
    val openYouTubeLinks by viewModel.openYouTubeLinks.collectAsStateWithLifecycle()
    val askForSharedLinks by viewModel.askForSharedLinks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Saveable: an open dialog stays open across rotation (CONTRIBUTING §3).
    var showChartsDialog by rememberSaveable { mutableStateOf(false) }
    var showLikeModeDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Music")
            ListItem(
                leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
                headlineContent = { Text("Trending charts") },
                supportingContent = { Text(viewModel.chartsLabel(chartRegions)) },
                modifier = Modifier.clickable(
                    onClickLabel = "Choose Trending charts",
                    onClick = { showChartsDialog = true },
                ),
            )
            StepperItem(
                icon = Icons.Default.History,
                title = "Continue cards",
                explanation = if (continueCards == 0) {
                    "Off: Home shows no Continue row"
                } else {
                    "Home cards of stations you left, to go on where you stopped"
                },
                value = continueCards,
                max = AppSettings.MAX_CONTINUE_CARDS,
                fewerLabel = "One card fewer",
                moreLabel = "One card more",
                onValueChange = viewModel::setContinueCards,
            )
            StepperItem(
                icon = Icons.Default.FavoriteBorder,
                title = "More like rows",
                explanation = if (moreLikeRows == 0) {
                    "Off: Home shows no More like rows"
                } else {
                    "Home rows of songs like your newest likes"
                },
                value = moreLikeRows,
                max = AppSettings.MAX_MORE_LIKE_ROWS,
                fewerLabel = "One row fewer",
                moreLabel = "One row more",
                onValueChange = viewModel::setMoreLikeRows,
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Album, contentDescription = null) },
                headlineContent = { Text("Full albums") },
                supportingContent = {
                    Text(
                        if (fullAlbums) {
                            "A Home row of full albums near your liked songs"
                        } else {
                            "Off: Home shows no Full albums row"
                        },
                    )
                },
                trailingContent = { Switch(checked = fullAlbums, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = fullAlbums,
                    role = Role.Switch,
                    onValueChange = viewModel::setFullAlbums,
                ),
            )
            ListItem(
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                },
                headlineContent = { Text("When you like a song") },
                supportingContent = { Text(likeMode.label) },
                modifier = Modifier.clickable(
                    onClickLabel = "Choose what a like does",
                    onClick = { showLikeModeDialog = true },
                ),
            )
            StepperItem(
                icon = Icons.Default.Radio,
                title = "Songs added per like",
                explanation = "After a liked song, and between songs in Liked radio",
                value = songsPerLike,
                max = AppSettings.MAX_SONGS_PER_LIKE,
                step = AppSettings.SONGS_PER_LIKE_STEP,
                fewerLabel = "Two songs fewer",
                moreLabel = "Two songs more",
                onValueChange = viewModel::setSongsPerLike,
            )
            HorizontalDivider()
            SectionHeader("Links")
            ListItem(
                leadingContent = { Icon(Icons.Default.Link, contentDescription = null) },
                headlineContent = { Text("Open YouTube links in CapulloTube") },
                supportingContent = {
                    Text(
                        "If YouTube or another app also opens these links, Android lets you " +
                            "choose in App info > Open by default.",
                    )
                },
                trailingContent = { Switch(checked = openYouTubeLinks, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = openYouTubeLinks,
                    role = Role.Switch,
                    onValueChange = viewModel::setOpenYouTubeLinks,
                ),
            )
            // Hidden, not disabled, while the app takes no links: there is nothing to choose.
            if (openYouTubeLinks) {
                ListItem(
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    headlineContent = { Text("Open by default") },
                    supportingContent = { Text("Choose which app opens YouTube links") },
                    modifier = Modifier.clickable(
                        onClickLabel = "Open Android's Open by default settings",
                        onClick = { openByDefaultSettings(context) },
                    ),
                )
            }
            ListItem(
                leadingContent = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                headlineContent = { Text("Ask what to do with shared links") },
                supportingContent = {
                    Text(
                        if (askForSharedLinks) {
                            "Choose Play now, Play next or Like for each link"
                        } else {
                            "A link plays now, or plays next when something is playing"
                        },
                    )
                },
                trailingContent = { Switch(checked = askForSharedLinks, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = askForSharedLinks,
                    role = Role.Switch,
                    onValueChange = viewModel::setAskForSharedLinks,
                ),
            )
            // Hidden, not disabled, in release builds (CONTRIBUTING §3).
            if (viewModel.isDebuggable) {
                HorizontalDivider()
                SectionHeader("Developer")
                ListItem(
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    headlineContent = { Text("Station debug") },
                    supportingContent = { Text("The live station state dump") },
                    modifier = Modifier.clickable(onClick = onOpenStationDebug),
                )
            }
        }
    }

    if (showChartsDialog) {
        ChartsDialog(
            selected = chartRegions,
            deviceDefaultLabel = viewModel.deviceDefaultLabel,
            regions = viewModel.regions,
            onConfirm = { codes ->
                viewModel.setChartRegions(codes)
                showChartsDialog = false
            },
            onDismiss = { showChartsDialog = false },
        )
    }
    if (showLikeModeDialog) {
        ChoiceDialog(
            title = "When you like a song",
            choices = LikeMode.entries,
            selected = likeMode,
            label = { it.label },
            explanation = { it.explanation(songsPerLike) },
            onPick = { mode ->
                viewModel.setLikeMode(mode)
                showLikeModeDialog = false
            },
            onDismiss = { showLikeModeDialog = false },
        )
    }
}

private val LikeMode.label: String
    get() = when (this) {
        LikeMode.BLEND -> "Blend in"
        LikeMode.SAVE_ONLY -> "Save only"
        LikeMode.SWITCH_NOW -> "Switch now"
    }

/** [songsPerLike]: Songs added per like, the count Blend in puts after the liked song. */
private fun LikeMode.explanation(songsPerLike: Int): String = when (this) {
    LikeMode.BLEND -> if (songsPerLike == 0) {
        "Songs added per like is 0, so the like is only saved"
    } else {
        "$songsPerLike songs like it play next, then the queue goes on"
    }
    LikeMode.SAVE_ONLY -> "The like is saved; the queue does not change"
    LikeMode.SWITCH_NOW -> "Songs like it play after this song; a list becomes a radio"
}

/** A count setting with a -/+ stepper that moves by [step]; the buttons stop at 0 and [max]. */
@Composable
private fun StepperItem(
    icon: ImageVector,
    title: String,
    explanation: String,
    value: Int,
    max: Int,
    fewerLabel: String,
    moreLabel: String,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(explanation) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onValueChange(value - step) }, enabled = value > 0) {
                    Icon(Icons.Default.Remove, contentDescription = fewerLabel)
                }
                Text("$value", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onValueChange(value + step) }, enabled = value < max) {
                    Icon(Icons.Default.Add, contentDescription = moreLabel)
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Android's "Open by default" page for this app, where the user picks which supported links it
 * opens and settles a tie with YouTube. The page exists from API 31; before that, and on builds
 * that leave it out, App info is the nearest screen and links to the same choice.
 */
private fun openByDefaultSettings(context: Context) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    val openByDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
    } else {
        appInfo
    }
    try {
        context.startActivity(openByDefault)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(appInfo)
    }
}

/**
 * Multi-select of the Trending charts: device default first, then every supported region by
 * name. Rows appear on Home in pick order, which each checked row shows. The device default is
 * the empty pick: checking it clears the countries, and checking a country leaves it. Picks past
 * [AppSettings.MAX_TRENDING_ROWS] are refused with a message, not dropped silently.
 */
@Composable
private fun ChartsDialog(
    selected: List<String>,
    deviceDefaultLabel: String,
    regions: List<RegionOption>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable: a half-made pick survives rotation (CONTRIBUTING §3).
    var picked by rememberSaveable { mutableStateOf(selected) }
    var limitHit by rememberSaveable { mutableStateOf(false) }
    // Open scrolled to the first chart; index 0 is the device-default row.
    val firstIndex = regions.indexOfFirst { it.code == selected.firstOrNull() } + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trending charts") },
        text = {
            Column {
                Text(
                    "Rows appear in the order you pick them",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (limitHit) {
                    Text(
                        "Up to ${AppSettings.MAX_TRENDING_ROWS} charts. Clear one to pick another.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
                    item(key = "device-default") {
                        ChartRow(deviceDefaultLabel, rank = null, checked = picked.isEmpty()) {
                            picked = emptyList()
                            limitHit = false
                        }
                    }
                    items(regions, key = { it.code }) { region ->
                        val index = picked.indexOf(region.code)
                        ChartRow(region.name, rank = index + 1, checked = index >= 0) {
                            when {
                                index >= 0 -> {
                                    picked = picked - region.code
                                    limitHit = false
                                }
                                picked.size >= AppSettings.MAX_TRENDING_ROWS -> limitHit = true
                                else -> {
                                    picked = picked + region.code
                                    limitHit = false
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A checkbox row; a checked country also shows its [rank], its row's place on Home. */
@Composable
private fun ChartRow(label: String, rank: Int?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
    ) {
        // Null onCheckedChange: the whole row is the toggle, so the box is not a second target.
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (checked && rank != null) {
            Text(
                "$rank",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A single-choice setting as radio buttons: a pick applies at once and closes the dialog; Cancel
 * or a tap outside keeps the current value. [explanation] gives a choice a line under its
 * [label].
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    explanation: (T) -> String? = { null },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { choice ->
                    ChoiceRow(
                        label = label(choice),
                        explanation = explanation(choice),
                        selected = choice == selected,
                        onSelect = { onPick(choice) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A radio-button row, with an optional [explanation] line under the [label]. */
@Composable
private fun ChoiceRow(
    label: String,
    explanation: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        // Null onClick: the whole row is the choice, so the button is not a second target.
        RadioButton(selected = selected, onClick = null)
        Column(
            Modifier
                .padding(start = 8.dp)
                .weight(1f),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            explanation?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
