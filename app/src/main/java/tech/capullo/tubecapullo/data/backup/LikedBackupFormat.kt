package tech.capullo.tubecapullo.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.util.ShareLink

object LikedBackupFormat {
    const val FORMAT = "tubecapullo-liked"

    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val LINK = Regex(
        """(?<![a-z0-9.-])(?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be)""" +
            """(?:/[^\s"'<>()\[\]{}|\\^`]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private const val TRAILING_PUNCTUATION = ".,;:!?*"

    fun encode(songs: List<LikedEntry>, albums: List<LikedEntry>, exportedAtEpochMs: Long): String = json.encodeToString(
        LikedExportFile.serializer(),
        LikedExportFile(
            format = FORMAT,
            version = VERSION,
            exportedAt = exportedAtEpochMs,
            tracks = songs.toExport(),
            albums = albums.toExport(),
        ),
    )

    private fun List<LikedEntry>.toExport() = sortedByDescending { it.likedAtEpochMs }.map { (video, likedAt) ->
        LikedExportTrack(
            videoId = video.videoId,
            title = video.title,
            uploader = video.uploader,
            durationSeconds = video.durationSeconds,
            thumbnailUrl = video.thumbnailUrl,
            likedAt = likedAt,
            url = ShareLink.youtuBeUrl(video.videoId),
        )
    }

    fun parse(text: String): LikedImportParse {
        exportObject(text)?.let { return decodeExport(it) }
        val links = extractLinks(text)
        return if (links.videoIds.isEmpty() && links.playlistsIgnored == 0) {
            LikedImportParse.NotRecognized
        } else {
            links
        }
    }

    fun extractLinks(text: String): LikedImportParse.Links {
        val videoIds = LinkedHashSet<String>()
        val playlistIds = HashSet<String>()
        LINK.findAll(text).forEach { match ->
            val link = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            val videoId = YouTubeUrls.parseVideoId(link)
            if (videoId != null) {
                videoIds += videoId
            } else {
                YouTubeUrls.parsePlaylistId(link)?.let { playlistIds += it }
            }
        }
        return LikedImportParse.Links(videoIds.toList(), playlistIds.size)
    }

    fun merge(incoming: List<LikedEntry>, existingIds: Set<String>): MergePlan {
        val seen = HashSet<String>()
        val unique = incoming.filter { seen.add(it.video.videoId) }
        val (already, fresh) = unique.partition { it.video.videoId in existingIds }
        return MergePlan(toInsert = fresh, alreadyLiked = already.size)
    }

    fun planLinks(videoIds: List<String>, existingIds: Set<String>, nowEpochMs: Long): LinkPlan {
        val unique = videoIds.distinct()
        val toResolve = unique.mapIndexedNotNull { position, id ->
            if (id in existingIds) null else PendingLike(id, nowEpochMs - position)
        }
        return LinkPlan(toResolve, alreadyLiked = unique.size - toResolve.size)
    }

    fun summary(
        imported: Int,
        albums: Int,
        alreadyLiked: Int,
        unavailable: Int,
        failed: Int,
        playlistsIgnored: Int,
    ): String {
        val parts = buildList {
            when {
                albums == 0 -> if (imported > 0) add("Imported $imported")
                imported == 0 -> add("Imported ${count(albums, "album")}")
                else -> add("Imported ${count(imported, "song")} and ${count(albums, "album")}")
            }
            if (alreadyLiked > 0) add("$alreadyLiked already liked")
            if (unavailable > 0) add("$unavailable unavailable")
            if (failed > 0) add("$failed not reached, import again to retry")
            if (playlistsIgnored > 0) {
                add(
                    if (playlistsIgnored == 1) {
                        "1 playlist skipped"
                    } else {
                        "$playlistsIgnored playlists skipped"
                    },
                )
            }
        }
        return parts.joinToString(" · ").ifEmpty { "The file has no songs to import" }
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun exportObject(text: String): JsonObject? {
        if (!text.trimStart().startsWith('{')) return null
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        return root.takeIf { format == FORMAT }
    }

    private fun decodeExport(root: JsonObject): LikedImportParse {
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: return LikedImportParse.Damaged
        if (version > VERSION) return LikedImportParse.NewerVersion(version)
        val file = try {
            json.decodeFromJsonElement<LikedExportFile>(root)
        } catch (_: SerializationException) {
            return LikedImportParse.Damaged
        } catch (_: IllegalArgumentException) {
            return LikedImportParse.Damaged
        }
        val (songs, badSongs) = file.tracks.partition(::validId)
        val (albums, badAlbums) = file.albums.partition(::validId)
        return LikedImportParse.Export(
            entries = songs.map { it.toEntry() },
            albums = albums.map { it.toEntry() },
            invalid = badSongs.size + badAlbums.size,
        )
    }

    private fun validId(track: LikedExportTrack) = YouTubeUrls.parseVideoId(track.videoId) == track.videoId

    private fun LikedExportTrack.toEntry() = LikedEntry(
        video = YtVideo(
            videoId = videoId,
            title = title,
            uploader = uploader,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
        ),
        likedAtEpochMs = likedAt,
    )
}

sealed interface LikedImportParse {
    data class Export(
        val entries: List<LikedEntry>,
        val albums: List<LikedEntry>,
        val invalid: Int,
    ) : LikedImportParse

    data class Links(val videoIds: List<String>, val playlistsIgnored: Int) : LikedImportParse

    data class NewerVersion(val version: Int) : LikedImportParse

    data object Damaged : LikedImportParse

    data object NotRecognized : LikedImportParse
}

data class MergePlan(val toInsert: List<LikedEntry>, val alreadyLiked: Int)

data class LinkPlan(val toResolve: List<PendingLike>, val alreadyLiked: Int)

data class PendingLike(val videoId: String, val likedAtEpochMs: Long)

@Serializable
internal data class LikedExportFile(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val tracks: List<LikedExportTrack>,
    val albums: List<LikedExportTrack> = emptyList(),
)

@Serializable
internal data class LikedExportTrack(
    val videoId: String,
    val title: String,
    val uploader: String? = null,
    val durationSeconds: Long = 0,
    val thumbnailUrl: String? = null,
    val likedAt: Long,
    val url: String? = null,
)
