package com.tivicopy.player.data.account

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await

/**
 * The account on this device (ADR-059): Firebase Auth, signed in with Google through Credential
 * Manager or, on a television, with a custom token a phone approved; and the owner's remote
 * `requireSignIn` switch, read from `config/app` in Firestore.
 *
 * Sits beside the local data and never under it: playlists, favourites and settings stay in
 * SQLite and SharedPreferences (ADR-006), signed in or not.
 *
 * In a build without a Firebase project every call is a no-op and [state] stays
 * [AccountState.Unavailable], so nothing here can stop such a build from starting.
 */
class AccountRepository(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean = FirebaseSetup.isConfigured

    private val _state = MutableStateFlow<AccountState>(
        if (isConfigured) AccountState.SignedOut else AccountState.Unavailable,
    )
    val state: StateFlow<AccountState> = _state

    /**
     * The owner's switch. Off until Firestore says otherwise, and the last answer is kept, so a
     * television that starts without a network is not let in merely because it could not ask.
     */
    private val _requireSignIn = MutableStateFlow(isConfigured && prefs.getBoolean(K_REQUIRE, false))
    val requireSignIn: StateFlow<Boolean> = _requireSignIn

    /** Whether the sign-in screen stands in front of the app right now; see [signInRequired]. */
    val gated: StateFlow<Boolean> = combine(_state, _requireSignIn) { state, require -> signInRequired(state, require) }
        .stateIn(scope, SharingStarted.Eagerly, signInRequired(_state.value, _requireSignIn.value))

    private var app: FirebaseApp? = null

    /** Connects to the project. Called once from `ServiceLocator.init`; safe to call again. */
    @Synchronized
    fun start() {
        if (!isConfigured || app != null) return
        runCatching {
            val firebase = FirebaseApp.getApps(appContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(appContext, FirebaseSetup.options())
            app = firebase
            val auth = FirebaseAuth.getInstance(firebase)
            publish(auth.currentUser)
            auth.addAuthStateListener { publish(it.currentUser) }
            FirebaseFirestore.getInstance(firebase).collection("config").document("app")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    // An empty cache says nothing about the switch; only the server, or a copy
                    // of what it said, does.
                    if (snapshot.metadata.isFromCache && !snapshot.exists()) return@addSnapshotListener
                    setRequireSignIn(snapshot.getBoolean(FIELD_REQUIRE) == true)
                }
        }.onFailure {
            Log.w(TAG, "Firebase did not start; accounts are off", it)
            app = null
            _state.value = AccountState.Unavailable
            _requireSignIn.value = false
        }
    }

    private fun publish(user: FirebaseUser?) {
        _state.value = if (user == null) {
            AccountState.SignedOut
        } else {
            AccountState.SignedIn(uid = user.uid, name = user.displayName, email = user.email)
        }
    }

    private fun setRequireSignIn(on: Boolean) {
        if (_requireSignIn.value == on) return
        _requireSignIn.value = on
        prefs.edit().putBoolean(K_REQUIRE, on).apply()
    }

    private fun auth(): FirebaseAuth? = app?.let(FirebaseAuth::getInstance)

    private fun functions(): FirebaseFunctions? = app?.let { FirebaseFunctions.getInstance(it, FirebaseSetup.FUNCTIONS_REGION) }

    /** Whether this device can offer "Sign in with Google" at all. */
    val canSignInWithGoogle: Boolean
        get() = isConfigured && FirebaseSetup.hasGoogleClient

    /**
     * Sign in with Google through Credential Manager, then into Firebase with the ID token.
     * [activity] hosts the account picker.
     */
    suspend fun signInWithGoogle(activity: Activity): SignInOutcome {
        val auth = auth() ?: return SignInOutcome.Failed("accounts are not configured")
        if (!FirebaseSetup.hasGoogleClient) return SignInOutcome.Failed("no web client")
        return try {
            val option = GetSignInWithGoogleOption.Builder(FirebaseSetup.webClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return SignInOutcome.Failed("unexpected credential ${credential.type}")
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            SignInOutcome.Success
        } catch (_: GetCredentialCancellationException) {
            SignInOutcome.Cancelled
        } catch (_: NoCredentialException) {
            SignInOutcome.NoAccount
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager refused: ${e.type}", e)
            SignInOutcome.Failed(e.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed", e)
            SignInOutcome.Failed(e.message)
        }
    }

    /** Asks for a code to show on this television. */
    suspend fun createPairing(): Result<Pairing> = runCatchingCancellable {
        val functions = functions() ?: error("accounts are not configured")
        val result = functions.getHttpsCallable("createPairing").call(emptyMap<String, Any>()).await()
        parsePairing(result.getData()) ?: error("unexpected createPairing response")
    }

    /** One poll: has a phone approved [pairing] yet? Signs in when it has. */
    suspend fun claimPairing(pairing: Pairing): ClaimOutcome {
        val functions = functions() ?: return ClaimOutcome.Failed("accounts are not configured")
        val auth = auth() ?: return ClaimOutcome.Failed("accounts are not configured")
        return try {
            val data = functions.getHttpsCallable("claimPairing")
                .call(mapOf("code" to pairing.code, "secret" to pairing.secret))
                .await().getData()
            val (status, token) = parseClaim(data)
            when {
                status == "approved" && token != null -> {
                    auth.signInWithCustomToken(token).await()
                    ClaimOutcome.SignedIn
                }
                status == "pending" -> ClaimOutcome.Pending
                status == "expired" -> ClaimOutcome.Expired
                else -> ClaimOutcome.Failed("unexpected claimPairing response")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ClaimOutcome.Failed(e.message)
        }
    }

    /** Signs out here, and tells Credential Manager to stop offering the account silently. */
    suspend fun signOut() {
        auth()?.signOut()
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /**
     * Deletes the account on the server — its documents and the account itself — then signs out
     * here. Local playlists and settings stay: they were never the account's.
     */
    suspend fun deleteAccount(): Result<Unit> = runCatchingCancellable {
        val functions = functions() ?: error("accounts are not configured")
        functions.getHttpsCallable("deleteAccount").call(emptyMap<String, Any>()).await()
        signOut()
    }

    private companion object {
        const val TAG = "Account"
        const val PREFS_NAME = "account"
        const val K_REQUIRE = "require_sign_in"
        const val FIELD_REQUIRE = "requireSignIn"
    }
}

/** [runCatching] that lets cancellation through, so a screen closing mid-call is not an error. */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
