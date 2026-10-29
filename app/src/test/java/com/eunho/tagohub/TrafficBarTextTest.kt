package com.eunho.tagohub

import com.eunho.tagohub.data.TrafficSpan
import com.eunho.tagohub.ui.map.trafficBarText
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficBarTextTest {
    @Test
    fun describesShareOfEachState() {
        val spans = listOf(TrafficSpan(0f, 0.2f, 1), TrafficSpan(0.2f, 0.3f, 3), TrafficSpan(0.3f, 1f, 4))
        assertEquals("남은 길 소통: 정체 20% · 서행 10% · 나머지 원활", trafficBarText(spans))
    }

    @Test
    fun smoothOnlyAndNoInfo() {
        assertEquals("남은 길 소통: 원활", trafficBarText(listOf(TrafficSpan(0f, 1f, 4))))
        assertEquals("소통 정보 없음", trafficBarText(emptyList()))
        assertEquals("소통 정보 없음", trafficBarText(listOf(TrafficSpan(0f, 1f, 0))))
    }
}
