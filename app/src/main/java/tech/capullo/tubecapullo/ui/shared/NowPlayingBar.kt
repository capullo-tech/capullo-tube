package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.util.NetworkAddress

/**
 * The bottom-bar pair shared by the search and home screens (extracted verbatim from
 * SearchScreen when Home landed): [BroadcastRow] when broadcasting, then [NowPlayingBar]. Both
 * screens render the identical arrangement, so the composables live here instead of one screen
 * importing the other's private copy.
 */

@Composable
internal fun BroadcastRow(httpPort: Int, listenerCount: Int) {
    // The count includes this phone's own snapclient, so "listeners" is honest: 1 = just here.
    val url = NetworkAddress.lanIpv4()?.let { "http://$it:$httpPort" }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Default.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                buildString {
                    append("  Broadcasting · $listenerCount listening")
                    if (url != null) append(" · $url")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun NowPlayingBar(
    video: YtVideo,
    state: PlaybackCoordinator.State,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, onClick = onOpen) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                video.uploader?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleLike) {
                Icon(
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like - more like this",
                    tint = if (isLiked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.isBuffering) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next track")
            }
        }
    }
}
