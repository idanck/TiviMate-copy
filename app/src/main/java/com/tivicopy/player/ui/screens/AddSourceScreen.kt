package com.tivicopy.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.NoticeTone
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioChip
import com.tivicopy.player.ui.common.panelSurface
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tivicopy.player.ui.common.StudioIcons

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
    // How the connection test came out: the verdict is green with a check, a failure the error
    // colour with an alert (MIGRATION.md §3). It used to be `primary` either way, which is now the
    // cue — the focus colour — and said "success" to a refused login too.
    var statusTone by remember { mutableStateOf(NoticeTone.DEFAULT) }
    var statusDetail by remember { mutableStateOf<String?>(null) }
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

    val tv = StudioTheme.television
    val studio = StudioTheme.colors
    val plainHttp = url.startsWith("http://", ignoreCase = true)

    // `TvAddSource`'s side panel: how an Xtream account's streams are delivered, and the plain-HTTP
    // warning. Beside the form on a television; under it on a phone (`PAddSource`).
    val aside: @Composable () -> Unit = {
        if (type == SourceType.XTREAM) {
            Text(
                text = stringResource(R.string.setup_output_format),
                style = StudioTheme.type.rowLabel,
                color = studio.ink,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            listOf(
                OutputFormat.TS to stringResource(R.string.setup_format_ts),
                OutputFormat.HLS to stringResource(R.string.setup_format_hls),
            ).forEach { (value, label) ->
                ListRow(
                    label = label,
                    onClick = { format = value },
                    selected = format == value,
                    trail = if (format == value) RowTrail.CHECK else RowTrail.NONE,
                )
            }
        }
        // NFR-SEC-06: plain HTTP is allowed, because most providers only offer it, but the risk
        // is stated rather than hidden.
        if (plainHttp) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = StudioIcons.Alert,
                    contentDescription = null,
                    tint = studio.error,
                    modifier = Modifier.padding(top = 1.dp).size(if (tv) 11.dp else 16.dp),
                )
                Text(
                    text = stringResource(R.string.setup_http_warning),
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                )
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (tv) 27.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).widthIn(max = 500.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 9.dp else 12.dp),
        ) {
            // `TvAddSource`: a Karantina title, the sentence that says what the app is, and the three
            // kinds of source as chips.
            // A phone's title is its top bar's (`PAddSource`).
            if (tv) {
                Text(text = stringResource(R.string.setup_title), style = StudioTheme.type.title, color = studio.ink)
            }
            Text(
                text = stringResource(R.string.setup_intro),
                style = if (tv) StudioTheme.type.body else StudioTheme.type.meta,
                color = studio.ink2,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    SourceType.XTREAM to stringResource(R.string.setup_type_xtream),
                    SourceType.M3U_URL to stringResource(R.string.setup_type_m3u),
                    SourceType.M3U_FILE to stringResource(R.string.setup_type_m3u_file),
                ).forEach { (value, label) ->
                    StudioChip(label = label, selected = type == value, onClick = { type = value })
                }
            }

            StudioField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.setup_name),
                editOnPress = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (type) {
                SourceType.XTREAM -> {
                    StudioField(
                        value = url,
                        onValueChange = { url = it },
                        label = stringResource(R.string.setup_server),
                        placeholder = "http://example.com:8080",
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StudioField(
                        value = username,
                        onValueChange = { username = it },
                        label = stringResource(R.string.setup_username),
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StudioField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.setup_password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SourceType.M3U_URL -> {
                    StudioField(
                        value = url,
                        onValueChange = { url = it },
                        label = stringResource(R.string.setup_url),
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StudioField(
                        value = epgUrl,
                        onValueChange = { epgUrl = it },
                        label = stringResource(R.string.setup_epg_url),
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SourceType.M3U_FILE -> {
                    Text(url.ifBlank { "—" }, style = StudioTheme.type.meta, color = studio.ink2)
                    StudioButton(
                        label = stringResource(R.string.setup_pick_file),
                        onClick = { filePicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "*/*")) },
                        variant = StudioButtonVariant.GHOST,
                        size = StudioButtonSize.MEDIUM,
                    )
                    StudioField(
                        value = epgUrl,
                        onValueChange = { epgUrl = it },
                        label = stringResource(R.string.setup_epg_url),
                        editOnPress = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!tv) aside()

            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (type == SourceType.XTREAM) {
                    StudioButton(
                        label = stringResource(R.string.setup_test),
                        icon = StudioIcons.Network,
                        size = if (tv) StudioButtonSize.MEDIUM else StudioButtonSize.LARGE,
                        variant = StudioButtonVariant.SECONDARY,
                        enabled = !busy && url.isNotBlank(),
                        onClick = {
                            busy = true
                            status = context.getString(R.string.setup_testing)
                            statusTone = NoticeTone.DEFAULT
                            statusDetail = null
                            scope.launch {
                                val result = ServiceLocator.sources.probeXtream(url, username, password, format)
                                when (result) {
                                    is Outcome.Ok -> {
                                        val account = result.value
                                        status = context.getString(R.string.setup_ok)
                                        statusTone = NoticeTone.OK
                                        statusDetail = listOfNotNull(
                                            account.status,
                                            account.maxConnections?.let { context.getString(R.string.setup_max_connections, it) },
                                        ).joinToString(" · ").ifEmpty { null }
                                    }
                                    is Outcome.Err -> {
                                        status = result.error.describe(context)
                                        statusTone = NoticeTone.ERROR
                                    }
                                }
                                busy = false
                            }
                        },
                    )
                }

                StudioButton(
                    label = stringResource(R.string.action_save),
                    icon = StudioIcons.Check,
                    size = if (tv) StudioButtonSize.MEDIUM else StudioButtonSize.LARGE,
                    variant = StudioButtonVariant.PRIMARY,
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
                )
            }
            status?.let { TestResult(text = it, detail = statusDetail, tone = statusTone) }
        }
        if (tv) {
            Column(
                modifier = Modifier
                    .width(290.dp)
                    .panelSurface(opacity = 1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                aside()
            }
        }
    }
}

/**
 * The connection test's verdict (`TvAddSource`, `PAddSource`): a check and the verdict in the
 * success colour, with the account's details after it in the secondary ink; a failure in the error
 * colour with an alert glyph, so neither is told apart by colour alone. While the test runs it is
 * a plain line in the secondary ink.
 */
@Composable
private fun TestResult(text: String, detail: String?, tone: NoticeTone) {
    val studio = StudioTheme.colors
    val colour = when (tone) {
        NoticeTone.OK -> studio.ok
        NoticeTone.ERROR -> studio.error
        NoticeTone.DEFAULT -> studio.ink2
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tone != NoticeTone.DEFAULT) {
            Icon(
                imageVector = if (tone == NoticeTone.OK) StudioIcons.Check else StudioIcons.Alert,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(if (StudioTheme.television) 13.dp else 18.dp),
            )
        }
        Text(
            text = buildAnnotatedString {
                append(text)
                if (detail != null) {
                    withStyle(SpanStyle(color = studio.ink2)) { append(" · $detail") }
                }
            },
            style = StudioTheme.type.meta,
            color = colour,
        )
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
