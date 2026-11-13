package tech.capullo.tubecapullo.data.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User choices that are not taste data: a few scalars, read synchronously by screens and by the
 * station engine. SharedPreferences, not DataStore: no new dependency for a handful of keys, and
 * every value is mirrored into a [StateFlow] so readers never touch the prefs file after
 * construction.
 *
 * The constructor reads the prefs file once (a small, blocking disk read on whichever thread first
 * injects this singleton). Writes go to memory first, then `apply()` persists them asynchronously.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _chartRegions = MutableStateFlow(readChartRegions())

    /**
     * The Trending charts Home shows, one row each, in this order: ISO 3166-1 alpha-2 codes picked
     * from [tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient.supportedRegions], at
     * most [MAX_TRENDING_ROWS]. Empty means one row for the device default:
     * [tech.capullo.tubecapullo.data.youtube.defaultChartRegion].
     */
    val chartRegions: StateFlow<List<String>> = _chartRegions.asStateFlow()

    /** Sets the Trending charts in row order; an empty list goes back to the device default.
     *  Repeats are dropped and the list is cut at [MAX_TRENDING_ROWS]. */
    fun setChartRegions(codes: List<String>) {
        val regions = codes.distinct().take(MAX_TRENDING_ROWS)
        prefs.edit { putString(KEY_CHART_REGIONS, encodeChartRegions(regions)) }
        _chartRegions.value = regions
    }

    /** The stored chart list. The first read after an update from the single-region builds
     *  moves that region into the list (and drops the old key), so a chosen chart survives. */
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

    /** How many "More like" rows Home shows, one per like, newest like first. 0 hides them. */
    val moreLikeRows: StateFlow<Int> = _moreLikeRows.asStateFlow()

    /** Sets the "More like" row count, clamped to 0..[MAX_MORE_LIKE_ROWS]. */
    fun setMoreLikeRows(rows: Int) {
        val count = rows.coerceIn(0, MAX_MORE_LIKE_ROWS)
        prefs.edit { putInt(KEY_MORE_LIKE_ROWS, count) }
        _moreLikeRows.value = count
    }

    private val _continueCards = MutableStateFlow(
        prefs.getInt(KEY_CONTINUE_CARDS, MAX_CONTINUE_CARDS).coerceIn(0, MAX_CONTINUE_CARDS),
    )

    /** How many Continue cards Home shows, newest left station first. 0 hides the row. */
    val continueCards: StateFlow<Int> = _continueCards.asStateFlow()

    /** Sets the Continue card count, clamped to 0..[MAX_CONTINUE_CARDS]. */
    fun setContinueCards(cards: Int) {
        val count = cards.coerceIn(0, MAX_CONTINUE_CARDS)
        prefs.edit { putInt(KEY_CONTINUE_CARDS, count) }
        _continueCards.value = count
    }

    private val _fullAlbums = MutableStateFlow(prefs.getBoolean(KEY_FULL_ALBUMS, true))

    /** Whether Home shows the Full albums row. Default on (owner decision 2026-11-12). */
    val fullAlbums: StateFlow<Boolean> = _fullAlbums.asStateFlow()

    fun setFullAlbums(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FULL_ALBUMS, enabled) }
        _fullAlbums.value = enabled
    }

    private val _likeMode = MutableStateFlow(readEnum(KEY_LIKE_MODE, LikeMode.BLEND))

    /** What a like does to the queue of any source but Liked (PLAN "Radio" item 6). Read at each
     *  like. */
    val likeMode: StateFlow<LikeMode> = _likeMode.asStateFlow()

    fun setLikeMode(mode: LikeMode) {
        prefs.edit { putString(KEY_LIKE_MODE, mode.name) }
        _likeMode.value = mode
    }

    private val _songsPerLike = MutableStateFlow(
        songsPerLikeOf(prefs.getInt(KEY_SONGS_PER_LIKE, DEFAULT_SONGS_PER_LIKE)),
    )

    /** "Songs added per like" (N, PLAN "Radio" items 6 and 7): the tracks of the liked song's
     *  pairs (N/2 of its Mix, N/2 related) a Blend in like puts after the current song, and the
     *  tracks that follow each like in Liked radio. 0, 2, 4 or [MAX_SONGS_PER_LIKE]. 0: a Blend
     *  in like only saves, and Liked radio plays likes only. Read at each like and each Liked
     *  radio like, so a change applies from the next. */
    val songsPerLike: StateFlow<Int> = _songsPerLike.asStateFlow()

    /** Sets Songs added per like ([songsPerLikeOf]: odd rounds up, clamped). */
    fun setSongsPerLike(songs: Int) {
        val count = songsPerLikeOf(songs)
        prefs.edit { putInt(KEY_SONGS_PER_LIKE, count) }
        _songsPerLike.value = count
    }

    /** The stored enum under [key]; [default] when unset or unknown (a name a later build
     *  renamed or dropped). */
    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E {
        val stored = prefs.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name == stored } ?: default
    }

    private val _askForSharedLinks =
        MutableStateFlow(prefs.getBoolean(KEY_ASK_FOR_SHARED_LINKS, false))

    /**
     * Whether a shared or opened video link asks first (Play now, Play next, Like, Cancel) instead
     * of playing at once. Default off: a share should do the obvious thing without a question.
     */
    val askForSharedLinks: StateFlow<Boolean> = _askForSharedLinks.asStateFlow()

    fun setAskForSharedLinks(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ASK_FOR_SHARED_LINKS, enabled) }
        _askForSharedLinks.value = enabled
    }

    companion object {
        /**
         * Trending rows Home may show. GUESS, unmeasured: each row is one kiosk fetch (~0.9 s,
         * probe 2026-11-10, docs/probes/trending-music-results.md), all fetched in parallel on
         * every Home open, so the count is requests per open. Higher risks YouTube's rate limit
         * and buries the rows under the fold; lower stops a listener who follows a few
         * countries.
         */
        const val MAX_TRENDING_ROWS = 5

        /**
         * Upper bound of the "More like" row setting. GUESS, unmeasured: each row is a full
         * StreamInfo fetch (the cost of resolving a stream) plus a Mix fetch, on every Home open
         * and after likes change, in parallel. Higher multiplies that cost and pushes Trending
         * far down; lower caps a listener with many tastes.
         */
        const val MAX_MORE_LIKE_ROWS = 5

        /**
         * "More like" rows before the listener picks a count. Owner decision 2026-11-12 (PLAN
         * "Home v2 and albums"), not measured: one row, so Continue, Listen again and Full
         * albums stay near the fold. A count the listener stored before stays as it was. Higher
         * pushes the rows below off-screen and multiplies the fetch cost.
         */
        const val DEFAULT_MORE_LIKE_ROWS = 1

        /**
         * Upper bound and default of the Continue cards setting: the stations Room keeps for
         * Continue (StationEngine keeps this many plus the playing one). GUESS (PLAN "Home v2 and
         * albums", "the last 6"), unmeasured: about two screen widths of cards, more stations
         * than a listener switches between in a day. Higher keeps stations nobody returns to;
         * lower drops one the listener meant to finish.
         */
        const val MAX_CONTINUE_CARDS = 6

        /**
         * Songs added per like before the listener picks a count. Owner decision 2026-11-11, not
         * measured: about seven minutes after a like, long enough to hear where the like leads,
         * short enough that the queue it interrupts comes back. One pair: one song of the liked
         * song's Mix and one related. Higher drifts further from the radio, the list or the likes.
         */
        const val DEFAULT_SONGS_PER_LIKE = 2

        /**
         * Upper bound of Songs added per like. GUESS (owner brief 2026-11-12), unmeasured: 6 is
         * three pairs, about 20 minutes after a like, past which a blend reads as a new station
         * rather than a detour (that is what Switch now is for) and Liked radio plays its likes
         * one track in seven. Higher: a like takes over the queue; lower: caps a listener who
         * wants more discovery.
         */
        const val MAX_SONGS_PER_LIKE = 6

        /** Songs added per like moves in whole pairs (owner decision 2026-11-12, "Pairs"). */
        const val SONGS_PER_LIKE_STEP = 2

        private const val PREFS_NAME = "app_settings"

        /** The single chart region of earlier builds: read once by [readChartRegions], which
         *  moves it into [KEY_CHART_REGIONS] and removes it. */
        private const val KEY_CHART_REGION_LEGACY = "chart_region"
        private const val KEY_CHART_REGIONS = "chart_regions"
        private const val KEY_MORE_LIKE_ROWS = "more_like_rows"
        private const val KEY_CONTINUE_CARDS = "continue_cards"
        private const val KEY_FULL_ALBUMS = "full_albums"

        /** The stored name predates the rename from "radio like mode": kept so the choice
         *  survives the update. */
        private const val KEY_LIKE_MODE = "radio_like_mode"

        /** The stored name predates the rename from "Songs from each Mix": kept so the count
         *  survives the update ([songsPerLikeOf] rounds an odd one up). */
        private const val KEY_SONGS_PER_LIKE = "mix_songs"
        private const val KEY_ASK_FOR_SHARED_LINKS = "ask_for_shared_links"
    }
}

/** What a like does to the queue (Settings > "When you like a song"). Applies to radios,
 *  Liked radio, playlists and channels; not to Liked, where every song is already liked. */
enum class LikeMode {
    /** The first [AppSettings.songsPerLike] unplayed songs of the liked song's pairs (its Mix and
     *  its related videos) go right after the current song; the rest of the queue (the radio or
     *  the list) resumes after them. In a list they are extras: the list's place and its repeat
     *  round do not change. */
    BLEND,

    /** The like is saved; the queue does not change. */
    SAVE_ONLY,

    /** The upcoming queue becomes the liked song's pairs; the current song plays on. A list (or
     *  Liked radio) becomes a radio from the liked song. */
    SWITCH_NOW,
}

/** Separates the codes in the stored chart list. ISO 3166-1 alpha-2 codes never contain it. */
private const val CHART_REGION_SEPARATOR = ","

/** A Songs added per like count as the setting holds it: an odd count (1, 3 and 5 were the
 *  choices before pairs) rounds up to whole pairs, then 0..[AppSettings.MAX_SONGS_PER_LIKE]. */
internal fun songsPerLikeOf(count: Int): Int {
    val even = if (count % AppSettings.SONGS_PER_LIKE_STEP != 0) count + 1 else count
    return even.coerceIn(0, AppSettings.MAX_SONGS_PER_LIKE)
}

/**
 * The chart list from the prefs: [stored] is the list key's raw value (null when it was never
 * written), [legacy] the single region earlier builds stored (null when the user kept the device
 * default). The list key wins once written, even when empty: an empty list is the user's choice
 * of the device default, not a missing value.
 */
internal fun decodeChartRegions(stored: String?, legacy: String?): List<String> = when {
    stored != null -> stored.split(CHART_REGION_SEPARATOR).filter { it.isNotBlank() }
    legacy.isNullOrBlank() -> emptyList()
    else -> listOf(legacy)
}

internal fun encodeChartRegions(codes: List<String>): String = codes.joinToString(CHART_REGION_SEPARATOR)
