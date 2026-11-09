package com.tivicopy.player.ui.account

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.account.ClaimOutcome
import com.tivicopy.player.data.account.Pairing
import com.tivicopy.player.ui.common.GlassMaterial
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.glass
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/**
 * The television's sign-in (ADR-059), D-pad only. Two routes side by side:
 *
 * - **Sign in with Google on the television**, through Credential Manager — only where it can
 *   exist, Android 14 and later with a recent Google Play services (developer.android.com/identity/
 *   form-factors). On an older television the button is not drawn at all.
 * - **A QR code and a short code**, always: the phone opens the approval page, signs in with
 *   Google and approves; this screen polls until it is approved and signs itself in.
 */
@Composable
internal fun TvSignIn() {
    val onTelevision = googleOnThisTelevision()
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SignInHeading()
            if (onTelevision) {
                Text(
                    text = stringResource(R.string.account_tv_on_this_tv),
                    style = StudioTheme.type.meta,
                    color = StudioTheme.colors.ink2,
                    textAlign = TextAlign.Center,
                )
                GoogleSignInBlock(initialFocus = true)
            }
        }
        PairingPanel(takesFocus = !onTelevision, modifier = Modifier.weight(1f))
    }
}

/**
 * Settings → Account on a television that is signed out: the same two routes as [TvSignIn], for
 * when the owner's switch is off and the viewer signs in of their own accord.
 */
@Composable
internal fun TvAccountSignIn() {
    if (googleOnThisTelevision()) GoogleSignInBlock(initialFocus = false)
    PairingPanel(takesFocus = false, modifier = Modifier.padding(12.dp))
}

/** Credential Manager's Sign in with Google reaches televisions from Android 14 (API 34). */
private fun googleOnThisTelevision(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ServiceLocator.account.canSignInWithGoogle

/**
 * The QR code and the code, and the polling behind them. A code lasts ten minutes; a new one
 * follows on its own [MAX_CODES] times, and then the screen waits for OK, so a television left on
 * this screen overnight does not keep the Functions busy until morning.
 */
@Composable
private fun PairingPanel(takesFocus: Boolean, modifier: Modifier = Modifier) {
    val account = ServiceLocator.account
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var stopped by remember { mutableStateOf<Int?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        stopped = null
        repeat(MAX_CODES) {
            val next = account.createPairing().getOrNull()
            if (next == null) {
                pairing = null
                stopped = R.string.account_tv_code_failed
                return@LaunchedEffect
            }
            pairing = next
            var failures = 0
            while (System.currentTimeMillis() < next.expiresAtUtc) {
                delay(POLL_INTERVAL_MS)
                when (account.claimPairing(next)) {
                    // The account state changes and the screen goes away by itself.
                    ClaimOutcome.SignedIn -> return@LaunchedEffect
                    ClaimOutcome.Expired -> break
                    ClaimOutcome.Pending -> failures = 0
                    is ClaimOutcome.Failed -> if (++failures >= MAX_FAILED_POLLS) {
                        pairing = null
                        stopped = R.string.account_tv_code_failed
                        return@LaunchedEffect
                    }
                }
            }
        }
        pairing = null
        stopped = R.string.account_tv_code_paused
    }

    Column(
        modifier = modifier
            .glass(GlassMaterial.REGULAR, RoundedCornerShape(24.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.account_tv_pair_title),
            style = StudioTheme.type.titleSmall,
            color = StudioTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        val current = pairing
        val reason = stopped
        when {
            current != null -> {
                QrCode(
                    text = current.url,
                    description = stringResource(R.string.account_tv_qr_description),
                    size = 200.dp,
                )
                Text(
                    text = current.display,
                    style = StudioTheme.type.hero.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    color = StudioTheme.colors.ink,
                )
                Text(
                    text = stringResource(R.string.account_tv_pair_steps, pageAddress(current.url)),
                    style = StudioTheme.type.meta,
                    color = StudioTheme.colors.ink2,
                    textAlign = TextAlign.Center,
                )
            }
            reason != null -> {
                Text(
                    text = stringResource(reason),
                    style = StudioTheme.type.body,
                    color = StudioTheme.colors.ink2,
                    textAlign = TextAlign.Center,
                )
                StudioButton(
                    label = stringResource(R.string.account_tv_new_code),
                    onClick = { round++ },
                    variant = StudioButtonVariant.PRIMARY,
                    size = StudioButtonSize.MEDIUM,
                    focusRequester = if (takesFocus) rememberInitialFocus() else null,
                )
            }
            else -> Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.account_tv_code_loading),
                    style = StudioTheme.type.meta,
                    color = StudioTheme.colors.ink2,
                )
            }
        }
    }
}

/** `https://example.web.app/pair?code=…` → `example.web.app/pair`, short enough to type. */
internal fun pageAddress(url: String): String =
    url.substringAfter("://").substringBefore('?')

private const val POLL_INTERVAL_MS = 4_000L
private const val MAX_CODES = 3
private const val MAX_FAILED_POLLS = 5
