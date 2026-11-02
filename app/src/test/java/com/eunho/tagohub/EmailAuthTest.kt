package com.eunho.tagohub

import com.eunho.tagohub.data.EmailAuth
import com.eunho.tagohub.data.LoginMethod
import com.eunho.tagohub.data.LoginMethods
import com.eunho.tagohub.data.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailAuthTest {
    @Test
    fun validEmailAndPasswordPass() {
        assertNull(EmailAuth.validate("a@b.com", "123456", signUp = true))
        assertNull(EmailAuth.validate(" user.name+x@mail.co.kr ", "abcdef", signUp = false))
    }

    @Test
    fun badEmailOrShortPasswordGetAMessage() {
        assertEquals("이메일 주소를 확인해 주세요", EmailAuth.validate("abc", "123456", true))
        assertEquals("이메일 주소를 확인해 주세요", EmailAuth.validate("a@b", "123456", true))
        assertEquals("비밀번호는 6자 이상으로 해 주세요", EmailAuth.validate("a@b.com", "12345", true))
    }

    @Test
    fun emailOnlyCheckIsUsedByLinkLogin() {
        assertNull(EmailAuth.validateEmail(" a@b.com "))
        assertEquals("이메일 주소를 확인해 주세요", EmailAuth.validateEmail("abc"))
    }

    @Test
    fun koreanPhoneNumbersBecomeInternationalFormat() {
        assertEquals("+821012345678", PhoneNumber.toE164("010-1234-5678"))
        assertEquals("+821012345678", PhoneNumber.toE164(" 01012345678 "))
        assertEquals("+821012345678", PhoneNumber.toE164("+82 10-1234-5678"))
        assertEquals("+821012345678", PhoneNumber.toE164("+82 010-1234-5678"))
        assertEquals("+821012345678", PhoneNumber.toE164("0082 10 1234 5678"))
        assertEquals("+821012345678", PhoneNumber.toE164("82 10 1234 5678"))
    }

    @Test
    fun otherCountriesAndBadNumbersAreHandled() {
        assertEquals("+16505553434", PhoneNumber.toE164("+1 650-555-3434"))
        assertNull(PhoneNumber.toE164(""))
        assertNull(PhoneNumber.toE164("abc"))
        assertNull(PhoneNumber.toE164("1234"))
        assertNull(PhoneNumber.toE164("+1234"))
        assertNull(PhoneNumber.toE164("+8210123456789012345"))
    }

    @Test
    fun verificationCodeMustBeSixDigits() {
        assertEquals(true, PhoneNumber.isCode("123456"))
        assertEquals(true, PhoneNumber.isCode(" 654321 "))
        assertEquals(false, PhoneNumber.isCode("12345"))
        assertEquals(false, PhoneNumber.isCode("1234567"))
        assertEquals(false, PhoneNumber.isCode("12345a"))
    }

    @Test
    fun maskedNumberHidesTheMiddle() {
        assertEquals("+82 10-****-5678", PhoneNumber.masked("+821012345678"))
        assertEquals("+16505553434", PhoneNumber.masked("+16505553434"))
    }

    @Test
    fun phoneAndLinkCodesBecomePlainKorean() {
        assertEquals("전화번호를 확인해 주세요", EmailAuth.message("ERROR_INVALID_PHONE_NUMBER"))
        assertEquals("인증번호가 맞지 않아요", EmailAuth.message("ERROR_INVALID_VERIFICATION_CODE"))
        assertEquals("인증번호 시간이 지났어요. 다시 받아 주세요", EmailAuth.message("ERROR_SESSION_EXPIRED"))
        assertEquals("링크가 올바르지 않거나 시간이 지났어요. 다시 받아 주세요", EmailAuth.message("ERROR_EXPIRED_ACTION_CODE"))
    }

    @Test
    fun firebaseCodesBecomePlainKorean() {
        assertEquals("이미 가입된 이메일이에요. 처음 가입한 방법(비밀번호나 구글)으로 로그인해 주세요", EmailAuth.message("ERROR_EMAIL_ALREADY_IN_USE"))
        assertEquals("같은 종류의 로그인 방법이 이미 연결돼 있어요", EmailAuth.message("ERROR_PROVIDER_ALREADY_LINKED"))
        assertEquals("이메일이나 비밀번호가 맞지 않아요", EmailAuth.message("ERROR_INVALID_LOGIN_CREDENTIALS"))
        assertEquals("인터넷 연결을 확인해 주세요", EmailAuth.message("ERROR_NETWORK_REQUEST_FAILED"))
        assertEquals("처리하지 못했어요. 잠시 뒤에 다시 해 주세요", EmailAuth.message(null))
    }

    @Test
    fun loginMethodsShowWhatIsLinkedAndWhatCanBeAdded() {
        val google = LoginMethod(LoginMethods.GOOGLE, "a@gmail.com")
        val phone = LoginMethod(LoginMethods.PHONE, "+82 10-****-5678")
        assertEquals("구글 (a@gmail.com)", google.label)
        assertEquals("이메일", LoginMethod(LoginMethods.PASSWORD).label)
        assertEquals(listOf(LoginMethods.PASSWORD, LoginMethods.PHONE), LoginMethods.missing(listOf(google)))
        assertEquals(listOf(LoginMethods.PASSWORD), LoginMethods.missing(listOf(phone, google)))
        assertEquals(LoginMethods.ALL, LoginMethods.missing(emptyList()))
        assertEquals(emptyList<String>(), LoginMethods.missing(listOf(google, phone, LoginMethod(LoginMethods.PASSWORD, "a@b.com"))))
    }
}
