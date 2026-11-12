package tech.capullo.tubecapullo.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.LikeEffect
import tech.capullo.tubecapullo.station.RollOver
import tech.capullo.tubecapullo.ui.player.LikeChipAbove
import tech.capullo.tubecapullo.ui.player.rememberLikeChipState
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.util.TitleCleaner

/** The now-playing mini-bar: the track's art, title and channel, then like, play/pause and next
 *  packed at the end with no spacing, so the title and channel lines get every dp the art and
 *  buttons leave (owner requests 2026-11-11; sizes in [Dimens], MINI_BAR_*). No extra
 *  minimumInteractiveComponentSize: 48 dp targets do not fit side by side in the bar's slots.
 *  Tap opens the Player; the buttons act in place. After a like, the chip ([likeEffects]) says
 *  just above the bar what it did to the queue; when a list or album ends ([rollOvers]), it says
 *  the station goes on as a radio. */
@Composable
internal fun NowPlayingBar(
    video: YtVideo,
    state: PlaybackCoordinator.State,
    isLiked: Boolean,
    likeEffects: Flow<LikeEffect>,
    rollOvers: Flow<RollOver>,
    onToggleLike: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    val likeChip = rememberLikeChipState(likeEffects, rollOvers)
    // Display only: the raw title stays the record everywhere else.
    val title = remember(video.title, video.uploader) {
        TitleCleaner.clean(video.title, video.uploader)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, onClick = onOpen) {
        LikeChipAbove(likeChip)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.MINI_BAR_HEIGHT)
                // No end padding: the last button's own inset (11 dp) puts its glyph closest to
                // the art's 8 dp at the start.
                .padding(start = Dimens.MINI_BAR_PADDING),
        ) {
            BarArt(video.thumbnailUrl)
            Spacer(Modifier.width(Dimens.MINI_BAR_ART_TEXT_GAP))
            // Long names scroll instead of ellipsizing, the Player's way.
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                video.uploader?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
            }
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(Dimens.MINI_BAR_BUTTON_SIZE),
            ) {
                Icon(
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like - more like this",
                    tint = if (isLiked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(Dimens.MINI_BAR_ICON_SIZE),
                )
            }
            // The ring replaces play/pause while a track resolves, in the same slot, so the text
            // beside it does not reflow.
            Box(Modifier.size(Dimens.MINI_BAR_BUTTON_SIZE), contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(Dimens.MINI_BAR_SPINNER_SIZE))
                } else {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(Dimens.MINI_BAR_BUTTON_SIZE),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(Dimens.MINI_BAR_ICON_SIZE),
                        )
                    }
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(Dimens.MINI_BAR_BUTTON_SIZE)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    modifier = Modifier.size(Dimens.MINI_BAR_ICON_SIZE),
                )
            }
        }
    }
}

/** The thumbnail over a tonal MusicNote placeholder, so a slow load shows the note, not a
 *  flicker (the Home cards' pattern). */
@Composable
private fun BarArt(url: String?) {
    Box(
        Modifier
            .size(Dimens.MINI_BAR_ART_SIZE)
            .clip(RoundedCornerShape(Dimens.MINI_BAR_ART_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(Dimens.MINI_BAR_ART_NOTE_SIZE),
        )
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null, // decorative; the title text carries the meaning
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
