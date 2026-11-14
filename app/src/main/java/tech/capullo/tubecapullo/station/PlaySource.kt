package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo

sealed interface PlaySource {
    val isCollection: Boolean get() = this is Liked || this is Playlist || this is Channel

    data class SongRadio(val seed: YtVideo) : PlaySource

    data class SearchRadio(val query: String, val results: List<YtVideo>) : PlaySource

    data class Playlist(
        val url: String,
        val title: String,
        val shuffle: Boolean = false,
    ) : PlaySource {
        companion object {
            const val MIX_PLACEHOLDER = "YouTube Mix"
        }
    }

    data class Channel(val url: String, val name: String) : PlaySource

    data class Liked(val shuffle: Boolean) : PlaySource

    data object LikedRadio : PlaySource

    data class Album(val video: YtVideo, val chapters: List<YtChapter> = emptyList()) : PlaySource
}
