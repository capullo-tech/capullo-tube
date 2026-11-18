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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import tech.capullo.tubecapullo.data.settings.HistoryRetention
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.similar.LastfmGate
import tech.capullo.tubecapullo.data.similar.LastfmStatus
import tech.capullo.tubecapullo.data.similar.SimilarDepth
import tech.capullo.tubecapullo.data.similar.isLastfmKeyShape

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
    val lyricsCard by viewModel.lyricsCard.collectAsStateWithLifecycle()
    val lyricsOnline by viewModel.lyricsOnline.collectAsStateWithLifecycle()
    val lyricsDelayMs by viewModel.lyricsDelayMs.collectAsStateWithLifecycle()
    val lyricsAutoDelayMs by viewModel.lyricsAutoDelayMs.collectAsStateWithLifecycle()
    val likeMode by viewModel.likeMode.collectAsStateWithLifecycle()
    val songsPerLike by viewModel.songsPerLike.collectAsStateWithLifecycle()
    val openYouTubeLinks by viewModel.openYouTubeLinks.collectAsStateWithLifecycle()
    val askForSharedLinks by viewModel.askForSharedLinks.collectAsStateWithLifecycle()
    val historyRetention by viewModel.historyRetention.collectAsStateWithLifecycle()
    val similarOnline by viewModel.similarOnline.collectAsStateWithLifecycle()
    val similarDepth by viewModel.similarDepth.collectAsStateWithLifecycle()
    val lastfmApiKey by viewModel.lastfmApiKey.collectAsStateWithLifecycle()
    val lastfmStatus by viewModel.lastfmStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showChartsDialog by rememberSaveable { mutableStateOf(false) }
    var showLikeModeDialog by rememberSaveable { mutableStateOf(false) }
    var showRetentionDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showDepthDialog by rememberSaveable { mutableStateOf(false) }
    var showLastfmKeyDialog by rememberSaveable { mutableStateOf(false) }

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
                leadingContent = { Icon(Icons.Default.Lyrics, contentDescription = null) },
                headlineContent = { Text("Lyrics card") },
                supportingContent = {
                    Text(
                        if (lyricsCard) {
                            "The Player shows the current lyric line; tap it for all lyrics"
                        } else {
                            "Off: a slim Lyrics tab; tap it for the lyrics"
                        },
                    )
                },
                trailingContent = { Switch(checked = lyricsCard, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = lyricsCard,
                    role = Role.Switch,
                    onValueChange = viewModel::setLyricsCard,
                ),
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                headlineContent = { Text("Look up lyrics online") },
                supportingContent = {
                    Text(
                        if (lyricsOnline) {
                            "Each song's artist, title and length go to LRCLIB (lrclib.net)"
                        } else {
                            "Off: lyrics only from video descriptions and earlier lookups"
                        },
                    )
                },
                trailingContent = { Switch(checked = lyricsOnline, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = lyricsOnline,
                    role = Role.Switch,
                    onValueChange = viewModel::setLyricsOnline,
                ),
            )
            StepperItem(
                icon = Icons.Default.Timer,
                title = "Lyrics timing",
                explanation = "Added to the speakers' $lyricsAutoDelayMs ms delay. More shows lyrics later",
                value = lyricsDelayMs,
                min = -AppSettings.MAX_LYRICS_DELAY_MS,
                max = AppSettings.MAX_LYRICS_DELAY_MS,
                step = AppSettings.LYRICS_DELAY_STEP_MS,
                valueLabel = delayLabel(lyricsDelayMs),
                fewerLabel = "Lyrics 0.1 s earlier",
                moreLabel = "Lyrics 0.1 s later",
                onValueChange = viewModel::setLyricsDelayMs,
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
            SectionHeader("Similar songs")
            ListItem(
                leadingContent = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                headlineContent = { Text("Find similar songs online") },
                supportingContent = {
                    Text(
                        if (similarOnline) {
                            "Radios add songs Last.fm finds similar. Each radio song's artist and title go to Last.fm (last.fm)"
                        } else {
                            "Off: radios play YouTube's Mix and related videos only"
                        },
                    )
                },
                trailingContent = { Switch(checked = similarOnline, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = similarOnline,
                    role = Role.Switch,
                    onValueChange = viewModel::setSimilarOnline,
                ),
            )
            if (similarOnline) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Explore, contentDescription = null) },
                    headlineContent = { Text("Depth") },
                    supportingContent = { Text("${similarDepth.label}: ${similarDepth.explanation}") },
                    modifier = Modifier.clickable(
                        onClickLabel = "Choose how deep similar songs go",
                        onClick = { showDepthDialog = true },
                    ),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                    headlineContent = { Text("Last.fm API key") },
                    supportingContent = {
                        val problem = lastfmProblem(lastfmStatus, viewModel.activeLastfmKey(lastfmApiKey), System.currentTimeMillis())
                        Text(
                            lastfmKeyLabel(lastfmApiKey, viewModel.hasBuiltInLastfmKey) + ". Similar songs by Last.fm" +
                                (problem?.let { "\n$it" } ?: ""),
                        )
                    },
                    modifier = Modifier.clickable(
                        onClickLabel = "Set your own Last.fm API key",
                        onClick = { showLastfmKeyDialog = true },
                    ),
                )
            }
            HorizontalDivider()
            SectionHeader("History")
            ListItem(
                leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                headlineContent = { Text("Keep history for") },
                supportingContent = { Text("${historyRetention.label}. Plays of liked songs and albums always stay") },
                modifier = Modifier.clickable(
                    onClickLabel = "Choose how long to keep play history",
                    onClick = { showRetentionDialog = true },
                ),
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                headlineContent = { Text("Clear play history") },
                supportingContent = { Text("Library History, play counts, Continue cards and saved lyrics. Likes stay") },
                modifier = Modifier.clickable(
                    onClickLabel = "Clear play history",
                    onClick = { showClearHistoryDialog = true },
                ),
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
    if (showDepthDialog) {
        ChoiceDialog(
            title = "Depth",
            choices = SimilarDepth.entries,
            selected = similarDepth,
            label = { it.label },
            explanation = { it.explanation },
            onPick = { depth ->
                viewModel.setSimilarDepth(depth)
                showDepthDialog = false
            },
            onDismiss = { showDepthDialog = false },
        )
    }
    if (showLastfmKeyDialog) {
        LastfmKeyDialog(
            current = lastfmApiKey,
            hasBuiltIn = viewModel.hasBuiltInLastfmKey,
            onSave = { key ->
                viewModel.setLastfmApiKey(key)
                showLastfmKeyDialog = false
            },
            onDismiss = { showLastfmKeyDialog = false },
        )
    }
    if (showRetentionDialog) {
        ChoiceDialog(
            title = "Keep history for",
            choices = HistoryRetention.entries,
            selected = historyRetention,
            label = { it.label },
            explanation = { it.explanation },
            onPick = { retention ->
                viewModel.setHistoryRetention(retention)
                showRetentionDialog = false
            },
            onDismiss = { showRetentionDialog = false },
        )
    }
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear play history?") },
            text = {
                Text(
                    "Removes every song from Library History, the play counts behind Listen again, " +
                        "the Home Continue cards, and the saved lyrics. Liked songs and albums stay.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPlayHistory()
                        showClearHistoryDialog = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel") } },
        )
    }
}

private val LikeMode.label: String
    get() = when (this) {
        LikeMode.BLEND -> "Blend in"
        LikeMode.SAVE_ONLY -> "Save only"
        LikeMode.SWITCH_NOW -> "Switch now"
    }

private fun LikeMode.explanation(songsPerLike: Int): String = when (this) {
    LikeMode.BLEND -> if (songsPerLike == 0) {
        "Songs added per like is 0, so the like is only saved"
    } else {
        "$songsPerLike songs like it play next, then the queue goes on"
    }
    LikeMode.SAVE_ONLY -> "The like is saved; the queue does not change"
    LikeMode.SWITCH_NOW -> "Songs like it play after this song; a list becomes a radio"
}

private val HistoryRetention.label: String
    get() = when (this) {
        HistoryRetention.THREE_MONTHS -> "3 months"
        HistoryRetention.ONE_YEAR -> "1 year"
        HistoryRetention.FOREVER -> "Forever"
    }

private val HistoryRetention.explanation: String
    get() = when (this) {
        HistoryRetention.THREE_MONTHS -> "Songs not played for 3 months leave History and Listen again"
        HistoryRetention.ONE_YEAR -> "Songs not played for a year leave History and Listen again"
        HistoryRetention.FOREVER -> "Nothing is removed"
    }

private val SimilarDepth.label: String
    get() = when (this) {
        SimilarDepth.FAMILIAR -> "Familiar"
        SimilarDepth.DEEP -> "Deep"
    }

private val SimilarDepth.explanation: String
    get() = when (this) {
        SimilarDepth.FAMILIAR -> "Last.fm's closest songs, mostly well known"
        SimilarDepth.DEEP -> "Songs further down Last.fm's list, less known"
    }

internal fun lastfmProblem(status: LastfmStatus, activeKey: String?, nowMs: Long): String? {
    val rejected = activeKey?.let { status.rejectedKeys[it] }
    return when {
        rejected == LastfmGate.ERROR_SUSPENDED_KEY -> "Last.fm suspended this key. Add your own key to go on"
        rejected != null -> "Last.fm does not accept this key. Check it, or clear it to use the app's key"
        nowMs < status.pausedUntilMs -> "Last.fm asked to slow down: similar songs pause for ${(status.pausedUntilMs - nowMs + 59_999) / 60_000} min"
        else -> null
    }
}

internal fun lastfmKeyLabel(personal: String, hasBuiltIn: Boolean): String = when {
    personal.isNotBlank() -> "Your key ending ${personal.takeLast(4)}"
    hasBuiltIn -> "The app's key, shared by every install"
    else -> "No key: add your own to find similar songs"
}

@Composable
private fun LastfmKeyDialog(
    current: String,
    hasBuiltIn: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val trimmed = text.trim()
    val valid = trimmed.isEmpty() || isLastfmKeyShape(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Last.fm API key") },
        text = {
            Column {
                Text(
                    "Optional. Get a free key at last.fm/api/account/create and paste the API key " +
                        "(not the shared secret). " +
                        if (hasBuiltIn) "Leave empty to use the app's key." else "The app has no key of its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text("A key is 32 characters, 0-9 and a-f") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(trimmed) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun delayLabel(delayMs: Int): String = if (delayMs > 0) "+$delayMs ms" else "$delayMs ms"

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
    min: Int = 0,
    valueLabel: String = "$value",
    onValueChange: (Int) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(explanation) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onValueChange(value - step) }, enabled = value > min) {
                    Icon(Icons.Default.Remove, contentDescription = fewerLabel)
                }
                Text(valueLabel, style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun ChartsDialog(
    selected: List<String>,
    deviceDefaultLabel: String,
    regions: List<RegionOption>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf(selected) }
    var limitHit by rememberSaveable { mutableStateOf(false) }
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

@Composable
private fun ChartRow(label: String, rank: Int?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
    ) {
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
