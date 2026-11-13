package tech.capullo.tubecapullo.ui.search

import tech.capullo.tubecapullo.data.youtube.CollectionKind
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtCollection

/** The Search screen's filter chips, in chip order. [collectionKind] is what a non-song filter
 *  lists; null for Songs. */
enum class SearchFilter(val label: String, val collectionKind: CollectionKind?) {
    SONGS("Songs", null),
    PLAYLISTS("Playlists", CollectionKind.PLAYLISTS),
    ALBUMS("Albums", CollectionKind.ALBUMS),
    CHANNELS("Channels", CollectionKind.CHANNELS),
}

/** What one submit of the search box fetches. */
sealed interface SearchRequest {
    /** A playlist link: the whole playlist, playable as itself. */
    data class PlaylistLink(val playlistId: String) : SearchRequest

    /** A video link: that one video. */
    data class VideoLink(val videoId: String) : SearchRequest

    /** Search terms under the Songs filter. */
    data class Songs(val query: String) : SearchRequest

    /** Search terms under the Playlists, Albums or Channels filter. */
    data class Collections(val query: String, val kind: CollectionKind) : SearchRequest
}

/**
 * What [input] asks for under [filter]; null for a blank box. A link ignores the filter: a
 * playlist link lists the playlist, a video link that video, whatever chip is on. Playlist wins
 * over video for a watch URL carrying `&list=` - pasting a song "from" a playlist usually means
 * the playlist.
 */
fun searchRequest(input: String, filter: SearchFilter): SearchRequest? {
    val query = input.trim()
    if (query.isEmpty()) return null
    YouTubeUrls.parsePlaylistId(query)?.let { return SearchRequest.PlaylistLink(it) }
    YouTubeUrls.parseVideoId(query)?.let { return SearchRequest.VideoLink(it) }
    val kind = filter.collectionKind ?: return SearchRequest.Songs(query)
    return SearchRequest.Collections(query, kind)
}

/** A collection row's second line: the owner or artist, then the size when YouTube gave one.
 *  Null when neither is known (a searched channel usually). */
fun collectionDetails(collection: YtCollection): String? {
    val size = collection.itemCount?.let { if (it == 1L) "1 song" else "$it songs" }
    return listOfNotNull(collection.uploader, size).joinToString(" · ").ifEmpty { null }
}
