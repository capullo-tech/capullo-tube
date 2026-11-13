package tech.capullo.tubecapullo.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.station.StationEngine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LikedBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val likedTracks: LikedTracks,
    private val likedAlbums: LikedAlbums,
    private val extractor: YouTubeExtractorClient,
    private val stationEngine: StationEngine,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)

    private val _progress = MutableStateFlow<BackupProgress?>(null)

    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<BackupMessage?>(null)

    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    private val messageIds = AtomicLong()

    fun messageShown(shown: BackupMessage) {
        _message.update { if (it?.id == shown.id) null else it }
    }

    fun export(uri: Uri) = launchExclusive(BackupProgress.Exporting) {
        val (entries, albums) = try {
            likedTracks.entries() to likedAlbums.entries()
        } catch (e: Exception) {
            Log.w(TAG, "export result=failed reason=read", e)
            return@launchExclusive "Could not read your liked songs: ${reason(e)}"
        }
        val text = LikedBackupFormat.encode(entries, albums, System.currentTimeMillis())
        try {
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("the storage app gave no file to write")
                out.bufferedWriter().use { it.write(text) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "export result=failed reason=write", e)
            return@launchExclusive "Could not write the file: ${reason(e)}. " +
                "Pick another folder or storage app."
        }
        Log.i(TAG, "export n=${entries.size} albums=${albums.size}")
        val songs = if (entries.size == 1) "1 song" else "${entries.size} songs"
        when (albums.size) {
            0 -> "Exported $songs"
            1 -> "Exported $songs and 1 album"
            else -> "Exported $songs and ${albums.size} albums"
        }
    }

    fun import(uri: Uri) = launchExclusive(BackupProgress.Reading) {
        val text = try {
            readText(uri)
        } catch (e: Exception) {
            Log.w(TAG, "import result=failed reason=read", e)
            return@launchExclusive "Could not read the file: ${reason(e)}"
        }
        when (val parsed = LikedBackupFormat.parse(text)) {
            is LikedImportParse.Export -> importExport(parsed)
            is LikedImportParse.Links -> importLinks(parsed)
            is LikedImportParse.NewerVersion -> {
                Log.i(TAG, "import result=rejected reason=newer-version version=${parsed.version}")
                "File made by a newer CapulloTube version. Update the app, then import again."
            }
            LikedImportParse.Damaged -> {
                Log.i(TAG, "import result=rejected reason=damaged")
                "The CapulloTube export is damaged or incomplete. Export it again."
            }
            LikedImportParse.NotRecognized -> {
                Log.i(TAG, "import result=rejected reason=not-recognized")
                "Not a CapulloTube export or a list of YouTube links"
            }
        }
    }

    private suspend fun importExport(parsed: LikedImportParse.Export): String {
        val plan = LikedBackupFormat.merge(parsed.entries, likedTracks.likedIds())
        val inserted = likedTracks.importAll(plan.toInsert.sortedByDescending { it.likedAtEpochMs })
        val albumPlan = LikedBackupFormat.merge(parsed.albums, likedAlbums.likedIds())
        val albums = likedAlbums.importAll(albumPlan.toInsert.sortedByDescending { it.likedAtEpochMs })
        val alreadyLiked = plan.alreadyLiked + plan.toInsert.size - inserted.size +
            albumPlan.alreadyLiked + albumPlan.toInsert.size - albums.size
        stationEngine.onLikesImported(inserted)
        stationEngine.onAlbumsImported(albums)
        Log.i(
            TAG,
            "import kind=json parsed=${parsed.entries.size + parsed.albums.size + parsed.invalid} " +
                "new=${inserted.size} albums=${albums.size} existing=$alreadyLiked " +
                "failed=${parsed.invalid}",
        )
        return LikedBackupFormat.summary(
            imported = inserted.size,
            albums = albums.size,
            alreadyLiked = alreadyLiked,
            unavailable = parsed.invalid,
            failed = 0,
            playlistsIgnored = 0,
        )
    }

    private suspend fun importLinks(parsed: LikedImportParse.Links): String {
        val plan = LikedBackupFormat.planLinks(
            parsed.videoIds,
            likedTracks.likedIds(),
            System.currentTimeMillis(),
        )
        val resolved = mutableListOf<LikedEntry>()
        var unavailable = 0
        var failed = 0
        plan.toResolve.forEachIndexed { index, pending ->
            _progress.value = BackupProgress.Resolving(index + 1, plan.toResolve.size)
            try {
                resolved += LikedEntry(extractor.video(pending.videoId), pending.likedAtEpochMs)
            } catch (e: YouTubeError.Unavailable) {
                unavailable++
                Log.i(TAG, "import-lookup id=${pending.videoId} result=unavailable reason=${q(e)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                Log.i(TAG, "import-lookup id=${pending.videoId} result=failed reason=${q(e)}")
            }
        }
        _progress.value = BackupProgress.Saving
        val inserted = likedTracks.importAll(resolved)
        val alreadyLiked = plan.alreadyLiked + resolved.size - inserted.size
        stationEngine.onLikesImported(inserted)
        Log.i(
            TAG,
            "import kind=links parsed=${parsed.videoIds.size} new=${inserted.size} " +
                "existing=$alreadyLiked failed=${unavailable + failed} unavailable=$unavailable " +
                "playlists=${parsed.playlistsIgnored}",
        )
        return LikedBackupFormat.summary(
            imported = inserted.size,
            albums = 0,
            alreadyLiked = alreadyLiked,
            unavailable = unavailable,
            failed = failed,
            playlistsIgnored = parsed.playlistsIgnored,
        )
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("the storage app gave no file to read")
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_IMPORT_BYTES) {
                    throw IOException("it is larger than ${MAX_IMPORT_BYTES / BYTES_PER_MB} MB")
                }
            }
        }
        out.toByteArray().decodeToString()
    }

    private fun launchExclusive(start: BackupProgress, block: suspend () -> String) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "busy result=ignored")
            return
        }
        _progress.value = start
        scope.launch {
            val text = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "result=failed reason=storage", e)
                "Could not update your liked songs: ${reason(e)}"
            } finally {
                _progress.value = null
                running.set(false)
            }
            _message.value = BackupMessage(messageIds.incrementAndGet(), text)
        }
    }

    private fun reason(e: Exception): String = e.message?.takeIf { it.isNotBlank() }
        ?: e::class.simpleName.orEmpty()

    private fun q(e: Exception): String = "\"${e.message.orEmpty()}\""

    private companion object {
        const val TAG = "TubeBackup"

        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

        const val BYTES_PER_MB = 1024 * 1024
    }
}

sealed interface BackupProgress {
    data object Exporting : BackupProgress
    data object Reading : BackupProgress

    data class Resolving(val current: Int, val total: Int) : BackupProgress
    data object Saving : BackupProgress
}

data class BackupMessage(val id: Long, val text: String)
