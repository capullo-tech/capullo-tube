package tech.capullo.tubecapullo.data.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.tubecapullo.BuildConfig
import tech.capullo.tubecapullo.data.similar.SimilarDepth
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _chartRegions = MutableStateFlow(readChartRegions())

    val chartRegions: StateFlow<List<String>> = _chartRegions.asStateFlow()

    fun setChartRegions(codes: List<String>) {
        val regions = codes.distinct().take(MAX_TRENDING_ROWS)
        prefs.edit { putString(KEY_CHART_REGIONS, encodeChartRegions(regions)) }
        _chartRegions.value = regions
    }

    private fun readChartRegions(): List<String> {
        val stored = prefs.getString(KEY_CHART_REGIONS, null)
        val legacy = prefs.getString(KEY_CHART_REGION_LEGACY, null)
        val regions = decodeChartRegions(stored, legacy)
        if (stored == null && legacy != null) {
            prefs.edit {
                putString(KEY_CHART_REGIONS, encodeChartRegions(regions))
                remove(KEY_CHART_REGION_LEGACY)
            }
        }
        return regions
    }

    private val _moreLikeRows = MutableStateFlow(
        prefs.getInt(KEY_MORE_LIKE_ROWS, DEFAULT_MORE_LIKE_ROWS).coerceIn(0, MAX_MORE_LIKE_ROWS),
    )

    val moreLikeRows: StateFlow<Int> = _moreLikeRows.asStateFlow()

    fun setMoreLikeRows(rows: Int) {
        val count = rows.coerceIn(0, MAX_MORE_LIKE_ROWS)
        prefs.edit { putInt(KEY_MORE_LIKE_ROWS, count) }
        _moreLikeRows.value = count
    }

    private val _continueCards = MutableStateFlow(
        prefs.getInt(KEY_CONTINUE_CARDS, MAX_CONTINUE_CARDS).coerceIn(0, MAX_CONTINUE_CARDS),
    )

    val continueCards: StateFlow<Int> = _continueCards.asStateFlow()

    fun setContinueCards(cards: Int) {
        val count = cards.coerceIn(0, MAX_CONTINUE_CARDS)
        prefs.edit { putInt(KEY_CONTINUE_CARDS, count) }
        _continueCards.value = count
    }

    private val _fullAlbums = MutableStateFlow(prefs.getBoolean(KEY_FULL_ALBUMS, true))

    val fullAlbums: StateFlow<Boolean> = _fullAlbums.asStateFlow()

    fun setFullAlbums(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FULL_ALBUMS, enabled) }
        _fullAlbums.value = enabled
    }

    private val _likeMode = MutableStateFlow(readEnum(KEY_LIKE_MODE, LikeMode.BLEND))

    val likeMode: StateFlow<LikeMode> = _likeMode.asStateFlow()

    fun setLikeMode(mode: LikeMode) {
        prefs.edit { putString(KEY_LIKE_MODE, mode.name) }
        _likeMode.value = mode
    }

    private val _songsPerLike = MutableStateFlow(
        songsPerLikeOf(prefs.getInt(KEY_SONGS_PER_LIKE, DEFAULT_SONGS_PER_LIKE)),
    )

    val songsPerLike: StateFlow<Int> = _songsPerLike.asStateFlow()

    fun setSongsPerLike(songs: Int) {
        val count = songsPerLikeOf(songs)
        prefs.edit { putInt(KEY_SONGS_PER_LIKE, count) }
        _songsPerLike.value = count
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E {
        val stored = prefs.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name == stored } ?: default
    }

    private val _askForSharedLinks =
        MutableStateFlow(prefs.getBoolean(KEY_ASK_FOR_SHARED_LINKS, false))

    val askForSharedLinks: StateFlow<Boolean> = _askForSharedLinks.asStateFlow()

    fun setAskForSharedLinks(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ASK_FOR_SHARED_LINKS, enabled) }
        _askForSharedLinks.value = enabled
    }

    private val _lyricsCard = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_CARD, false))

    val lyricsCard: StateFlow<Boolean> = _lyricsCard.asStateFlow()

    fun setLyricsCard(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LYRICS_CARD, enabled) }
        _lyricsCard.value = enabled
    }

    private val _lyricsOnline = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_ONLINE, true))

    val lyricsOnline: StateFlow<Boolean> = _lyricsOnline.asStateFlow()

    fun setLyricsOnline(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LYRICS_ONLINE, enabled) }
        _lyricsOnline.value = enabled
    }

    private val _lyricsDelayMs = MutableStateFlow(lyricsDelayOf(prefs.getInt(KEY_LYRICS_DELAY_MS, 0)))

    val lyricsDelayMs: StateFlow<Int> = _lyricsDelayMs.asStateFlow()

    fun setLyricsDelayMs(delayMs: Int) {
        val delay = lyricsDelayOf(delayMs)
        prefs.edit { putInt(KEY_LYRICS_DELAY_MS, delay) }
        _lyricsDelayMs.value = delay
    }

    // Without a built-in key the feature starts off: Settings would otherwise offer songs it
    // cannot fetch until the user adds a personal key.
    private val _similarOnline = MutableStateFlow(prefs.getBoolean(KEY_SIMILAR_ONLINE, BuildConfig.LASTFM_API_KEY.isNotBlank()))

    val similarOnline: StateFlow<Boolean> = _similarOnline.asStateFlow()

    fun setSimilarOnline(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SIMILAR_ONLINE, enabled) }
        _similarOnline.value = enabled
    }

    private val _similarDepth = MutableStateFlow(readEnum(KEY_SIMILAR_DEPTH, SimilarDepth.FAMILIAR))

    val similarDepth: StateFlow<SimilarDepth> = _similarDepth.asStateFlow()

    fun setSimilarDepth(depth: SimilarDepth) {
        prefs.edit { putString(KEY_SIMILAR_DEPTH, depth.name) }
        _similarDepth.value = depth
    }

    private val _lastfmApiKey = MutableStateFlow(prefs.getString(KEY_LASTFM_API_KEY, null).orEmpty())

    /** The user's own Last.fm key; empty means the built-in key. */
    val lastfmApiKey: StateFlow<String> = _lastfmApiKey.asStateFlow()

    fun setLastfmApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit { putString(KEY_LASTFM_API_KEY, trimmed) }
        _lastfmApiKey.value = trimmed
    }

    private val _historyRetention = MutableStateFlow(readEnum(KEY_HISTORY_RETENTION, HistoryRetention.FOREVER))

    val historyRetention: StateFlow<HistoryRetention> = _historyRetention.asStateFlow()

    fun setHistoryRetention(retention: HistoryRetention) {
        prefs.edit { putString(KEY_HISTORY_RETENTION, retention.name) }
        _historyRetention.value = retention
    }

    private val _tunnelEnabled = MutableStateFlow(prefs.getBoolean(KEY_TUNNEL_ENABLED, false))

    /** Public link: a Cloudflare quick tunnel to the web player while broadcasting. */
    val tunnelEnabled: StateFlow<Boolean> = _tunnelEnabled.asStateFlow()

    fun setTunnelEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TUNNEL_ENABLED, enabled) }
        _tunnelEnabled.value = enabled
    }

    companion object {
        const val MAX_TRENDING_ROWS = 5

        const val MAX_MORE_LIKE_ROWS = 5

        const val DEFAULT_MORE_LIKE_ROWS = 1

        const val MAX_CONTINUE_CARDS = 6

        const val DEFAULT_SONGS_PER_LIKE = 2

        const val MAX_SONGS_PER_LIKE = 6

        const val SONGS_PER_LIKE_STEP = 2

        const val MAX_LYRICS_DELAY_MS = 2_000

        const val LYRICS_DELAY_STEP_MS = 100

        private const val PREFS_NAME = "app_settings"

        private const val KEY_CHART_REGION_LEGACY = "chart_region"
        private const val KEY_CHART_REGIONS = "chart_regions"
        private const val KEY_MORE_LIKE_ROWS = "more_like_rows"
        private const val KEY_CONTINUE_CARDS = "continue_cards"
        private const val KEY_FULL_ALBUMS = "full_albums"

        private const val KEY_LIKE_MODE = "radio_like_mode"

        private const val KEY_SONGS_PER_LIKE = "mix_songs"
        private const val KEY_ASK_FOR_SHARED_LINKS = "ask_for_shared_links"
        private const val KEY_LYRICS_CARD = "lyrics_card"
        private const val KEY_LYRICS_ONLINE = "lyrics_online"
        private const val KEY_LYRICS_DELAY_MS = "lyrics_delay_ms"
        private const val KEY_HISTORY_RETENTION = "history_retention"
        private const val KEY_SIMILAR_ONLINE = "similar_online"
        private const val KEY_SIMILAR_DEPTH = "similar_depth"
        private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
        private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"
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
