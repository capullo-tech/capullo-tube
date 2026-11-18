package tech.capullo.tubecapullo.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.capullo.tubecapullo.BuildConfig
import tech.capullo.tubecapullo.data.history.PlayHistoryRetention
import tech.capullo.tubecapullo.data.lyrics.LyricsTiming
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.HistoryRetention
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.similar.LastfmClient
import tech.capullo.tubecapullo.data.similar.LastfmStatus
import tech.capullo.tubecapullo.data.similar.SimilarDepth
import tech.capullo.tubecapullo.data.similar.lastfmKeyOf
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.defaultChartRegion
import tech.capullo.tubecapullo.links.YouTubeLinkAlias
import tech.capullo.tubecapullo.snapcast.SnapcastManager
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
    snapcastManager: SnapcastManager,
    private val playHistoryRetention: PlayHistoryRetention,
    lastfmClient: LastfmClient,
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

    val lyricsCard: StateFlow<Boolean> = appSettings.lyricsCard

    fun setLyricsCard(enabled: Boolean) = appSettings.setLyricsCard(enabled)

    val lyricsOnline: StateFlow<Boolean> = appSettings.lyricsOnline

    fun setLyricsOnline(enabled: Boolean) = appSettings.setLyricsOnline(enabled)

    val similarOnline: StateFlow<Boolean> = appSettings.similarOnline

    fun setSimilarOnline(enabled: Boolean) = appSettings.setSimilarOnline(enabled)

    val similarDepth: StateFlow<SimilarDepth> = appSettings.similarDepth

    fun setSimilarDepth(depth: SimilarDepth) = appSettings.setSimilarDepth(depth)

    val lastfmApiKey: StateFlow<String> = appSettings.lastfmApiKey

    fun setLastfmApiKey(key: String) = appSettings.setLastfmApiKey(key)

    val hasBuiltInLastfmKey: Boolean = BuildConfig.LASTFM_API_KEY.isNotBlank()

    val lastfmStatus: StateFlow<LastfmStatus> = lastfmClient.gate.status

    /** The key Last.fm calls go out with: the personal one, else the built-in one. */
    fun activeLastfmKey(personal: String): String? = lastfmKeyOf(personal, BuildConfig.LASTFM_API_KEY)

    val lyricsDelayMs: StateFlow<Int> = appSettings.lyricsDelayMs

    fun setLyricsDelayMs(delayMs: Int) = appSettings.setLyricsDelayMs(delayMs)

    val lyricsAutoDelayMs: StateFlow<Long> = snapcastManager.state
        .map { LyricsTiming.autoDelayMs(it.groups, snapcastManager.localClientId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsTiming.SNAPSERVER_BUFFER_MS)

    val likeMode: StateFlow<LikeMode> = appSettings.likeMode

    fun setLikeMode(mode: LikeMode) = appSettings.setLikeMode(mode)

    val songsPerLike: StateFlow<Int> = appSettings.songsPerLike

    fun setSongsPerLike(songs: Int) = appSettings.setSongsPerLike(songs)

    val openYouTubeLinks: StateFlow<Boolean> = linkAlias.enabled

    fun setOpenYouTubeLinks(enabled: Boolean) = linkAlias.setEnabled(enabled)

    val askForSharedLinks: StateFlow<Boolean> = appSettings.askForSharedLinks

    fun setAskForSharedLinks(enabled: Boolean) = appSettings.setAskForSharedLinks(enabled)

    val historyRetention: StateFlow<HistoryRetention> = appSettings.historyRetention

    fun setHistoryRetention(retention: HistoryRetention) = appSettings.setHistoryRetention(retention)

    fun clearPlayHistory() = playHistoryRetention.clear()
}
