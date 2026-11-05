package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlightTest {
    // 목표 정류장 (36.35, 127.38). 위도 0.001 ≈ 111m
    private fun t() = AlightTracker("대전역", 36.35, 127.38)

    @Test fun stagesOnceEach() {
        val t = t()
        assertEquals(AlightStage.Watching, t.update(36.37, 127.38).stage)          // 2.2km
        val heads = t.update(36.357, 127.38)                                        // 780m
        assertEquals(AlightStage.Heads, heads.stage)
        assertEquals("대전역 정류장까지 약 780m 남았습니다.", heads.speak)
        assertNull(t.update(36.3569, 127.38).speak)                                 // 같은 단계는 한 번만
        assertTrue(t.update(36.353, 127.38).vibrate)                                // 330m 곧
        assertEquals("대전역 정류장입니다. 내리세요.", t.update(36.3505, 127.38).speak)  // 55m
    }

    @Test fun passedAndBadAccuracy() {
        val t = t()
        assertNull(t.update(36.3502, 127.38, accuracyM = 200.0).speak)             // 부정확한 위치는 무시
        t.update(36.3502, 127.38)
        val p = t.update(36.347, 127.38)                                            // 가장 가까웠던 곳에서 330m 멀어짐
        assertEquals(AlightStage.Passed, p.stage)
        assertTrue(p.finished)
        assertEquals("1.2km", AlightTracker.distanceText(1234.0))
        assertEquals("2km", AlightTracker.distanceText(1990.0))
    }
}
