package tech.capullo.tubecapullo.data.similar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.util.TitleCleaner
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Last.fm's names for a seed. [via] says how they were found, for the station log:
 * `track` (the parsed names), `retry` (the bare names), `artist` (similar artists' top songs),
 * or why there are none (`off`, `no-key`, `unparsed`, `none`, `error`).
 */
data class SimilarNames(val tracks: List<SimilarTrack>, val via: String)

data class SimilarFind(val videos: List<YtVideo>, val tried: Int, val searched: Int, val skippedKnown: Int)

/** A third radio list beside YouTube's Mix and related videos: songs Last.fm finds similar. */
interface SimilarFinder {
    /** Last.fm only, no YouTube call: safe to run beside the Mix and related fetches. */
    suspend fun names(seed: YtVideo): SimilarNames

    /**
     * Up to [count] YouTube videos for [names], skipping songs already in [knownIds] or whose
     * cleaned title is in [knownKeys] (the seed's Mix and related): this list exists to add songs
     * YouTube did not offer.
     */
    suspend fun resolve(names: SimilarNames, knownIds: Set<String>, knownKeys: Set<String>, count: Int): SimilarFind
}

@Singleton
class LastfmSimilarFinder @Inject constructor(
    private val lastfm: LastfmClient,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    @param:Named(BUILT_IN_KEY) private val builtInKey: String,
) : SimilarFinder {

    override suspend fun names(seed: YtVideo): SimilarNames {
        if (!settings.similarOnline.value) return SimilarNames(emptyList(), "off")
        val key = lastfmKeyOf(settings.lastfmApiKey.value, builtInKey) ?: return SimilarNames(emptyList(), "no-key")
        val queries = SeedNames.queries(seed)
        if (queries.isEmpty()) return SimilarNames(emptyList(), "unparsed")
        val tracks = try {
            byTrack(queries, key)
                ?: queries.lastOrNull { it.trusted }?.let { byArtist(it.artist, key) }
                ?: SimilarNames(emptyList(), "none")
        } catch (e: Exception) {
            return SimilarNames(emptyList(), "error")
        }
        return tracks.copy(tracks = depthOrder(tracks.tracks, settings.similarDepth.value))
    }

    private suspend fun byTrack(queries: List<NameQuery>, key: String): SimilarNames? {
        queries.forEachIndexed { i, query ->
            val tracks = lastfm.similarTracks(query.artist, query.title, SIMILAR_NAMES, key)
            if (tracks.size >= MIN_SIMILAR_NAMES) return SimilarNames(tracks, if (i == 0) "track" else "retry")
        }
        return null
    }

    /** The probe's misses were songs Last.fm did not know; their artist it usually does. */
    private suspend fun byArtist(artist: String, key: String): SimilarNames {
        val artists = lastfm.similarArtists(artist, FALLBACK_ARTISTS, key)
        if (artists.isEmpty()) return SimilarNames(emptyList(), "none")
        val tops = artists.map { similar ->
            lastfm.topTracks(similar.name, FALLBACK_TOP_TRACKS, key).map { it.copy(match = similar.match) }
        }
        // One song per artist per round, so the list does not open with one artist's hits.
        val tracks = (0 until FALLBACK_TOP_TRACKS).flatMap { rank -> tops.mapNotNull { it.getOrNull(rank) } }
        return SimilarNames(tracks, if (tracks.isEmpty()) "none" else "artist")
    }

    override suspend fun resolve(
        names: SimilarNames,
        knownIds: Set<String>,
        knownKeys: Set<String>,
        count: Int,
    ): SimilarFind {
        if (count <= 0) return SimilarFind(emptyList(), 0, 0, 0)
        val seen = HashSet(knownIds)
        val keys = HashSet(knownKeys)
        var skippedKnown = 0
        val candidates = names.tracks.filter { track ->
            val known = SeedNames.key(track.title) in keys ||
                SeedNames.key(SeedNames.bareTitle(track.title)) in keys ||
                SeedNames.key(track.artist + track.title) in keys
            if (known) skippedKnown++
            !known
        }
        val found = mutableListOf<YtVideo>()
        var tried = 0
        var searched = 0
        var next = 0
        // Batches of the missing count, resolved together so a radio start waits for one round
        // of searches, not one per song.
        while (found.size < count && next < candidates.size && searched < count * RESOLVE_ATTEMPTS_PER_PICK) {
            val batch = candidates.subList(next, minOf(next + count - found.size, candidates.size))
            next += batch.size
            tried += batch.size
            val answers = coroutineScope {
                batch.map { track -> async { runCatching { resolver.resolve(track.artist, track.title) }.getOrNull() } }.awaitAll()
            }
            answers.forEach { answer ->
                if (answer == null) return@forEach
                if (answer.searched) searched++
                val video = answer.video ?: return@forEach
                val key = TitleCleaner.key(video.title, video.uploader)
                if (found.size < count && seen.add(video.videoId) && (key == null || keys.add(key))) found += video
            }
        }
        return SimilarFind(found, tried, searched, skippedKnown)
    }

    companion object {
        const val BUILT_IN_KEY = "lastfm_api_key"

        /** Last.fm returns up to this many names per call; the probe asked for 40. */
        internal const val SIMILAR_NAMES = 40

        /** GUESS: fewer names than this is a weak answer; try the next query. */
        internal const val MIN_SIMILAR_NAMES = 5

        /** GUESS: similar artists asked for when the song is unknown to Last.fm. */
        internal const val FALLBACK_ARTISTS = 6

        /** GUESS: top songs per similar artist. */
        internal const val FALLBACK_TOP_TRACKS = 3

        /** GUESS: YouTube searches allowed per wanted song before giving up on this seed. */
        internal const val RESOLVE_ATTEMPTS_PER_PICK = 2
    }
}

/** A personal key replaces the built-in one; with neither, similar songs are off. */
internal fun lastfmKeyOf(personal: String, builtIn: String): String? = personal.trim().ifEmpty { builtIn.trim() }.ifEmpty { null }

/** Last.fm API keys are 32 hex characters. */
internal fun isLastfmKeyShape(key: String): Boolean = key.length == 32 && key.all { it in '0'..'9' || it in 'a'..'f' }
