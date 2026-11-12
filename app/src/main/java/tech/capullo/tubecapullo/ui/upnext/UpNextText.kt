package tech.capullo.tubecapullo.ui.upnext

import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.LikeEffect
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.RollOver
import tech.capullo.tubecapullo.station.Why
import tech.capullo.tubecapullo.util.TitleCleaner

/** The Player's top-bar title: [word] is set bold, [name] in normal weight. Every source has
 *  both: Radio, Playlist, Channel or Album, then its name. */
data class SourceTitle(val word: String, val name: String)

/**
 * Display text for the station: the Player's source title (also under the Queue sheet's title),
 * the Queue's why lines and the like chip. Pure, so the wording rules are unit-tested; every
 * title goes through [TitleCleaner] (display only, the raw title stays the record).
 */
object UpNextText {

    /** YouTube's auto-generated artist channels are "<Artist> - Topic". Mirrors
     *  TitleCleaner.TOPIC_SUFFIX (private there); keep the two in step. */
    private const val TOPIC_SUFFIX = " - Topic"

    /** The name of the likes list, in the Player title of Liked and of Liked radio. */
    private const val LIKED_SONGS = "Liked songs"

    fun sourceTitle(source: PlaySource): SourceTitle = when (source) {
        // A radio is named after its origin (owner call 2026-11-12, replacing the bare word of
        // 2026-11-11): the base list is generated from the seed; Spotify names radios after the
        // seed and keeps the name as the station drifts. So the name never follows a chain, a
        // like or a Switch now; only a new station (a start, a roll-over) renames it.
        is PlaySource.SongRadio -> SourceTitle("Radio", clean(source.seed))
        is PlaySource.SearchRadio -> SourceTitle("Radio", "\"${source.query}\"")
        is PlaySource.Playlist -> SourceTitle("Playlist", source.title)
        is PlaySource.Channel -> SourceTitle("Channel", source.name)
        // Liked is a list like any playlist (owner call 2026-11-12).
        is PlaySource.Liked -> SourceTitle("Playlist", LIKED_SONGS)
        PlaySource.LikedRadio -> SourceTitle("Radio", LIKED_SONGS)
        is PlaySource.Album -> SourceTitle("Album", clean(source.video))
    }

    /** Where [video] came from, in the listener's words. The seed is the song the listener
     *  tapped, so it reads as their own pick. */
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

    /** The like chip: what a like did to the queue, once the liked song's fetch landed. Nothing
     *  added reads the same in either mode: the queue stayed as it was. */
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

    /** The roll chip (PLAN "Home v2 and albums"): a playlist, channel or album ended and the
     *  station goes on as a radio from it. */
    fun rollChip(rollOver: RollOver): String = "${sourceTitle(rollOver.from).word} ended · continuing as Radio"

    /** The uploader as a channel name for display and search: "Daft Punk - Topic" gives
     *  "Daft Punk". */
    fun channelName(uploader: String): String = uploader.trim().removeSuffix(TOPIC_SUFFIX).trim().ifEmpty { uploader }

    fun clean(video: YtVideo): String = TitleCleaner.clean(video.title, video.uploader)
}
