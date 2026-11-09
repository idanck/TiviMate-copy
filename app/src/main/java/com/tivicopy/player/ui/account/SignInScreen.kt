package com.tivicopy.player.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.account.SignInOutcome
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.UpdatePrompt
import com.tivicopy.player.ui.common.findActivity
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.launch

/**
 * The screen in front of the app while the owner's `requireSignIn` switch is on and nobody is
 * signed in (ADR-059): on a phone the Google button alone, on a television [TvSignIn]. Nothing
 * plays behind it; the update prompt still shows over it, so a build that fixes a broken sign-in
 * can always reach the device.
 */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StudioTheme.colors.ground)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        if (StudioTheme.television) TvSignIn() else Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SignInHeading()
            GoogleSignInBlock(initialFocus = false)
        }
        UpdatePrompt()
    }
}

/** The glyph, the title and the sentence that says why, shared by the phone and the television. */
@Composable
internal fun SignInHeading() {
    Icon(
        imageVector = StudioIcons.Account,
        contentDescription = null,
        tint = StudioTheme.colors.cue,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text = stringResource(R.string.account_sign_in_title),
        style = StudioTheme.type.h1,
        color = StudioTheme.colors.ink,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.account_sign_in_body),
        style = StudioTheme.type.body,
        color = StudioTheme.colors.ink2,
        textAlign = TextAlign.Center,
    )
}

/**
 * "Sign in with Google" and what became of the last attempt. Credential Manager draws the
 * account picker over the activity; this only reports the outcome.
 */
@Composable
internal fun GoogleSignInBlock(initialFocus: Boolean) {
    val account = ServiceLocator.account
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    StudioButton(
        label = stringResource(if (busy) R.string.account_signing_in else R.string.account_sign_in_google),
        onClick = {
            val activity = context.findActivity() ?: return@StudioButton
            if (busy) return@StudioButton
            busy = true
            problem = null
            scope.launch {
                problem = when (account.signInWithGoogle(activity)) {
                    SignInOutcome.Success, SignInOutcome.Cancelled -> null
                    SignInOutcome.NoAccount -> R.string.account_error_no_account
                    is SignInOutcome.Failed -> R.string.account_error_failed
                }
                busy = false
            }
        },
        variant = StudioButtonVariant.PRIMARY,
        enabled = account.canSignInWithGoogle && !busy,
        icon = StudioIcons.Account,
        focusRequester = if (initialFocus) rememberInitialFocus() else null,
    )
    problem?.let {
        Text(
            text = stringResource(it),
            style = StudioTheme.type.meta,
            color = StudioTheme.colors.error,
            textAlign = TextAlign.Center,
        )
    }
}

/** A focus requester that takes focus once it is placed, for the ten-foot layout. */
@Composable
internal fun rememberInitialFocus(): androidx.compose.ui.focus.FocusRequester {
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return requester
}
