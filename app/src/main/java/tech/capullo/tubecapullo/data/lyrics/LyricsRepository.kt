package tech.capullo.tubecapullo.data.lyrics

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.history.LyricsDao
import tech.capullo.tubecapullo.data.history.LyricsEntity
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LyricsState {
    val videoId: String

    data class Loading(override val videoId: String) : LyricsState

    data class Done(override val videoId: String, val lyrics: Lyrics?) : LyricsState
}

@Singleton
class LyricsRepository @Inject constructor(
    private val dao: LyricsDao,
    private val lrclib: LrclibClient,
    private val extractor: YouTubeExtractorClient,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<LyricsState?>(null)

    val current: StateFlow<LyricsState?> = _current.asStateFlow()

    private var lookup: Job? = null

    fun onTrackStarted(video: YtVideo) {
        lookup?.cancel()
        _current.value = LyricsState.Loading(video.videoId)
        lookup = scope.launch {
            val lyrics = load(video)
            _current.compareAndSet(
                LyricsState.Loading(video.videoId),
                LyricsState.Done(video.videoId, lyrics),
            )
        }
    }

    private suspend fun load(video: YtVideo): Lyrics? {
        val started = SystemClock.elapsedRealtime()
        val cached = try {
            dao.byId(video.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics id=${video.videoId} cache read failed", e)
            null
        }
        if (cached != null && isFresh(cached, System.currentTimeMillis())) {
            val lyrics = cached.toLyrics()
            log(video.videoId, lyrics, started, "cache=hit")
            return lyrics
        }
        val query = LyricsQuery.of(video.title, video.uploader)
        val queryText = "q=\"${query?.artist.orEmpty()} | ${query?.title.orEmpty()}\""
        val found = try {
            fetch(video, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.message ?: e::class.simpleName
            log(video.videoId, null, started, "cache=miss $queryText error=\"$error\"")
            return null
        }
        val entity = found ?: entity(video, KIND_NONE, null, null)
        try {
            dao.upsert(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics id=${video.videoId} cache write failed", e)
        }
        val lyrics = entity.toLyrics()
        log(video.videoId, lyrics, started, "cache=miss $queryText")
        return lyrics
    }

    private suspend fun fetch(video: YtVideo, query: LyricsQuery?): LyricsEntity? {
        if (query != null && video.durationSeconds in 1..StationEngine.MAX_TRACK_SECONDS) {
            lrclib.find(query, video.durationSeconds)?.let { match ->
                val record = match.record
                val synced = record.syncedLyrics?.takeIf { Lrc.parse(it).isNotEmpty() }
                val text = synced ?: record.plainLyrics
                if (!text.isNullOrBlank()) {
                    val kind = if (synced != null) KIND_SYNCED else KIND_PLAIN
                    return entity(video, kind, match.source, text)
                }
            }
        }
        return DescriptionLyrics.extract(extractor.description(video.videoId))?.let {
            entity(video, KIND_PLAIN, LyricsSource.DESCRIPTION, it)
        }
    }

    private fun entity(
        video: YtVideo,
        kind: String,
        source: LyricsSource?,
        text: String?,
    ): LyricsEntity {
        val now = System.currentTimeMillis()
        return LyricsEntity(video.videoId, kind, source?.logName, text, now)
    }

    private fun log(videoId: String, lyrics: Lyrics?, startedMs: Long, rest: String) {
        Log.i(
            TAG,
            "lyrics id=$videoId source=${lyrics?.source?.logName ?: "none"} " +
                "synced=${lyrics?.synced ?: false} " +
                "ms=${SystemClock.elapsedRealtime() - startedMs} $rest",
        )
    }

    internal companion object {

        const val LYRICS_RETRY_DAYS = 7L

        const val KIND_SYNCED = "synced"
        const val KIND_PLAIN = "plain"
        const val KIND_NONE = "none"

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private const val TAG = "TubeLyrics"

        fun isFresh(entity: LyricsEntity, nowMs: Long): Boolean = entity.kind != KIND_NONE ||
            nowMs - entity.fetchedAtEpochMs < LYRICS_RETRY_DAYS * DAY_MS

        fun LyricsEntity.toLyrics(): Lyrics? {
            val source = source?.let(LyricsSource::fromLogName) ?: return null
            val text = text ?: return null
            return when (kind) {
                KIND_SYNCED -> Lyrics.synced(source, text)
                KIND_PLAIN -> Lyrics.plain(source, text)
                else -> null
            }
        }
    }
}
