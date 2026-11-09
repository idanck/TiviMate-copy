package com.idanck.shidur.data.account

/** Who is signed in on this device (ADR-059). */
sealed interface AccountState {
    /** This build carries no Firebase project: accounts do not exist here. */
    data object Unavailable : AccountState
    data object SignedOut : AccountState
    data class SignedIn(val uid: String, val name: String?, val email: String?) : AccountState
}

/** How a sign-in with Google ended. */
sealed interface SignInOutcome {
    data object Success : SignInOutcome

    /** The person closed the account picker. Not an error, and not worth a message. */
    data object Cancelled : SignInOutcome

    /** The device has no Google account to offer, or Credential Manager cannot reach one. */
    data object NoAccount : SignInOutcome

    data class Failed(val reason: String?) : SignInOutcome
}

/** A code a television shows, from the createPairing function. */
data class Pairing(
    val code: String,
    /** The code as shown: `ABCD-EFGH`. */
    val display: String,
    /** Proof that this television asked for the code; never shown. */
    val secret: String,
    val expiresAtUtc: Long,
    /** The approval page, with the code in it: what the QR code carries. */
    val url: String,
)

/** One poll of claimPairing. */
sealed interface ClaimOutcome {
    data object Pending : ClaimOutcome
    data object Expired : ClaimOutcome
    data object SignedIn : ClaimOutcome
    data class Failed(val reason: String?) : ClaimOutcome
}

/**
 * Whether the sign-in screen stands in front of the app. Only a build with a Firebase project,
 * with the owner's remote switch on, and nobody signed in: the switch defaults to off, so the
 * first update that carries accounts changes nothing on any device until the owner turns it on.
 */
fun signInRequired(state: AccountState, requireSignIn: Boolean): Boolean =
    requireSignIn && state is AccountState.SignedOut

/**
 * Whether `ensureProfile` should be called for [uid]: not when this device already confirmed the
 * profile of that account ([confirmedUid]), and not while a call for it is running ([runningUid]).
 */
fun profileCheckNeeded(uid: String, confirmedUid: String?, runningUid: String?): Boolean =
    uid != confirmedUid && uid != runningUid

/** Parses the createPairing response; null when it is not one. */
internal fun parsePairing(data: Any?): Pairing? {
    val map = data as? Map<*, *> ?: return null
    val code = map["code"] as? String ?: return null
    val secret = map["secret"] as? String ?: return null
    val url = map["url"] as? String ?: return null
    val expires = (map["expiresAt"] as? Number)?.toLong() ?: return null
    val display = map["display"] as? String ?: "${code.take(4)}-${code.drop(4)}"
    return Pairing(code = code, display = display, secret = secret, expiresAtUtc = expires, url = url)
}

/**
 * Parses a claimPairing response into its status and, only when it is `approved`, the custom
 * token: a token beside any other status is not one to sign in with.
 */
internal fun parseClaim(data: Any?): Pair<String, String?> {
    val map = data as? Map<*, *> ?: return "invalid" to null
    val status = map["status"] as? String ?: return "invalid" to null
    return status to (map["token"] as? String).takeIf { status == "approved" }
}
