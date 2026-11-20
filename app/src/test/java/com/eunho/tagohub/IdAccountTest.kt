package com.eunho.tagohub

import com.eunho.tagohub.data.IdAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 아이디 회원: 아이디·가입 입력 검사 */
class IdAccountTest {
    @Test
    fun usernameRules() {
        assertEquals("eunho_3023", IdAccount.normUsername(" EunHo_3023 "))
        assertNull(IdAccount.normUsername("abc"))
        assertNull(IdAccount.normUsername("한글아이디"))
        assertNull(IdAccount.normUsername("a".repeat(21)))
    }

    @Test
    fun signUpChecks() {
        val ok = { u: String, p: String, c: String, e: String, ph: String -> IdAccount.validateSignUp(u, p, c, e, ph) }
        assertNull(ok("eunho", "abcd1234", "abcd1234", "me@x.com", "010-1234-5678"))
        assertEquals("비밀번호는 8자 이상으로 해 주세요", ok("eunho", "ab12", "ab12", "me@x.com", "01012345678"))
        assertEquals("비밀번호에 영어와 숫자를 함께 넣어 주세요", ok("eunho", "abcdefgh", "abcdefgh", "me@x.com", "01012345678"))
        assertEquals("비밀번호 확인이 맞지 않아요", ok("eunho", "abcd1234", "abcd1235", "me@x.com", "01012345678"))
        assertEquals("이메일 주소를 확인해 주세요", ok("eunho", "abcd1234", "abcd1234", "me@x", "01012345678"))
        assertEquals("전화번호를 확인해 주세요", ok("eunho", "abcd1234", "abcd1234", "me@x.com", "12"))
    }
}
