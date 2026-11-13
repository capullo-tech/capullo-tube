package tech.capullo.tubecapullo.ui.upnext

import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.StationState
import tech.capullo.tubecapullo.station.Why

/** Where a row sits against the playhead: dimmed, highlighted or plain in the Queue sheet. */
internal enum class RowState { PLAYED, CURRENT, UPCOMING }

/** One line of the Queue sheet's list. [key] is stable per position in the list, not per track:
 *  a track can appear twice (a list on repeat has it both played and upcoming), so ids alone are
 *  not unique. */
internal sealed interface QueueEntry {
    val key: String
}

internal data class TrackEntry(
    val video: YtVideo,
    /** Where an upcoming track came from; null for the played and current rows. */
    val why: Why?,
    val pinned: Boolean,
    val state: RowState,
    override val key: String,
) : QueueEntry {
    /** Only what is still to come can be removed. */
    val removable: Boolean get() = state == RowState.UPCOMING
}

/** One chapter of the playing album (album mode). [number] counts from 1; a tap seeks to
 *  [startMs]. */
internal data class ChapterEntry(
    val number: Int,
    val title: String,
    val startMs: Long,
    val state: RowState,
    override val key: String,
) : QueueEntry

/** The last line when nothing is still to come: says why, so the list does not just stop. */
internal data class EndLine(val text: String, override val key: String) : QueueEntry

internal val QueueEntry.rowState: RowState?
    get() = when (this) {
        is TrackEntry -> state
        is ChapterEntry -> state
        is EndLine -> null
    }

/**
 * The Queue sheet's one list (owner decision 2026-11-12): the songs played this session, oldest
 * first, then the current song, then the upcoming ones in play order. In album mode the album's
 * row is the current song and its chapters follow it in their own order, each played, current or
 * upcoming against the playing chapter; the queued tracks come after the last chapter.
 */
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
    state.queued.forEachIndexed { i, item ->
        add(TrackEntry(item.video, item.why, item.pinned, RowState.UPCOMING, "up-$i"))
    }
    if (none { it.rowState == RowState.UPCOMING }) {
        // A radio refills from the next Mix (an album rolls into one); a list with nothing
        // queued is on its last track.
        val reason = when {
            state.current == null -> "Nothing playing"
            state.isCollection -> "End of the list"
            else -> "Looking for the next track"
        }
        add(EndLine(reason, "end"))
    }
}

/**
 * The row the sheet opens on, as the first visible row (owner decision 2026-11-12): the first
 * upcoming one, so the played and current rows sit just above it, one pull down away. With
 * nothing upcoming, the current row (in album mode the album's row, so the playing chapter shows
 * below it); with nothing at all, the top.
 */
internal fun initialIndex(entries: List<QueueEntry>): Int {
    val upcoming = entries.indexOfFirst { it.rowState == RowState.UPCOMING }
    if (upcoming >= 0) return upcoming
    return entries.indexOfFirst { it.rowState == RowState.CURRENT }.coerceAtLeast(0)
}
