package tech.capullo.tubecapullo.player

/** How often [PlaybackService] samples the buffer while ExoPlayer is buffering. */
internal const val BUFFER_SAMPLE_MS = 150L

/**
 * How far ExoPlayer's buffer has filled toward what it waits for before it plays, 0 to 1: the
 * media buffered ahead of the position over [thresholdMs]. Near the end of a track less than
 * [thresholdMs] is left to load and ExoPlayer starts once all of it is in, so the remaining
 * duration caps the threshold. A negative [remainingMs] (C.TIME_UNSET included) means the
 * duration is unknown and leaves the threshold alone. With nothing to wait for the buffer is full.
 */
internal fun bufferFill(bufferedMs: Long, positionMs: Long, thresholdMs: Long, remainingMs: Long): Float {
    val needed = if (remainingMs >= 0) minOf(thresholdMs, remainingMs) else thresholdMs
    if (needed <= 0) return 1f
    return ((bufferedMs - positionMs).toFloat() / needed).coerceIn(0f, 1f)
}
