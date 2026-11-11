package com.codepress.app.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** Google Sign-In via Credential Manager, exchanged for a Firebase Auth session (persisted by Firebase). */
class AuthManager(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    sealed interface State {
        data object SignedOut : State
        data object Busy : State
        data class SignedIn(val name: String?, val email: String?, val photoUrl: String?) : State
    }

    private val _state = MutableStateFlow<State>(auth.currentUser.toState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    /**
     * @param activityContext must be an Activity context so the account sheet can be shown.
     * @param webClientId the OAuth *web* client ID from google-services.json (R.string.default_web_client_id).
     */
    suspend fun signIn(activityContext: Context, webClientId: String) {
        if (_state.value == State.Busy) return
        _error.value = null
        _state.value = State.Busy
        try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw IllegalStateException("Unexpected credential type returned.")
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            _error.value = "Sign-in was cancelled."
        } catch (e: NoCredentialException) {
            _error.value = "No Google account is available on this phone. Add one in Android Settings and try again."
        } catch (e: GetCredentialException) {
            _error.value = "Google sign-in failed: ${e.message ?: e.type}. If this persists, check the SHA-1 fingerprint and Google provider in Firebase."
        } catch (e: GoogleIdTokenParsingException) {
            _error.value = "Google returned an invalid sign-in token."
        } catch (e: FirebaseNetworkException) {
            _error.value = "No internet connection. Check your network and try again."
        } catch (e: FirebaseAuthException) {
            _error.value = "Firebase rejected the sign-in (${e.errorCode}). Make sure the Google provider is enabled in Firebase Authentication."
        } catch (e: Throwable) {
            _error.value = "Sign-in failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _state.value = auth.currentUser.toState()
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        try { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) } catch (_: Throwable) {}
        _state.value = State.SignedOut
    }

    private fun FirebaseUser?.toState(): State =
        if (this == null) State.SignedOut else State.SignedIn(displayName, email, photoUrl?.toString())
}
