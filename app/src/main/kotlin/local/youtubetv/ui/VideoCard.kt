package local.youtubetv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import local.youtubetv.data.StoredVideo

/**
 * One video, as a card in the grid.
 *
 * It carries the thumbnail, a **one-line** title, the exact publish date and a duration badge.
 * It carries no watch-state text and no percentage. A text line under the date made a whole
 * grid row taller whenever one card in that row carried a label, leaving a gap under the other
 * four. Decided on the TCL, 2026-09-19. See spec section 7.3.
 *
 * The date line also carries the video's YouTube view count after the date, as a drawn eye and
 * a rounded number, when YouTube gave one. Build ticket 59. It is on the same line, so it adds
 * no height to the card.
 *
 * When [watched] is true the thumbnail carries a dark wash, and a small green square holding a
 * tick sits at the right end of the date line. The marker is on that line rather than on the
 * picture because it hid too much of the picture there. There is still no text line saying
 * watched, on purpose: a text line under the date made a whole grid row taller whenever one
 * card in that row carried a label.
 * [inProgressFraction] is the other half of this: a red bar along the bottom edge of the
 * thumbnail, saying how far a part watched video got. It is worked out by
 * [local.youtubetv.ui.inProgressFraction], never here.
 *
 * The card does not know whether it is the grid's focus target, and it must not: reading that
 * would recompose every visible card on every key press. Instead it owns a `FocusRequester`,
 * hands it to the grid through [register] for as long as it is composed, and the grid asks
 * that requester directly. The only state here is [focused], which changes for at most two
 * cards per press.
 *
 * @param register called with this card's requester once the card exists. It returns the
 *   function that takes it out again when the card goes away.
 * @param onHighlightChanged said on every change of this card's own highlight, the gains and
 *   the losses both. The grid keeps the record and its retry ladder reads that record as its
 *   exit test. It was a gain-only report until build ticket 47, which needed a record that goes
 *   false again when the card really loses the highlight.
 */
@Composable
fun VideoCard(
    video: StoredVideo,
    watched: Boolean,
    inProgressFraction: Float?,
    thumbnails: ThumbnailLoader,
    ink: Ink,
    register: (FocusRequester) -> () -> Unit,
    onHighlightChanged: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }

    // Registered from an effect rather than while composing. Composition can be run again or
    // abandoned, so it must leave nothing behind it; an effect runs once the card is really
    // there and is undone the moment the lazy grid drops it.
    DisposableEffect(register) {
        val unregister = register(requester)
        onDispose { unregister() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // How `OverlayFocusTest` says which card holds the highlight. See `ui/TestTags.kt`.
            .testTag(TestTags.videoCard(video.videoId))
            // Background and border carry the shape themselves. A clip here as well would add
            // a rounded-corner clip per card on every frame, and drawing is the other half of
            // what made scrolling jarring.
            .background(
                color = if (focused) ink.focusFill else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 3.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                // Only a real change is reported on. Compose delivers `onFocusChanged` on
                // other things than a gain or a loss, and a record written from a delivery
                // that changed nothing would report a loss this card never had.
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            .onSelectOrHoldKey(onSelect = onSelect, onHold = onHold)
            .focusable()
            .padding(6.dp),
    ) {
        CardThumbnail(
            video = video,
            watched = watched,
            inProgressFraction = inProgressFraction,
            thumbnails = thumbnails,
            ink = ink,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Spacer(Modifier.height(6.dp))
        // One line, not two. Two lines cost more vertical space than a whole extra row of
        // cards, and the whole title is readable in the header line above the grid. At four
        // columns a card is half as wide again as it was, so more of the title fits before
        // the ellipsis, which is the point of the wider card.
        Text(
            text = video.title.ifBlank { video.videoId },
            color = if (focused) ink.onFocus else ink.onSurface,
            style = Fonts.CardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        // The date at the left, the watched marker at the right end of the same line. The Row
        // is here whether or not the card is watched, so an unwatched card's date is laid out
        // by exactly the same code and the two dates share a baseline across a grid row.
        //
        // The marker is a fraction of `Fonts.Meta.lineHeight`, and `CardOverlaysTest` holds
        // that fraction below 1, so the date sets this line's height and the marker does not.
        // The grid is a `LazyVerticalGrid` with no item height, so a marker that set the
        // height would make a whole grid row taller the moment one card in it was watched,
        // which is the one thing ticket 15 forbids.
        //
        // The Row is deliberately **not** pinned to `dateLineHeight`. That was tried on
        // 2026-09-20 and measured on the television, and it pushed the grid down: a date
        // measures about 30px on the TCL while `lineHeight` is 34px there, because Compose
        // trims a single line to the font's own ascent and descent. Pinning the Row grew the
        // line by 4px, moved the second row of cards from y=735 to y=739, and the third from
        // y=1055 to y=1063. The criterion this ticket has to meet is that no grid row got
        // taller, so the pin went and the natural height stayed.
        //
        // What that leaves: the marker is 28px inside a 30px line on this set, a margin of
        // about 7%. A television whose fallback font had tighter metrics than this one could
        // close that margin. It is recorded in ticket 15's known gaps.
        val dateLineHeight = with(LocalDensity.current) { Fonts.Meta.lineHeight.toDp() }
        val metaColor = if (focused) ink.onFocusMuted else ink.onSurfaceMuted
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The date and the view count share what the watched marker leaves. Build ticket 59.
            //
            // Inside this inner Row the date is measured first and given all the room there is,
            // so it is cut only where it was cut before this ticket: when it is wider than the
            // whole line by itself. The view count is measured second, with what is left, and
            // [WholeOrNothing] drops it entirely when it does not fit. So at a large font scale
            // the count gives way and the date never does. The owner's ticket: "the date must never
            // be cut".
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = exactDate(video.videoPublishedAt),
                    color = metaColor,
                    style = Fonts.Meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                roundedViewCount(video.viewCount)?.let { count ->
                    WholeOrNothing {
                        ViewCount(
                            count = count,
                            color = metaColor,
                            eyeHeight = dateLineHeight * CardOverlays.VIEW_COUNT_EYE_LINE_FRACTION,
                            modifier = Modifier.testTag(TestTags.viewCount(video.videoId)),
                        )
                    }
                }
            }
            if (watched) {
                WatchedMarker(
                    ink = ink,
                    side = dateLineHeight * CardOverlays.WATCHED_MARKER_LINE_FRACTION,
                )
            }
        }
    }
}

@Composable
private fun CardThumbnail(
    video: StoredVideo,
    watched: Boolean,
    inProgressFraction: Float?,
    thumbnails: ThumbnailLoader,
    ink: Ink,
    modifier: Modifier = Modifier,
) {
    // The cache is asked here, while composing, so a picture that has already been decoded is
    // drawn in this very frame. Waiting for the coroutine meant a card scrolled back over
    // flashed its dark tile and then recomposed, which read as the grid stuttering.
    val ready = thumbnails.cached(video.videoId)
    val picture by produceState(ready, video.videoId) {
        if (value == null) value = thumbnails.load(video.videoId, video.thumbnailUrl)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ink.thumbnailPlaceholder),
    ) {
        picture?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Drawn over the picture but under both badges, so a duration or an UNAVAILABLE
        // marker stays readable on a watched card. The wash is now the only thing on the
        // thumbnail that says watched, and the owner chose on 2026-09-20 to keep it exactly as it
        // is for that reason: a watched card still reads as watched from across the room,
        // before anyone reads a word of text. Spec section 7.3.
        if (watched) {
            Box(modifier = Modifier.matchParentSize().background(ink.watchedWash))
        }
        cardBadge(video)?.let { label ->
            Badge(
                label = label,
                ink = ink,
                modifier = Modifier
                    .align(CardOverlays.stateBadgeCorner)
                    .padding(CardOverlays.inset),
            )
        }
        durationBadge(video.durationSeconds)?.let { label ->
            Badge(
                label = label,
                ink = ink,
                modifier = Modifier
                    .align(CardOverlays.durationBadgeCorner)
                    .padding(CardOverlays.inset),
            )
        }
        // Last, so the bar sits on the very bottom edge and over everything else. A video the
        // household has not finished carries it whether or not it also carries the wash.
        inProgressFraction?.let { fraction ->
            InProgressBar(
                fraction = fraction,
                ink = ink,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The red In progress bar, along the bottom edge of a thumbnail.
 *
 * Full width, so the household reads the fraction by where the red stops rather than by how
 * long it is. Spec section 7.3.
 */
@Composable
private fun InProgressBar(fraction: Float, ink: Ink, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(4.dp).background(ink.inProgressTrack)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(ink.inProgress),
        )
    }
}

@Composable
private fun Badge(label: String, ink: Ink, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(ink.badgeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, color = ink.onSurface, style = Fonts.Badge, maxLines = 1)
    }
}

/**
 * Where the things drawn on top of a thumbnail sit, and how big the watched marker is.
 *
 * They are values rather than literals at each call site because what matters is a rule, not a
 * number. The two badges must not stand in the same corner, or one hides the other on a card
 * that carries both. The watched marker must stay smaller than the date line it now sits on.
 * `CardOverlaysTest` asserts both.
 *
 * Spec section 7.3 records these. **The marker is no longer on the thumbnail at all**: it moved
 * to the card's date line on 2026-09-20 by ticket 15, because it hid too much of the picture.
 */
internal object CardOverlays {
    /** How far every overlay sits in from the thumbnail's edge. */
    val inset = 6.dp

    /**
     * The watched marker's side, as a fraction of the date line's height.
     *
     * The marker is measured off that line rather than given a fixed dp size, so it follows
     * the line at any font scale. Staying below 1 is the whole rule: the date, not the marker,
     * must set the line's height, or every grid row grows taller the moment one card in it is
     * watched. `CardOverlaysTest` keeps it below 1.
     */
    const val WATCHED_MARKER_LINE_FRACTION = 0.82f

    /**
     * The tick's own box, as a fraction of the green square's side.
     *
     * Being below 1 is not enough. Nothing clips the tick, so the whole stroke has to land
     * inside the square by itself. `CardOverlaysTest` works the real bound out from this value
     * and [WATCHED_TICK_STROKE_FRACTION].
     */
    const val WATCHED_MARKER_GLYPH_FRACTION = 0.62f

    /**
     * The tick's stroke, as a fraction of the tick's own box.
     *
     * It lives here rather than inside `WatchedTick` because it is half of the rule above:
     * a round cap reaches half a stroke width past the end of the line, so a fatter stroke
     * pushes the tick nearer the edge of the square.
     */
    const val WATCHED_TICK_STROKE_FRACTION = 0.24f

    /**
     * The view count's eye, its height as a fraction of the date line's height. Build ticket 59.
     *
     * The same rule as [WATCHED_MARKER_LINE_FRACTION], for the same reason: below 1, the date
     * sets the line's height and the eye does not, so no grid row grows taller because one card
     * in it has a count. `CardOverlaysTest` keeps it below 1. Half the line was chosen to match
     * the eye beside the date in the mockup of build ticket 59.
     */
    const val VIEW_COUNT_EYE_LINE_FRACTION = 0.5f

    /** The eye's width as a multiple of its height, measured off design 3 of the mockup. */
    const val VIEW_COUNT_EYE_ASPECT = 1.65f

    /** The upper lid's stroke, as a fraction of the eye's height. */
    const val VIEW_COUNT_EYE_STROKE_FRACTION = 0.13f

    /** How far down the eye's box the two ends of the upper lid sit, as a fraction of its height. */
    const val VIEW_COUNT_EYE_LID_END_FRACTION = 0.64f

    /** The pupil's radius, as a fraction of the eye's height. The pupil sits on the box's bottom. */
    const val VIEW_COUNT_EYE_PUPIL_FRACTION = 0.25f

    val stateBadgeCorner: Alignment = Alignment.TopStart
    val durationBadgeCorner: Alignment = Alignment.BottomEnd
}

/**
 * The watched marker: a tick inside a filled green square, at the right end of the date line.
 *
 * It has moved twice, and both times the picture won. It was a 38dp bare tick in the dead
 * centre of the thumbnail; on 2026-09-20 the owner saw that grid on the television and it went to
 * a 26dp circle in the thumbnail's top-right corner. He then judged that the corner badge still
 * hid too much of the thumbnail, so by ticket 15 the marker left the picture altogether. It is
 * now a small square on the card's date line, and the dark wash is the only thing left on the
 * thumbnail that says watched. Spec section 7.3.
 *
 * @param side how wide and tall the green square is. The caller works it out from the date
 *   line's own height, so the marker never sets that height itself.
 */
@Composable
private fun WatchedMarker(ink: Ink, side: Dp, modifier: Modifier = Modifier) {
    // The square is a shaped background rather than a clip, for the same reason the circle
    // before it was: a clip is a graphics layer per watched card on every frame, and this card
    // already says at the top why it avoids one. Nothing needs clipping here, because the tick
    // and its round caps land inside the square by themselves. `CardOverlaysTest` proves it.
    //
    // A square is the cheaper shape of the two as well: a plain rectangle background needs no
    // outline to be built and cached per card.
    Box(
        modifier = modifier
            .size(side)
            .background(ink.watchedTick, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        WatchedTick(
            color = ink.watchedTickGlyph,
            modifier = Modifier.size(side * CardOverlays.WATCHED_MARKER_GLYPH_FRACTION),
        )
    }
}

/**
 * The view count on the date line: the separator, the eye and the rounded number. Build ticket 59.
 *
 * The owner chose all of it on 2026-10-03: "let's put it on the date line, number rounded, instead
 * of the word "views" use and eye icon/emoji (it takes less space than the word)", then "drawn
 * eye", then "eye design 3". All three parts take the date's own colour and style, so the line
 * reads as one line. See spec section 7.3.
 */
@Composable
private fun ViewCount(count: String, color: Color, eyeHeight: Dp, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = DATE_LINE_SEPARATOR, color = color, style = Fonts.Meta, maxLines = 1, softWrap = false)
        ViewCountEye(
            color = color,
            modifier = Modifier.size(width = eyeHeight * CardOverlays.VIEW_COUNT_EYE_ASPECT, height = eyeHeight),
        )
        Spacer(Modifier.width(eyeHeight * 0.4f))
        Text(text = count, color = color, style = Fonts.Meta, maxLines = 1, softWrap = false)
    }
}

/**
 * Eye design 3, "Upper lid and pupil": the curve of an upper eyelid, and a solid round pupil
 * under it, with no lower lid. Build ticket 59. It follows the third card of that ticket's
 * mockup.
 *
 * **Drawn, never a font character**, for the reason spec section 7.3 gives for the watched
 * tick: an emoji's shape and colour come from whatever font the television falls back to.
 *
 * The lid is one quadratic curve whose two ends sit [CardOverlays.VIEW_COUNT_EYE_LID_END_FRACTION]
 * of the way down the box and whose top touches the box's top edge, round caps included. The
 * pupil sits on the box's bottom edge. `CardOverlaysTest` proves every part lands inside the box,
 * because nothing clips it.
 */
@Composable
private fun ViewCountEye(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.height * CardOverlays.VIEW_COUNT_EYE_STROKE_FRACTION
        val half = stroke / 2f
        val lidEnd = size.height * CardOverlays.VIEW_COUNT_EYE_LID_END_FRACTION
        // A quadratic curve reaches halfway from its ends to its control point. Its top should
        // be the stroke's centre line, half a stroke below the box's top edge, so the control
        // point is twice that height above the ends.
        val controlY = 2f * half - lidEnd
        val lid = Path().apply {
            moveTo(half, lidEnd)
            quadraticTo(size.width / 2f, controlY, size.width - half, lidEnd)
        }
        drawPath(
            path = lid,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val pupil = size.height * CardOverlays.VIEW_COUNT_EYE_PUPIL_FRACTION
        drawCircle(
            color = color,
            radius = pupil,
            center = Offset(size.width / 2f, size.height - pupil),
        )
    }
}

/**
 * Lays out its content at its natural width when that fits, and not at all when it does not.
 *
 * It is what lets the view count give way before the date at a large font scale. A count cut
 * short by an ellipsis would read as a smaller number, so the whole count goes or none of it does.
 */
@Composable
private fun WholeOrNothing(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        if (placeable.width <= constraints.maxWidth) {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            layout(0, 0) {}
        }
    }
}

/**
 * The tick itself.
 *
 * Drawn rather than written. The owner allowed a tick emoji when he asked for this marker to move,
 * but an emoji's shape, its colour and whether it brings its own background all come from
 * whatever font the television falls back to, and none of that is the app's to control. This
 * tick is two strokes and a round cap. It looks the same on every set, it takes the green the
 * app chose, and the shape is what has to read from three metres away. Spec section 7.3.
 *
 * The round caps reach a little outside the box handed to it, which is why the square around it
 * is wider than the tick.
 */
@Composable
private fun WatchedTick(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.52f)
            lineTo(size.width * 0.36f, size.height)
            lineTo(size.width, 0f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = size.height * CardOverlays.WATCHED_TICK_STROKE_FRACTION,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
