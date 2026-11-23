package tech.capullo.tubecapullo.ui.upnext

import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.StationState
import tech.capullo.tubecapullo.station.Why

internal enum class RowState { PLAYED, CURRENT, UPCOMING }

internal sealed interface QueueEntry {
    val key: String
}

internal data class TrackEntry(
    val video: YtVideo,
    val why: Why?,
    val pinned: Boolean,
    val state: RowState,
    override val key: String,
    val blended: Boolean = false,
) : QueueEntry {
    val removable: Boolean get() = state == RowState.UPCOMING
}

internal data class ChapterEntry(
    val number: Int,
    val title: String,
    val startMs: Long,
    val state: RowState,
    override val key: String,
) : QueueEntry

internal data class EndLine(val text: String, override val key: String) : QueueEntry

internal val QueueEntry.rowState: RowState?
    get() = when (this) {
        is TrackEntry -> state
        is ChapterEntry -> state
        is EndLine -> null
    }

internal fun queueEntries(state: StationState): List<QueueEntry> = buildList {
    state.earlier.forEachIndexed { i, video ->
        add(TrackEntry(video, why = null, pinned = false, RowState.PLAYED, "played-$i"))
    }
    state.current?.let { add(TrackEntry(it, why = null, pinned = false, RowState.CURRENT, "now")) }
    state.album?.let { album ->
        album.chapters.forEachIndexed { i, chapter ->
            val rowState = when {
                i < album.index -> RowState.PLAYED
                i == album.index -> RowState.CURRENT
                else -> RowState.UPCOMING
            }
            add(ChapterEntry(i + 1, chapter.title, chapter.startMs, rowState, "chapter-$i"))
        }
    }
    // Keys follow the song, not its place, so a dragged row keeps its identity.
    val seen = HashMap<String, Int>()
    state.queued.forEach { item ->
        val n = seen.merge(item.video.videoId, 1, Int::plus)!!
        val key = if (n == 1) "up-${item.video.videoId}" else "up-${item.video.videoId}-$n"
        add(TrackEntry(item.video, item.why, item.pinned, RowState.UPCOMING, key, item.blended))
    }
    if (none { it.rowState == RowState.UPCOMING }) {
        val reason = when {
            state.current == null -> "Nothing playing"
            state.isCollection -> "End of the list"
            else -> "Looking for the next track"
        }
        add(EndLine(reason, "end"))
    }
}

/** The sheet opens on the playing song (an album's row, its chapters below it). */
internal fun initialIndex(entries: List<QueueEntry>): Int = entries.indexOfFirst { it.rowState == RowState.CURRENT }.coerceAtLeast(0)

/** [key]'s place among the upcoming songs, 0 = next; null for any other row. */
internal fun upcomingPosition(entries: List<QueueEntry>, key: Any): Int? {
    var n = 0
    for (entry in entries) {
        if (entry !is TrackEntry || entry.state != RowState.UPCOMING) continue
        if (entry.key == key) return n
        n++
    }
    return null
}
