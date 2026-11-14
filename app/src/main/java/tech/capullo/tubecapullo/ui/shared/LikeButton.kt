package tech.capullo.tubecapullo.ui.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun LikeButton(
    isLiked: Boolean,
    likeLabel: String,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    IconButton(onClick = onLike, modifier = modifier) {
        Icon(
            if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isLiked) "Unlike" else likeLabel,
            tint = if (isLiked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = iconModifier,
        )
    }
}
