package tech.capullo.tubecapullo.data.lyrics

import tech.capullo.audio.snapcast.Group

object LyricsTiming {

    const val SNAPSERVER_BUFFER_MS = 1_000L

    const val SONG_NUDGE_STEP_MS = 500L

    fun autoDelayMs(groups: List<Group>, localClientId: String): Long {
        val local = groups.asSequence()
            .flatMap { it.clients }
            .firstOrNull { it.connected && it.id == localClientId }
        return SNAPSERVER_BUFFER_MS - (local?.config?.latency ?: 0)
    }

    fun lineIndex(lyrics: Lyrics, positionMs: Long, offsetMs: Long): Int = if (lyrics.synced) {
        Lrc.currentLineIndex(lyrics.lines, positionMs - offsetMs)
    } else {
        -1
    }

    fun seekTarget(lineTimeMs: Long, offsetMs: Long): Long = (lineTimeMs + offsetMs).coerceAtLeast(0)
}
