package tech.capullo.tubecapullo.data.similar

import tech.capullo.tubecapullo.data.lyrics.LyricsQuery
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.inTrackWindow
import tech.capullo.tubecapullo.util.TitleCleaner

/**
 * The search result that is the song Last.fm named, or null. The title must match (a longer
 * title may only add text after it), the artist must appear in the parsed artist or the
 * uploader, and the length must fit a radio track. Among matches a studio upload beats a live,
 * cover, karaoke or sped-up one, unless Last.fm asked for that version.
 */
internal fun pickResolution(results: List<YtVideo>, artist: String, title: String): YtVideo? = results
    .filter { it.inTrackWindow() && namesMatch(it, artist, title) }
    .minByOrNull { versionPenalty(it.title, title) }

internal fun namesMatch(video: YtVideo, artist: String, title: String): Boolean = SongNames(video).matches(artist, title)

/**
 * A video's title and artist keys, parsed once. A Last.fm name matches when the video's song
 * title starts with the named title (or its bare form) and the named artist appears in the
 * parsed artist, the uploader or the title. Re-uploads such as "*LAMENTO BOLIVIANO* -
 * ENANITOS VERDES - 1994" match "Enanitos Verdes - Lamento Boliviano".
 */
internal class SongNames(video: YtVideo) {
    private val titles: List<String>
    private val artists: String

    init {
        val parsed = LyricsQuery.of(video.title, video.uploader)
        titles = listOfNotNull(parsed?.title, TitleCleaner.clean(video.title, video.uploader)).map(SeedNames::key)
        artists = listOfNotNull(parsed?.artist, video.uploader?.let(TitleCleaner::artistName), video.title)
            .joinToString("|") { SeedNames.key(it) }
    }

    fun matches(artist: String, title: String): Boolean = matches(SongKey(artist, title))

    fun matches(song: SongKey): Boolean {
        if (song.title.isEmpty() || song.artist.isEmpty()) return false
        val titleOk = titles.any { got -> got.startsWith(song.title) || (song.bare.isNotEmpty() && got.startsWith(song.bare)) }
        return titleOk && song.artist in artists
    }
}

/** A named song's match keys, computed once. */
internal class SongKey(artist: String, title: String) {
    val title = SeedNames.key(title)
    val bare = SeedNames.key(SeedNames.bareTitle(title))
    val artist = SeedNames.key(SeedNames.firstArtist(artist))

    companion object {
        /** The song a video is, when its title names an artist; null when it cannot tell. */
        fun of(video: YtVideo): SongKey? = LyricsQuery.of(video.title, video.uploader)
            ?.let { q -> q.artist?.let { SongKey(it, q.title) } }
    }
}

/**
 * True when [a] and [b] are different versions: one is marked live, acoustic, a remix, a
 * cover and so on, and the other is not. A lyric video or a re-upload is the same version.
 */
internal fun differentVersions(a: String, b: String): Boolean = versionPenalty(a, b) > 0 || versionPenalty(b, a) > 0

internal fun versionPenalty(videoTitle: String, wanted: String): Int {
    val got = videoTitle.lowercase()
    val want = wanted.lowercase()
    return UNWANTED_VERSIONS.count { it.containsMatchIn(got) && !it.containsMatchIn(want) }
}

private val UNWANTED_VERSIONS = listOf(
    "live", "en vivo", "ao vivo", "cover", "karaoke", "sped up", "slowed", "reverb", "nightcore",
    "8d", "instrumental", "remix", "acoustic", "acústico", "concert", "concierto",
).map { Regex("""(?<![\p{L}\p{N}])${Regex.escape(it)}(?![\p{L}\p{N}])""") }
