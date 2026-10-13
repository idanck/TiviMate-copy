package com.tivicopy.player.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.PinResult

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
        vm.submitPin(pin) { result ->
            busy = false
            pin = ""
            message = when (result) {
                PinResult.Accepted -> null
                PinResult.Wrong -> context.getString(R.string.pin_wrong)
                is PinResult.LockedOut -> context.getString(R.string.pin_locked, result.seconds)
            }
            if (result == PinResult.Accepted) onAccepted()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            placeholder = { Text(stringResource(R.string.pin_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { submit() }, enabled = !busy && pin.isNotEmpty()) {
            Text(stringResource(R.string.action_ok))
        }
    }
}

/**
 * The prompt for whatever is waiting on the PIN — a locked channel to play, a lock to take off.
 * Drawn once, above both shells, so every route onto a locked channel ends here.
 */
@Composable
fun PinRequestDialog(vm: AppViewModel) {
    val request by vm.pinRequest.collectAsState()
    val pending = request ?: return
    // The app's plate, not Material's dialog (ADR-025; FocusVocabularyTest holds it to that).
    AppDialog(
        onDismissRequest = vm::cancelPinRequest,
        title = { Text(pending.channel.displayName) },
        text = { PinEntry(vm = vm, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = vm::cancelPinRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
