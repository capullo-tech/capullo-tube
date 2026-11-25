package tech.capullo.tubecapullo.data.quantumcast

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuantumCast's read-only playlists provider: one playlist per radio station, holding the songs
 * QuantumCast identified on it. The read permission, the package visibility and the hand-off
 * intent filter live in AndroidManifest.xml. No Android types here, so link parsing can use it
 * in JVM tests.
 */
object QuantumCastContract {
    const val AUTHORITY = "tech.capullo.quantumcast.playlists"

    const val PLAYLISTS_PATH = "playlists"
    const val TRACKS_PATH = "tracks"

    /** Restricts a track query to rows whose youtube_url is a video link. */
    const val LINK_PARAM = "link"
    const val LINK_YOUTUBE = "youtube"

    const val ID = "_id"
    const val NAME = "name"
    const val COUNTRY_CODE = "country_code"
    const val FAVICON = "favicon"
    const val TRACK_COUNT = "track_count"
    const val YOUTUBE_COUNT = "youtube_count"
    const val UPDATED_AT = "updated_at"

    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ARTWORK_URL = "artwork_url"
    const val YOUTUBE_URL = "youtube_url"
    const val STATION_NAME = "station_name"

    private val TRACKS_LINK = Regex(
        """^content://${Regex.escape(AUTHORITY)}/$PLAYLISTS_PATH/(\d+)/$TRACKS_PATH$""",
    )

    /** The playlist id of a `content://…/playlists/{id}/tracks` link, the hand-off QuantumCast's Play sends. */
    fun playlistIdOfTracksLink(link: String): Long? = TRACKS_LINK.find(link.substringBefore('?'))
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
}

data class RadioPlaylist(
    val id: Long,
    val name: String,
    val countryCode: String,
    val favicon: String?,
    val trackCount: Int,
    val playableCount: Int,
    val updatedAtEpochMs: Long,
) {
    /** The station name, or [FALLBACK_TITLE] for a station QuantumCast has no name for. */
    val title: String get() = name.ifBlank { FALLBACK_TITLE }

    companion object {
        const val FALLBACK_TITLE = "QuantumCast"
    }
}

/** One song row of a QuantumCast playlist, as the provider returns it. */
data class RadioTrackRow(
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val youtubeUrl: String?,
    val stationName: String? = null,
)

/** A playlist as QuantumCast's Play handed it over: the station's name and its playable songs. */
data class RadioHandOff(val title: String, val videos: List<YtVideo>)

// Any app can claim the provider's authority where QuantumCast is missing, so its strings are
// cut to a size that keeps the station snapshots, saved every few seconds, small.
internal const val MAX_TEXT_CHARS = 200
private const val MAX_URL_CHARS = 2048

/**
 * The playable songs of [rows], newest first: rows whose youtube_url is only a search link are
 * skipped, and a video identified more than once keeps its newest row. The provider does not know
 * a song's length, so durationSeconds is 0, which the app reads as unknown.
 */
internal fun radioVideos(rows: List<RadioTrackRow>): List<YtVideo> {
    val seen = HashSet<String>()
    return rows.mapNotNull { row ->
        val videoId = row.youtubeUrl?.let(YouTubeUrls::parseVideoId) ?: return@mapNotNull null
        if (!seen.add(videoId)) return@mapNotNull null
        YtVideo(
            videoId = videoId,
            title = row.title.take(MAX_TEXT_CHARS),
            uploader = row.artist?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_CHARS),
            durationSeconds = 0,
            thumbnailUrl = row.artworkUrl?.takeIf(::isWebUrl)
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )
    }
}

/** The hand-off read: the station name of the first row that has one, and the playable songs. */
internal fun radioHandOff(rows: List<RadioTrackRow>): RadioHandOff = RadioHandOff(
    title = rows.firstNotNullOfOrNull { row -> row.stationName?.takeIf { it.isNotBlank() } }
        ?.take(MAX_TEXT_CHARS)
        ?: RadioPlaylist.FALLBACK_TITLE,
    videos = radioVideos(rows),
)

private fun isWebUrl(url: String): Boolean = url.length <= MAX_URL_CHARS && (url.startsWith("https://") || url.startsWith("http://"))

/**
 * Reads QuantumCast's playlists. QuantumCast may be missing, too old to have the provider, or have
 * no playlists yet; each of those reads as an empty result.
 */
@Singleton
class QuantumCastPlaylists @Inject constructor(@ApplicationContext private val context: Context) {
    private val playlistsUri: Uri = Uri.Builder()
        .scheme("content")
        .authority(QuantumCastContract.AUTHORITY)
        .appendPath(QuantumCastContract.PLAYLISTS_PATH)
        .build()

    // The last failed read's reason, so a provider that stays unavailable logs once rather than
    // on every Library refresh.
    @Volatile private var lastProblem: String? = null

    /** The playlists, newest activity first, read again whenever QuantumCast reports a change. */
    val changes: Flow<List<RadioPlaylist>> = callbackFlow<Unit> {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val watching = try {
            context.contentResolver.registerContentObserver(playlistsUri, true, observer)
            true
        } catch (e: SecurityException) {
            // Android throws here when no installed app provides the authority.
            report("observe", "error=security ${e.message}")
            false
        }
        trySend(Unit)
        awaitClose {
            if (watching) context.contentResolver.unregisterContentObserver(observer)
        }
    }.conflate().map { playlists() }

    suspend fun playlists(): List<RadioPlaylist> = query("playlists", playlistsUri, PLAYLIST_COLUMNS, ::readPlaylists).orEmpty()

    /** Playlist [playlistId] with its station name and song counts, or null when QuantumCast does not have it. */
    suspend fun playlist(playlistId: Long): RadioPlaylist? {
        val rows = query("playlist id=$playlistId", playlistUri(playlistId), PLAYLIST_COLUMNS, ::readPlaylists)
        return rows?.firstOrNull()
    }

    /** The playlist's songs that have a YouTube video link, last detected first. */
    suspend fun videos(playlistId: Long): List<YtVideo> {
        val uri = tracksUri(playlistId).buildUpon()
            .appendQueryParameter(QuantumCastContract.LINK_PARAM, QuantumCastContract.LINK_YOUTUBE)
            .build()
        val rows = query("tracks id=$playlistId", uri, TRACK_COLUMNS, ::readTracks)
        return radioVideos(rows.orEmpty())
    }

    /**
     * What QuantumCast's Play handed over, or null when it cannot be read. It reads exactly the
     * URI QuantumCast granted, `playlists/{id}/tracks` without a query, so it works on that grant
     * alone when the read permission was never granted (CapulloTube installed first).
     */
    suspend fun handedOver(playlistId: Long): RadioHandOff? = query("hand-off id=$playlistId", tracksUri(playlistId), TRACK_COLUMNS, ::readTracks)?.let(::radioHandOff)

    private fun tracksUri(playlistId: Long): Uri = playlistUri(playlistId).buildUpon()
        .appendPath(QuantumCastContract.TRACKS_PATH)
        .build()

    private fun playlistUri(playlistId: Long): Uri = playlistsUri.buildUpon()
        .appendPath(playlistId.toString())
        .build()

    private fun readPlaylists(cursor: Cursor): List<RadioPlaylist> {
        val id = cursor.getColumnIndexOrThrow(QuantumCastContract.ID)
        val name = cursor.getColumnIndexOrThrow(QuantumCastContract.NAME)
        val country = cursor.getColumnIndexOrThrow(QuantumCastContract.COUNTRY_CODE)
        val favicon = cursor.getColumnIndexOrThrow(QuantumCastContract.FAVICON)
        val tracks = cursor.getColumnIndexOrThrow(QuantumCastContract.TRACK_COUNT)
        val youtube = cursor.getColumnIndexOrThrow(QuantumCastContract.YOUTUBE_COUNT)
        val updated = cursor.getColumnIndexOrThrow(QuantumCastContract.UPDATED_AT)
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    RadioPlaylist(
                        id = cursor.getLong(id),
                        name = cursor.getString(name).orEmpty().take(MAX_TEXT_CHARS),
                        countryCode = cursor.getString(country).orEmpty(),
                        favicon = cursor.getString(favicon)?.takeIf { it.isNotBlank() },
                        trackCount = cursor.getInt(tracks),
                        playableCount = cursor.getInt(youtube),
                        updatedAtEpochMs = cursor.getLong(updated),
                    ),
                )
            }
        }
    }

    private fun readTracks(cursor: Cursor): List<RadioTrackRow> {
        val title = cursor.getColumnIndexOrThrow(QuantumCastContract.TITLE)
        val artist = cursor.getColumnIndexOrThrow(QuantumCastContract.ARTIST)
        val artwork = cursor.getColumnIndexOrThrow(QuantumCastContract.ARTWORK_URL)
        val youtube = cursor.getColumnIndexOrThrow(QuantumCastContract.YOUTUBE_URL)
        val station = cursor.getColumnIndexOrThrow(QuantumCastContract.STATION_NAME)
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    RadioTrackRow(
                        title = cursor.getString(title).orEmpty(),
                        artist = cursor.getString(artist),
                        artworkUrl = cursor.getString(artwork),
                        youtubeUrl = cursor.getString(youtube),
                        stationName = cursor.getString(station),
                    ),
                )
            }
        }
    }

    /**
     * Runs [read] on the provider's cursor, or returns null when the provider is missing (null
     * cursor) or anything goes wrong, from the query through the last row read: a refused read,
     * an unknown URI or column, or an exception the provider sends back through Binder
     * (NullPointerException, UnsupportedOperationException) and errors reading the rows
     * (SQLiteException on a BLOB column, a cursor window that cannot be allocated). Whoever
     * answers for the authority must not be able to crash the app.
     */
    private suspend fun <T> query(
        what: String,
        uri: Uri,
        projection: Array<String>,
        read: (Cursor) -> T,
    ): T? = withContext(Dispatchers.IO) {
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor == null) {
                report(what, "result=unavailable")
                null
            } else {
                cursor.use(read).also { lastProblem = null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(what, "error=${e::class.simpleName} ${e.message}")
            null
        }
    }

    private fun report(what: String, problem: String) {
        if (problem == lastProblem) return
        lastProblem = problem
        Log.i(TAG, "quantumcast read=$what $problem")
    }

    private companion object {
        const val TAG = "TubeQuantumCast"

        val PLAYLIST_COLUMNS = arrayOf(
            QuantumCastContract.ID,
            QuantumCastContract.NAME,
            QuantumCastContract.COUNTRY_CODE,
            QuantumCastContract.FAVICON,
            QuantumCastContract.TRACK_COUNT,
            QuantumCastContract.YOUTUBE_COUNT,
            QuantumCastContract.UPDATED_AT,
        )

        val TRACK_COLUMNS = arrayOf(
            QuantumCastContract.TITLE,
            QuantumCastContract.ARTIST,
            QuantumCastContract.ARTWORK_URL,
            QuantumCastContract.YOUTUBE_URL,
            QuantumCastContract.STATION_NAME,
        )
    }
}
