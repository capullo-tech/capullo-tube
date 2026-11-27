package tech.capullo.tubecapullo.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.BuildConfig
import tech.capullo.tubecapullo.data.similar.SimilarDepth
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val STORE_NAME = "app_settings"

/**
 * The settings file. Builds before the move to DataStore kept the same keys in a SharedPreferences
 * file of the same name; the first read copies them here, with their types, and deletes that file.
 */
val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = STORE_NAME,
    corruptionHandler = replaceCorruptStore(STORE_NAME),
    produceMigrations = { listOf(SharedPreferencesMigration(it, STORE_NAME)) },
)

/**
 * The app's settings, kept in a Preferences DataStore.
 *
 * [load] reads the file before the settings exist, so every value starts at what the user saved,
 * never at a default. A setter changes its value at once and queues the write; one coroutine on
 * [scope] writes them to the file in the order they were made. A write that fails with an
 * [IOException] is logged and the value holds until the app stops.
 */
class AppSettings private constructor(
    private val store: DataStore<Preferences>,
    private val saved: Preferences,
    scope: CoroutineScope,
) {
    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    store.edit { write(it) }
                } catch (e: IOException) {
                    Log.e(TAG, "$STORE_NAME: write failed, the value holds until the app stops", e)
                }
            }
        }
    }

    private fun save(write: (MutablePreferences) -> Unit) {
        writes.trySend(write)
    }

    private val _resetNotice = MutableStateFlow(saved[RESET_NOTICE] == true)

    /** The settings file was corrupt and has been replaced by the defaults; true until the user has been told. */
    val resetNotice: StateFlow<Boolean> = _resetNotice.asStateFlow()

    /** The user has been told that the settings were reset. */
    fun acknowledgeReset() {
        save { it.remove(RESET_NOTICE) }
        _resetNotice.value = false
    }

    private val _chartRegions = MutableStateFlow(readChartRegions())

    val chartRegions: StateFlow<List<String>> = _chartRegions.asStateFlow()

    fun setChartRegions(codes: List<String>) {
        val regions = codes.distinct().take(MAX_TRENDING_ROWS)
        save { it[CHART_REGIONS] = encodeChartRegions(regions) }
        _chartRegions.value = regions
    }

    private fun readChartRegions(): List<String> {
        val stored = saved[CHART_REGIONS]
        val legacy = saved[CHART_REGION_LEGACY]
        val regions = decodeChartRegions(stored, legacy)
        if (stored == null && legacy != null) {
            save {
                it[CHART_REGIONS] = encodeChartRegions(regions)
                it.remove(CHART_REGION_LEGACY)
            }
        }
        return regions
    }

    private val _moreLikeRows = MutableStateFlow(
        (saved[MORE_LIKE_ROWS] ?: DEFAULT_MORE_LIKE_ROWS).coerceIn(0, MAX_MORE_LIKE_ROWS),
    )

    val moreLikeRows: StateFlow<Int> = _moreLikeRows.asStateFlow()

    fun setMoreLikeRows(rows: Int) {
        val count = rows.coerceIn(0, MAX_MORE_LIKE_ROWS)
        save { it[MORE_LIKE_ROWS] = count }
        _moreLikeRows.value = count
    }

    private val _continueCards = MutableStateFlow(
        (saved[CONTINUE_CARDS] ?: MAX_CONTINUE_CARDS).coerceIn(0, MAX_CONTINUE_CARDS),
    )

    val continueCards: StateFlow<Int> = _continueCards.asStateFlow()

    fun setContinueCards(cards: Int) {
        val count = cards.coerceIn(0, MAX_CONTINUE_CARDS)
        save { it[CONTINUE_CARDS] = count }
        _continueCards.value = count
    }

    private val _fullAlbums = MutableStateFlow(saved[FULL_ALBUMS] ?: true)

    val fullAlbums: StateFlow<Boolean> = _fullAlbums.asStateFlow()

    fun setFullAlbums(enabled: Boolean) {
        save { it[FULL_ALBUMS] = enabled }
        _fullAlbums.value = enabled
    }

    private val _likeMode = MutableStateFlow(readEnum(LIKE_MODE, LikeMode.BLEND))

    val likeMode: StateFlow<LikeMode> = _likeMode.asStateFlow()

    fun setLikeMode(mode: LikeMode) {
        save { it[LIKE_MODE] = mode.name }
        _likeMode.value = mode
    }

    private val _songsPerLike = MutableStateFlow(songsPerLikeOf(saved[SONGS_PER_LIKE] ?: DEFAULT_SONGS_PER_LIKE))

    val songsPerLike: StateFlow<Int> = _songsPerLike.asStateFlow()

    fun setSongsPerLike(songs: Int) {
        val count = songsPerLikeOf(songs)
        save { it[SONGS_PER_LIKE] = count }
        _songsPerLike.value = count
    }

    private inline fun <reified E : Enum<E>> readEnum(key: Preferences.Key<String>, default: E): E {
        val stored = saved[key] ?: return default
        return enumValues<E>().firstOrNull { it.name == stored } ?: default
    }

    private val _askForSharedLinks = MutableStateFlow(saved[ASK_FOR_SHARED_LINKS] ?: false)

    val askForSharedLinks: StateFlow<Boolean> = _askForSharedLinks.asStateFlow()

    fun setAskForSharedLinks(enabled: Boolean) {
        save { it[ASK_FOR_SHARED_LINKS] = enabled }
        _askForSharedLinks.value = enabled
    }

    private val _lyricsCard = MutableStateFlow(saved[LYRICS_CARD] ?: false)

    val lyricsCard: StateFlow<Boolean> = _lyricsCard.asStateFlow()

    fun setLyricsCard(enabled: Boolean) {
        save { it[LYRICS_CARD] = enabled }
        _lyricsCard.value = enabled
    }

    private val _lyricsOnline = MutableStateFlow(saved[LYRICS_ONLINE] ?: true)

    val lyricsOnline: StateFlow<Boolean> = _lyricsOnline.asStateFlow()

    fun setLyricsOnline(enabled: Boolean) {
        save { it[LYRICS_ONLINE] = enabled }
        _lyricsOnline.value = enabled
    }

    private val _lyricsDelayMs = MutableStateFlow(lyricsDelayOf(saved[LYRICS_DELAY_MS] ?: 0))

    val lyricsDelayMs: StateFlow<Int> = _lyricsDelayMs.asStateFlow()

    fun setLyricsDelayMs(delayMs: Int) {
        val delay = lyricsDelayOf(delayMs)
        save { it[LYRICS_DELAY_MS] = delay }
        _lyricsDelayMs.value = delay
    }

    // Without a built-in key the feature starts off: Settings would otherwise offer songs it
    // cannot fetch until the user adds a personal key.
    private val _similarOnline = MutableStateFlow(saved[SIMILAR_ONLINE] ?: BuildConfig.LASTFM_API_KEY.isNotBlank())

    val similarOnline: StateFlow<Boolean> = _similarOnline.asStateFlow()

    fun setSimilarOnline(enabled: Boolean) {
        save { it[SIMILAR_ONLINE] = enabled }
        _similarOnline.value = enabled
    }

    private val _similarDepth = MutableStateFlow(readEnum(SIMILAR_DEPTH, SimilarDepth.FAMILIAR))

    val similarDepth: StateFlow<SimilarDepth> = _similarDepth.asStateFlow()

    fun setSimilarDepth(depth: SimilarDepth) {
        save { it[SIMILAR_DEPTH] = depth.name }
        _similarDepth.value = depth
    }

    private val _lastfmApiKey = MutableStateFlow(saved[LASTFM_API_KEY].orEmpty())

    /** The user's own Last.fm key; empty means the built-in key. */
    val lastfmApiKey: StateFlow<String> = _lastfmApiKey.asStateFlow()

    fun setLastfmApiKey(key: String) {
        val trimmed = key.trim()
        save { it[LASTFM_API_KEY] = trimmed }
        _lastfmApiKey.value = trimmed
    }

    private val _historyRetention = MutableStateFlow(readEnum(HISTORY_RETENTION, HistoryRetention.FOREVER))

    val historyRetention: StateFlow<HistoryRetention> = _historyRetention.asStateFlow()

    fun setHistoryRetention(retention: HistoryRetention) {
        save { it[HISTORY_RETENTION] = retention.name }
        _historyRetention.value = retention
    }

    companion object {
        /** Reads [store] and returns the settings it holds. */
        suspend fun load(store: DataStore<Preferences>, scope: CoroutineScope): AppSettings = AppSettings(store, store.data.defaultsOnReadError(STORE_NAME).first(), scope)

        const val MAX_TRENDING_ROWS = 5

        const val MAX_MORE_LIKE_ROWS = 5

        const val DEFAULT_MORE_LIKE_ROWS = 1

        const val MAX_CONTINUE_CARDS = 6

        const val DEFAULT_SONGS_PER_LIKE = 2

        const val MAX_SONGS_PER_LIKE = 6

        const val SONGS_PER_LIKE_STEP = 2

        const val MAX_LYRICS_DELAY_MS = 2_000

        const val LYRICS_DELAY_STEP_MS = 100

        private const val TAG = "Settings"

        // Names and types are those of the SharedPreferences file that the migration copies.
        private val CHART_REGION_LEGACY = stringPreferencesKey("chart_region")
        private val CHART_REGIONS = stringPreferencesKey("chart_regions")
        private val MORE_LIKE_ROWS = intPreferencesKey("more_like_rows")
        private val CONTINUE_CARDS = intPreferencesKey("continue_cards")
        private val FULL_ALBUMS = booleanPreferencesKey("full_albums")

        private val LIKE_MODE = stringPreferencesKey("radio_like_mode")

        private val SONGS_PER_LIKE = intPreferencesKey("mix_songs")
        private val ASK_FOR_SHARED_LINKS = booleanPreferencesKey("ask_for_shared_links")
        private val LYRICS_CARD = booleanPreferencesKey("lyrics_card")
        private val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")
        private val LYRICS_DELAY_MS = intPreferencesKey("lyrics_delay_ms")
        private val HISTORY_RETENTION = stringPreferencesKey("history_retention")
        private val SIMILAR_ONLINE = booleanPreferencesKey("similar_online")
        private val SIMILAR_DEPTH = stringPreferencesKey("similar_depth")
        private val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
    }
}

enum class LikeMode {
    BLEND,

    SAVE_ONLY,

    SWITCH_NOW,
}

enum class HistoryRetention(private val days: Long?) {
    THREE_MONTHS(92),

    ONE_YEAR(366),

    FOREVER(null),
    ;

    fun cutoffEpochMs(nowEpochMs: Long): Long? = days?.let { nowEpochMs - TimeUnit.DAYS.toMillis(it) }
}

private const val CHART_REGION_SEPARATOR = ","

internal fun songsPerLikeOf(count: Int): Int {
    val even = if (count % AppSettings.SONGS_PER_LIKE_STEP != 0) count + 1 else count
    return even.coerceIn(0, AppSettings.MAX_SONGS_PER_LIKE)
}

internal fun lyricsDelayOf(delayMs: Int): Int = delayMs.coerceIn(-AppSettings.MAX_LYRICS_DELAY_MS, AppSettings.MAX_LYRICS_DELAY_MS)

internal fun decodeChartRegions(stored: String?, legacy: String?): List<String> = when {
    stored != null -> stored.split(CHART_REGION_SEPARATOR).filter { it.isNotBlank() }
    legacy.isNullOrBlank() -> emptyList()
    else -> listOf(legacy)
}

internal fun encodeChartRegions(codes: List<String>): String = codes.joinToString(CHART_REGION_SEPARATOR)
