package tech.capullo.tubecapullo.ui.search

import tech.capullo.tubecapullo.data.youtube.CollectionKind
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtCollection

enum class SearchFilter(val label: String, val collectionKind: CollectionKind?) {
    SONGS("Songs", null),
    PLAYLISTS("Playlists", CollectionKind.PLAYLISTS),
    ALBUMS("Albums", CollectionKind.ALBUMS),
    CHANNELS("Channels", CollectionKind.CHANNELS),
}

sealed interface SearchRequest {
    data class PlaylistLink(val playlistId: String) : SearchRequest

    data class VideoLink(val videoId: String) : SearchRequest

    data class Songs(val query: String) : SearchRequest

    data class Collections(val query: String, val kind: CollectionKind) : SearchRequest
}

fun searchRequest(input: String, filter: SearchFilter): SearchRequest? {
    val query = input.trim()
    if (query.isEmpty()) return null
    YouTubeUrls.parsePlaylistId(query)?.let { return SearchRequest.PlaylistLink(it) }
    YouTubeUrls.parseVideoId(query)?.let { return SearchRequest.VideoLink(it) }
    val kind = filter.collectionKind ?: return SearchRequest.Songs(query)
    return SearchRequest.Collections(query, kind)
}

fun collectionDetails(collection: YtCollection): String? {
    val size = collection.itemCount?.let { if (it == 1L) "1 song" else "$it songs" }
    return listOfNotNull(collection.uploader, size).joinToString(" · ").ifEmpty { null }
}
