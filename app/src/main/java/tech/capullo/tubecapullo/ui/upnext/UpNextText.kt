package tech.capullo.tubecapullo.ui.upnext

import tech.capullo.tubecapullo.data.lyrics.LyricsQuery
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.LikeEffect
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.RollOver
import tech.capullo.tubecapullo.station.Why
import tech.capullo.tubecapullo.util.TitleCleaner

data class SourceTitle(val word: String, val name: String)

object UpNextText {
    private const val TOPIC_SUFFIX = " - Topic"

    private const val LIKED_SONGS = "Liked songs"

    fun sourceTitle(source: PlaySource): SourceTitle = when (source) {
        is PlaySource.SongRadio -> SourceTitle("Radio", clean(source.seed))
        is PlaySource.SearchRadio -> SourceTitle("Radio", "\"${source.query}\"")
        is PlaySource.Playlist -> SourceTitle("Playlist", source.title)
        is PlaySource.Channel -> SourceTitle("Channel", source.name)
        is PlaySource.Liked -> SourceTitle("Playlist", LIKED_SONGS)
        PlaySource.LikedRadio -> SourceTitle("Radio", LIKED_SONGS)
        is PlaySource.Album -> SourceTitle("Album", clean(source.video))
    }

    /**
     * The reason line of an Up next row. Radio picks name their source (YouTube's Mix, its
     * related videos, Last.fm's similar songs) and add the song they came from only when it
     * is not the station's own seed, which the sheet's title already names. Songs a like
     * brought add which like: "Similar · ♥ Feel Good Inc.".
     */
    fun provenance(why: Why, video: YtVideo, stationSeedId: String? = null, blended: Boolean = false): String {
        val song = why.fromTitle?.let(::songName)
        return when (why.provenance) {
            Provenance.SEED, Provenance.USER -> "You picked this"
            Provenance.MIX, Provenance.RELATED, Provenance.SIMILAR -> {
                val source = when (why.provenance) {
                    Provenance.MIX -> "Mix"
                    Provenance.RELATED -> "Related"
                    else -> "Similar"
                }
                when {
                    song == null -> source
                    blended -> "$source · ♥ $song"
                    why.fromVideoId != null && why.fromVideoId == stationSeedId -> source
                    else -> "$source · $song"
                }
            }
            Provenance.LIKED -> "Your like"
            Provenance.SEARCH -> "From your search"
            Provenance.PLAYLIST -> why.fromTitle?.let { "From $it" } ?: "From a playlist"
            Provenance.CHANNEL -> video.uploader?.let { "From ${channelName(it)}" } ?: "From the channel"
            Provenance.ALBUM -> "The album"
        }
    }

    /** The video a station grows from, when it has one. */
    fun stationSeedId(source: PlaySource?): String? = when (source) {
        is PlaySource.SongRadio -> source.seed.videoId
        is PlaySource.SearchRadio -> source.results.firstOrNull()?.videoId
        is PlaySource.Album -> source.video.videoId
        else -> null
    }

    /** "Radiohead - Creep (Official Video)" -> "Creep". */
    private fun songName(title: String): String = LyricsQuery.of(title, null)?.title ?: TitleCleaner.clean(title, null)

    fun likeChip(effect: LikeEffect): String {
        val song = clean(effect.seed)
        val added = when (effect) {
            is LikeEffect.Blended -> effect.added
            is LikeEffect.Switched -> effect.added
        }
        return when {
            added == 0 -> "No new songs like $song"
            effect is LikeEffect.Blended ->
                if (added == 1) "Added 1 song like $song" else "Added $added songs like $song"
            else -> "Switching to songs like $song"
        }
    }

    fun rollChip(rollOver: RollOver): String = "${sourceTitle(rollOver.from).word} ended · continuing as Radio"

    fun channelName(uploader: String): String = uploader.trim().removeSuffix(TOPIC_SUFFIX).trim().ifEmpty { uploader }

    fun clean(video: YtVideo): String = TitleCleaner.clean(video.title, video.uploader)
}
