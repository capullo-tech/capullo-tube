package tech.capullo.tubecapullo.ui.upnext

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

    fun provenance(why: Why, video: YtVideo): String {
        val from = why.fromTitle?.let { TitleCleaner.clean(it, null) }
        return when (why.provenance) {
            Provenance.SEED, Provenance.USER -> "You picked this"
            Provenance.MIX -> if (from != null) "From the Mix of $from" else "From a Mix"
            Provenance.RELATED -> if (from != null) "Related to $from" else "Related"
            Provenance.LIKED -> "Your like"
            Provenance.SEARCH -> "From your search"
            Provenance.PLAYLIST -> why.fromTitle?.let { "From $it" } ?: "From a playlist"
            Provenance.CHANNEL -> video.uploader?.let { "From ${channelName(it)}" } ?: "From the channel"
            Provenance.ALBUM -> "The album"
        }
    }

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
