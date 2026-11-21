package com.eunho.tagohub

import com.eunho.tagohub.ui.common.EMAIL_DOMAINS
import com.eunho.tagohub.ui.common.joinEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 회원가입 이메일: 앞자리 + 고른 뒷자리 (웹 account.js joinEmail 과 같음) */
class SignUpEmailTest {
    @Test fun joinsIdAndDomain() {
        assertEquals("gidrl@naver.com", joinEmail("gidrl", "naver.com"))
        assertEquals("gidrl@company.co.kr", joinEmail(" gidrl ", "@company.co.kr"))
    }

    @Test fun keepsWhatWasTypedWhenItHasAtOrNoDomain() {
        assertEquals("a@b.com", joinEmail("a@b.com", "naver.com"))
        assertEquals("gidrl", joinEmail("gidrl", ""))
    }

    @Test fun domainListEndsWithCustom() {
        assertEquals("naver.com", EMAIL_DOMAINS.first())
        assertTrue(EMAIL_DOMAINS.last().isEmpty() && "gmail.com" in EMAIL_DOMAINS)
    }
}
