package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole YouTube surface the rest of the app is allowed to touch: search, playlist/mix
 * expansion, related streams and stream resolution. Nothing else in TubeCapullo imports
 * `org.schabi.newpipe.*`, so when extraction breaks (and it will - YouTube rotates its player JS,
 * see the pin comment in pins.versions.toml) the blast radius is this file.
 *
 * Every call suspends onto IO and translates the extractor's dozen exception types into
 * [YouTubeError], which callers can actually branch on.
 *
 * Ported from telecloud-radio's YouTubeExtractorClient; [relatedStreams] and [mix] are new - they
 * are the two feeds the station's candidate pool draws from (HANDOFF-bootstrap §5).
 */
@Singleton
class YouTubeExtractorClient @Inject constructor(
    private val downloader: TubeCapulloDownloader,
) {
    private val initialized = AtomicBoolean(false)

    // Resolved stream URLs, kept until shortly before they expire. Resolution is by far the
    // expensive call (it fetches and runs YouTube's player JS through Rhino), so prefetching the
    // next few tracks is only cheap because of this.
    private val streamCache = mutableMapOf<String, YtStream>()

    /**
     * Search YouTube for videos. Results are metadata only - no stream is resolved, so this stays
     * one HTTP round trip regardless of how many results come back.
     */
    suspend fun search(query: String, limit: Int = 30): List<YtVideo> = extract("search") {
        val info = SearchInfo.getInfo(
            ServiceList.YouTube,
            ServiceList.YouTube.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                "",
            ),
        )
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .take(limit)
            .mapNotNull { it.toYtVideo() }
    }

    /**
     * Expand a playlist URL into its videos, following pagination up to [limit]. Long playlists page
     * 100 at a time; the cap keeps a 5000-track mix from turning one paste into a hundred requests.
     */
    suspend fun playlist(url: String, limit: Int = 200): List<YtVideo> = extract("playlist") {
        val playlistId = YouTubeUrls.parsePlaylistId(url)
            ?: throw YouTubeError.Failed("Not a YouTube playlist link")

        val info = PlaylistInfo.getInfo(ServiceList.YouTube, YouTubeUrls.playlistUrl(playlistId))
        val videos = info.relatedItems.mapNotNull { it.toYtVideo() }.toMutableList()

        var nextPage = info.nextPage
        while (nextPage != null && videos.size < limit) {
            val page = PlaylistInfo.getMoreItems(
                ServiceList.YouTube,
                YouTubeUrls.playlistUrl(playlistId),
                nextPage,
            )
            videos += page.items.mapNotNull { it.toYtVideo() }
            nextPage = page.nextPage
        }

        videos.take(limit)
    }

    /**
     * The Mix ("radio") playlist YouTube generates from [videoId] - the platform's own
     * this-song-radio algorithm, fetched as a snapshot. One of the candidate pool's two feeds. Its
     * measured behaviour (probe run 2026-08-18, docs/probes/rd-mix-results.md): ~50-60 unique
     * tracks per fetch, semi-stable across fetches (~60% overlap), nearly disjoint from
     * [relatedStreams] (5-10% overlap), seed included as the first item.
     */
    suspend fun mix(videoId: String, limit: Int = MIX_RAW_FETCH_LIMIT): List<YtVideo> = extract("mix") {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, YouTubeUrls.mixUrl(videoId))
        val videos = info.relatedItems.mapNotNull { it.toYtVideo() }.toMutableList()

        var nextPage = info.nextPage
        while (nextPage != null && videos.size < limit) {
            val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, YouTubeUrls.mixUrl(videoId), nextPage)
            videos += page.items.mapNotNull { it.toYtVideo() }
            nextPage = page.nextPage
        }

        // Mix pagination CYCLES: the probe measured 100 raw items collapsing to ~53 unique IDs -
        // the "endless" playlist repeats itself after its real depth. Dedup is what makes [limit]
        // mean tracks rather than page rows.
        videos.take(limit).distinctBy { it.videoId }
    }

    /**
     * The videos YouTube shows next to [videoId] - the candidate pool's other feed: cheap,
     * per-video, no session state.
     *
     * NOTE this runs the same full `StreamInfo` fetch as [resolveAudioStream] (page + player JS).
     * When the station engine lands (M4) and wants both the stream and the neighbours of the same
     * video, merge the two calls into one fetch rather than paying for it twice.
     */
    suspend fun relatedStreams(videoId: String, limit: Int = 20): List<YtVideo> = extract("related") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
        (info.relatedItems ?: emptyList())
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .take(limit)
            .mapNotNull { it.toYtVideo() }
    }

    /** Metadata for a single video, for the "paste a link" path. */
    suspend fun video(videoId: String): YtVideo = extract("video") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
        YtVideo(
            videoId = videoId,
            title = info.name.orEmpty().ifBlank { videoId },
            uploader = info.uploaderName?.takeIf { it.isNotBlank() },
            durationSeconds = info.duration,
            thumbnailUrl = info.thumbnails.bestThumbnailUrl(),
        )
    }

    /**
     * Resolve a directly-playable audio URL for [videoId].
     *
     * @param preferM4a see [AudioStreamSelector]. Defaults to false because TubeCapullo currently
     *   only streams - ExoPlayer plays either container, so bitrate wins. Flip the default when the
     *   M4 disk cache lands, if its metadata pipeline turns out to be M4A-only like telecloud's.
     */
    suspend fun resolveAudioStream(videoId: String, preferM4a: Boolean = false): YtStream {
        cachedStream(videoId)?.let { return it }

        return extract("resolve") {
            val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
            if (info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM
            ) {
                throw YouTubeError.Unavailable("Live streams aren't supported")
            }

            val chosen = AudioStreamSelector.select(
                info.audioStreams.map { it.toCandidate() },
                preferM4a = preferM4a,
            ) ?: throw YouTubeError.Unavailable("No audio-only stream available")

            YtStream(
                url = chosen.url,
                mimeType = chosen.mimeType,
                bitrateKbps = chosen.bitrateKbps,
                contentLength = chosen.contentLength,
                expiresAtEpochSeconds = YouTubeUrls.streamExpiryEpochSeconds(chosen.url),
                itag = chosen.itag,
                isM4a = chosen.isM4a,
            ).also { synchronized(streamCache) { streamCache[videoId] = it } }
        }
    }

    /**
     * Drop the cached URL for [videoId], forcing the next [resolveAudioStream] to re-resolve. Call
     * this when playback fails with a 403: the usual cause is a URL that expired early.
     */
    fun invalidate(videoId: String) {
        synchronized(streamCache) { streamCache.remove(videoId) }
    }

    private fun cachedStream(videoId: String): YtStream? = synchronized(streamCache) {
        val cached = streamCache[videoId] ?: return null
        val expiry = cached.expiresAtEpochSeconds
        // No expiry parsed = can't reason about it, so don't trust it.
        if (expiry == null) {
            streamCache.remove(videoId)
            return null
        }
        // Re-resolve while there's still headroom, so a long track doesn't expire mid-playback.
        if (expiry - System.currentTimeMillis() / 1000 < EXPIRY_MARGIN_SECONDS) {
            streamCache.remove(videoId)
            return null
        }
        return cached
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            val locale = Locale.getDefault()
            NewPipe.init(
                downloader,
                Localization(locale.language, locale.country.ifBlank { "US" }),
                ContentCountry(locale.country.ifBlank { "US" }),
            )
        }
    }

    // Single place where extractor exceptions become YouTubeError. BotCheck is called out on its
    // own because it means the source is dead for every video until a PoToken provider exists,
    // not just this one - the UI needs to say something different.
    private suspend fun <T> extract(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            block()
        } catch (e: YouTubeError) {
            throw e
        } catch (e: SignInConfirmNotBotException) {
            throw YouTubeError.BotCheck(e)
        } catch (e: AgeRestrictedContentException) {
            throw YouTubeError.Unavailable("Age-restricted", e)
        } catch (e: GeographicRestrictionException) {
            throw YouTubeError.Unavailable("Not available in this country", e)
        } catch (e: PaidContentException) {
            throw YouTubeError.Unavailable("Paid content", e)
        } catch (e: PrivateContentException) {
            throw YouTubeError.Unavailable("Private", e)
        } catch (e: YoutubeMusicPremiumContentException) {
            throw YouTubeError.Unavailable("YouTube Music Premium only", e)
        } catch (e: SoundCloudGoPlusContentException) {
            throw YouTubeError.Unavailable("Not available", e)
        } catch (e: AccountTerminatedException) {
            throw YouTubeError.Unavailable("Uploader's account was terminated", e)
        } catch (e: ContentNotAvailableException) {
            throw YouTubeError.Unavailable(e.message ?: "Not available", e)
        } catch (e: ReCaptchaException) {
            throw YouTubeError.Failed("YouTube is rate-limiting this device. Try again shortly.", e)
        } catch (e: Exception) {
            throw YouTubeError.Failed("YouTube $what failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun AudioStream.toCandidate() = AudioCandidate(
        url = url.orEmpty(),
        mimeType = format?.mimeType ?: "audio/mp4",
        // averageBitrate is what YouTube reports per stream; the itag's nominal bitrate is the
        // fallback when it's missing.
        bitrateKbps = averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 } ?: 0,
        contentLength = itagItem?.contentLength?.takeIf { it > 0 },
        itag = itagItem?.id ?: -1,
        isM4a = format == MediaFormat.M4A,
        isProgressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl,
    )

    private fun StreamInfoItem.toYtVideo(): YtVideo? {
        val id = YouTubeUrls.parseVideoId(url ?: return null) ?: return null
        return YtVideo(
            videoId = id,
            title = name.orEmpty().ifBlank { id },
            uploader = uploaderName?.takeIf { it.isNotBlank() },
            durationSeconds = duration.coerceAtLeast(0),
            thumbnailUrl = thumbnails.bestThumbnailUrl(),
        )
    }

    // Biggest thumbnail YouTube offered. Unknown dimensions sort last rather than winning by default.
    private fun List<Image>.bestThumbnailUrl(): String? = maxByOrNull { if (it.height > 0) it.height else 0 }?.url

    private companion object {
        /** Re-resolve when a stream URL has less than this long to live. */
        const val EXPIRY_MARGIN_SECONDS = 5 * 60L

        /** Raw items to page before dedup. The probe measured a mix's unique depth at ~50-60,
         *  reached inside the first two pages; paging past ~100 raw items buys repeats, not
         *  tracks (docs/probes/rd-mix-results.md, run 2026-08-18). */
        const val MIX_RAW_FETCH_LIMIT = 100
    }
}
