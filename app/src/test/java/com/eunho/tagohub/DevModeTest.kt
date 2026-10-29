package com.eunho.tagohub

import com.eunho.tagohub.core.DevMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DevModeTest {
    @Test
    fun debugBuildsShowTheDeveloperText() {
        // 단위 테스트는 디버그 빌드: 개발자 모드가 켜져 있어 자세한 문구가 나감
        assertEquals("local.properties 를 확인하세요", DevMode.msg("local.properties 를 확인하세요", "다시 시도해 주세요"))
    }

    @Test
    fun defaultUserTextHasNoDeveloperTerms() {
        assertEquals(false, DevMode.UNAVAILABLE.contains("local.properties"))
    }
}
