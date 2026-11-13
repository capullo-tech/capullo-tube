package tech.capullo.tubecapullo.data.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    companion object {
        const val MAX_TRENDING_ROWS = 5

        const val MAX_MORE_LIKE_ROWS = 5

        const val DEFAULT_MORE_LIKE_ROWS = 1

        const val MAX_CONTINUE_CARDS = 6

        const val DEFAULT_SONGS_PER_LIKE = 2

        const val MAX_SONGS_PER_LIKE = 6

        const val SONGS_PER_LIKE_STEP = 2

        private const val PREFS_NAME = "app_settings"

        private const val KEY_CHART_REGION_LEGACY = "chart_region"
        private const val KEY_CHART_REGIONS = "chart_regions"
        private const val KEY_MORE_LIKE_ROWS = "more_like_rows"
        private const val KEY_CONTINUE_CARDS = "continue_cards"
        private const val KEY_FULL_ALBUMS = "full_albums"

        private const val KEY_LIKE_MODE = "radio_like_mode"

        private const val KEY_SONGS_PER_LIKE = "mix_songs"
        private const val KEY_ASK_FOR_SHARED_LINKS = "ask_for_shared_links"
    }
}

enum class LikeMode {
    BLEND,

    SAVE_ONLY,

    SWITCH_NOW,
}

private const val CHART_REGION_SEPARATOR = ","

internal fun songsPerLikeOf(count: Int): Int {
    val even = if (count % AppSettings.SONGS_PER_LIKE_STEP != 0) count + 1 else count
    return even.coerceIn(0, AppSettings.MAX_SONGS_PER_LIKE)
}

internal fun decodeChartRegions(stored: String?, legacy: String?): List<String> = when {
    stored != null -> stored.split(CHART_REGION_SEPARATOR).filter { it.isNotBlank() }
    legacy.isNullOrBlank() -> emptyList()
    else -> listOf(legacy)
}

internal fun encodeChartRegions(codes: List<String>): String = codes.joinToString(CHART_REGION_SEPARATOR)
