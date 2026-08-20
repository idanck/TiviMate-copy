package com.tivicopy.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.model.OutputFormat
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.settings.SettingsChoice
import com.tivicopy.player.ui.settings.SettingsSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adding a source (FR-SRC-01..03, FR-SRC-06/08).
 *
 * NFR-UX-07 asks for three screens at most between a fresh install and a playing channel: this
 * is screen two — pick a type, fill in what the provider gave you, save, and the first refresh
 * starts immediately.
 */
@Composable
fun AddSourceScreen(
    vm: AppViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(OutputFormat.TS) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            url = uri.toString()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSectionTitle(stringResource(R.string.setup_title))
        Text(
            text = stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsChoice(
            title = stringResource(R.string.setup_title),
            options = listOf(
                SourceType.XTREAM to stringResource(R.string.setup_type_xtream),
                SourceType.M3U_URL to stringResource(R.string.setup_type_m3u),
                SourceType.M3U_FILE to stringResource(R.string.setup_type_m3u_file),
            ),
            selected = type,
            onSelect = { type = it },
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.setup_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        when (type) {
            SourceType.XTREAM -> {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.setup_server)) },
                    placeholder = { Text("http://example.com:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.setup_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.setup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsChoice(
                    title = stringResource(R.string.setup_output_format),
                    options = listOf(
                        OutputFormat.TS to stringResource(R.string.setup_format_ts),
                        OutputFormat.HLS to stringResource(R.string.setup_format_hls),
                    ),
                    selected = format,
                    onSelect = { format = it },
                )
            }

            SourceType.M3U_URL -> {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.setup_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text(stringResource(R.string.setup_epg_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SourceType.M3U_FILE -> {
                Text(url.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { filePicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "*/*")) }) {
                    Text(stringResource(R.string.setup_pick_file))
                }
                OutlinedTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text(stringResource(R.string.setup_epg_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // NFR-SEC-06: plain HTTP is allowed, because most providers only offer it, but the risk
        // is stated rather than hidden.
        if (url.startsWith("http://", ignoreCase = true)) {
            Text(
                text = stringResource(R.string.setup_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        status?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (type == SourceType.XTREAM) {
                TextButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true
                        status = context.getString(R.string.setup_testing)
                        scope.launch {
                            val result = ServiceLocator.sources.probeXtream(url, username, password, format)
                            status = when (result) {
                                is Outcome.Ok -> {
                                    val account = result.value
                                    context.getString(R.string.setup_ok) +
                                        (account.status?.let { " · $it" } ?: "") +
                                        (account.maxConnections?.let { " · $it conn." } ?: "")
                                }
                                is Outcome.Err -> result.error.describe(context)
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.setup_test)) }
            }

            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val playlist = Playlist(
                            name = name.ifBlank { defaultName(context, type) },
                            type = type,
                            url = url.trim(),
                            username = username.takeIf { it.isNotBlank() },
                            password = password.takeIf { it.isNotBlank() },
                            outputFormat = format,
                            epgUrl = epgUrl.takeIf { it.isNotBlank() },
                        )
                        val id = withContext(Dispatchers.IO) { ServiceLocator.sources.savePlaylist(playlist) }
                        ServiceLocator.settings.setActivePlaylist(id)
                        vm.reload()
                        vm.refreshPlaylist(id)
                        busy = false
                        onDone()
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/**
 * The name a source gets when the user leaves the field blank. It is written to the database and
 * shown in the playlist switcher, so it is a resource like anything else the user reads.
 */
private fun defaultName(context: android.content.Context, type: SourceType): String = context.getString(
    when (type) {
        SourceType.XTREAM -> R.string.setup_type_xtream
        SourceType.M3U_URL -> R.string.setup_type_m3u
        SourceType.M3U_FILE -> R.string.setup_type_m3u_file
    },
)
