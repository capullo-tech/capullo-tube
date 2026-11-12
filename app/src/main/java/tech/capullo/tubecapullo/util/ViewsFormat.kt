package tech.capullo.tubecapullo.util

import tech.capullo.tubecapullo.data.youtube.YtVideo

/**
 * The line after the channel on cards and rows (Home, Search songs, Up next): "1.2M views ·
 * 2019", "34K views" or "2019", whichever is known; null hides the line (a Mix track knows
 * neither, related-quality probe 2026-11-12). One copy for the three surfaces, or they drift.
 */
internal fun viewsLine(video: YtVideo): String? {
    val views = video.viewCount.takeIf { it >= 0 }?.let {
        if (it == 1L) "1 view" else "${compactCount(it)} views"
    }
    return listOfNotNull(views, video.uploadYear?.toString()).joinToString(" · ").ifEmpty { null }
}

/**
 * YouTube's compact count: 999, 1.2K, 34K, 1.2M, 5B. One decimal below 10 of a unit, dropped
 * when it is 0; rounded down, as YouTube does, so 999_999 is 999K and never 1000K.
 */
internal fun compactCount(count: Long): String {
    val (unit, suffix) = when {
        count >= BILLION -> BILLION to "B"
        count >= MILLION -> MILLION to "M"
        count >= THOUSAND -> THOUSAND to "K"
        else -> return count.toString()
    }
    val tenths = count * 10 / unit
    if (tenths >= 100) return "${count / unit}$suffix"
    val whole = tenths / 10
    val tenth = tenths % 10
    return if (tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
}

private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
private const val BILLION = 1_000_000_000L
