package tech.capullo.tubecapullo.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import tech.capullo.tubecapullo.player.BUFFER_SAMPLE_MS

/**
 * The buffering ring: how far the buffer has filled toward playing when [progress] is known,
 * the indeterminate spinner when it is null. The determinate ring keeps the spinner's look (no
 * track, same cap) so only the arc's meaning changes.
 */
@Composable
internal fun BufferRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    if (progress == null) {
        CircularProgressIndicator(modifier, strokeWidth = strokeWidth)
        return
    }
    // One sample period per move: the arc glides between samples instead of stepping, and a drop
    // to a new measure (a seek, a rebuffer) is over in the same short time.
    val shown by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = BUFFER_SAMPLE_MS.toInt(), easing = LinearEasing),
        label = "bufferProgress",
    )
    CircularProgressIndicator(
        progress = { shown },
        modifier = modifier,
        strokeWidth = strokeWidth,
        trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
        strokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
    )
}
