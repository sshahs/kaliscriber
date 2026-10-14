package com.kaliscriber.app.export

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.kaliscriber.core.export.GoogleDocsExporter

/**
 * Asks Google for a Drive access token limited to files this app creates (drive.file). This only
 * works once the app's package name and signing SHA-1 are registered as an Android OAuth client in
 * a Google Cloud project with the Drive API enabled; otherwise the caller falls back to a .docx.
 */
object GoogleAuth {
    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** The user must pick an account or consent; launch this, then call [tokenFromResult]. */
        data class NeedsConsent(val intent: PendingIntent) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GoogleDocsExporter.SCOPE)))
        .build()

    fun authorize(context: Context, onOutcome: (Outcome) -> Unit) {
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                val token = result.accessToken
                onOutcome(
                    when {
                        result.hasResolution() && pending != null -> Outcome.NeedsConsent(pending)
                        token != null -> Outcome.Token(token)
                        else -> Outcome.Failed("Google did not return an access token")
                    },
                )
            }
            .addOnFailureListener { e -> onOutcome(Outcome.Failed(e.message ?: "Google sign-in failed")) }
    }

    fun tokenFromResult(context: Context, data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data!!).accessToken
    }.getOrNull()
}
