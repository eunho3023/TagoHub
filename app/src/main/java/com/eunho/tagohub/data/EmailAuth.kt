package com.eunho.tagohub.data

/**
 * 이메일 가입·로그인에서 쓰는 검사와 안내 문구 (Firebase 호출과 따로 두어 시험하기 쉽게).
 */
object EmailAuth {
    const val MIN_PASSWORD = 6

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")

    /** 이메일 주소만 검사 (이메일 링크 로그인용). 올바르면 null */
    fun validateEmail(email: String): String? = if (EMAIL.matches(email.trim())) null else "이메일 주소를 확인해 주세요"

    /** 입력이 올바르면 null, 아니면 사용자에게 보여 줄 안내 */
    fun validate(email: String, password: String, signUp: Boolean): String? = when {
        validateEmail(email) != null -> "이메일 주소를 확인해 주세요"
        password.length < MIN_PASSWORD -> "비밀번호는 ${MIN_PASSWORD}자 이상으로 해 주세요"
        signUp && password.length > 100 -> "비밀번호가 너무 길어요"
        else -> null
    }

    /** Firebase 인증 오류 코드(ERROR_…)를 사용자 안내로. 모르는 코드는 일반 안내 */
    fun message(code: String?): String = when (code) {
        "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
            "이미 가입된 이메일이에요. 처음 가입한 방법(비밀번호나 구글)으로 로그인해 주세요"
        "ERROR_PROVIDER_ALREADY_LINKED" -> "같은 종류의 로그인 방법이 이미 연결돼 있어요"
        "ERROR_INVALID_EMAIL" -> "이메일 주소를 확인해 주세요"
        "ERROR_WEAK_PASSWORD" -> "비밀번호가 너무 약해요. 더 길게 해 주세요"
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_LOGIN_CREDENTIALS" -> "이메일이나 비밀번호가 맞지 않아요"
        "ERROR_USER_DISABLED" -> "사용이 정지된 계정이에요"
        "ERROR_TOO_MANY_REQUESTS" -> "시도가 너무 많아요. 잠시 뒤에 다시 해 주세요"
        "ERROR_NETWORK_REQUEST_FAILED" -> "인터넷 연결을 확인해 주세요"
        "ERROR_OPERATION_NOT_ALLOWED" -> "이 로그인 방법을 아직 쓸 수 없어요"
        "ERROR_INVALID_PHONE_NUMBER" -> "전화번호를 확인해 주세요"
        "ERROR_INVALID_VERIFICATION_CODE" -> "인증번호가 맞지 않아요"
        "ERROR_SESSION_EXPIRED" -> "인증번호 시간이 지났어요. 다시 받아 주세요"
        "ERROR_QUOTA_EXCEEDED" -> "문자를 너무 많이 보냈어요. 나중에 다시 해 주세요"
        "ERROR_INVALID_ACTION_CODE", "ERROR_EXPIRED_ACTION_CODE" -> "링크가 올바르지 않거나 시간이 지났어요. 다시 받아 주세요"
        else -> "처리하지 못했어요. 잠시 뒤에 다시 해 주세요"
    }
}

/** 계정에 붙은 로그인 방법 하나. [provider] 는 Firebase 제공자 ID(google.com / password / phone), [detail] 은 그 방법의 이메일·전화번호 */
data class LoginMethod(val provider: String, val detail: String? = null) {
    val label: String get() = LoginMethods.label(provider) + (detail?.let { " ($it)" } ?: "")
}

/** 한 사람이 구글·이메일·전화번호를 한 계정에 모두 붙여 쓸 수 있게: 이름과 아직 안 붙인 방법 */
object LoginMethods {
    const val GOOGLE = "google.com"
    const val PASSWORD = "password"   // 이메일+비밀번호와 이메일 링크 모두
    const val PHONE = "phone"
    val ALL = listOf(GOOGLE, PASSWORD, PHONE)

    fun label(provider: String): String = when (provider) {
        GOOGLE -> "구글"
        PASSWORD -> "이메일"
        PHONE -> "전화번호"
        else -> provider
    }

    /** 아직 이 계정에 붙이지 않은 방법 ([ALL] 순서) */
    fun missing(methods: List<LoginMethod>): List<String> = ALL.filter { p -> methods.none { it.provider == p } }
}

/** 전화번호 로그인에서 쓰는 입력 정리와 검사 (Firebase 호출과 따로 두어 시험하기 쉽게) */
object PhoneNumber {
    /** 인증번호(문자로 오는 숫자) 자리수 */
    const val CODE_LENGTH = 6

    /**
     * 입력한 번호를 국제 형식(+82…)으로. 010-1234-5678 은 앞의 0을 떼고 +82 를 붙이고, +나 00 으로 시작하면 그 나라 번호 그대로.
     * 숫자 개수가 맞지 않으면 null.
     */
    fun toE164(raw: String): String? {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        var e = when {
            t.startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+82" + digits.drop(1)
            digits.startsWith("82") -> "+$digits"
            else -> return null
        }
        if (e.startsWith("+820")) e = "+82" + e.drop(4)       // +82 010-… 처럼 0을 한 번 더 쓴 경우
        return if (e.length - 1 in 8..15) e else null
    }

    /** 문자로 받은 인증번호가 형식에 맞는지 (숫자 [CODE_LENGTH]자리) */
    fun isCode(code: String): Boolean = code.trim().let { it.length == CODE_LENGTH && it.all(Char::isDigit) }

    /** 화면에 보여 줄 때 가운데를 가림: +821012345678 → +82 10-****-5678 */
    fun masked(e164: String): String {
        if (!e164.startsWith("+82") || e164.length < 11) return e164
        val rest = e164.drop(3)
        return "+82 ${rest.take(2)}-****-${rest.takeLast(4)}"
    }
}

/**
 * 즐겨찾기를 새로 넣을 때 로그인이 안 되어 있으면 한 번 물어봄: 로그인하고 추가 / 로그인 없이 추가 / 취소.
 * [pending] 에 값이 있으면 화면이 안내 창을 띄움.
 */
object LoginGate {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Favorite?>(null)

    /** 로그인(이메일·구글 계정 또는 전화번호)이 되어 있는지. 익명 로그인은 로그인이 아님 */
    fun signedIn(): Boolean = CloudAccount.state.value.let { it.email != null || it.phone != null }

    fun ask(f: Favorite) { pending.value = f }
    fun clear() { pending.value = null }
}
