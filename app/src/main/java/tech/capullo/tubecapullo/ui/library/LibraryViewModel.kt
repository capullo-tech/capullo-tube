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
import tech.capullo.tubecapullo.data.backup.BackupMessage
import tech.capullo.tubecapullo.data.backup.LikedBackup
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class LibraryRow(val video: YtVideo, val isAlbum: Boolean)

internal fun historyRows(history: List<YtVideo>, albumIds: Set<String>): List<LibraryRow> = history.map { LibraryRow(it, it.videoId in albumIds) }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val backup: LikedBackup,
    likedTracks: LikedTracks,
    likedAlbums: LikedAlbums,
    playHistory: PlayHistory,
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

    val playerState = player.state

    fun playLiked(shuffle: Boolean) = player.start(PlaySource.Liked(shuffle))

    fun playLikedRadio() = player.start(PlaySource.LikedRadio)

    fun playSongRadio(video: YtVideo) = player.start(PlaySource.SongRadio(video))

    fun playAlbum(video: YtVideo) = player.start(PlaySource.Album(video))

    fun playNext(video: YtVideo) = stationEngine.playNext(video)

    fun unlike(video: YtVideo) = stationEngine.toggleLike(video)

    fun unlikeAlbum(album: YtVideo) = stationEngine.toggleAlbumLike(album)

    val backupProgress = backup.progress

    val backupMessage = backup.message

    fun exportLiked(uri: Uri) = backup.export(uri)

    fun importLiked(uri: Uri) = backup.import(uri)

    fun backupMessageShown(message: BackupMessage) = backup.messageShown(message)
}
