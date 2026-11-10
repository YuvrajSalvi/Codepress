package com.codepress.app.data

import android.content.Context
import android.content.Intent
import com.codepress.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google sign-in (via Firebase Auth) plus per-user chat backup in Firestore: users/{uid}/chats/{projectId}. */
object CloudAuth {
    data class CloudChat(val projectId: String, val name: String, val json: String)

    private const val MAX_BYTES = 900_000 // Firestore documents are capped at 1 MiB.

    val user: FirebaseUser? get() = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()

    fun client(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /** Turns the result Intent of the Google account picker into a signed-in Firebase user. */
    suspend fun finishSignIn(data: Intent?): FirebaseUser {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(com.google.android.gms.common.api.ApiException::class.java)
        val token = account.idToken ?: throw IllegalStateException("Google did not return an ID token. Check the web client ID in google-services.json.")
        val credential = GoogleAuthProvider.getCredential(token, null)
        return FirebaseAuth.getInstance().signInWithCredential(credential).awaitTask().user
            ?: throw IllegalStateException("Sign-in finished but no user was returned.")
    }

    fun signOut(context: Context) {
        runCatching { FirebaseAuth.getInstance().signOut() }
        runCatching { client(context).signOut() }
    }

    fun pushChat(projectId: String, name: String, json: String) {
        val u = user ?: return
        if (json.toByteArray().size > MAX_BYTES) return
        runCatching {
            FirebaseFirestore.getInstance().collection("users").document(u.uid)
                .collection("chats").document(projectId)
                .set(mapOf("name" to name, "json" to json, "updatedAt" to System.currentTimeMillis()))
        }
    }

    fun deleteChat(projectId: String) {
        val u = user ?: return
        runCatching {
            FirebaseFirestore.getInstance().collection("users").document(u.uid).collection("chats").document(projectId).delete()
        }
    }

    suspend fun pullChats(): List<CloudChat> {
        val u = user ?: return emptyList()
        val snap = FirebaseFirestore.getInstance().collection("users").document(u.uid).collection("chats").get().awaitTask()
        return snap.documents.mapNotNull { d ->
            val json = d.getString("json") ?: return@mapNotNull null
            CloudChat(d.id, d.getString("name") ?: "Restored project", json)
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
