package com.idanck.shidur.ui.mobile

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.account.AccountState
import com.idanck.shidur.data.account.FirebaseSetup
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.youtube.YtChannelState
import com.idanck.shidur.data.youtube.YtProblem
import com.idanck.shidur.data.youtube.YtSearchKind
import com.idanck.shidur.data.youtube.YtSubsState
import com.idanck.shidur.data.youtube.YtVideo
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioField
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.LiveBadge
import com.idanck.shidur.ui.common.LiveBadgeSize
import com.idanck.shidur.ui.common.YouTubePlayerView
import com.idanck.shidur.ui.common.claim
import com.idanck.shidur.ui.theme.StudioTheme
import com.idanck.shidur.ui.tv.AddChannelDialog
import com.idanck.shidur.ui.tv.ChannelThumb
import com.idanck.shidur.ui.tv.YouTubeSession
import com.idanck.shidur.ui.tv.YtChannelDisc
import com.idanck.shidur.ui.tv.YtPage
import com.idanck.shidur.ui.tv.YtVideoCard
import com.idanck.shidur.ui.tv.liveOf
import com.idanck.shidur.ui.tv.openInYouTube
import com.idanck.shidur.ui.tv.problemText
import com.idanck.shidur.ui.tv.videoMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * YouTube on the phone (ADR-061 stage 4; the canvas's `PYouTube`, `PYouTubePlayer`,
 * `PYouTubeChannel`, `PYouTubeSearch` and `PYouTubeConnect`).
 *
 * A tab between Radio and Recordings with the television's shelves laid out for a thumb. A tap
 * opens the player screen: YouTube's player across the top, everything of Shidur's under it and
 * never on it; turned on its side, the player takes the whole screen. It never shrinks into a mini
 * player over the tabs, since nothing may cover it, and leaving the screen stops it: YouTube's
 * player may not play in the background.
 */

/** The YouTube tab: the shelves, or a channel's page or search over them. */
@Composable
internal fun PhoneYouTube(session: YouTubeSession, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val repo = ServiceLocator.youtube
    val saved by repo.saved.collectAsState()
    val channels by repo.channels.collectAsState()
    val history by repo.history.collectAsState()
    val problem by repo.problem.collectAsState()
    val subs by repo.subs.collectAsState()
    val account by ServiceLocator.account.state.collectAsState()
    val signedIn = account is AccountState.SignedIn
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            repo.refresh()
            delay(REFRESH_MS)
        }
    }
    LaunchedEffect(saved) {
        if (saved.any { it !in repo.channels.value }) repo.refresh(force = true)
    }
    LaunchedEffect(signedIn) {
        while (true) {
            repo.refreshSubscriptions(signedIn, phone = true)
            delay(SUBS_REFRESH_MS)
        }
    }
    BackHandler(enabled = session.page != null) { session.back() }

    val play: (YtVideo) -> Unit = { video ->
        session.play(video)
        onOpenPlayer()
    }
    val live = remember(saved, channels) { liveOf(saved, channels) }

    Box(modifier = modifier.fillMaxSize()) {
        when (val page = session.page) {
            is YtPage.Channel -> PhoneYouTubeChannel(id = page.id, state = channels[page.id], onPlay = play)
            YtPage.Search -> PhoneYouTubeSearch(session = session, onOpenChannel = { session.open(YtPage.Channel(it)) }, onPlay = play)
            null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                problem?.let { p -> item(key = "problem") { Note(problemText(p)) } }
                item(key = "live") {
                    Shelf(stringResource(R.string.yt_rail_live), stringResource(R.string.yt_rail_live_caption)) {
                        if (live.isEmpty() && channels.isNotEmpty()) {
                            Note(stringResource(R.string.yt_no_live))
                        } else {
                            VideoRow(live, onPlay = play)
                        }
                    }
                }
                when (val s = subs) {
                    is YtSubsState.Feed -> item(key = "subs") {
                        Shelf(stringResource(R.string.yt_rail_subs), stringResource(R.string.yt_rail_subs_caption)) {
                            if (s.videos.isEmpty()) {
                                Note(stringResource(if (s.failed) R.string.yt_subs_failed else R.string.yt_channel_empty))
                            } else {
                                VideoRow(s.videos, onPlay = play)
                            }
                        }
                    }
                    is YtSubsState.Invite -> item(key = "subs") {
                        Shelf(stringResource(R.string.yt_rail_subs), null) {
                            PhoneConnectCard(revoked = s.revoked, signedIn = true)
                        }
                    }
                    YtSubsState.Hidden -> Unit
                }
                item(key = "channels") {
                    Shelf(stringResource(R.string.yt_rail_channels), stringResource(R.string.yt_rail_channels_caption)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(saved.mapNotNull { channels[it] }, key = { it.channel.id }) { state ->
                                YtChannelDisc(
                                    label = state.channel.title,
                                    thumb = state.channel.thumb,
                                    live = state.live.isNotEmpty(),
                                    onClick = { session.open(YtPage.Channel(state.channel.id)) },
                                )
                            }
                            item(key = "add") {
                                YtChannelDisc(
                                    label = stringResource(R.string.yt_add_channel),
                                    thumb = null,
                                    live = false,
                                    add = true,
                                    onClick = { adding = true },
                                )
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item(key = "continue") {
                        Shelf(stringResource(R.string.yt_rail_continue), null) {
                            VideoRow(history.map { it.video }, onPlay = play)
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddChannelDialog(
            repo = repo,
            onDismiss = { adding = false },
            onAdded = { state ->
                adding = false
                repo.save(state.channel.id)
            },
        )
    }
}

@Composable
private fun Shelf(title: String, caption: String?, content: @Composable () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.h1, color = studio.ink)
            caption?.let { Text(it, style = type.caption, color = studio.ink3) }
        }
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = StudioTheme.type.meta, color = StudioTheme.colors.ink3, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun VideoRow(videos: List<YtVideo>, onPlay: (YtVideo) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(videos, key = { it.id }) { video ->
            YtVideoCard(video = video, onScreen = false, progress = null, width = CARD_WIDTH, onClick = { onPlay(video) })
        }
    }
}

/** Two cards to a row, for a channel's page and search on a phone. */
@Composable
private fun VideoPairs(videos: List<YtVideo>, onPlay: (YtVideo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        videos.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { video ->
                    Box(modifier = Modifier.weight(1f)) {
                        YtVideoCard(video = video, onScreen = false, progress = null, width = null, onClick = { onPlay(video) })
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Connecting on a phone (`PYouTubeConnect`): the account is already on the device, so Google's
 * own picker asks for read-only YouTube access, and the one-time code it returns for the project's
 * web client goes to the server, which keeps the token (`ytLinkCode`). The phone holds no token.
 */
@Composable
private fun PhoneConnectCard(revoked: Boolean, signedIn: Boolean) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.youtube
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.yt_connect_failed)

    val link: (String) -> Unit = { code ->
        scope.launch {
            busy = true
            repo.linkWithCode(code)
                .onSuccess {
                    failed = null
                    repo.refreshSubscriptions(signedIn = signedIn, phone = true)
                }
                .onFailure { failed = failedText }
            busy = false
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val code = res.data?.let { data -> runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).serverAuthCode }.getOrNull() }
        if (code != null) link(code) else failed = failedText
    }
    val connect: () -> Unit = {
        failed = null
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_READONLY)))
            .requestOfflineAccess(FirebaseSetup.webClientId, true)
            .build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    runCatching { consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                        .onFailure { failed = failedText }
                } else {
                    result.serverAuthCode?.let(link) ?: run { failed = failedText }
                }
            }
            .addOnFailureListener { failed = failedText }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(studio.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (revoked) R.string.yt_connect_revoked_title else R.string.yt_connect_card_title),
            style = type.body.copy(fontWeight = FontWeight.Bold),
            color = studio.ink,
        )
        Text(
            stringResource(if (revoked) R.string.yt_connect_revoked_body else R.string.yt_connect_phone_body),
            style = type.meta,
            color = studio.ink2,
        )
        failed?.let { Text(it, style = type.meta, color = studio.error) }
        StudioButton(
            label = stringResource(R.string.yt_connect_phone_action),
            onClick = connect,
            icon = StudioIcons.Account,
            variant = StudioButtonVariant.PRIMARY,
            enabled = !busy,
        )
    }
}

/**
 * The player screen (`PYouTubePlayer`): YouTube's player across the top with its own controls,
 * what is on it under it, and the other live streams under that. On its side, the player alone.
 */
@Composable
internal fun PhoneYouTubePlayer(
    session: YouTubeSession,
    onClose: () -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = ServiceLocator.youtube
    val state by session.player.state.collectAsState()
    val saved by repo.saved.collectAsState()
    val channels by repo.channels.collectAsState()
    val context = LocalContext.current
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val live = remember(saved, channels) { liveOf(saved, channels) }
    BackHandler(onBack = onClose)
    LaunchedEffect(session) {
        while (true) {
            delay(NOTE_EVERY_MS)
            session.noteCurrent()
        }
    }

    val playable = state.video != null && !state.notEmbeddable && !state.unavailable && !state.liveEnded
    if (landscape) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            YouTubePlayerView(controller = session.player, visible = playable, modifier = Modifier.fillMaxSize())
            if (!playable) PlayerMessage(state, session, live, context)
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(studio.ground)
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            YouTubePlayerView(controller = session.player, visible = playable, modifier = Modifier.fillMaxSize())
            if (!playable) PlayerMessage(state, session, live, context)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val video = state.video
            if (video != null) {
                if (video.live) LiveBadge(label = stringResource(R.string.yt_live_badge), size = LiveBadgeSize.S)
                Text(video.title, style = type.title, color = studio.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                videoMeta(video)?.let { Text(it, style = type.meta, color = studio.ink2) }
                val channel = channels[video.channelId]?.channel
                val isSaved = video.channelId in saved
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.weight(1f).clickable { onOpenChannel(video.channelId) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ChannelThumb(channel?.thumb, video.channelTitle, size = 40.dp)
                        Text(
                            channel?.title ?: video.channelTitle,
                            style = type.body.copy(fontWeight = FontWeight.Bold),
                            color = studio.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StudioButton(
                        label = stringResource(if (isSaved) R.string.yt_saved_channel else R.string.yt_save_channel),
                        onClick = { repo.toggleSaved(video.channelId) },
                        icon = StudioIcons.star(isSaved),
                        iconOnly = true,
                        size = StudioButtonSize.MEDIUM,
                        active = isSaved,
                    )
                }
            }
            val others = live.filter { it.id != state.video?.id }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.yt_rail_live), style = type.h1, color = studio.ink)
                others.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { session.play(v) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(modifier = Modifier.width(140.dp)) {
                            YtVideoCard(video = v, onScreen = false, progress = null, width = null, onClick = { session.play(v) })
                        }
                    }
                }
            }
        }
    }
}

/** What the player screen says where the player would be, when it cannot play. */
@Composable
private fun PlayerMessage(
    state: com.idanck.shidur.ui.common.YouTubePlayerController.State,
    session: YouTubeSession,
    live: List<YtVideo>,
    context: android.content.Context,
) {
    val type = StudioTheme.type
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val offAir = session.offAir
        val text = when {
            state.liveEnded -> stringResource(R.string.yt_live_ended)
            state.notEmbeddable -> stringResource(R.string.yt_not_embeddable)
            state.unavailable || state.error != null -> stringResource(R.string.yt_video_gone)
            offAir != null -> stringResource(R.string.yt_off_air, offAir)
            else -> null
        }
        text?.let { Text(it, style = type.body, color = Color.White) }
        val video = state.video
        if (state.notEmbeddable && video != null) {
            StudioButton(
                label = stringResource(R.string.yt_open_in_app),
                onClick = { openInYouTube(context, video) },
                icon = StudioIcons.Stream,
                variant = StudioButtonVariant.PRIMARY,
            )
        }
        val next = live.firstOrNull { it.id != video?.id }
        if ((state.liveEnded || offAir != null) && next != null) {
            StudioButton(
                label = stringResource(R.string.yt_next_live),
                onClick = { session.play(next) },
                icon = StudioIcons.Forward,
                variant = StudioButtonVariant.PRIMARY,
            )
        }
    }
}

/** A channel's page on a phone (`PYouTubeChannel`). */
@Composable
private fun PhoneYouTubeChannel(id: String, state: YtChannelState?, onPlay: (YtVideo) -> Unit) {
    val repo = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val saved by repo.saved.collectAsState()
    val listed by repo.listed.collectAsState()
    LaunchedEffect(id) { if (state == null) repo.channel(id) }
    var tab by remember(id) { mutableStateOf(0) }
    val numbers = java.text.NumberFormat.getIntegerInstance()
    val isSaved = id in saved
    val inList = listed.any { it.id == id }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChannelThumb(state?.channel?.thumb, state?.channel?.title.orEmpty(), size = 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.yt_channel_kind), style = type.meta, color = studio.ink2)
                Text(state?.channel?.title.orEmpty(), style = type.title, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    state?.channel?.subscribers?.let { stringResource(R.string.yt_subscribers, numbers.format(it)) },
                    state?.channel?.videos?.let { stringResource(R.string.yt_video_count, numbers.format(it)) },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = type.meta, color = studio.ink2)
            }
        }
        // The same two actions as on the television: keep it in "your channels" (primary on a phone),
        // and also put its live stream in the channel list and guide.
        StudioButton(
            label = stringResource(if (isSaved) R.string.yt_remove_channel else R.string.yt_save_channel),
            onClick = { repo.toggleSaved(id) },
            icon = StudioIcons.star(isSaved),
            variant = StudioButtonVariant.PRIMARY,
            active = isSaved,
            modifier = Modifier.fillMaxWidth(),
        )
        StudioButton(
            label = stringResource(if (inList) R.string.yt_remove_from_list else R.string.yt_add_to_list),
            onClick = { if (inList) repo.removeFromList(id) else state?.let(repo::addToList) },
            icon = if (inList) StudioIcons.Check else StudioIcons.Plus,
            active = inList,
            enabled = inList || state != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioChip(label = stringResource(R.string.yt_tab_live), selected = tab == 0, onClick = { tab = 0 })
            StudioChip(label = stringResource(R.string.yt_tab_videos), selected = tab == 1, onClick = { tab = 1 })
        }
        val videos = when {
            state == null -> emptyList()
            tab == 0 -> state.live + state.upcoming
            else -> state.recent
        }
        if (state != null && videos.isEmpty()) Text(stringResource(R.string.yt_channel_empty), style = type.meta, color = studio.ink3)
        VideoPairs(videos, onPlay)
    }
}

/** Search on a phone (`PYouTubeSearch`): the system keyboard, and the server asked once typing pauses. */
@Composable
private fun PhoneYouTubeSearch(session: YouTubeSession, onOpenChannel: (String) -> Unit, onPlay: (YtVideo) -> Unit) {
    val repo = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<YtProblem?>(null) }
    val query = session.searchQuery
    val kind = session.searchKind
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.claim { false } }
    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < 2) {
            problem = null
            return@LaunchedEffect
        }
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
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StudioField(
            value = query,
            onValueChange = { session.searchQuery = it.take(100) },
            placeholder = stringResource(R.string.yt_search),
            icon = StudioIcons.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            focusRequester = field,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                YtSearchKind.ALL to R.string.yt_search_all,
                YtSearchKind.CHANNEL to R.string.yt_search_channels,
                YtSearchKind.LIVE to R.string.yt_search_live,
                YtSearchKind.VIDEO to R.string.yt_search_videos,
            ).forEach { (k, label) ->
                StudioChip(label = stringResource(label), selected = kind == k, onClick = { session.searchKind = k })
            }
        }
        val result = session.searchResult?.takeIf { query.trim().length >= 2 }
        val note = when {
            query.trim().length < 2 -> stringResource(R.string.yt_search_hint)
            problem != null -> problemText(problem ?: YtProblem.OTHER)
            busy && result == null -> stringResource(R.string.yt_search_busy)
            result != null && result.channels.isEmpty() && result.videos.isEmpty() -> stringResource(R.string.yt_search_none)
            else -> null
        }
        note?.let { Text(it, style = type.meta, color = studio.ink3) }
        if (result != null) {
            if (result.channels.isNotEmpty()) {
                Text(stringResource(R.string.yt_search_channels), style = type.h1, color = studio.ink)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    result.channels.forEach { hit ->
                        YtChannelDisc(label = hit.title, thumb = hit.thumb, live = false, onClick = { onOpenChannel(hit.id) })
                    }
                }
            }
            if (result.videos.isNotEmpty()) {
                Text(stringResource(R.string.yt_search_videos_title), style = type.h1, color = studio.ink)
                VideoPairs(result.videos, onPlay)
            }
        }
    }
}

private const val YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
private const val REFRESH_MS = 5 * 60 * 1000L
private const val SUBS_REFRESH_MS = 10 * 60 * 1000L
private const val NOTE_EVERY_MS = 30_000L
private const val SEARCH_PAUSE_MS = 1_200L
private val CARD_WIDTH = 220.dp
