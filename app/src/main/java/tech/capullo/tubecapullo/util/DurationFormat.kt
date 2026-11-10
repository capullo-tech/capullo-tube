package tech.capullo.tubecapullo.util

/**
 * `m:ss` for row/card subtitles; null hides the segment entirely (0 = unknown or live, and this
 * source doesn't handle live streams). Shared by the search rows and the home cards - one copy,
 * or the two surfaces drift.
 */
internal fun formatDuration(seconds: Long): String? {
    if (seconds <= 0) return null
    val minutes = seconds / 60
    return "%d:%02d".format(minutes, seconds % 60)
}
