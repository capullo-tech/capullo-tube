package tech.capullo.tubecapullo.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.backup.BackupMessage
import tech.capullo.tubecapullo.data.backup.LikedBackup
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.quantumcast.QuantumCastPlaylists
import tech.capullo.tubecapullo.data.quantumcast.RadioPlaylist
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class LibraryRow(val video: YtVideo, val isAlbum: Boolean)

internal fun historyRows(history: List<YtVideo>, albumIds: Set<String>): List<LibraryRow> = history.map { LibraryRow(it, it.videoId in albumIds) }

/** "12 songs · 3 without a YouTube link": only the songs with a YouTube link play here. */
internal fun radioPlaylistCounts(playlist: RadioPlaylist): String {
    val playable = playlist.playableCount
    val missing = playlist.trackCount - playable
    val songs = if (playable == 1) "1 song" else "$playable songs"
    return when {
        missing <= 0 -> songs
        playable == 0 -> "$missing without a YouTube link"
        else -> "$songs · $missing without a YouTube link"
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val backup: LikedBackup,
    likedTracks: LikedTracks,
    likedAlbums: LikedAlbums,
    private val playHistory: PlayHistory,
    quantumCast: QuantumCastPlaylists,
) : ViewModel() {
    val liked: StateFlow<List<YtVideo>?> = likedTracks.observe()
        .stateIn<List<YtVideo>?>(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    val albums: StateFlow<List<YtVideo>?> = likedAlbums.observeEntries()
        .map { entries -> entries.map { it.video } }
        .stateIn<List<YtVideo>?>(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    val history: StateFlow<List<LibraryRow>?> = combine(
        playHistory.observeRecent(),
        stationEngine.likedAlbumIds,
        ::historyRows,
    ).stateIn<List<LibraryRow>?>(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        null,
    )

    val radioPlaylists: StateFlow<List<RadioPlaylist>?> = quantumCast.changes
        .stateIn<List<RadioPlaylist>?>(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    val playerState = player.state

    fun playLiked(shuffle: Boolean) = player.start(PlaySource.Liked(shuffle))

    fun playLikedRadio() = player.start(PlaySource.LikedRadio)

    fun playSongRadio(video: YtVideo) = player.start(PlaySource.SongRadio(video))

    fun playAlbum(video: YtVideo) = player.start(PlaySource.Album(video))

    fun playRadioPlaylist(playlist: RadioPlaylist, shuffle: Boolean) = player.start(PlaySource.QuantumCast(playlist.id, playlist.title, shuffle))

    fun playNext(video: YtVideo) = stationEngine.playNext(video)

    fun unlike(video: YtVideo) = stationEngine.toggleLike(video)

    fun unlikeAlbum(album: YtVideo) = stationEngine.toggleAlbumLike(album)

    fun removeFromHistory(video: YtVideo) {
        viewModelScope.launch { playHistory.remove(video.videoId, player.state.value.video?.videoId) }
    }

    val backupProgress = backup.progress

    val backupMessage = backup.message

    fun exportLiked(uri: Uri) = backup.export(uri)

    fun importLiked(uri: Uri) = backup.import(uri)

    fun backupMessageShown(message: BackupMessage) = backup.messageShown(message)
}
