package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import tech.capullo.tubecapullo.ui.theme.Dimens

/**
 * Material's ListItem at the compact density (Search, Library and Queue rows): the same slots,
 * colors and merged semantics, but [Dimens] paddings instead of ListItem's fixed 8 dp and its
 * 72 dp two-line floor, a titleSmall headline (ListItem's is bodyLarge) and a bodySmall supporting
 * line (ListItem's is bodyMedium). Everything is centred vertically. The container is opaque,
 * ListItem's surface color by default, so a swipe background never shows through a row; a
 * highlighted row (the Queue's current song) passes its own opaque [containerColor].
 */
@Composable
fun CompactListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    headlineColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = ListItemDefaults.containerColor,
) {
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        // One focusable node per row, as ListItem does.
        modifier = Modifier.semantics(mergeDescendants = true) {}.then(modifier),
        color = containerColor,
        contentColor = headlineColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.LIST_ROW_HORIZONTAL_PADDING,
                    end = if (trailingContent != null) {
                        Dimens.LIST_ROW_TRAILING_END_PADDING
                    } else {
                        Dimens.LIST_ROW_HORIZONTAL_PADDING
                    },
                    top = Dimens.LIST_ROW_VERTICAL_PADDING,
                    bottom = Dimens.LIST_ROW_VERTICAL_PADDING,
                ),
        ) {
            leadingContent?.let {
                CompositionLocalProvider(LocalContentColor provides secondaryColor, content = it)
                Spacer(Modifier.width(Dimens.LIST_ROW_LEADING_GAP))
            }
            Column(Modifier.weight(1f)) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.titleSmall,
                    content = headlineContent,
                )
                supportingContent?.let {
                    CompositionLocalProvider(
                        LocalContentColor provides secondaryColor,
                        LocalTextStyle provides MaterialTheme.typography.bodySmall,
                        content = it,
                    )
                }
            }
            trailingContent?.let {
                Spacer(Modifier.width(Dimens.LIST_ROW_TRAILING_GAP))
                CompositionLocalProvider(LocalContentColor provides secondaryColor, content = it)
            }
        }
    }
}
