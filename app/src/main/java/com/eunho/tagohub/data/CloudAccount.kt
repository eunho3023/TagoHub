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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 지금 로그인 상태: 이메일(또는 전화번호)이 있으면 가입한 계정, 둘 다 없으면 익명(앱을 지우면 연결이 끊김) */
data class AccountState(val email: String?, val signedIn: Boolean, val phone: String? = null)

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
        return AccountState(u?.email?.takeIf { it.isNotBlank() }, u != null, u?.phoneNumber?.takeIf { it.isNotBlank() })
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
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            "이 폰에 로그인할 구글 계정이 없어요. 폰 설정에서 구글 계정을 추가한 뒤 다시 해 주세요"
        } catch (e: Exception) {
            Log.w(TAG, "구글 로그인 실패", e)
            "로그인에 실패했어요 (${e.javaClass.simpleName})"
        }
    }

    /**
     * 내 데이터 삭제: 클라우드에 올린 설정 문서(users/{내 ID})와 로그인 계정을 지우고, 이 기기에 저장된 동기화 항목
     * (즐겨찾기·집/회사·안내 설정 등)도 비운 뒤 새 익명 계정으로 다시 시작함. 성공하면 null, 아니면 사용자에게 보여 줄 안내.
     * 앱 제보(reports)는 읽기·삭제를 막아 두었으므로 앱에서는 지울 수 없음 — 이메일로 삭제를 요청.
     */
    suspend fun deleteMyData(activity: Activity): String? {
        val app = activity.applicationContext
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        try {
            if (user != null) {
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(user.uid).delete().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "클라우드 데이터 삭제 실패", e)
            return "삭제하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요"
        }
        CloudSync.stop(app)
        for ((file, key) in CloudSyncSpec.items) app.getSharedPreferences(file, android.content.Context.MODE_PRIVATE).edit().remove(key).apply()
        var note: String? = null
        try {
            user?.delete()?.await()
        } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
            // 오래 전에 로그인한 구글 계정은 보안상 다시 로그인해야 계정 자체를 지울 수 있음. 데이터는 이미 지움
            runCatching { auth.signOut() }
            note = "데이터는 지웠어요. 계정 자체를 지우려면 구글 계정을 다시 연결한 뒤 한 번 더 눌러 주세요"
        } catch (e: Exception) {
            Log.w(TAG, "계정 삭제 실패", e)
            runCatching { auth.signOut() }
            note = "데이터는 지웠지만 계정을 지우지는 못했어요"
        }
        refresh()
        CloudSync.restart(app)
        return note
    }

    /**
     * 이메일 가입. 익명 계정이면 그 계정에 이메일을 이어 붙여서 지금까지의 즐겨찾기·설정이 그대로 유지됨.
     * 성공하면 null, 아니면 보여 줄 안내.
     */
    suspend fun signUpWithEmail(context: android.content.Context, email: String, password: String): String? {
        EmailAuth.validate(email, password, signUp = true)?.let { return it }
        return try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email.trim(), password)
            if (user != null && user.isAnonymous) user.linkWithCredential(credential).await()
            else auth.createUserWithEmailAndPassword(email.trim(), password).await()
            refresh()
            CloudSync.restart(context.applicationContext)
            null
        } catch (e: Exception) {
            Log.w(TAG, "이메일 가입 실패", e)
            EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: if (e is java.io.IOException) "ERROR_NETWORK_REQUEST_FAILED" else null)
        }
    }

    /** 이메일 로그인. 다른 계정으로 바뀌면 그 계정의 즐겨찾기·설정을 받아 옴. 성공하면 null(또는 안내), 실패하면 안내 */
    suspend fun signInWithEmail(context: android.content.Context, email: String, password: String): String? {
        EmailAuth.validate(email, password, signUp = false)?.let { return it }
        return try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
            refresh()
            CloudSync.restart(context.applicationContext)
            null
        } catch (e: Exception) {
            Log.w(TAG, "이메일 로그인 실패", e)
            EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: if (e is java.io.IOException) "ERROR_NETWORK_REQUEST_FAILED" else null)
        }
    }

    /** 비밀번호 재설정 메일 보내기. 성공하면 null */
    suspend fun sendPasswordReset(email: String): String? {
        if (EmailAuth.validate(email, "x".repeat(EmailAuth.MIN_PASSWORD), signUp = false) != null) return "이메일 주소를 확인해 주세요"
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
            null
        } catch (e: Exception) {
            Log.w(TAG, "비밀번호 재설정 실패", e)
            EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: if (e is java.io.IOException) "ERROR_NETWORK_REQUEST_FAILED" else null)
        }
    }

    /** Firebase 오류에서 오류 코드를 뽑음 (문자 보내기 한도 초과·네트워크 오류 포함) */
    private fun codeOf(e: Exception): String? = when (e) {
        is com.google.firebase.auth.FirebaseAuthException -> e.errorCode
        is com.google.firebase.FirebaseTooManyRequestsException -> "ERROR_TOO_MANY_REQUESTS"
        is com.google.firebase.FirebaseNetworkException, is java.io.IOException -> "ERROR_NETWORK_REQUEST_FAILED"
        else -> null
    }

    private const val SWITCHED = "다른 계정의 데이터를 받아 왔어요. 앱을 다시 켜면 반영돼요"

    /**
     * 새 로그인 수단을 지금 계정에 붙임. 익명 계정이면 그 계정에 이어 붙여서 즐겨찾기·설정이 그대로 유지되고,
     * 이미 다른 계정에서 쓰는 수단이면 그 계정으로 바꿔 로그인함. 바꿨으면 true.
     */
    private suspend fun linkOrSignIn(credential: com.google.firebase.auth.AuthCredential): Boolean {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null || !user.isAnonymous) {
            auth.signInWithCredential(credential).await()
            return true
        }
        return try {
            user.linkWithCredential(credential).await()
            false
        } catch (e: FirebaseAuthUserCollisionException) {
            auth.signInWithCredential(e.updatedCredential ?: credential).await()
            true
        }
    }

    // ── 이메일 링크 로그인 (비밀번호 없이, 메일로 온 링크를 누르면 로그인) ──────────────────────────

    private const val LINK_PREFS = "ui"
    private const val LINK_EMAIL_KEY = "email_link_pending"

    /** 앱이 받은 이메일 로그인 링크. 처리되면 비움 */
    val emailLink = MutableStateFlow<String?>(null)

    fun isEmailLink(link: String?): Boolean =
        link != null && runCatching { FirebaseAuth.getInstance().isSignInWithEmailLink(link) }.getOrDefault(false)

    /** 링크를 보낼 때 적어 둔 이메일 (같은 폰에서 링크를 열면 다시 묻지 않으려고). 없으면 null */
    fun pendingLinkEmail(context: android.content.Context): String? =
        context.getSharedPreferences(LINK_PREFS, android.content.Context.MODE_PRIVATE).getString(LINK_EMAIL_KEY, null)?.takeIf { it.isNotBlank() }

    /** 로그인 링크를 메일로 보냄. 성공하면 null, 아니면 보여 줄 안내 */
    suspend fun sendEmailLink(context: android.content.Context, email: String): String? {
        EmailAuth.validateEmail(email)?.let { return it }
        return try {
            val projectId = com.google.firebase.FirebaseApp.getInstance().options.projectId ?: return "이메일 링크를 아직 쓸 수 없어요"
            val settings = com.google.firebase.auth.ActionCodeSettings.newBuilder()
                .setUrl("https://$projectId.firebaseapp.com/finishSignUp")
                .setHandleCodeInApp(true)
                .setAndroidPackageName(context.packageName, false, null)
                .build()
            FirebaseAuth.getInstance().sendSignInLinkToEmail(email.trim(), settings).await()
            context.getSharedPreferences(LINK_PREFS, android.content.Context.MODE_PRIVATE).edit().putString(LINK_EMAIL_KEY, email.trim()).apply()
            null
        } catch (e: Exception) {
            Log.w(TAG, "이메일 링크 보내기 실패", e)
            EmailAuth.message(codeOf(e))
        }
    }

    /** 받은 링크로 로그인을 마침. [email] 을 생략하면 링크를 보낼 때 적어 둔 이메일을 씀. 성공하면 null(또는 안내), 실패하면 안내 */
    suspend fun completeEmailLink(context: android.content.Context, link: String, email: String? = null): String? {
        val address = (email ?: pendingLinkEmail(context)).orEmpty().trim()
        EmailAuth.validateEmail(address)?.let { return it }
        return try {
            if (!FirebaseAuth.getInstance().isSignInWithEmailLink(link)) return EmailAuth.message("ERROR_INVALID_ACTION_CODE")
            val switched = linkOrSignIn(com.google.firebase.auth.EmailAuthProvider.getCredentialWithLink(address, link))
            context.getSharedPreferences(LINK_PREFS, android.content.Context.MODE_PRIVATE).edit().remove(LINK_EMAIL_KEY).apply()
            emailLink.value = null
            refresh()
            CloudSync.restart(context.applicationContext)
            if (switched) SWITCHED else null
        } catch (e: Exception) {
            Log.w(TAG, "이메일 링크 로그인 실패", e)
            EmailAuth.message(codeOf(e))
        }
    }

    // ── 전화번호 로그인 (문자로 온 인증번호) ───────────────────────────────────────────────────────

    private var phoneVerificationId: String? = null
    private var phoneResendToken: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken? = null

    /**
     * 인증번호 문자를 보냄 ([e164] 는 +82… 형식). 문자를 보내면 [onSent], 끝나면 [onDone]: 실패하면 안내,
     * 기기가 문자를 알아서 읽어 바로 로그인된 경우엔 null(또는 계정이 바뀐 안내). [resend] 가 true 면 같은 번호로 다시 보냄.
     */
    fun sendPhoneCode(activity: Activity, e164: String, resend: Boolean, onSent: () -> Unit, onDone: (String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.useAppLanguage()
        val callbacks = object : com.google.firebase.auth.PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: com.google.firebase.auth.PhoneAuthCredential) {
                kotlinx.coroutines.MainScope().launch { onDone(signInWithPhone(activity.applicationContext, credential)) }
            }

            override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                Log.w(TAG, "전화번호 인증 실패", e)
                onDone(EmailAuth.message(codeOf(e)))
            }

            override fun onCodeSent(verificationId: String, token: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken) {
                phoneVerificationId = verificationId
                phoneResendToken = token
                onSent()
            }
        }
        val builder = com.google.firebase.auth.PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(e164)
            .setTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        if (resend) phoneResendToken?.let { builder.setForceResendingToken(it) }
        com.google.firebase.auth.PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    /** 문자로 받은 인증번호로 로그인을 마침. 성공하면 null(또는 안내), 실패하면 안내 */
    suspend fun confirmPhoneCode(context: android.content.Context, code: String): String? {
        val id = phoneVerificationId ?: return "인증번호를 먼저 받아 주세요"
        if (!PhoneNumber.isCode(code)) return "인증번호 ${PhoneNumber.CODE_LENGTH}자리를 입력해 주세요"
        return signInWithPhone(context, com.google.firebase.auth.PhoneAuthProvider.getCredential(id, code.trim()))
    }

    private suspend fun signInWithPhone(context: android.content.Context, credential: com.google.firebase.auth.PhoneAuthCredential): String? = try {
        val switched = linkOrSignIn(credential)
        phoneVerificationId = null
        phoneResendToken = null
        refresh()
        CloudSync.restart(context.applicationContext)
        if (switched) SWITCHED else null
    } catch (e: Exception) {
        Log.w(TAG, "전화번호 로그인 실패", e)
        EmailAuth.message(codeOf(e))
    }

    fun signOut(activity: Activity) {
        runCatching { FirebaseAuth.getInstance().signOut() }
        CloudSync.restart(activity.applicationContext)
        refresh()
    }
}
