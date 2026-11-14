package tech.capullo.tubecapullo.ui.home

import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.passesRelatedGate
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import tech.capullo.tubecapullo.util.TitleCleaner

internal const val ALBUM_MIN_SECONDS = 25 * 60L
internal const val ALBUM_MAX_SECONDS = 120 * 60L

internal const val FULL_ALBUM_CARDS = 12

internal const val FULL_ALBUM_SEEDS = 6

internal const val FULL_ALBUM_CHAPTER_CHECKS = 8

internal const val FULL_ALBUMS_MIN_CARDS = FULL_ALBUM_CARDS

internal const val FULL_ALBUM_ARTISTS = 3

internal fun YtVideo.inAlbumWindow(): Boolean = durationSeconds in ALBUM_MIN_SECONDS..ALBUM_MAX_SECONDS

internal fun hasAlbumTitle(title: String): Boolean = ALBUM_WORD.containsMatchIn(title) || ALBUM_SCRIPT_WORDS.any { it in title }

internal fun looksLikeAlbum(video: YtVideo, chaptered: Set<String>): Boolean = hasAlbumTitle(video.title) || video.videoId in chaptered

internal fun artistName(uploader: String): String = UpNextText.channelName(uploader).removeSuffix("VEVO").trim().ifEmpty { uploader }

internal fun fullAlbumCandidates(fetches: List<YtRelated>, excludedIds: Set<String>): List<YtVideo> {
    val lists = fetches.map { fetch ->
        fetch.videos.filter {
            it.inAlbumWindow() && passesRelatedGate(it, fetch.seedViews) && it.videoId !in excludedIds
        }
    }
    val seen = HashSet<String>()
    return buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            lists.forEach { list -> list.getOrNull(rank)?.takeIf { seen.add(it.videoId) }?.let(::add) }
        }
    }
}

internal fun chapterChecks(candidates: List<YtVideo>, limit: Int): List<YtVideo> = candidates.filterNot { hasAlbumTitle(it.title) }.take(limit)

internal fun albumsOf(candidates: List<YtVideo>, chaptered: Set<String>): List<YtVideo> = candidates.filter { looksLikeAlbum(it, chaptered) }

internal data class FullAlbumCards(val cards: List<YtVideo>, val dupes: Int)

internal fun fullAlbumCards(albums: List<YtVideo>, artists: Collection<String>): FullAlbumCards {
    val byAlbum = LinkedHashMap<String, YtVideo>()
    albums.forEach { video ->
        val key = TitleCleaner.albumKey(video.title, video.uploader, artists) ?: "id:${video.videoId}"
        val kept = byAlbum[key]
        if (kept == null || PREFERRED_UPLOAD.compare(video, kept) > 0) byAlbum[key] = video
    }
    return FullAlbumCards(byAlbum.values.take(FULL_ALBUM_CARDS), dupes = albums.size - byAlbum.size)
}

private val PREFERRED_UPLOAD = compareBy<YtVideo>({ it.uploaderVerified }, { it.viewCount })

internal fun fullAlbumArtists(likes: List<YtVideo>): List<String> {
    val counts = LinkedHashMap<String, Int>()
    likes.forEach { like -> like.uploader?.let { artistName(it) }?.let { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts.entries.sortedByDescending { it.value }.take(FULL_ALBUM_ARTISTS).map { it.key }
}

private val ALBUM_WORD = Regex(
    """(?<![\p{L}\p{N}])(albums?|álbum(es)?|albüm|альбом\p{L}*)(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)

private val ALBUM_SCRIPT_WORDS = listOf("アルバム", "앨범", "专辑", "專輯", "ألبوم", "البوم")
