package com.idanck.shidur.data.account

import com.google.firebase.FirebaseOptions
import com.idanck.shidur.BuildConfig

/**
 * The Firebase project this build talks to (ADR-059), from the `google-services.json` CI read
 * at build time. The repository is public and holds none of it: a build made without the file has
 * every field empty, [isConfigured] false, and the app runs as it did before accounts existed.
 */
object FirebaseSetup {

    /**
     * Where the callable functions run. The same constant is in `firebase/functions/src/config.ts`
     * and `firebase/hosting/src/firebase.ts`; a function is addressed by its region. `me-west1`
     * (Tel Aviv), where Firestore is.
     */
    const val FUNCTIONS_REGION = "me-west1"

    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    /** Whether Sign in with Google can be offered: it needs the project's web client. */
    val hasGoogleClient: Boolean
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    val webClientId: String
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID

    fun options(): FirebaseOptions = FirebaseOptions.Builder()
        .setApiKey(BuildConfig.FIREBASE_API_KEY)
        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
        .apply {
            if (BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
        }
        .build()
}
