package com.tivicopy.player.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.theme.StudioTheme

/*
 * Studio's base components (MIGRATION.md, round 2 §2 and stage 7): what replaced Material's text
 * field, switch, choice, spinner, snackbar and empty message. The button is `StudioButton.kt`, the
 * progress bar `Components.kt`, the dialog `Panel.kt`.
 *
 * Every one of them is shared by both shells and draws the canvas's ten-foot or touch version from
 * `StudioTheme.television`. The television's sizes are the canvas's 1080p pixels halved; the
 * phone's are as drawn. A text size is taken from the shell's own ramp wherever one of its steps
 * is the size drawn, so the viewer's font-scale setting reaches it.
 */

/**
 * `Field` and `PField`: a labelled input on the surface colour, inside a hairline.
 *
 * Focus rings it in the cue — on a television the plate also steps up to `card-focus`; on a phone,
 * where focus means the keyboard is up, it does not. An error rings it in the error colour and
 * turns the helper line into the reason, with an alert glyph so it is not carried by colour alone.
 *
 * Built on [BasicTextField] rather than restyling Material's: the outlined field's floating label,
 * its notch and its indicator are three things the canvas does not draw.
 *
 * @param helper a line under the field: a hint, or with [isError] the reason it was refused
 * @param trailing a control at the end of the field, such as the channel filter's clear button
 */
@Composable
fun StudioField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    icon: ImageVector? = null,
    helper: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    val ringWidth = if (tv) TV_RING else PHONE_RING
    val ring = animatedColour(
        when {
            focused -> studio.cue
            isError -> studio.error
            else -> studio.cue.copy(alpha = 0f)
        },
    )
    val plate = animatedColour(if (focused && tv) studio.cardFocus else studio.surface)
    val inputStyle = type.rowLabel.copy(fontWeight = FontWeight.Normal, color = studio.ink)

    Column(
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(if (tv) 5.dp else 6.dp),
    ) {
        if (!label.isNullOrEmpty()) {
            Text(label, style = type.meta.copy(fontWeight = FontWeight.SemiBold), color = studio.ink2)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = inputStyle,
            cursorBrush = SolidColor(studio.cue),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { input ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (tv) 36.dp else 48.dp)
                        .clip(shape)
                        .background(plate)
                        // The resting hairline and the ring are two lines, each one width for
                        // good: only their colours cross (`Focus.kt`).
                        .border(1.dp, studio.hairline, shape)
                        .border(ringWidth, ring, shape)
                        .padding(horizontal = if (tv) 11.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 7.dp else 10.dp),
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = studio.ink2,
                            modifier = Modifier.size(if (tv) 14.dp else 20.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                            Text(placeholder, style = inputStyle, color = studio.ink3, maxLines = 1)
                        }
                        input()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (!helper.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 6.dp),
            ) {
                val colour = if (isError) studio.error else studio.ink3
                if (isError) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colour,
                        modifier = Modifier.size(if (tv) 11.dp else 16.dp),
                    )
                }
                Text(helper, style = type.caption.copy(fontWeight = FontWeight.Normal), color = colour)
            }
        }
    }
}

/**
 * `Toggle` and `PToggle`: the switch at the end of a setting's row.
 *
 * On, it is ink with a dark knob on a television — the cue is focus there — and the cue with a
 * dark knob on a phone, where the cue marks what is on. Off, it is the faint track with a grey
 * knob. The knob sits at the start when off and slides to the end when on, so it follows the
 * reading direction.
 *
 * Only a picture of the state: the row it sits in is what takes focus and the press. [focused]
 * draws the canvas's own ring for the rare toggle that is focusable by itself.
 */
@Composable
fun StudioToggle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    enabled: Boolean = true,
) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val width = if (tv) 32.dp else 44.dp
    val height = if (tv) 18.dp else 26.dp
    val knob = if (tv) 14.dp else 20.dp
    val inset = if (tv) 2.dp else 3.dp
    val on = if (tv) studio.ink else studio.cue
    val onKnob = if (tv) studio.ground else studio.onCue
    val track = animatedColour(if (checked) on else studio.track)
    val knobColour = animatedColour(if (checked) onKnob else studio.ink2)
    val ring = animatedColour(if (focused) studio.cue else studio.cue.copy(alpha = 0f))
    val travel by animateDpAsState(
        targetValue = if (checked) width - knob - inset else inset,
        animationSpec = tween(Motion.FAST, easing = Motion.Ease),
        label = "toggleKnob",
    )
    val gap = if (tv) 1.5.dp else 3.dp
    val ringWidth = if (tv) 1.5.dp else 3.dp
    Box(
        modifier = modifier
            .size(width, height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            // `0 0 0 3px ground, 0 0 0 6px cue`: a ring outside the track with a gap of ground in
            // between, drawn rather than laid out so the row does not move when it appears.
            .drawBehind {
                val outset = (gap + ringWidth / 2).toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-outset, -outset),
                    size = Size(size.width + outset * 2, size.height + outset * 2),
                    cornerRadius = CornerRadius(size.height / 2 + outset),
                    style = Stroke(width = ringWidth.toPx()),
                )
            }
            .clip(CircleShape)
            .background(track)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
    ) {
        Box(
            modifier = Modifier
                .offset(x = travel, y = inset)
                .size(knob)
                .clip(CircleShape)
                .background(knobColour),
        )
    }
}

/**
 * `Segmented`: a short, mutually exclusive choice laid out in one row — the decoder for a channel.
 *
 * The chosen segment is ink; a focused one becomes the cue and lifts, as every focusable control
 * does. One component for both shells (the canvas's `size="phone"`): only the sizes change.
 */
@Composable
fun <T> StudioSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val outer = RoundedCornerShape(if (tv) 7.dp else 12.dp)
    val inner = RoundedCornerShape(if (tv) 5.dp else 9.dp)
    Row(
        modifier = modifier
            .clip(outer)
            .background(studio.surface)
            .border(1.dp, studio.hairlineSoft, outer)
            .padding(if (tv) 2.dp else 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 2.dp else 4.dp),
    ) {
        options.forEach { (value, label) ->
            var focused by remember { mutableStateOf(false) }
            val chosen = value == selected
            val fill = animatedColour(
                when {
                    focused -> studio.cue
                    chosen -> studio.ink
                    else -> studio.ink.copy(alpha = 0f)
                },
            )
            val content = animatedColour(
                when {
                    focused -> studio.onCue
                    chosen -> studio.ground
                    else -> studio.ink2
                },
            )
            Box(
                modifier = Modifier
                    .height(if (tv) 28.dp else 32.dp)
                    .focusLift(focused, shape = inner)
                    .clip(inner)
                    .background(fill)
                    .onFocusChanged { focused = it.isFocused }
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = StudioTheme.type.meta.copy(
                        fontWeight = if (chosen || focused) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `Loader`: a ring of the track colour with a quarter of it in ink, turning (`st-spin`, 900ms).
 *
 * In the text colour, never the cue: loading is not where the remote is (MIGRATION.md §3).
 *
 * @param label what TalkBack says is loading
 */
@Composable
fun Loader(modifier: Modifier = Modifier, size: Dp = 32.dp, label: String? = null) {
    val studio = StudioTheme.colors
    val turn = rememberInfiniteTransition(label = "loader")
    val angle = turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(LOADER_TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "loaderAngle",
    )
    // `max(3px, size / 14)`: never thinner than a hairline that would shimmer while it turns.
    val stroke = maxOf(1.5.dp, size / 14)
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                if (label != null) contentDescription = label
            }
            // Read in the draw phase: the turning costs a redraw, not a recomposition per frame.
            .graphicsLayer { rotationZ = angle.value },
    ) {
        val width = stroke.toPx()
        val arc = Size(this.size.width - width, this.size.height - width)
        val topLeft = Offset(width / 2, width / 2)
        drawArc(studio.track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width))
        drawArc(studio.ink, -90f, 90f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width))
    }
}

/** `Notice`'s three tones: only the glyph takes the colour; the text stays ink. */
enum class NoticeTone { DEFAULT, OK, ERROR }

/**
 * `Notice` and `PNotice`: a short message that comes and goes by itself, on the panel colour
 * inside a hairline, lifted off what is under it. What replaced Material's snackbar.
 */
@Composable
fun StudioNotice(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: NoticeTone = NoticeTone.DEFAULT,
) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val shape = RoundedCornerShape(if (tv) 9.dp else 14.dp)
    Row(
        modifier = modifier
            .widthIn(max = if (tv) 550.dp else 358.dp)
            .shadow(if (tv) 8.dp else 6.dp, shape)
            .clip(shape)
            .background(studio.panel)
            .border(1.dp, studio.hairline, shape)
            .padding(horizontal = if (tv) 14.dp else 16.dp, vertical = if (tv) 10.dp else 12.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when (tone) {
                    NoticeTone.OK -> studio.ok
                    NoticeTone.ERROR -> studio.error
                    NoticeTone.DEFAULT -> studio.ink2
                },
                modifier = Modifier.size(if (tv) 14.dp else 20.dp),
            )
        }
        // 24px at 1080p is the television's body step; 14 on a phone is its meta step.
        val style = if (tv) StudioTheme.type.body else StudioTheme.type.meta
        Text(text, style = style.copy(fontWeight = FontWeight.Medium), color = studio.ink)
    }
}

/**
 * A message for the app's [SnackbarHostState] that carries a [NoticeTone], so the host can draw a
 * failure as one. No action and no dismiss button: a notice comes and goes by itself.
 */
class NoticeVisuals(
    override val message: String,
    val tone: NoticeTone = NoticeTone.DEFAULT,
) : SnackbarVisuals {
    override val actionLabel: String? get() = null
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Short
}

/**
 * Where the app's messages appear, drawn as [StudioNotice]s: Material's snackbar queue and timing,
 * the canvas's plate. A failure gets the error glyph, so it is not told apart by colour alone.
 */
@Composable
fun StudioNoticeHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val tone = (data.visuals as? NoticeVisuals)?.tone ?: NoticeTone.DEFAULT
        StudioNotice(
            text = data.visuals.message,
            icon = if (tone == NoticeTone.ERROR) Icons.Filled.ErrorOutline else null,
            tone = tone,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * `EmptyState` and `PEmptyState`: what a list says when there is nothing in it — a glyph in a
 * circle, an optional title, the sentence, and optionally the one action that would fill it.
 *
 * The action is the screen's main one: a primary button on a phone; on a television a medium
 * button, which takes focus like any other.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Info,
    title: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = if (tv) 380.dp else 300.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (tv) 9.dp else 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (tv) 56.dp else 64.dp)
                    .clip(CircleShape)
                    .background(studio.card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = studio.ink2,
                    modifier = Modifier.size(if (tv) 26.dp else 30.dp),
                )
            }
            if (!title.isNullOrEmpty()) {
                // 32px at 1080p and 18 on a phone: the h1 step, a little smaller in each shell.
                val h1 = type.h1
                Text(
                    text = title,
                    style = h1.copy(fontSize = h1.fontSize * (if (tv) 16f / 17f else 18f / 20f)),
                    color = studio.ink,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = text,
                // 26px at 1080p is the television's row-label size; 14 on a phone is its meta.
                style = if (tv) type.rowLabel.copy(fontWeight = FontWeight.Normal) else type.meta,
                color = studio.ink2,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                StudioButton(
                    label = action,
                    onClick = onAction,
                    modifier = Modifier.padding(top = 6.dp),
                    variant = if (tv) StudioButtonVariant.SECONDARY else StudioButtonVariant.PRIMARY,
                    size = if (tv) StudioButtonSize.MEDIUM else StudioButtonSize.LARGE,
                )
            }
        }
    }
}

/** The cue ring around a focused field: `--st-ring`, 4px at 1080p and 2dp on a phone. */
private val TV_RING = 2.dp
private val PHONE_RING = 2.dp

/** One turn of the loader (`st-spin`). */
private const val LOADER_TURN_MS = 900
