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
import tech.capullo.tubecapullo.links.YouTubeLinkAlias
import tech.capullo.tubecapullo.util.countryName
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

data class RegionOption(val code: String, val name: String)

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
    private val linkAlias: YouTubeLinkAlias,
) : ViewModel() {
    val chartRegions: StateFlow<List<String>> = appSettings.chartRegions

    val regions: List<RegionOption> = extractor.supportedRegions()
        .map { RegionOption(it, countryName(it)) }
        .sortedWith(compareBy(Collator.getInstance()) { it.name })

    val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val deviceDefaultLabel: String =
        deviceDefaultLabel(Locale.getDefault().country, extractor.supportedRegions().toSet())

    fun chartsLabel(codes: List<String>): String = if (codes.isEmpty()) {
        deviceDefaultLabel
    } else {
        codes.joinToString(", ") { countryName(it) }
    }

    fun setChartRegions(codes: List<String>) = appSettings.setChartRegions(codes)

    val moreLikeRows: StateFlow<Int> = appSettings.moreLikeRows

    fun setMoreLikeRows(rows: Int) = appSettings.setMoreLikeRows(rows)

    val continueCards: StateFlow<Int> = appSettings.continueCards

    fun setContinueCards(cards: Int) = appSettings.setContinueCards(cards)

    val fullAlbums: StateFlow<Boolean> = appSettings.fullAlbums

    fun setFullAlbums(enabled: Boolean) = appSettings.setFullAlbums(enabled)

    val likeMode: StateFlow<LikeMode> = appSettings.likeMode

    fun setLikeMode(mode: LikeMode) = appSettings.setLikeMode(mode)

    val songsPerLike: StateFlow<Int> = appSettings.songsPerLike

    fun setSongsPerLike(songs: Int) = appSettings.setSongsPerLike(songs)

    val openYouTubeLinks: StateFlow<Boolean> = linkAlias.enabled

    fun setOpenYouTubeLinks(enabled: Boolean) = linkAlias.setEnabled(enabled)

    val askForSharedLinks: StateFlow<Boolean> = appSettings.askForSharedLinks

    fun setAskForSharedLinks(enabled: Boolean) = appSettings.setAskForSharedLinks(enabled)
}
