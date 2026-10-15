package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * and the parental section. The field takes focus as it appears, so a remote's digit keys type
 * straight into it; OK on the keyboard or the button submits.
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

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

    // `TvPin`, `PPin`: a lock, the title, the length, then one box per digit. The canvas's
    // on-screen keypad is a behaviour change (MIGRATION.md round 2 §5) and is not drawn: digits
    // come from the remote's number keys or the keyboard, into the field the boxes are drawn on.
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    Column(
        modifier = modifier,
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
        PinBoxes(
            pin = pin,
            onPin = { pin = it.filter(Char::isDigit).take(PIN_MAX) },
            onDone = { submit() },
            focusRequester = focus,
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
        StudioButton(
            label = stringResource(R.string.action_ok),
            onClick = { submit() },
            variant = StudioButtonVariant.PRIMARY,
            size = StudioButtonSize.MEDIUM,
            enabled = !busy && pin.isNotEmpty(),
        )
    }
}

/**
 * The PIN as the canvas draws it: six boxes, a dot in each digit typed, and an ink edge on the box
 * the next digit goes into. A real text field underneath takes the input — the remote's digits,
 * the keyboard, OK to submit — and is kept out of sight.
 */
@Composable
private fun PinBoxes(
    pin: String,
    onPin: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.small
    BasicTextField(
        value = pin,
        onValueChange = onPin,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { input ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 8.dp)) {
                repeat(PIN_MAX) { index ->
                    val next = focused && index == pin.length
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
            // The field itself, present for input and accessibility but not drawn.
            Box(modifier = Modifier.size(1.dp).alpha(0f)) { input() }
        },
    )
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
