package com.eunho.tagohub.data

import android.app.Activity
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 아이디 회원: Firebase 계정(이메일·비밀번호 + 문자로 인증한 전화번호)에 아이디를 붙여 아이디로 로그인.
 * 아이디 ↔ 계정 연결은 중계 서버 /w/auth/… (server/tago-proxy/src/auth.js, D1).
 *  - 가입: 아이디·비밀번호·이메일·전화번호(문자 인증). 지금 기기의 익명 계정에 이어 붙여 즐겨찾기·설정이 그대로
 *  - 로그인: 아이디·비밀번호 → 서버가 비밀번호가 맞을 때만 이메일을 알려 줌 → 그 이메일로 Firebase 로그인
 *  - 아이디 찾기: 전화번호나 이메일 → 가운데를 가린 아이디
 *  - 비밀번호 바꾸기: 아이디 + 이메일(바꾸는 링크를 메일로) 또는 아이디 + 전화번호(문자 인증 뒤 앱에서 새 비밀번호)
 *  - 개발자: 서버 비밀 값 DEV_IDS 에 든 아이디로 로그인하면 [developer] — 내비게이션·지도 고치기를 씀
 */
object IdAccount {
    private const val TAG = "TagoIdAccount"
    const val MIN_PASSWORD = 8

    data class Me(val username: String? = null, val dev: Boolean = false)

    private val _me = MutableStateFlow(Me())
    val me: StateFlow<Me> = _me

    /** 개발자인지 (개발자 아이디로 로그인했거나 이 기기에 관리자 키를 넣음): 내비게이션·지도 고치기 */
    val developer = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 입력 검사 (화면과 따로 두어 시험하기 쉽게) ──

    private val USERNAME = Regex("^[a-z0-9_]{4,20}$")

    fun normUsername(s: String): String? = s.trim().lowercase().takeIf { USERNAME.matches(it) }

    fun validateSignUp(username: String, password: String, confirm: String, email: String, phone: String): String? = when {
        normUsername(username) == null -> "아이디는 영어 소문자·숫자·밑줄(_)로 4~20자예요"
        password.length < MIN_PASSWORD -> "비밀번호는 ${MIN_PASSWORD}자 이상으로 해 주세요"
        password.none { it.isLetter() } || password.none { it.isDigit() } -> "비밀번호에 영어와 숫자를 함께 넣어 주세요"
        password != confirm -> "비밀번호 확인이 맞지 않아요"
        EmailAuth.validateEmail(email) != null -> "이메일 주소를 확인해 주세요"
        PhoneNumber.toE164(phone) == null -> "전화번호를 확인해 주세요"
        else -> null
    }

    // ── 서버 ──

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    private fun base(): String? = DataProxy.current?.url?.trimEnd('/')

    /** (HTTP 코드, 응답) — 연결 실패면 (-1, null) */
    private suspend fun post(op: String, body: JSONObject): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        val b = base() ?: return@withContext -1 to null
        runCatching {
            client.newCall(Request.Builder().url("$b/w/auth/$op").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                r.code to runCatching { JSONObject(r.body.string()) }.getOrNull()
            }
        }.getOrElse { Log.w(TAG, "서버 요청 실패 $op", it); -1 to null }
    }

    private const val NET = "인터넷 연결을 확인해 주세요"
    private const val BUSY = "시도가 너무 많아요. 15분 뒤에 다시 해 주세요"

    private suspend fun idToken(force: Boolean = false): String? =
        runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(force)?.await()?.token }.getOrNull()

    /** 로그인한 계정의 아이디·개발자인지 다시 받음 (로그인 상태가 바뀔 때마다) */
    suspend fun refresh() {
        val user = FirebaseAuth.getInstance().currentUser
        val next = if (user == null || user.isAnonymous) Me() else {
            val t = idToken() ?: return
            val (code, j) = post("me", JSONObject().put("idToken", t))
            if (code != 200 || j == null) return   // 못 받으면 가진 것 유지
            Me(j.optString("username").takeIf { it.isNotBlank() && it != "null" }, j.optBoolean("dev"))
        }
        _me.value = next
        update()
    }

    private fun update() {
        val dev = _me.value.dev
        MapEdits.setDeveloper(dev)
        developer.value = dev || MapEdits.adminKeySet()
    }

    // 자동 로그인: 켜 두면(기본) 앱을 다시 켜도 로그인이 그대로, 끄면 앱을 새로 켤 때 로그아웃 (MainActivity)
    private const val PREFS = "ui"
    private const val AUTO_LOGIN = "auto_login"
    fun autoLogin(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(AUTO_LOGIN, true)
    fun setAutoLogin(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(AUTO_LOGIN, on).apply()
    }

    /** 앱을 켤 때: 로그인 상태가 바뀌면 아이디를 다시 받음 */
    fun init() {
        update()
        scope.launch {
            CloudAccount.state.map { FirebaseAuth.getInstance().currentUser?.uid to it.signedIn }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch { MapEdits.admin.collect { update() } }
    }

    /** 지도 고치기를 서버에 보낼 때 쓰는 개발자 토큰 */
    suspend fun devToken(): String? = if (_me.value.dev) idToken() else null

    /** 아이디를 쓸 수 있으면 true, 이미 있으면 false, 못 물으면 null */
    suspend fun isFree(username: String): Boolean? {
        val u = normUsername(username) ?: return false
        val (code, j) = post("check", JSONObject().put("username", u))
        return if (code == 200) j?.optBoolean("free") else null
    }

    /** 아이디·비밀번호로 로그인. 성공하면 null, 아니면 안내 */
    suspend fun login(context: android.content.Context, username: String, password: String): String? {
        val u = normUsername(username) ?: return "아이디나 비밀번호가 맞지 않아요"
        if (password.isEmpty()) return "비밀번호를 넣어 주세요"
        val (code, j) = post("login", JSONObject().put("username", u).put("password", password))
        val email = j?.optString("email")?.takeIf { code == 200 && it.isNotBlank() } ?: return when (code) {
            401 -> "아이디나 비밀번호가 맞지 않아요"
            429 -> BUSY
            -1 -> NET
            else -> "로그인하지 못했어요. 잠시 뒤에 다시 해 주세요"
        }
        CloudAccount.signInWithEmail(context, email, password)?.let { return it }
        refresh()
        return null
    }

    /** 아이디 찾기: 가운데를 가린 아이디들 (없으면 빈 목록). 실패하면 안내 */
    suspend fun find(phone: String?, email: String?): Result<List<String>> {
        val body = JSONObject()
        if (phone != null) body.put("phone", PhoneNumber.toE164(phone) ?: return Result.failure(Exception("전화번호를 확인해 주세요")))
        else body.put("email", email?.trim()?.takeIf { EmailAuth.validateEmail(it) == null } ?: return Result.failure(Exception("이메일 주소를 확인해 주세요")))
        val (code, j) = post("find", body)
        if (code == 429) return Result.failure(Exception(BUSY))
        if (code != 200 || j == null) return Result.failure(Exception(if (code == -1) NET else "찾지 못했어요. 잠시 뒤에 다시 해 주세요"))
        val arr = j.optJSONArray("ids") ?: JSONArray()
        return Result.success((0 until arr.length()).map { arr.getString(it) })
    }

    /** 비밀번호 바꾸기 전 확인: 아이디와 연락처가 맞는지, 전화번호로 바꿀 수 있는지(문자 인증한 번호인지) */
    data class ResetCheck(val ok: Boolean, val phoneOk: Boolean)

    suspend fun checkReset(username: String, phone: String?, email: String?): Result<ResetCheck> {
        val u = normUsername(username) ?: return Result.success(ResetCheck(false, false))
        val body = JSONObject().put("username", u)
        if (phone != null) body.put("phone", PhoneNumber.toE164(phone) ?: return Result.failure(Exception("전화번호를 확인해 주세요")))
        else body.put("email", email?.trim()?.takeIf { EmailAuth.validateEmail(it) == null } ?: return Result.failure(Exception("이메일 주소를 확인해 주세요")))
        val (code, j) = post("reset", body)
        if (code == 429) return Result.failure(Exception(BUSY))
        if (code != 200 || j == null) return Result.failure(Exception(if (code == -1) NET else "확인하지 못했어요. 잠시 뒤에 다시 해 주세요"))
        return Result.success(ResetCheck(j.optBoolean("ok"), j.optBoolean("phoneOk")))
    }

    // ── 문자 인증 (가입·비밀번호 바꾸기) ──

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var verified: PhoneAuthCredential? = null
    private var verifiedNumber: String? = null

    /** 인증번호 문자 보내기. 보내면 [onSent], 실패하면 [onFail](안내). 폰이 문자를 알아서 읽으면 바로 인증됨 */
    fun sendCode(activity: Activity, phone: String, resend: Boolean, onSent: () -> Unit, onFail: (String) -> Unit) {
        val e164 = PhoneNumber.toE164(phone) ?: return onFail("전화번호를 확인해 주세요")
        verified = null
        verifiedNumber = null
        val auth = FirebaseAuth.getInstance()
        auth.useAppLanguage()
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) { verified = credential; verifiedNumber = e164 }
            override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                Log.w(TAG, "문자 인증 실패", e)
                onFail(EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode
                    ?: if (e is com.google.firebase.FirebaseTooManyRequestsException) "ERROR_TOO_MANY_REQUESTS" else null))
            }
            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                verificationId = id
                resendToken = token
                verifiedNumber = e164
                onSent()
            }
        }
        val b = PhoneAuthOptions.newBuilder(auth).setPhoneNumber(e164).setTimeout(60L, TimeUnit.SECONDS).setActivity(activity).setCallbacks(callbacks)
        if (resend) resendToken?.let { b.setForceResendingToken(it) }
        PhoneAuthProvider.verifyPhoneNumber(b.build())
    }

    /** 받은 인증번호 → 인증 정보 (폰이 알아서 읽었으면 그것) */
    private fun credential(code: String): PhoneAuthCredential? =
        verified ?: verificationId?.takeIf { PhoneNumber.isCode(code) }?.let { PhoneAuthProvider.getCredential(it, code.trim()) }

    /**
     * 가입. [code] 가 있으면 그 번호를 문자로 인증해 계정에 붙이고(아이디 찾기·비밀번호 바꾸기에 씀), 없으면 번호만 적어 둠.
     * 지금 기기의 익명 계정에 이어 붙여서 즐겨찾기·설정이 그대로 감. 성공하면 null(또는 알림), 실패하면 안내 — 성공 여부는 [me] 로
     */
    suspend fun signUp(context: android.content.Context, username: String, password: String, email: String, phone: String, code: String?): String? {
        val u = normUsername(username) ?: return "아이디를 확인해 주세요"
        val auth = FirebaseAuth.getInstance()
        val now = auth.currentUser
        if (now != null && !now.isAnonymous) return "로그인된 계정이 있어요. 설정에서 로그아웃한 뒤 가입해 주세요"
        when (isFree(u)) { false -> return "이미 쓰고 있는 아이디예요"; null -> return NET; true -> Unit }
        val phoneCred = code?.takeIf { it.isNotBlank() }?.let { c ->
            if (verifiedNumber != PhoneNumber.toE164(phone)) return "인증번호를 받은 전화번호와 달라요. 다시 받아 주세요"
            credential(c) ?: return "인증번호 ${PhoneNumber.CODE_LENGTH}자리를 입력해 주세요"
        }
        // 1) 이메일·비밀번호 (익명이면 그 계정에 이어 붙임)
        try {
            val emailCred = com.google.firebase.auth.EmailAuthProvider.getCredential(email.trim(), password)
            if (now != null) now.linkWithCredential(emailCred).await() else auth.createUserWithEmailAndPassword(email.trim(), password).await()
        } catch (e: Exception) {
            Log.w(TAG, "가입 실패", e)
            return EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: if (e is java.io.IOException) "ERROR_NETWORK_REQUEST_FAILED" else null)
        }
        // 2) 문자로 인증한 전화번호 붙이기 (다른 계정의 번호면 번호만 적어 둠)
        var note: String? = null
        if (phoneCred != null) {
            try {
                auth.currentUser?.linkWithCredential(phoneCred)?.await()
            } catch (e: FirebaseAuthUserCollisionException) {
                note = "이 전화번호는 다른 계정에 인증돼 있어서, 이 아이디는 이메일로만 비밀번호를 바꿀 수 있어요"
            } catch (e: Exception) {
                Log.w(TAG, "전화번호 붙이기 실패", e)
                return if (e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) "인증번호가 맞지 않아요" else EmailAuth.message(null)
            }
        }
        // 3) 서버에 아이디 등록 (토큰을 새로 받아 이메일·전화번호가 들어가게)
        val t = idToken(force = true) ?: return NET
        val (c, j) = post("register", JSONObject().put("idToken", t).put("username", u).put("phone", PhoneNumber.toE164(phone)))
        if (c != 200) return when (c) {
            409 -> if (j?.optString("error") == "taken") "방금 다른 사람이 그 아이디로 가입했어요. 다른 아이디로 해 주세요" else "이미 아이디가 있는 계정이에요"
            -1 -> NET
            else -> "가입을 마치지 못했어요 ($c). 잠시 뒤에 다시 해 주세요"
        }
        CloudAccount.refresh()
        CloudSync.restart(context.applicationContext)
        refresh()
        return note
    }

    /** 비밀번호 바꾸기 (전화번호): 문자 인증으로 그 계정에 로그인한 뒤, 아이디가 맞으면 새 비밀번호로. 성공하면 null */
    suspend fun resetByPhone(context: android.content.Context, username: String, code: String, newPassword: String, confirm: String): String? {
        val u = normUsername(username) ?: return "아이디를 확인해 주세요"
        when {
            newPassword.length < MIN_PASSWORD -> return "비밀번호는 ${MIN_PASSWORD}자 이상으로 해 주세요"
            newPassword.none { it.isLetter() } || newPassword.none { it.isDigit() } -> return "비밀번호에 영어와 숫자를 함께 넣어 주세요"
            newPassword != confirm -> return "비밀번호 확인이 맞지 않아요"
        }
        val cred = credential(code) ?: return "인증번호 ${PhoneNumber.CODE_LENGTH}자리를 입력해 주세요"
        val auth = FirebaseAuth.getInstance()
        return try {
            auth.signInWithCredential(cred).await()
            refresh()
            if (_me.value.username != u) {
                auth.signOut()
                CloudAccount.refresh()
                CloudSync.restart(context.applicationContext)
                return "아이디와 전화번호가 맞지 않아요"
            }
            auth.currentUser?.updatePassword(newPassword)?.await()
            CloudAccount.refresh()
            CloudSync.restart(context.applicationContext)
            null
        } catch (e: Exception) {
            Log.w(TAG, "비밀번호 바꾸기 실패", e)
            if (e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) "인증번호가 맞지 않아요"
            else EmailAuth.message((e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode)
        }
    }
}
