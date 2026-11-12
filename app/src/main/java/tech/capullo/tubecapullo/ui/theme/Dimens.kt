package tech.capullo.tubecapullo.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared sizes of the compact density pass. Every value below is a Spotify-like density chosen
 * after the owner's review on 2026-11-11 ("fonts are too big, icons too separated; Spotify fits
 * more in the mini bar"). None is measured on a device: each is a GUESS, to tune on the test phone
 * (OPPO 1080x2400, 3x, font scale 1.0). The text sizes that go with them live in [Typography].
 *
 * Touch targets: a button sized below 48 dp here keeps the clickable at its full slot, and Compose
 * extends a pointer target below its 48 dp minimum toward that size where no neighbour is hit
 * (inference from Compose's touch-target rule, not measured). Smaller slots trade a little of
 * that tolerance for density; the 40 dp ones match the clickable IconButton already had.
 */
object Dimens {
    // Now-playing mini-bar (ShellBars.kt).

    /** The bar's height. Smaller crowds the two text lines (14 + 12 sp); larger is today's
     *  64 dp bar the owner found loose. */
    val MINI_BAR_HEIGHT = 56.dp

    /** Gap between the bar's edge and the art, on the start side and above and below the art
     *  ([MINI_BAR_HEIGHT] - 2x this = [MINI_BAR_ART_SIZE]). */
    val MINI_BAR_PADDING = 8.dp

    /** The bar's square track art. Smaller and the art stops reading as a cover. */
    val MINI_BAR_ART_SIZE = 40.dp

    /** Corner radius of the bar's art. */
    val MINI_BAR_ART_CORNER = 6.dp

    /** The tonal MusicNote placeholder inside [MINI_BAR_ART_SIZE]. */
    val MINI_BAR_ART_NOTE_SIZE = 20.dp

    /** Gap between the art and the title and channel lines. */
    val MINI_BAR_ART_TEXT_GAP = 10.dp

    /** Each of the bar's like, play/pause and next buttons, packed edge to edge with no
     *  spacing so the text gets the rest of the width. Below ~40 dp neighbouring taps start to
     *  land on the wrong button; above it the text loses width again. */
    val MINI_BAR_BUTTON_SIZE = 44.dp

    /** The glyph inside [MINI_BAR_BUTTON_SIZE]. */
    val MINI_BAR_ICON_SIZE = 22.dp

    /** The loading ring that stands in for play/pause while a track resolves. */
    val MINI_BAR_SPINNER_SIZE = 24.dp

    // Bottom navigation (TubeApp.kt).

    /** NavigationBar height above the system inset; Material's default is 80 dp. The item
     *  layout centres icon + label (22 + 4 + 4 + 16 = 46 dp) and keeps at least 4 dp above and
     *  below, so below ~54 dp the label clips. */
    val NAV_BAR_HEIGHT = 64.dp

    /** The tab glyphs; Material's default is 24 dp. */
    val NAV_BAR_ICON_SIZE = 22.dp

    // Home card rows (HomeScreen.kt).

    /** Card width and its square art. On a 393 dp screen, after the 16 dp start padding, this
     *  shows about 2.5 cards, so the row reads as scrollable; wider shows exactly 2 and hides
     *  that cue, narrower squeezes the 2-line titles. */
    val HOME_CARD_WIDTH = 140.dp

    /** Corner radius of a card's art and its long-press ripple. */
    val HOME_CARD_CORNER = 8.dp

    /** Gap between cards in a row. */
    val HOME_CARD_SPACING = 12.dp

    /** Space above a section header: separates a row from the previous section's titles. */
    val HOME_SECTION_HEADER_TOP = 16.dp

    /** Space between a section header and its cards. */
    val HOME_SECTION_HEADER_BOTTOM = 8.dp

    // Content list rows: Search, Library and the Queue sheet (ui/shared/CompactListItem.kt).

    /** Space above and below a row's content. Material's ListItem uses 8 dp plus a 72 dp floor
     *  for two lines; with this a one-line-title row is 48 + 2x6 = 60 dp. */
    val LIST_ROW_VERTICAL_PADDING = 6.dp

    /** Space at the row's start, and at its end when nothing trails the text. */
    val LIST_ROW_HORIZONTAL_PADDING = 16.dp

    /** Space at the row's end when buttons trail: a 40 dp button's own 9 dp inset puts its glyph
     *  17 dp from the edge, about the 16 dp of the art at the start. */
    val LIST_ROW_TRAILING_END_PADDING = 8.dp

    /** Gap between the art and the text (Material's ListItem: 16 dp). */
    val LIST_ROW_LEADING_GAP = 12.dp

    /** Gap between the text and the trailing buttons (Material's ListItem: 16 dp). */
    val LIST_ROW_TRAILING_GAP = 8.dp

    /** A row's square art or thumbnail. */
    val LIST_ART_SIZE = 48.dp

    /** Corner radius of a row's art. */
    val LIST_ART_CORNER = 6.dp

    /** The tonal placeholder glyph inside [LIST_ART_SIZE] while the art loads. */
    val LIST_ART_NOTE_SIZE = 20.dp

    /** A trailing row button (like, unlike, share, menu), packed with no spacing between
     *  buttons. 40 dp is the clickable IconButton already had; only the 4 dp of layout padding
     *  on each side is gone. */
    val LIST_BUTTON_SIZE = 40.dp

    /** The glyph inside [LIST_BUTTON_SIZE]. */
    val LIST_ICON_SIZE = 22.dp

    // Player (PlayerScreen.kt).

    /** The shuffle and repeat buttons beside the transport, secondary to the 56 dp skip and
     *  68 dp play buttons. */
    val PLAYER_ORDER_BUTTON_SIZE = 40.dp

    /** The lyrics card at the Player's bottom: a fixed slot, so the Player does not move while
     *  the lines change. Calculated, not tuned: two lines of bodyMedium (20 sp line height each)
     *  and 12 dp above and below. At a font scale above 1.0 the lines need more than 20 dp and
     *  the second one clips (our inference from the arithmetic; not seen on a device). The card
     *  is a row of the Player's column under the control rows, so it never overlaps them; with
     *  the 12 dp gap above it, it takes 76 dp from the art, the only flexible item. Arithmetic
     *  from the Player's sizes (274 dp of fixed rows; not measured on a device): on the OPPO
     *  (360 x 800 dp) the art goes from 360 to about 330 dp, on the OnePlus 3 (360 x 640 dp)
     *  from about 230 to 154 dp, and on a 393 x 873 dp phone it keeps its 393 dp and the
     *  content moves up. */
    val PLAYER_LYRICS_CARD_HEIGHT = 64.dp

    // Search (SearchScreen.kt).

    /** Gap between the four filter chips. Was 8 dp; at 6 dp each chip on a 360 dp screen gets
     *  1.5 dp more for its label. Smaller and the chips start to read as one bar. */
    val SEARCH_CHIP_SPACING = 6.dp

    // Player drawers: the Queue sheet and the lyrics drawer (ui/shared/PlayerDrawer.kt). Owner
    // review 2026-11-11: a drawer may expand upwards but stays a drawer, never full screen.

    /** The expanded drawer's maximum height, as a fraction of the window height below the
     *  status bar. Higher and the Player's top bar disappears behind the drawer (it reads as a
     *  new screen); lower and fewer queue rows or lyric lines fit. */
    const val PLAYER_DRAWER_MAX_HEIGHT_FRACTION = 0.85f

    /** Height of M3's default drag handle above the drawer's content (22 dp vertical padding x 2
     *  + 4 dp bar = 48 dp, ModalBottomSheet's BottomSheetDefaults.DragHandle). The content cap is
     *  the drawer cap minus this, so the whole drawer stays within
     *  PLAYER_DRAWER_MAX_HEIGHT_FRACTION. */
    val PLAYER_DRAWER_HANDLE_SPACE = 48.dp

    /** Gap between the drawer and each side of the screen, so it reads as floating. The
     *  drawer's bottom stays on the screen edge, so its bottom corners stay square. */
    val PLAYER_DRAWER_SIDE_MARGIN = 8.dp

    // Queue sheet (UpNextSheet.kt).

    /** Alpha of a played row's art and text in the Queue (owner decision 2026-11-12: played
     *  rows dimmed). GUESS: chosen without a device check. Lower and a played title gets hard
     *  to read in daylight; higher and played rows no longer read apart from upcoming ones. The
     *  row's buttons keep full alpha: they still act. */
    const val UP_NEXT_PLAYED_ALPHA = 0.5f

    /** The round badge that carries the GraphicEq mark over the current row's art, inside
     *  [LIST_ART_SIZE]. A filled primary circle keeps the mark legible on any thumbnail without
     *  a scrim over the whole art. GUESS: chosen by eye from the 48 dp art and the 20 dp glyph
     *  ([LIST_ART_NOTE_SIZE]) it holds; smaller and the glyph touches the edge, larger and it
     *  hides the art. */
    val UP_NEXT_PLAYING_BADGE_SIZE = 28.dp
}
