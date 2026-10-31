package com.eunho.tagohub

import com.eunho.tagohub.data.EmailAuth
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
    fun firebaseCodesBecomePlainKorean() {
        assertEquals("이미 가입된 이메일이에요. 로그인해 주세요", EmailAuth.message("ERROR_EMAIL_ALREADY_IN_USE"))
        assertEquals("이메일이나 비밀번호가 맞지 않아요", EmailAuth.message("ERROR_INVALID_LOGIN_CREDENTIALS"))
        assertEquals("인터넷 연결을 확인해 주세요", EmailAuth.message("ERROR_NETWORK_REQUEST_FAILED"))
        assertEquals("처리하지 못했어요. 잠시 뒤에 다시 해 주세요", EmailAuth.message(null))
    }
}
