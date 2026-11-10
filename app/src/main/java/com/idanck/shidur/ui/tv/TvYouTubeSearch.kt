package com.idanck.shidur.ui.tv

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.youtube.YtLinkCode
import com.idanck.shidur.data.youtube.YtLinkPoll
import com.idanck.shidur.data.youtube.YtProblem
import com.idanck.shidur.data.youtube.YtSearchKind
import com.idanck.shidur.data.youtube.YtVideo
import com.idanck.shidur.ui.account.QrCode
import com.idanck.shidur.ui.common.DialogButton
import com.idanck.shidur.ui.common.FocusFill
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioDialog
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.claim
import com.idanck.shidur.ui.common.filledContentColour
import com.idanck.shidur.ui.common.focusLift
import com.idanck.shidur.ui.common.focusSurface
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/*
 * YouTube's search and connecting an account, on the television (ADR-061 stage 3; the canvas's
 * `TvYouTubeSearch` and `TvYouTubeConnect`).
 */

/**
 * Search inside YouTube's screen. Shidur's own keyboard, keys the remote walks, at the start edge;
 * the results beside it fill in once typing pauses, so the project's searches a day (about a
 * hundred, for everybody) are not spent on every letter. The server keeps every answer for six
 * hours. Voice search goes through Android's speech service where the television has one.
 */
@Composable
internal fun YouTubeSearchPage(
    session: YouTubeSession,
    onOpenChannel: (String) -> Unit,
    onPlay: (YtVideo) -> Unit,
) {
    val repo = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<YtProblem?>(null) }
    val query = session.searchQuery
    val kind = session.searchKind

    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            problem = null
            return@LaunchedEffect
        }
        // Coming back from a channel opened from here: what is on screen is already the answer.
        if (session.searchedFor == (q to kind) && session.searchResult != null) return@LaunchedEffect
        delay(SEARCH_PAUSE_MS)
        busy = true
        repo.search(q, kind)
            .onSuccess {
                session.searchResult = it
                session.searchedFor = q to kind
                problem = null
            }
            .onFailure { problem = (it as? YouTubeRepository.YtException)?.problem ?: YtProblem.OTHER }
        busy = false
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        YouTubeKeyboard(
            text = query,
            onText = { session.searchQuery = it.take(MAX_QUERY) },
            modifier = Modifier.width(KEYBOARD_WIDTH),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.focusGroup()) {
                listOf(
                    YtSearchKind.ALL to R.string.yt_search_all,
                    YtSearchKind.CHANNEL to R.string.yt_search_channels,
                    YtSearchKind.LIVE to R.string.yt_search_live,
                    YtSearchKind.VIDEO to R.string.yt_search_videos,
                ).forEach { (k, label) ->
                    StudioChip(label = stringResource(label), selected = kind == k, onClick = { session.searchKind = k })
                }
            }
            val result = session.searchResult?.takeIf { query.trim().length >= MIN_QUERY }
            val note = when {
                query.trim().length < MIN_QUERY -> stringResource(R.string.yt_search_hint)
                problem != null -> problemText(problem ?: YtProblem.OTHER)
                busy && result == null -> stringResource(R.string.yt_search_busy)
                result != null && result.channels.isEmpty() && result.videos.isEmpty() -> stringResource(R.string.yt_search_none)
                else -> null
            }
            note?.let { Text(it, style = type.meta, color = studio.ink3) }
            if (result != null) {
                if (result.channels.isNotEmpty()) {
                    Text(stringResource(R.string.yt_search_channels), style = type.h1, color = studio.ink)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(result.channels, key = { it.id }) { hit ->
                            YtChannelDisc(label = hit.title, thumb = hit.thumb, live = false, onClick = { onOpenChannel(hit.id) })
                        }
                    }
                }
                if (result.videos.isNotEmpty()) {
                    Text(stringResource(R.string.yt_search_videos_title), style = type.h1, color = studio.ink)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(result.videos, key = { it.id }) { video ->
                            YtVideoCard(video = video, onScreen = false, progress = null, width = null, onClick = { onPlay(video) })
                        }
                    }
                }
            }
        }
    }
}

/** The keyboard: letters in rows of eight, Hebrew or English, then space, delete, language and voice. */
@Composable
private fun YouTubeKeyboard(text: String, onText: (String) -> Unit, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var hebrew by remember { mutableStateOf(true) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.claim { false } }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
        }
    }
    val keys = if (hebrew) HEBREW_KEYS else ENGLISH_KEYS
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(stringResource(R.string.yt_search_title), style = type.titleSmall, color = studio.ink)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(studio.card)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(StudioIcons.Search, contentDescription = null, tint = studio.ink2, modifier = Modifier.size(14.dp))
            Text(
                text = text,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.width(1.5.dp).height(17.dp).background(studio.cue))
        }
        Column(modifier = Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            keys.chunked(KEYS_PER_ROW).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    chunk.forEachIndexed { col, key ->
                        KeyboardKey(
                            label = key,
                            onClick = { onText(text + key) },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (row == 0 && col == 0) Modifier.focusRequester(firstKey) else Modifier),
                        )
                    }
                    // A short last row keeps its keys the width of the others.
                    repeat(KEYS_PER_ROW - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.focusGroup()) {
            StudioButton(
                label = stringResource(R.string.yt_key_space),
                onClick = { if (text.isNotEmpty() && !text.endsWith(" ")) onText("$text ") },
                size = StudioButtonSize.MEDIUM,
            )
            StudioButton(
                label = stringResource(R.string.yt_key_delete),
                onClick = { if (text.isNotEmpty()) onText(text.dropLast(1)) },
                icon = StudioIcons.Back,
                size = StudioButtonSize.MEDIUM,
            )
            StudioButton(
                label = if (hebrew) "ABC" else "אבג",
                onClick = { hebrew = !hebrew },
                size = StudioButtonSize.MEDIUM,
            )
            StudioButton(
                label = stringResource(R.string.yt_key_voice),
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hebrew) "he-IL" else "en-US")
                    runCatching { voice.launch(intent) }
                },
                icon = StudioIcons.Audio,
                iconOnly = true,
                size = StudioButtonSize.MEDIUM,
            )
        }
    }
}

@Composable
private fun KeyboardKey(label: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(31.dp)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = StudioTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = filledContentColour(focused = focused),
        )
    }
}

/** The subscriptions rail before an account is connected (`TvYouTube`, account off), or after it was lost (`TvYouTubeStates`). */
@Composable
internal fun YouTubeConnectCard(revoked: Boolean, onConnect: () -> Unit, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = modifier
            .width(CONNECT_CARD_WIDTH)
            .clip(RoundedCornerShape(14.dp))
            .background(studio.card)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (revoked) R.string.yt_connect_revoked_title else R.string.yt_connect_card_title),
                style = type.body.copy(fontWeight = FontWeight.Bold),
                color = studio.ink,
            )
            Text(
                stringResource(if (revoked) R.string.yt_connect_revoked_body else R.string.yt_connect_card_body),
                style = type.meta,
                color = studio.ink2,
                maxLines = 2,
            )
        }
        StudioButton(
            label = stringResource(if (revoked) R.string.yt_connect_again else R.string.yt_connect_action),
            onClick = onConnect,
            icon = StudioIcons.Plus,
            size = StudioButtonSize.MEDIUM,
        )
    }
}

/**
 * Connecting a YouTube account on the television (`TvYouTubeConnect`): Google's flow for TVs and
 * limited-input devices. The television shows a code; the viewer enters it at Google's address on
 * a phone or a computer and approves read-only access. The token stays on the server. The YouTube
 * screen holds its player paused and its frame empty while this is open.
 */
@Composable
internal fun ConnectYouTubeDialog(repo: YouTubeRepository, onDismiss: () -> Unit, onLinked: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var code by remember { mutableStateOf<YtLinkCode?>(null) }
    var problem by remember { mutableStateOf<YtProblem?>(null) }
    var outcome by remember { mutableStateOf<YtLinkPoll?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var expiresAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(attempt) {
        code = null
        problem = null
        outcome = null
        val started = repo.linkStart()
        val c = started.getOrNull()
        if (c == null) {
            problem = (started.exceptionOrNull() as? YouTubeRepository.YtException)?.problem ?: YtProblem.OTHER
            return@LaunchedEffect
        }
        code = c
        expiresAt = SystemClock.elapsedRealtime() + c.expiresInSeconds * 1000L
        while (SystemClock.elapsedRealtime() < expiresAt) {
            delay(c.intervalSeconds.coerceAtLeast(5) * 1000L)
            when (repo.linkPoll().getOrNull()) {
                YtLinkPoll.LINKED -> {
                    onLinked()
                    return@LaunchedEffect
                }
                YtLinkPoll.DENIED -> {
                    outcome = YtLinkPoll.DENIED
                    return@LaunchedEffect
                }
                YtLinkPoll.EXPIRED -> {
                    outcome = YtLinkPoll.EXPIRED
                    return@LaunchedEffect
                }
                // Pending, or one poll that failed: ask again at the next interval.
                YtLinkPoll.PENDING, null -> Unit
            }
        }
        outcome = YtLinkPoll.EXPIRED
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(15_000)
        }
    }

    val failed = problem != null || outcome != null
    StudioDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.yt_connect_title),
        icon = StudioIcons.Account,
        buttons = {
            if (failed) {
                DialogButton(
                    label = stringResource(R.string.yt_connect_retry),
                    onClick = { attempt++ },
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
            }
            DialogButton(label = stringResource(R.string.yt_connect_later), onClick = onDismiss, initialFocus = !failed)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.yt_connect_body), style = type.body, color = studio.ink2)
                val c = code
                when {
                    problem != null -> Text(problemText(problem ?: YtProblem.OTHER), style = type.body, color = studio.error)
                    outcome == YtLinkPoll.DENIED -> Text(stringResource(R.string.yt_connect_denied), style = type.body, color = studio.error)
                    outcome == YtLinkPoll.EXPIRED -> Text(stringResource(R.string.yt_connect_expired), style = type.body, color = studio.error)
                    c == null -> Text(stringResource(R.string.yt_connect_starting), style = type.meta, color = studio.ink3)
                    else -> {
                        Text(stringResource(R.string.yt_connect_step1, TimeModel.ltr(c.verificationUrl.removePrefix("https://").removePrefix("http://"))), style = type.body, color = studio.ink)
                        Text(stringResource(R.string.yt_connect_step2), style = type.body, color = studio.ink)
                        Text(
                            TimeModel.ltr(c.userCode),
                            style = type.hero.copy(fontWeight = FontWeight.ExtraBold),
                            color = studio.ink,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.yt_connect_step3), style = type.body, color = studio.ink)
                        val minutes = ((expiresAt - now) / 60_000L).coerceAtLeast(0L)
                        Text(stringResource(R.string.yt_connect_waiting, minutes.toInt()), style = type.meta, color = studio.ink3)
                    }
                }
            }
            code?.takeIf { problem == null && outcome == null }?.let { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QrCode(text = c.verificationUrl, description = stringResource(R.string.yt_connect_qr), size = 150.dp)
                    Text(
                        stringResource(R.string.yt_connect_qr),
                        style = type.caption,
                        color = studio.ink3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(150.dp),
                    )
                }
            }
        }
    }
}

private const val MIN_QUERY = 2
private const val MAX_QUERY = 100

/** How long typing has to pause before the server is asked. */
private const val SEARCH_PAUSE_MS = 1_200L

private const val KEYS_PER_ROW = 8
private val KEYBOARD_WIDTH = 300.dp
private val CONNECT_CARD_WIDTH = 450.dp

private val HEBREW_KEYS = listOf(
    "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח",
    "ט", "י", "כ", "ל", "מ", "נ", "ס", "ע",
    "פ", "צ", "ק", "ר", "ש", "ת", "ך", "ם",
    "ן", "ף", "ץ", "1", "2", "3", "4", "5",
    "6", "7", "8", "9", "0", "'", "-", "@",
)

private val ENGLISH_KEYS = listOf(
    "a", "b", "c", "d", "e", "f", "g", "h",
    "i", "j", "k", "l", "m", "n", "o", "p",
    "q", "r", "s", "t", "u", "v", "w", "x",
    "y", "z", "1", "2", "3", "4", "5", "6",
    "7", "8", "9", "0", ".", "-", "_", "@",
)
