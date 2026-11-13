package tech.capullo.tubecapullo.station

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo

data class StationSnapshot(
    val id: Long,
    val savedAtEpochMs: Long,
    val source: PlaySource,
    val slots: List<SnapshotSlot>,
    val left: Int,
    val positionMs: Long,
    val upcoming: List<Int>,
    val history: List<Int>,
    val dismissedIds: Set<String>,
    val chainSeed: YtVideo?,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val likesRound: List<YtVideo>,
    val roundPlayed: Int,
    val collection: List<YtVideo>,
    val collectionWhy: Why?,
    val round: List<Int>,
    val roundPos: Int,
    val nextRound: List<Int>?,
) {
    val leftVideo: YtVideo get() = slots[left].video
}

data class SnapshotSlot(val video: YtVideo, val why: Why, val pinned: Boolean, val blended: Boolean)

object StationSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(snapshot: StationSnapshot): String = json.encodeToString(SnapshotDto.serializer(), snapshot.toDto())

    fun decode(text: String): StationSnapshot? = try {
        json.decodeFromString(SnapshotDto.serializer(), text).toSnapshot()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun StationSnapshot.toDto() = SnapshotDto(
        id = id,
        savedAt = savedAtEpochMs,
        source = source.toDto(),
        slots = slots.map { SlotDto(it.video.toDto(), it.why.toDto(), it.pinned, it.blended) },
        left = left,
        positionMs = positionMs,
        upcoming = upcoming,
        history = history,
        dismissedIds = dismissedIds.toList(),
        chainSeed = chainSeed?.toDto(),
        shuffle = shuffle,
        repeat = repeat.name,
        likesRound = likesRound.map { it.toDto() },
        roundPlayed = roundPlayed,
        collection = collection.map { it.toDto() },
        collectionWhy = collectionWhy?.toDto(),
        round = round,
        roundPos = roundPos,
        nextRound = nextRound,
    )

    private fun SnapshotDto.toSnapshot(): StationSnapshot {
        val slots = slots.map { SnapshotSlot(it.video.toVideo(), it.why.toWhy(), it.pinned, it.blended) }
        val indices = upcoming + history + round + nextRound.orEmpty() + left
        require(indices.all { it in slots.indices }) { "slot index out of range" }
        return StationSnapshot(
            id = id,
            savedAtEpochMs = savedAt,
            source = source.toSource(),
            slots = slots,
            left = left,
            positionMs = positionMs,
            upcoming = upcoming,
            history = history,
            dismissedIds = dismissedIds.toSet(),
            chainSeed = chainSeed?.toVideo(),
            shuffle = shuffle,
            repeat = RepeatMode.valueOf(repeat),
            likesRound = likesRound.map { it.toVideo() },
            roundPlayed = roundPlayed,
            collection = collection.map { it.toVideo() },
            collectionWhy = collectionWhy?.toWhy(),
            round = round,
            roundPos = roundPos,
            nextRound = nextRound,
        )
    }

    private fun PlaySource.toDto(): SourceDto = when (this) {
        is PlaySource.SongRadio -> SourceDto(SONG, video = seed.toDto())
        is PlaySource.SearchRadio -> SourceDto(SEARCH, text = query, videos = results.map { it.toDto() })
        is PlaySource.Playlist -> SourceDto(PLAYLIST, url = url, text = title, shuffle = shuffle)
        is PlaySource.Channel -> SourceDto(CHANNEL, url = url, text = name)
        is PlaySource.Liked -> SourceDto(LIKED, shuffle = shuffle)
        PlaySource.LikedRadio -> SourceDto(LIKED_RADIO)
        is PlaySource.Album -> SourceDto(
            ALBUM,
            video = video.toDto(),
            chapters = chapters.map { ChapterDto(it.title, it.startMs) },
        )
    }

    private fun SourceDto.toSource(): PlaySource = when (type) {
        SONG -> PlaySource.SongRadio(requireNotNull(video).toVideo())
        SEARCH -> PlaySource.SearchRadio(text.orEmpty(), videos.map { it.toVideo() })
        PLAYLIST -> PlaySource.Playlist(requireNotNull(url), text.orEmpty(), shuffle)
        CHANNEL -> PlaySource.Channel(requireNotNull(url), text.orEmpty())
        LIKED -> PlaySource.Liked(shuffle)
        LIKED_RADIO -> PlaySource.LikedRadio
        ALBUM -> PlaySource.Album(
            requireNotNull(video).toVideo(),
            chapters.map { YtChapter(it.title, it.startMs) },
        )
        else -> throw IllegalArgumentException("unknown source type $type")
    }

    private fun YtVideo.toDto() = VideoDto(
        id = videoId,
        title = title,
        uploader = uploader,
        seconds = durationSeconds,
        thumb = thumbnailUrl,
        uploaderUrl = uploaderUrl,
        views = viewCount,
        year = uploadYear,
        verified = uploaderVerified,
    )

    private fun VideoDto.toVideo() = YtVideo(
        videoId = id,
        title = title,
        uploader = uploader,
        durationSeconds = seconds,
        thumbnailUrl = thumb,
        uploaderUrl = uploaderUrl,
        viewCount = views,
        uploadYear = year,
        uploaderVerified = verified,
    )

    private fun Why.toDto() = WhyDto(provenance.name, fromVideoId, fromTitle)

    private fun WhyDto.toWhy() = Why(Provenance.valueOf(provenance), fromVideoId, fromTitle)

    private const val SONG = "song"
    private const val SEARCH = "search"
    private const val PLAYLIST = "playlist"
    private const val CHANNEL = "channel"
    private const val LIKED = "liked"
    private const val LIKED_RADIO = "liked-radio"
    private const val ALBUM = "album"
}

@Serializable
private data class SnapshotDto(
    val id: Long,
    val savedAt: Long,
    val source: SourceDto,
    val slots: List<SlotDto>,
    val left: Int,
    val positionMs: Long,
    val upcoming: List<Int>,
    val history: List<Int>,
    val dismissedIds: List<String> = emptyList(),
    val chainSeed: VideoDto? = null,
    val shuffle: Boolean = false,
    val repeat: String = RepeatMode.OFF.name,
    val likesRound: List<VideoDto> = emptyList(),
    val roundPlayed: Int = 0,
    val collection: List<VideoDto> = emptyList(),
    val collectionWhy: WhyDto? = null,
    val round: List<Int> = emptyList(),
    val roundPos: Int = -1,
    val nextRound: List<Int>? = null,
)

@Serializable
private data class SourceDto(
    val type: String,
    val video: VideoDto? = null,
    val videos: List<VideoDto> = emptyList(),
    val url: String? = null,
    val text: String? = null,
    val shuffle: Boolean = false,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
private data class SlotDto(
    val video: VideoDto,
    val why: WhyDto,
    val pinned: Boolean = false,
    val blended: Boolean = false,
)

@Serializable
private data class VideoDto(
    val id: String,
    val title: String,
    val uploader: String? = null,
    val seconds: Long = 0,
    val thumb: String? = null,
    val uploaderUrl: String? = null,
    val views: Long = -1,
    val year: Int? = null,
    val verified: Boolean = false,
)

@Serializable
private data class WhyDto(
    @SerialName("prov") val provenance: String,
    val fromVideoId: String? = null,
    val fromTitle: String? = null,
)

@Serializable
private data class ChapterDto(val title: String, val startMs: Long)
