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

/**
 * The likes backup file and the import merge rules, as pure functions (no Android, no I/O) so the
 * JVM tests cover exactly what ships. [LikedBackup] does the reading, writing and network.
 *
 * Export: versioned JSON, newest like first: the liked songs (`tracks`) and, from version 2, the
 * liked albums (`albums`, the same entry shape). Each entry carries a `youtu.be` url, so the file
 * is useful outside the app too.
 *
 * Import takes either that JSON (any version up to [VERSION]; a version 1 file has no albums and
 * imports as songs) or any text that contains YouTube links (songs). Import only ever adds
 * likes: an id that is already liked keeps its own like date.
 */
object LikedBackupFormat {

    /** The `format` field: what marks a JSON file as ours. It keeps the app's old name on purpose:
     *  files exported before the rename to CapulloTube carry this id, and a new id would make
     *  every build refuse the other's files. It is an identifier, never shown to the user. */
    const val FORMAT = "tubecapullo-liked"

    /** The newest file version this build reads and the one it writes. Bump it when the schema
     *  changes in a way an older build would misread; older builds then refuse the file with
     *  [LikedImportParse.NewerVersion] instead of importing it wrong. 2: + `albums`. A version 1
     *  build would read a version 2 file's songs and drop its albums without a word, so it
     *  refuses the file and asks for an update instead. */
    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true // a v1 reader must survive fields a later v1 writer adds
        encodeDefaults = true
        explicitNulls = false // no uploader/thumbnail: omit the key rather than write null
        prettyPrint = true
    }

    // A YouTube link inside arbitrary text: optional scheme, optional subdomain, youtube.com or
    // youtu.be, then everything up to whitespace or a character that delimits URLs in prose,
    // Markdown, HTML or JSON. The lookbehind keeps "notyoutube.com" from matching at "youtube".
    // YouTubeUrls decides what the match means.
    private val LINK = Regex(
        """(?<![a-z0-9.-])(?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be)""" +
            """(?:/[^\s"'<>()\[\]{}|\\^`]*)?""",
        RegexOption.IGNORE_CASE,
    )

    // Sentence punctuation that sticks to the end of a link in prose ("see youtu.be/x.").
    private const val TRAILING_PUNCTUATION = ".,;:!?*"

    /** The JSON export of the liked [songs] and [albums], each newest like first, stamped
     *  [exportedAtEpochMs]. */
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

    /** What [text] holds: our export, a list of links, or neither. */
    fun parse(text: String): LikedImportParse {
        exportObject(text)?.let { return decodeExport(it) }
        val links = extractLinks(text)
        return if (links.videoIds.isEmpty() && links.playlistsIgnored == 0) {
            LikedImportParse.NotRecognized
        } else {
            links
        }
    }

    /**
     * Every YouTube video link in [text], first occurrence order, duplicates collapsed. A link
     * that names only a playlist is counted, not expanded: a playlist is not a list of likes. A
     * watch link that also carries `&list=` names a song, so it counts as that song.
     */
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

    /** The merge rule: [incoming] minus every id in [existingIds] and minus repeats inside
     *  [incoming] (the first one wins). Survivors keep their own like dates. */
    fun merge(incoming: List<LikedEntry>, existingIds: Set<String>): MergePlan {
        val seen = HashSet<String>()
        val unique = incoming.filter { seen.add(it.video.videoId) }
        val (already, fresh) = unique.partition { it.video.videoId in existingIds }
        return MergePlan(toInsert = fresh, alreadyLiked = already.size)
    }

    /**
     * The link-import half of the merge: which of [videoIds] (file order) still need a like, and
     * the like date each gets. The first link is newest: it gets [nowEpochMs], and each later
     * position 1 ms less, so the Liked list shows the file's order. The date depends on the
     * position in the file, not among the new ids, so a re-import gives a link the same date.
     */
    fun planLinks(videoIds: List<String>, existingIds: Set<String>, nowEpochMs: Long): LinkPlan {
        val unique = videoIds.distinct()
        val toResolve = unique.mapIndexedNotNull { position, id ->
            if (id in existingIds) null else PendingLike(id, nowEpochMs - position)
        }
        return LinkPlan(toResolve, alreadyLiked = unique.size - toResolve.size)
    }

    /**
     * The import result for the snackbar, only the non-zero parts: "Imported 38 · 2 already
     * liked · 1 unavailable". [failed] counts lookups that broke for a transient reason (network,
     * rate limit, bot check); a second import of the same file retries exactly those.
     */
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

    // A JSON object whose format field is ours, else null (then the text is scanned for links:
    // someone else's JSON can still carry YouTube links).
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
        // Version first, before the schema: a newer file may not decode as v1 at all, and the
        // user must hear "newer version", not "damaged".
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
        // An id YouTubeUrls would not accept as a bare id cannot be played: count it, skip it.
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

/** What an import file holds. */
sealed interface LikedImportParse {
    /** Our JSON export: liked songs ([entries]) and liked albums ([albums], none before version
     *  2). [invalid] entries had an id that is not a YouTube video id. */
    data class Export(
        val entries: List<LikedEntry>,
        val albums: List<LikedEntry>,
        val invalid: Int,
    ) : LikedImportParse

    /** Text with YouTube links: video ids in file order, plus playlist links left out. */
    data class Links(val videoIds: List<String>, val playlistsIgnored: Int) : LikedImportParse

    /** Our export, written by a newer app version with a schema this build cannot read. */
    data class NewerVersion(val version: Int) : LikedImportParse

    /** Marked as our export, but a field is missing or has the wrong type. */
    data object Damaged : LikedImportParse

    /** Neither our export nor text with a YouTube link in it. */
    data object NotRecognized : LikedImportParse
}

/** [toInsert] are new likes, [alreadyLiked] the incoming ids left as they are. */
data class MergePlan(val toInsert: List<LikedEntry>, val alreadyLiked: Int)

/** Link import: the ids to look up and like (with their like dates), and the count skipped. */
data class LinkPlan(val toResolve: List<PendingLike>, val alreadyLiked: Int)

/** A linked video still to look up, and the like date it gets once found. */
data class PendingLike(val videoId: String, val likedAtEpochMs: Long)

@Serializable
internal data class LikedExportFile(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    /** The liked songs. */
    val tracks: List<LikedExportTrack>,
    /** The liked albums (version 2 on; absent in version 1). */
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
    /** Written for readers outside the app; the import ignores it and trusts [videoId]. */
    val url: String? = null,
)
