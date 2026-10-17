package local.youtubetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import local.youtubetv.R

/**
 * Every colour a screen draws with, read once at the top and handed down.
 *
 * `colorResource` is a resource lookup, and it is cheap only in small numbers. A video card
 * used eight of them, so a grid of a dozen cards spent about a hundred lookups every time the
 * grid recomposed, which is on every press of the D-pad. Taking a colour as a parameter costs
 * a card nothing.
 *
 * This does **not** make the lookups free. They still run whenever the screen that calls
 * [screenInk] recomposes. It moves them off the cards, which are the many, and onto the
 * screen, which is the one, and the screen is built so that moving the highlight does not
 * recompose it.
 *
 * [Ink] is `@Immutable`, so a card whose other arguments have not changed is skipped even
 * though a fresh instance is handed to it each time.
 *
 * The values themselves still live in `res/values/colors.xml`, which stays the one place the
 * app's colours are defined.
 */
@Immutable
data class Ink(
    val surface: Color,
    val surfaceRaised: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,
    val accent: Color,
    val focusFill: Color,
    val onFocus: Color,
    val onFocusMuted: Color,
    val badgeScrim: Color,
    val thumbnailPlaceholder: Color,
    val watchedWash: Color,
    /** The filled circle of the watched marker. */
    val watchedTick: Color,
    /** The tick drawn inside that circle. */
    val watchedTickGlyph: Color,
    val menuScrim: Color,
    /** The red In progress bar: on a card's thumbnail, and under the player. */
    val inProgress: Color,
    val inProgressTrack: Color,
)

@Composable
fun screenInk(): Ink = Ink(
    surface = colorResource(R.color.surface),
    surfaceRaised = colorResource(R.color.surface_raised),
    onSurface = colorResource(R.color.on_surface),
    onSurfaceMuted = colorResource(R.color.on_surface_muted),
    onSurfaceFaint = colorResource(R.color.on_surface_faint),
    accent = colorResource(R.color.accent),
    focusFill = colorResource(R.color.focus_fill),
    onFocus = colorResource(R.color.on_focus),
    onFocusMuted = colorResource(R.color.on_focus_muted),
    badgeScrim = colorResource(R.color.badge_scrim),
    thumbnailPlaceholder = colorResource(R.color.thumbnail_placeholder),
    watchedWash = colorResource(R.color.watched_wash),
    watchedTick = colorResource(R.color.watched_tick),
    watchedTickGlyph = colorResource(R.color.watched_tick_glyph),
    menuScrim = colorResource(R.color.menu_scrim),
    inProgress = colorResource(R.color.in_progress),
    inProgressTrack = colorResource(R.color.in_progress_track),
)
