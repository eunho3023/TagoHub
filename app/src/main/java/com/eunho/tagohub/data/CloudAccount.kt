package com.eunho.tagohub.data

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.eunho.tagohub.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/** 지금 로그인 상태: 이메일이 있으면 구글 계정, 없으면 익명(앱을 지우면 연결이 끊김) */
data class AccountState(val email: String?, val signedIn: Boolean)

/**
 * 구글 계정 연결. 익명 계정이면 그 계정에 구글을 연결해서 즐겨찾기·설정이 그대로 이어지게 함.
 * 이미 다른 기기에서 쓰던 구글 계정이면 그 계정으로 바꿔 로그인하고, 클라우드에 있는 값을 받아 옴(이 기기의 익명 데이터는 대신 올라가지 않음).
 */
object CloudAccount {
    private const val TAG = "TagoAccount"
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AccountState> = _state

    private fun read(): AccountState {
        val u = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        return AccountState(u?.email?.takeIf { it.isNotBlank() }, u != null)
    }

    fun refresh() { _state.value = read() }

    /** 성공하면 null, 실패하면 사용자에게 보여 줄 안내 */
    suspend fun signInWithGoogle(activity: Activity): String? {
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(activity.getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false)
                .build()
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return "구글 계정을 받지 못했어요"
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            val firebase = GoogleAuthProvider.getCredential(token, null)
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            var switched = false
            if (user != null && user.isAnonymous) {
                try {
                    user.linkWithCredential(firebase).await()
                } catch (e: FirebaseAuthUserCollisionException) {
                    auth.signInWithCredential(firebase).await()
                    switched = true
                }
            } else {
                auth.signInWithCredential(firebase).await()
                switched = true
            }
            refresh()
            CloudSync.restart(activity.applicationContext)
            if (switched) "다른 계정의 데이터를 받아 왔어요. 앱을 다시 켜면 반영돼요" else null
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            "취소했어요"
        } catch (e: Exception) {
            Log.w(TAG, "구글 로그인 실패", e)
            "로그인에 실패했어요 (${e.javaClass.simpleName})"
        }
    }

    fun signOut(activity: Activity) {
        runCatching { FirebaseAuth.getInstance().signOut() }
        CloudSync.restart(activity.applicationContext)
        refresh()
    }
}
