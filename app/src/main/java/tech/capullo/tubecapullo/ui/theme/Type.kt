package tech.capullo.tubecapullo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material's baseline scale, the base every compact role below is copied from. */
private val baseline = Typography()

/**
 * The compact type scale: Spotify-like density, owner review 2026-11-11 ("fonts are too big");
 * GUESS, tune on device (OPPO, font scale 1.0). Each role names what uses it, so a screen picks
 * the role, never a size. Roles not listed keep Material's baseline. The sizes that go with these
 * live in [Dimens].
 */
val Typography = Typography(
    // Top-bar titles (TopAppBar's title role), Home section headers ("More like", "Listen
    // again", "Trending in"), the Up next sheet title and the Player's track title. Baseline is
    // 22 sp Normal; 18 sp SemiBold keeps them headers through weight instead of size.
    titleLarge = baseline.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // List and card titles: Search, Library and Up next rows, Home cards, the mini-bar title.
    // Baseline's own values, pinned here because the rows now take them explicitly (ListItem's
    // headline role is bodyLarge, 16 sp).
    titleSmall = baseline.titleSmall.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    // Body copy and the Player's channel line (baseline values, pinned).
    bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    // Secondary lines: channel and duration under a row or card title, the mini-bar channel
    // (baseline values, pinned; ListItem's supporting role is bodyMedium, 14 sp).
    bodySmall = baseline.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    // Chips and small labels: Search's filter chips (whose own role is labelLarge, 14 sp) and
    // the bottom navigation labels (baseline values, pinned).
    labelMedium = baseline.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    // Text field input and Settings rows: left at baseline so typed text stays easy to read.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)
