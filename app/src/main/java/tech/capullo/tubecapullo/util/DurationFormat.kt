package tech.capullo.tubecapullo.util

internal fun formatDuration(seconds: Long): String? {
    if (seconds <= 0) return null
    val minutes = seconds / 60
    return "%d:%02d".format(minutes, seconds % 60)
}
