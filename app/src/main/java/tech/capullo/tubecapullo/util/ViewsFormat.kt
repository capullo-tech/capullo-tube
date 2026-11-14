package tech.capullo.tubecapullo.util

import tech.capullo.tubecapullo.data.youtube.YtVideo

internal fun viewsLine(video: YtVideo): String? {
    val views = video.viewCount.takeIf { it >= 0 }?.let {
        if (it == 1L) "1 view" else "${compactCount(it)} views"
    }
    return listOfNotNull(views, video.uploadYear?.toString()).joinToString(" · ").ifEmpty { null }
}

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
