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

/**
 * The whole YouTube surface the rest of the app is allowed to touch: search, the trending-music
 * kiosk, playlist/mix expansion, related streams and stream resolution. Nothing else in
 * CapulloTube imports `org.schabi.newpipe.*`, so when extraction breaks (and it will - YouTube
 * rotates its player JS, see the pin comment in pins.versions.toml) the blast radius is this file.
 *
 * Every call suspends onto IO and translates the extractor's dozen exception types into
 * [YouTubeError], which callers can actually branch on.
 *
 * Ported from telecloud-radio's YouTubeExtractorClient; [relatedStreams] and [mix] are new - they
 * feed the station: every fetch from a song takes both, merged in pairs (station/Pairs.kt).
 */
@Singleton
class YouTubeExtractorClient @Inject constructor(private val downloader: TubeCapulloDownloader) {
    private val initialized = AtomicBoolean(false)

    // Resolved stream URLs, kept until shortly before they expire. Resolution is by far the
    // expensive call (it fetches and runs YouTube's player JS through Rhino), so prefetching the
    // next few tracks is only cheap because of this.
    private val streamCache = mutableMapOf<String, YtStream>()

    /**
     * Search YouTube for videos (the plain `VIDEOS` filter): the Search screen's Songs filter and
     * the station's channel fallback. Any single playable video qualifies - official audio, music
     * videos, live versions, covers, uploads outside a label - so a song is found however it was
     * uploaded (owner call 2026-11-11: YouTube Music's `MUSIC_SONGS` left those out). Live
     * streams are dropped by the `VIDEO_STREAM` filter in [toVideos]; durations are not
     * filtered here. Results are metadata only - no stream is resolved, so this stays one
     * HTTP round trip regardless of how many results come back.
     */
    suspend fun search(query: String, limit: Int = 30): List<YtVideo> = extract("search") {
        searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS).toVideos().take(limit)
    }

    /**
     * Search for playlists, albums or channels, per [kind]: the Search screen's other filters.
     * One page, metadata only. The NewPipe v0.26.5 filters (SearchFilterProbeTest, run
     * 2026-11-11, "daft punk"):
     *  - [CollectionKind.PLAYLISTS]: `MUSIC_PLAYLISTS`, user playlists from YouTube Music.
     *  - [CollectionKind.ALBUMS]: `MUSIC_ALBUMS`, the artists' `OLAK5uy_` album playlists.
     *  - [CollectionKind.CHANNELS]: the plain `CHANNELS` filter, NOT `MUSIC_ARTISTS`. The music
     *    filter lists each artist's auto-generated "<Artist> - Topic" channel (3 of 3 checked),
     *    which has no Videos tab, so a channel start from it finds no uploads and falls back to a
     *    name search. The plain filter lists the official channel first, with its uploads.
     * No item in that run had an item count (all -1, channels included), so
     * [YtCollection.itemCount] is null in practice; the plain `PLAYLISTS` filter does give one.
     */
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

    /** The first page of a search under one YouTube content filter. */
    private fun searchItems(query: String, contentFilter: String): List<InfoItem> = SearchInfo.getInfo(
        ServiceList.YouTube,
        ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf(contentFilter), ""),
    ).relatedItems

    /**
     * YouTube's trending-music chart (`trending_music` kiosk; general Trending is deprecated in
     * the extractor since YouTube removed that page 2025-07-21). Metadata only, like [search] -
     * the launch home's recommendation cards.
     *
     * [region]: an ISO 3166-1 alpha-2 code, or null for the device locale that
     * [ensureInitialized] pins as NewPipe's global content country. A code reaches THIS request
     * only: it is forced on the kiosk extractor instance (`Extractor.forceContentCountry`, public
     * in v0.26.5), so search, mix, related and stream resolution keep the device-locale country.
     * Whether the country changes Mix/related at all is the open question in
     * docs/probes/region-mix-criteria.md. A country YouTube Charts does not serve makes the kiosk
     * throw UnsupportedContentInCountryException, surfaced as [YouTubeError.Unavailable] with the
     * extractor's reason; [supportedRegions] keeps such codes out of the picker.
     */
    suspend fun trendingMusic(region: String?, limit: Int = 30): List<YtVideo> = extract("trending_music") {
        val kiosk = ServiceList.YouTube.kioskList.getExtractorByUrl(TRENDING_MUSIC_URL, null)
        region?.let { kiosk.forceContentCountry(ContentCountry(it)) }
        kiosk.fetchPage()
        KioskInfo.getInfo(kiosk).relatedItems.toVideos().take(limit)
    }

    /**
     * ISO 3166-1 alpha-2 codes the Trending charts picker may offer, in the extractor's order: the
     * YouTube service's supported content countries, narrowed to the ones YouTube Charts serves.
     * The narrowing matters: in v0.26.5 the service lists 109 countries but the trending-music
     * kiosk accepts 61 of them and throws for the rest, so offering those would let a user break
     * Home's Trending. In-memory lookup, no network.
     */
    fun supportedRegions(): List<String> {
        val service = ServiceList.YouTube.supportedCountries.map { it.countryCode }
        val chart = chartCountryCodes ?: return service
        return service.filter { it in chart }
    }

    /**
     * The countries YouTube Charts serves. The extractor keeps them in a protected static of a
     * package-private class ([CHART_COUNTRIES_FIELD] on YoutubeChartsBaseKioskExtractor, the
     * superclass of [YoutubeTrendingMusicExtractor], v0.26.5) with no public accessor, so this
     * reads it by reflection instead of copying 61 codes that would drift silently on a pin bump.
     * If a bump moves the field this is null: [supportedRegions] then offers every service
     * country and an unserved pick shows the kiosk's own error on Home - degraded, never a crash.
     */
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

    /**
     * A playlist link's title and videos, following pagination up to [limit]. Long playlists page
     * 100 at a time; the cap keeps a 5000-track playlist from turning one paste into a hundred
     * requests. A Mix (`RD...`) pages in cycles (see [mix]), so it stops at [MIX_RAW_FETCH_LIMIT]
     * raw items; repeats are dropped either way.
     */
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

    /**
     * The Mix ("radio") playlist YouTube generates from [videoId] - the platform's own
     * this-song-radio algorithm, fetched as a snapshot. A radio is these, chained. Its
     * measured behaviour (probe run 2026-08-18, docs/probes/rd-mix-results.md): ~50-60 unique
     * tracks per fetch, semi-stable across fetches (~60% overlap), nearly disjoint from
     * [relatedStreams] (5-10% overlap), seed included as the first item.
     */
    suspend fun mix(videoId: String, limit: Int = MIX_RAW_FETCH_LIMIT): List<YtVideo> = extract("mix") {
        val url = YouTubeUrls.mixUrl(videoId)
        // Mix pagination CYCLES: the probe measured 100 raw items collapsing to ~53 unique IDs -
        // the "endless" playlist repeats itself after its real depth. Dedup is what makes [limit]
        // mean tracks rather than page rows.
        PlaylistInfo.getInfo(ServiceList.YouTube, url).expand(url, limit).distinctBy { it.videoId }
    }

    /**
     * A channel's uploads, newest first: the channel page's Videos tab, paged up to [limit].
     * Regular videos only (no live or upcoming streams; shorts have their own tab). An item that
     * does not name its uploader gets the channel's name and URL. A channel without a Videos tab
     * gives an empty list: YouTube's auto-generated "<Artist> - Topic" channels have no tabs at
     * all, and no uploads playlist either (probe 2026-11-11, two Topic channels, v0.26.5).
     */
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

    /**
     * Every video NewPipe lists next to [videoId] (about 20; the related-quality probe of
     * 2026-11-12 saw 15 to 20 per seed), in YouTube's order, and [videoId]'s own views from the
     * same fetch: the second list of every fetch from a song, and the related gate's yardstick
     * (station/Pairs.kt). No cap (PLAN "Home v2 and albums": all of them, not the top 8). The
     * related-quality probe (docs/probes/related-quality-results.md) found most of the list are
     * long compilations, which the station's length window drops and Home's Full albums row
     * looks through.
     *
     * NOTE this runs the same full `StreamInfo` fetch as [resolveAudioStream] (page + player JS).
     * When the station engine lands (M4) and wants both the stream and the neighbours of the same
     * video, merge the two calls into one fetch rather than paying for it twice.
     */
    suspend fun relatedStreams(videoId: String): YtRelated = extract("related") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId))
        val videos = (info.relatedItems ?: emptyList())
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .mapNotNull { it.toYtVideo() }
        YtRelated(seedViews = info.viewCount, videos = videos)
    }

    /** Metadata for a single video, for the "paste a link" path. */
    suspend fun video(videoId: String): YtVideo = extract("video") {
        StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(videoId)).toYtVideo(videoId)
    }

    /**
     * A long video for album playback: its metadata and its YouTube chapters in time order
     * ([chapters]). One `StreamInfo` fetch. Album playback starts with it, and Home's Full
     * albums row asks it whether a long video has chapters.
     */
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

    /**
     * Resolve a directly-playable audio URL for [videoId].
     *
     * @param preferM4a see [AudioStreamSelector]. Defaults to false because CapulloTube currently
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

    /** This page's videos, then the following pages until [limit] raw items or the end. */
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

    /** A list's regular videos (search pages and channel tabs mix in other item types and live
     *  streams). */
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
            // NewPipe's own "unknown" is -1 too.
            viewCount = viewCount.coerceAtLeast(-1),
            uploadYear = uploadDate?.offsetDateTime()?.year,
            uploaderVerified = isUploaderVerified,
        )
    }

    private companion object {
        /** Re-resolve when a stream URL has less than this long to live. */
        const val EXPIRY_MARGIN_SECONDS = 5 * 60L

        /** Raw items to page before dedup. The probe measured a mix's unique depth at ~50-60,
         *  reached inside the first two pages; paging past ~100 raw items buys repeats, not
         *  tracks (docs/probes/rd-mix-results.md, run 2026-08-18). */
        const val MIX_RAW_FETCH_LIMIT = 100

        /** Uploads a channel station holds. GUESS: 200, the playlist cap - about 12 hours of
         *  music, 7 Videos-tab pages of 30 (Rick Astley's 131 uploads took 5 requests, probe
         *  2026-11-11). Higher costs a request per 30 more at every channel start; lower cuts
         *  off a big channel's back catalogue. */
        const val CHANNEL_UPLOADS_LIMIT = 200

        /** The trending-music kiosk URL. Mirrors YoutubeTrendingMusicLinkHandlerFactory's
         *  getUrl ("https://charts.youtube.com" + "/charts/TrendingVideos" + "/RightNow"),
         *  spelled out so the fetch needs no handler args; if the extractor moves the chart,
         *  re-derive from that factory (KIOSK_ID "trending_music"). */
        const val TRENDING_MUSIC_URL = "https://charts.youtube.com/charts/TrendingVideos/RightNow"

        /** Field name of the chart's country set in the v0.26.5 extractor; see
         *  [chartCountryCodes] for why it is read by reflection. */
        const val CHART_COUNTRIES_FIELD = "YT_CHARTS_SUPPORTED_COUNTRY_CODES"

        const val TAG = "YouTubeExtractor"
    }
}

/**
 * A searched playlist or album as a [YtCollection]: the canonical playlist URL (the YouTube Music
 * filters give `music.youtube.com` links), and the item count only when YouTube gave one. NewPipe
 * marks unknown, endless (a Mix) and "more than 100" with negative counts; all three become null.
 * Null when the URL carries no playlist id.
 */
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

/** A searched channel as a [YtCollection]: its page URL and name; no uploader. Null without a
 *  URL or a name, since a row could neither start nor name it. */
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

/**
 * Chapters a video needs before album playback steps through them: 3, YouTube's own minimum for
 * a chapter list (YouTube Help, "Video chapters": the first at 0:00, at least three, each at
 * least 10 s). Not a GUESS. Fewer and the album plays as one track.
 */
internal const val MIN_CHAPTERS = 3

/** A video's chapters from its [segments], in time order, one per start time (the first title
 *  wins); none when fewer than [MIN_CHAPTERS] remain. A blank title becomes "Chapter <n>". */
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

// Biggest thumbnail YouTube offered. Unknown dimensions sort last rather than winning by default.
private fun List<Image>.bestThumbnailUrl(): String? = maxByOrNull { if (it.height > 0) it.height else 0 }?.url
