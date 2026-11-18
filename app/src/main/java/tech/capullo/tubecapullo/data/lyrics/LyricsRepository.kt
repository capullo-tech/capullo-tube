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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.capullo.tubecapullo.data.history.LyricsDao
import tech.capullo.tubecapullo.data.history.LyricsEntity
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LyricsState {
    val videoId: String

    data class Loading(override val videoId: String) : LyricsState

    data class Done(
        override val videoId: String,
        val lyrics: Lyrics?,
        val offsetMs: Long = 0,
        val failed: Boolean = false,
    ) : LyricsState
}

@Singleton
class LyricsRepository @Inject constructor(
    private val dao: LyricsDao,
    private val lrclib: LrclibClient,
    private val extractor: YouTubeExtractorClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<LyricsState?>(null)

    val current: StateFlow<LyricsState?> = _current.asStateFlow()

    private var lookup: Job? = null

    private val offsetWrites = Mutex()

    fun onTrackStarted(video: YtVideo) {
        lookup?.cancel()
        _current.value = LyricsState.Loading(video.videoId)
        lookup = scope.launch {
            val done = load(video)
            _current.compareAndSet(LyricsState.Loading(video.videoId), done)
        }
    }

    fun retryFailed(video: YtVideo) {
        val done = _current.value as? LyricsState.Done ?: return
        if (done.failed && done.videoId == video.videoId) onTrackStarted(video)
    }

    fun nudge(videoId: String, deltaMs: Long) {
        scope.launch {
            offsetWrites.withLock {
                val done = (_current.value as? LyricsState.Done)
                    ?.takeIf { it.videoId == videoId && it.lyrics?.synced == true }
                    ?: return@withLock
                val offset = (done.offsetMs + deltaMs).coerceIn(-MAX_SONG_OFFSET_MS, MAX_SONG_OFFSET_MS)
                if (!_current.compareAndSet(done, done.copy(offsetMs = offset))) return@withLock
                try {
                    dao.setOffset(videoId, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "lyrics id=$videoId offset write failed", e)
                }
            }
        }
    }

    private suspend fun load(video: YtVideo): LyricsState.Done {
        val started = SystemClock.elapsedRealtime()
        try {
            dao.deleteUnplayed(keep = video.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics id=${video.videoId} cache purge failed", e)
        }
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
            return LyricsState.Done(video.videoId, lyrics, cached.offsetMs)
        }
        val query = LyricsQuery.of(video.title, video.uploader)
        val queryText = "q=\"${query?.artist.orEmpty()} | ${query?.title.orEmpty()}\""
        val found = fetch(video, query)
        val entity = found.entity ?: entity(video, KIND_NONE, null, null)
        if (found.cacheable) {
            try {
                dao.upsert(entity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lyrics id=${video.videoId} cache write failed", e)
            }
        }
        val lyrics = entity.toLyrics()
        log(video.videoId, lyrics, started, "cache=miss $queryText ${found.note}")
        return LyricsState.Done(video.videoId, lyrics, entity.offsetMs, failed = found.failed && lyrics == null)
    }

    private suspend fun fetch(video: YtVideo, query: LyricsQuery?): Found {
        if (video.durationSeconds !in 1..StationEngine.MAX_TRACK_SECONDS) {
            return Found(null, cacheable = false, failed = false, note = "skipped=duration")
        }
        var cacheable = true
        var failed = false
        var note = ""
        if (query != null && !settings.lyricsOnline.value) {
            cacheable = false
            note = "lrclib=off"
        } else if (query != null) {
            val match = try {
                lrclib.find(query, video.durationSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cacheable = false
                failed = true
                note = "lrclib-error=\"${e.message ?: e::class.simpleName}\""
                null
            }
            if (match?.record?.instrumental == true) {
                return Found(null, cacheable = true, failed = false, note = "instrumental=${match.source.logName}")
            }
            match?.let { lrclibEntity(video, it) }?.let { return Found(it, cacheable = true, failed = false, note = note) }
        }
        val description = try {
            extractor.description(video.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = "description-error=\"${e.message ?: e::class.simpleName}\""
            return Found(null, cacheable = false, failed = true, note = "$note $error".trim())
        }
        val entity = DescriptionLyrics.extract(description)?.let {
            entity(video, KIND_PLAIN, LyricsSource.DESCRIPTION, it)
        }
        return Found(entity, cacheable = cacheable, failed = failed, note = note)
    }

    private fun lrclibEntity(video: YtVideo, match: LrclibMatch): LyricsEntity? {
        val record = match.record
        val lrc = record.syncedLyrics?.takeIf { Lyrics.synced(match.source, it) != null }
        if (lrc != null) return entity(video, KIND_SYNCED, match.source, lrc)
        val plain = record.plainLyrics?.takeIf { it.isNotBlank() }
            ?: record.syncedLyrics?.let(Lrc::plainText)?.takeIf { it.isNotBlank() }
            ?: return null
        return entity(video, KIND_PLAIN, match.source, plain)
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
                "ms=${SystemClock.elapsedRealtime() - startedMs} ${rest.trim()}",
        )
    }

    private class Found(val entity: LyricsEntity?, val cacheable: Boolean, val failed: Boolean, val note: String)

    internal companion object {

        const val LYRICS_RETRY_DAYS = 7L

        const val MAX_SONG_OFFSET_MS = 60_000L

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
