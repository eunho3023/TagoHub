package com.eunho.tagohub

import com.eunho.tagohub.core.HeadingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 방위: 각도 계산, GPS·나침반 고르기, 회전 행렬에서 방위 구하기 */
class HeadingTest {
    @Test
    fun normalizeAndShortestDelta() {
        assertEquals(350.0, HeadingMath.normalize(-10.0), 1e-9)
        assertEquals(10.0, HeadingMath.normalize(370.0), 1e-9)
        assertEquals(20.0, HeadingMath.delta(350.0, 10.0), 1e-9)      // 350 → 10 은 시계 방향 20°
        assertEquals(-20.0, HeadingMath.delta(10.0, 350.0), 1e-9)
        assertEquals(-90.0, HeadingMath.delta(90.0, 0.0), 1e-9)
    }

    @Test
    fun smoothingCrossesNorthTheShortWay() {
        assertEquals(45.0, HeadingMath.smooth(null, 45.0), 1e-9)
        // 350° 에서 10° 로 갈 때 가운데(180°)를 거치지 않고 북쪽을 지나 2.5° 근처로
        val s = HeadingMath.smooth(350.0, 10.0, alpha = 0.5)
        assertEquals(0.0, s, 1e-9)
    }

    @Test
    fun gpsBearingIsOnlyTrustedWhenMovingFastEnough() {
        assertFalse(HeadingMath.gpsUsable(true, 1.0f))
        assertFalse(HeadingMath.gpsUsable(false, 20f))
        assertTrue(HeadingMath.gpsUsable(true, 2.5f))
    }

    @Test
    fun chooseGpsWhenFreshOtherwiseCompass() {
        assertEquals(80.0, HeadingMath.choose(80.0, 1_000, 10.0)!!, 0.0)
        assertEquals(10.0, HeadingMath.choose(80.0, 20_000, 10.0)!!, 0.0)    // GPS 방향이 오래됨 → 나침반
        assertEquals(10.0, HeadingMath.choose(null, 0, 10.0)!!, 0.0)
        assertNull(HeadingMath.choose(null, 0, null))
    }

    // 회전 행렬(행 우선 3x3, 기기→세계: 세계 x 동·y 북·z 위)
    private fun matrix(vararg v: Float) = floatArrayOf(*v)

    @Test
    fun flatPhoneUsesTheTopEdge() {
        // 단위 행렬: 평평하게 놓고 윗면이 북쪽
        assertEquals(0.0, HeadingMath.headingFromRotation(matrix(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), 1e-6)
        // 윗면이 동쪽: Y축 = 동 (R[1]=1, R[4]=0), X축 = 남 (R[0]=0,R[3]=-1)
        assertEquals(90.0, HeadingMath.headingFromRotation(matrix(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)), 1e-6)
    }

    @Test
    fun uprightPhoneUsesTheBackFace() {
        // 세워 놓고 뒷면이 동쪽을 향함: Z축(화면이 향하는 쪽) = 서쪽, Y축 = 위(하늘)
        assertEquals(90.0, HeadingMath.headingFromRotation(matrix(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)), 1e-6)
        // 뒷면이 북쪽을 향함: Z축 = 남쪽
        assertEquals(0.0, HeadingMath.headingFromRotation(matrix(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)), 1e-6)
        // 뒷면이 서쪽을 향함 → 270°
        assertEquals(270.0, HeadingMath.headingFromRotation(matrix(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)), 1e-6)
    }
}
