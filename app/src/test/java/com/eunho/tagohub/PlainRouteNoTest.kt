package com.eunho.tagohub

import com.eunho.tagohub.ui.bus.plainRouteNo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainRouteNoTest {
    @Test
    fun dropsTrailingBeon() {
        assertEquals("52-1", plainRouteNo("52-1번"))   // 원주 앱 안 파일 → TAGO 번호와 맞춤
        assertEquals("10", plainRouteNo(" 10 "))
        assertEquals("번영1", plainRouteNo("번영1"))   // 앞의 '번'은 그대로
    }
}
