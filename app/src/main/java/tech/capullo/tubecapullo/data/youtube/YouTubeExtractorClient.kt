package tech.capullo.tubecapullo.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
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
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingMusicExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeExtractorClient @Inject constructor(private val downloader: TubeCapulloDownloader) {
    private val initialized = AtomicBoolean(false)

    private val streamCache = mutableMapOf<String, YtStream>()

    suspend fun search(query: String, limit: Int = 30): List<YtVideo> = extract("search") {
        searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS).toVideos().take(limit)
    }

    suspend fun searchCollections(
        query: String,
        kind: CollectionKind,
        limit: Int = 30,
    ): List<YtCollection> = extract("search_${kind.name.lowercase(Locale.ROOT)}") {
        val filter = when (kind) {
            CollectionKind.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
            CollectionKind.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            CollectionKind.CHANNELS -> YoutubeSearchQueryHandlerFactory.CHANNELS
        }
        val items = searchItems(query, filter)
        val collections = when (kind) {
            CollectionKind.CHANNELS -> items.filterIsInstance<ChannelInfoItem>()
                .mapNotNull { it.toYtCollection() }
            CollectionKind.PLAYLISTS, CollectionKind.ALBUMS ->
                items.filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toYtCollection() }
        }
        collections.distinctBy { it.url }.take(limit)
    }

    private fun searchItems(query: String, contentFilter: String): List<InfoItem> = SearchInfo.getInfo(
        ServiceList.YouTube,
        ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf(contentFilter), ""),
    ).relatedItems

    suspend fun trendingMusic(region: String?, limit: Int = 30): List<YtVideo> = extract("trending_music") {
        val kiosk = ServiceList.YouTube.kioskList.getExtractorByUrl(TRENDING_MUSIC_URL, null)
        region?.let { kiosk.forceContentCountry(ContentCountry(it)) }
        kiosk.fetchPage()
        KioskInfo.getInfo(kiosk).relatedItems.toVideos().take(limit)
    }

    fun supportedRegions(): List<String> {
        val service = ServiceList.YouTube.supportedCountries.map { it.countryCode }
        val chart = chartCountryCodes ?: return service
        return service.filter { it in chart }
    }

    private val chartCountryCodes: Set<String>? by lazy {
        runCatching {
            val codes = YoutubeTrendingMusicExtractor::class.java.superclass
                .getDeclaredField(CHART_COUNTRIES_FIELD)
                .apply { isAccessible = true }
                .get(null) as Set<*>
            codes.filterIsInstance<String>().toSet().takeIf { it.isNotEmpty() }
                ?: error("$CHART_COUNTRIES_FIELD is empty")
        }.onFailure {
            Log.w(TAG, "chart country list unreadable; offering every service country", it)
        }.getOrNull()
    }

    suspend fun playlistInfo(url: String, limit: Int = 200): YtVideoList = extract("playlist") {
        val playlistId = YouTubeUrls.parsePlaylistId(url)
            ?: throw YouTubeError.Failed("Not a YouTube playlist link")
        val canonical = YouTubeUrls.playlistUrl(playlistId)
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, canonical)
        val raw = if (YouTubeUrls.isMixPlaylistId(playlistId)) {
            minOf(limit, MIX_RAW_FETCH_LIMIT)
        } else {
            limit
        }
        YtVideoList(
            title = info.name.orEmpty().ifBlank { playlistId },
            videos = info.expand(canonical, raw).distinctBy { it.videoId },
        )
    }

    suspend fun mix(videoId: String, limit: Int = MIX_RAW_FETCH_LIMIT): List<YtVideo> = extract("mix") {
        val url = YouTubeUrls.mixUrl(videoId)
        PlaylistInfo.getInfo(ServiceList.YouTube, url).expand(url, limit).distinctBy { it.videoId }
    }

    suspend fun channelUploads(
        url: String,
        limit: Int = CHANNEL_UPLOADS_LIMIT,
    ): YtVideoList = extract("channel") {
        val channel = ChannelInfo.getInfo(ServiceList.YouTube, url)
        val name = channel.name?.takeIf { it.isNotBlank() }
        val videos = mutableListOf<YtVideo>()
        val tab = channel.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        if (tab != null) {
            val first = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
            videos += first.relatedItems.toVideos()
            var nextPage = first.nextPage
            while (nextPage != null && videos.size < limit) {
                val page = ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, nextPage)
                videos += page.items.toVideos()
                nextPage = page.nextPage
            }
        }
        YtVideoList(
            title = name ?: url,
            videos = videos.take(limit).distinctBy { it.videoId }.map {
                it.copy(
                    uploader = it.uploader ?: name,
                    uploaderUrl = it.uploaderUrl ?: channel.url,
                )
            },
        )
    }

    suspend fun relatedStreams(videoId: String): YtRelated = extract("related") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
        val videos = (info.relatedItems ?: emptyList())
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .mapNotNull { it.toYtVideo() }
        YtRelated(seedViews = info.viewCount, videos = videos)
    }

    suspend fun video(videoId: String): YtVideo = extract("video") {
        StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId)).toYtVideo(videoId)
    }

    suspend fun album(videoId: String): YtAlbum = extract("album") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
        YtAlbum(info.toYtVideo(videoId), chapters(info.streamSegments.orEmpty()))
    }

    private fun StreamInfo.toYtVideo(videoId: String) = YtVideo(
        videoId = videoId,
        title = name.orEmpty().ifBlank { videoId },
        uploader = uploaderName?.takeIf { it.isNotBlank() },
        durationSeconds = duration,
        thumbnailUrl = thumbnails.bestThumbnailUrl(),
        uploaderUrl = uploaderUrl?.takeIf { it.isNotBlank() },
        viewCount = viewCount,
        uploadYear = uploadDate?.offsetDateTime()?.year,
        uploaderVerified = isUploaderVerified,
    )

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

    fun invalidate(videoId: String) {
        synchronized(streamCache) { streamCache.remove(videoId) }
    }

    private fun cachedStream(videoId: String): YtStream? = synchronized(streamCache) {
        val cached = streamCache[videoId] ?: return null
        val expiry = cached.expiresAtEpochSeconds
        if (expiry == null) {
            streamCache.remove(videoId)
            return null
        }
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
        bitrateKbps = averageBitrate.takeIf { it > 0 } ?: bitrate.takeIf { it > 0 } ?: 0,
        contentLength = itagItem?.contentLength?.takeIf { it > 0 },
        itag = itagItem?.id ?: -1,
        isM4a = format == MediaFormat.M4A,
        isProgressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl,
    )

    private fun PlaylistInfo.expand(url: String, limit: Int): List<YtVideo> {
        val videos = relatedItems.mapNotNull { it.toYtVideo() }.toMutableList()
        var next = nextPage
        while (next != null && videos.size < limit) {
            val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
            videos += page.items.mapNotNull { it.toYtVideo() }
            next = page.nextPage
        }
        return videos.take(limit)
    }

    private fun List<InfoItem>.toVideos(): List<YtVideo> = filterIsInstance<StreamInfoItem>()
        .filter { it.streamType == StreamType.VIDEO_STREAM }
        .mapNotNull { it.toYtVideo() }

    private fun StreamInfoItem.toYtVideo(): YtVideo? {
        val id = YouTubeUrls.parseVideoId(url ?: return null) ?: return null
        return YtVideo(
            videoId = id,
            title = name.orEmpty().ifBlank { id },
            uploader = uploaderName?.takeIf { it.isNotBlank() },
            durationSeconds = duration.coerceAtLeast(0),
            thumbnailUrl = thumbnails.bestThumbnailUrl(),
            uploaderUrl = uploaderUrl?.takeIf { it.isNotBlank() },
            viewCount = viewCount.coerceAtLeast(-1),
            uploadYear = uploadDate?.offsetDateTime()?.year,
            uploaderVerified = isUploaderVerified,
        )
    }

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 5 * 60L

        const val MIX_RAW_FETCH_LIMIT = 100

        const val CHANNEL_UPLOADS_LIMIT = 200

        const val TRENDING_MUSIC_URL = "https://charts.youtube.com/charts/TrendingVideos/RightNow"

        const val CHART_COUNTRIES_FIELD = "YT_CHARTS_SUPPORTED_COUNTRY_CODES"

        const val TAG = "YouTubeExtractor"
    }
}

internal fun PlaylistInfoItem.toYtCollection(): YtCollection? {
    val playlistId = YouTubeUrls.parsePlaylistId(url ?: return null) ?: return null
    return YtCollection(
        url = YouTubeUrls.playlistUrl(playlistId),
        title = name.orEmpty().ifBlank { playlistId },
        uploader = uploaderName?.takeIf { it.isNotBlank() },
        thumbnailUrl = thumbnails.bestThumbnailUrl(),
        itemCount = streamCount.takeIf { it >= 0 },
    )
}

internal fun ChannelInfoItem.toYtCollection(): YtCollection? {
    val pageUrl = url?.takeIf { it.isNotBlank() } ?: return null
    val channelName = name?.takeIf { it.isNotBlank() } ?: return null
    return YtCollection(
        url = pageUrl,
        title = channelName,
        uploader = null,
        thumbnailUrl = thumbnails.bestThumbnailUrl(),
        itemCount = streamCount.takeIf { it >= 0 },
    )
}

internal const val MIN_CHAPTERS = 3

internal fun chapters(segments: List<StreamSegment>): List<YtChapter> {
    val chapters = segments
        .sortedBy { it.startTimeSeconds }
        .distinctBy { it.startTimeSeconds }
        .mapIndexed { i, segment ->
            YtChapter(
                title = segment.title?.trim().orEmpty().ifEmpty { "Chapter ${i + 1}" },
                startMs = segment.startTimeSeconds.coerceAtLeast(0) * 1000L,
            )
        }
    return chapters.takeIf { it.size >= MIN_CHAPTERS }.orEmpty()
}

private fun List<Image>.bestThumbnailUrl(): String? = maxByOrNull { if (it.height > 0) it.height else 0 }?.url
