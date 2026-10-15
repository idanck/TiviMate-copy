package com.tivicopy.player.ui.common

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.PinResult
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * One PIN field and its verdict (FR-PRF-04/07), shared by the playback prompt, the Settings gate
 * and the parental section.
 *
 * On a phone the field takes focus as it appears and the system keyboard types into it; OK on the
 * keyboard or the button submits. On a television the canvas's keypad (`TvPin`, ADR-034) is the
 * input: the focus starts on its middle key, and the remote's number keys type wherever the focus
 * is — taken here, before the activity can read them as a channel number.
 */
@Composable
fun PinEntry(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.pin_enter),
    onAccepted: () -> Unit = {},
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val tv = StudioTheme.television
    LaunchedEffect(Unit) {
        // The keypad's key is laid out a frame later than the field was.
        if (tv) withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    fun submit() {
        if (busy || pin.isEmpty()) return
        busy = true
        val submitted = pin
        vm.submitPin(submitted) { result ->
            busy = false
            // Digits typed while the check ran are the next attempt, not this one's leftovers.
            if (pin == submitted) pin = ""
            message = when (result) {
                PinResult.Accepted -> null
                PinResult.Wrong -> context.getString(R.string.pin_wrong)
                is PinResult.LockedOut -> context.getString(R.string.pin_locked, result.seconds)
            }
            if (result == PinResult.Accepted) onAccepted()
        }
    }

    fun typeDigit(digit: Char) {
        if (pin.length < PIN_MAX) pin += digit
    }

    // `TvPin`, `PPin`: a lock, the title, the length, then one box per digit — and on a
    // television, the keypad under them.
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(
        modifier = modifier.then(
            if (tv) {
                Modifier.onPreviewKeyEvent { event ->
                    val digit = remoteDigit(event.nativeKeyEvent.keyCode)
                    val erase = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL
                    if (digit == null && !erase) return@onPreviewKeyEvent false
                    // Both halves of the press are taken, so the key-up cannot reach anything
                    // else either; the digit lands once, on the way down.
                    if (event.type == KeyEventType.KeyDown) {
                        if (digit != null) {
                            typeDigit(digit)
                        } else {
                            pin = pin.dropLast(1)
                        }
                    }
                    true
                }
            } else {
                Modifier
            },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = StudioIcons.Lock,
            contentDescription = null,
            tint = studio.ink2,
            modifier = Modifier.size(if (tv) 22.dp else 28.dp),
        )
        Text(
            text = title,
            style = if (tv) type.h1.copy(fontSize = type.h1.fontSize * (20f / 17f)) else type.h1,
            color = studio.ink,
            textAlign = TextAlign.Center,
        )
        Text(text = stringResource(R.string.pin_hint), style = type.meta, color = studio.ink2)
        if (tv) {
            PinDots(pin = pin, active = true, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            PinBoxes(
                pin = pin,
                onPin = { pin = it.filter(Char::isDigit).take(PIN_MAX) },
                onDone = { submit() },
                focusRequester = focus,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = StudioIcons.Alert,
                    contentDescription = null,
                    tint = studio.error,
                    modifier = Modifier.size(if (tv) 11.dp else 16.dp),
                )
                Text(it, style = type.caption.copy(fontWeight = FontWeight.Normal), color = studio.error)
            }
        }
        if (tv) {
            PinKeypad(
                onDigit = { typeDigit(it) },
                onErase = { pin = pin.dropLast(1) },
                onConfirm = { submit() },
                focusRequester = focus,
            )
            Text(
                text = stringResource(R.string.pin_keypad_hint),
                style = type.caption.copy(fontWeight = FontWeight.Normal),
                color = studio.ink2,
                textAlign = TextAlign.Center,
            )
        } else {
            StudioButton(
                label = stringResource(R.string.action_ok),
                onClick = { submit() },
                variant = StudioButtonVariant.PRIMARY,
                size = StudioButtonSize.MEDIUM,
                enabled = !busy && pin.isNotEmpty(),
            )
        }
    }
}

/** The digit a remote's number key, or a keyboard's number pad, stands for; null for any other. */
private fun remoteDigit(keyCode: Int): Char? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}

/**
 * `TvPin`'s keypad: nine digits, then erase, zero and confirm, three to a row. Each key is a
 * control that becomes the cue under the remote, as a button does. The focus starts on 5, the
 * middle, so no digit is more than two presses away.
 *
 * The canvas labels the bottom-left key "cancel"; it erases the last digit here, because the
 * prompt already has two ways to cancel — Back, and its own button (ADR-034).
 */
@Composable
private fun PinKeypad(
    onDigit: (Char) -> Unit,
    onErase: () -> Unit,
    onConfirm: () -> Unit,
    focusRequester: FocusRequester,
) {
    val rows = listOf("123", "456", "789")
    Column(
        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                digits.forEach { digit ->
                    KeypadKey(
                        label = digit.toString(),
                        onClick = { onDigit(digit) },
                        focusRequester = if (digit == '5') focusRequester else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeypadKey(
                icon = StudioIcons.Back,
                label = stringResource(R.string.pin_erase),
                onClick = onErase,
                modifier = Modifier.weight(1f),
            )
            KeypadKey(label = "0", onClick = { onDigit('0') }, modifier = Modifier.weight(1f))
            KeypadKey(
                icon = StudioIcons.Check,
                label = stringResource(R.string.action_ok),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One key of [PinKeypad]: a digit, or a glyph standing for [label]. */
@Composable
private fun KeypadKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val content = filledContentColour(focused)
    Box(
        modifier = modifier
            // 80px at 1080p.
            .height(40.dp)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // `clickable` carries the focus target on its own (`StudioButton`).
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        } else {
            Text(
                // A digit is a digit in either reading direction.
                text = label,
                // 34px at 1080p.
                style = StudioTheme.type.h1.copy(fontWeight = FontWeight.SemiBold),
                color = content,
            )
        }
    }
}

/**
 * The PIN as the canvas draws it: six boxes, a dot in each digit typed, and an ink edge on the box
 * the next digit goes into. A real text field underneath takes the phone's input — the keyboard,
 * Done to submit — and is kept out of sight.
 */
@Composable
private fun PinBoxes(
    pin: String,
    onPin: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = pin,
        onValueChange = onPin,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { input ->
            PinDots(pin = pin, active = focused)
            // The field itself, present for input and accessibility but not drawn.
            Box(modifier = Modifier.size(1.dp).alpha(0f)) { input() }
        },
    )
}

/**
 * The six boxes themselves, a dot in each digit typed and an ink edge on the box the next digit
 * goes into while [active].
 */
@Composable
private fun PinDots(pin: String, active: Boolean, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 8.dp),
    ) {
        repeat(PIN_MAX) { index ->
            val next = active && index == pin.length
            Box(
                modifier = Modifier
                    .size(width = if (tv) 28.dp else 40.dp, height = if (tv) 34.dp else 48.dp)
                    .clip(shape)
                    .background(studio.surface)
                    // One width for every box; only the colour marks the next one.
                    .border(1.dp, if (next) studio.ink else studio.hairline, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (index < pin.length) {
                    Box(
                        modifier = Modifier
                            .size(if (tv) 8.dp else 12.dp)
                            .clip(CircleShape)
                            .background(studio.ink),
                    )
                }
            }
        }
    }
}

/** The longest PIN the app accepts (FR-PRF-04). */
private const val PIN_MAX = 6

/**
 * The prompt for whatever is waiting on the PIN — a locked channel to play, a lock to take off.
 * Drawn once, above both shells, so every route onto a locked channel ends here.
 */
@Composable
fun PinRequestDialog(vm: AppViewModel) {
    val request by vm.pinRequest.collectAsState()
    val pending = request ?: return
    // The app's dialog, not Material's (ADR-025; FocusVocabularyTest holds it to that).
    StudioDialog(
        onDismissRequest = vm::cancelPinRequest,
        title = pending.channel.displayName,
        buttons = {
            DialogButton(label = stringResource(R.string.action_cancel), onClick = vm::cancelPinRequest)
        },
    ) {
        PinEntry(vm = vm, modifier = Modifier.fillMaxWidth())
    }
}
