package tech.capullo.tubecapullo.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.defaultChartRegion
import tech.capullo.tubecapullo.util.countryName
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/** One choice in the Trending charts list: an ISO 3166-1 alpha-2 [code] and its display
 *  [name]. */
data class RegionOption(val code: String, val name: String)

/**
 * The device-default choice's label: the chart [defaultChartRegion] picks for [localeCountry],
 * and, when that is a substitute for a country YouTube Charts does not serve, why.
 */
internal fun deviceDefaultLabel(localeCountry: String?, supported: Set<String>): String {
    val chart = countryName(defaultChartRegion(localeCountry, supported))
    val country = localeCountry?.trim().orEmpty()
    return if (country.isEmpty() || country.uppercase() in supported) {
        "Device default ($chart)"
    } else {
        "Device default (${countryName(country)} has no YouTube chart · $chart)"
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val appSettings: AppSettings,
    extractor: YouTubeExtractorClient,
) : ViewModel() {

    /** The chosen Trending charts in row order; empty follows the device locale. */
    val chartRegions: StateFlow<List<String>> = appSettings.chartRegions

    /** Every region the extractor supports, sorted by display name in the user's language. */
    val regions: List<RegionOption> = extractor.supportedRegions()
        .map { RegionOption(it, countryName(it)) }
        .sortedWith(compareBy(Collator.getInstance()) { it.name })

    /**
     * The Developer section exists only in debuggable builds (hidden, not disabled). Read from the
     * installed ApplicationInfo rather than BuildConfig, so it reflects the APK actually running.
     */
    val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Label for the device-default choice, naming the chart it currently resolves to. */
    val deviceDefaultLabel: String =
        deviceDefaultLabel(Locale.getDefault().country, extractor.supportedRegions().toSet())

    /** Display text for the chosen charts: their names in row order, or the device default. */
    fun chartsLabel(codes: List<String>): String = if (codes.isEmpty()) {
        deviceDefaultLabel
    } else {
        codes.joinToString(", ") { countryName(it) }
    }

    fun setChartRegions(codes: List<String>) = appSettings.setChartRegions(codes)

    /** How many "More like" rows Home shows; 0 hides them. */
    val moreLikeRows: StateFlow<Int> = appSettings.moreLikeRows

    fun setMoreLikeRows(rows: Int) = appSettings.setMoreLikeRows(rows)

    /** How many Continue cards Home shows; 0 hides the row. */
    val continueCards: StateFlow<Int> = appSettings.continueCards

    fun setContinueCards(cards: Int) = appSettings.setContinueCards(cards)

    /** Whether Home shows the Full albums row. */
    val fullAlbums: StateFlow<Boolean> = appSettings.fullAlbums

    fun setFullAlbums(enabled: Boolean) = appSettings.setFullAlbums(enabled)

    /** What a like does to the queue (any source but Liked). */
    val likeMode: StateFlow<LikeMode> = appSettings.likeMode

    fun setLikeMode(mode: LikeMode) = appSettings.setLikeMode(mode)

    /** Songs added per like: after a Blend in like, and after each like in Liked radio. */
    val songsPerLike: StateFlow<Int> = appSettings.songsPerLike

    fun setSongsPerLike(songs: Int) = appSettings.setSongsPerLike(songs)
}
