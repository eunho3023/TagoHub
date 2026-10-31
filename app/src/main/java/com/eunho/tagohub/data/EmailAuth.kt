package com.eunho.tagohub.data

/**
 * 이메일 가입·로그인에서 쓰는 검사와 안내 문구 (Firebase 호출과 따로 두어 시험하기 쉽게).
 */
object EmailAuth {
    const val MIN_PASSWORD = 6

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")

    /** 입력이 올바르면 null, 아니면 사용자에게 보여 줄 안내 */
    fun validate(email: String, password: String, signUp: Boolean): String? = when {
        !EMAIL.matches(email.trim()) -> "이메일 주소를 확인해 주세요"
        password.length < MIN_PASSWORD -> "비밀번호는 ${MIN_PASSWORD}자 이상으로 해 주세요"
        signUp && password.length > 100 -> "비밀번호가 너무 길어요"
        else -> null
    }

    /** Firebase 인증 오류 코드(ERROR_…)를 사용자 안내로. 모르는 코드는 일반 안내 */
    fun message(code: String?): String = when (code) {
        "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE" -> "이미 가입된 이메일이에요. 로그인해 주세요"
        "ERROR_INVALID_EMAIL" -> "이메일 주소를 확인해 주세요"
        "ERROR_WEAK_PASSWORD" -> "비밀번호가 너무 약해요. 더 길게 해 주세요"
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_LOGIN_CREDENTIALS" -> "이메일이나 비밀번호가 맞지 않아요"
        "ERROR_USER_DISABLED" -> "사용이 정지된 계정이에요"
        "ERROR_TOO_MANY_REQUESTS" -> "시도가 너무 많아요. 잠시 뒤에 다시 해 주세요"
        "ERROR_NETWORK_REQUEST_FAILED" -> "인터넷 연결을 확인해 주세요"
        "ERROR_OPERATION_NOT_ALLOWED" -> "이메일 가입을 아직 쓸 수 없어요"
        else -> "처리하지 못했어요. 잠시 뒤에 다시 해 주세요"
    }
}

/**
 * 즐겨찾기를 새로 넣을 때 로그인이 안 되어 있으면 한 번 물어봄: 로그인하고 추가 / 로그인 없이 추가 / 취소.
 * [pending] 에 값이 있으면 화면이 안내 창을 띄움.
 */
object LoginGate {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Favorite?>(null)

    /** 로그인(이메일 또는 구글 계정)이 되어 있는지. 익명 로그인은 로그인이 아님 */
    fun signedIn(): Boolean = CloudAccount.state.value.email != null

    fun ask(f: Favorite) { pending.value = f }
    fun clear() { pending.value = null }
}
