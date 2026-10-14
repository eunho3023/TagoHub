package com.eunho.tagohub

import com.eunho.tagohub.core.OffsetLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OffsetLineTest {
    @Test
    fun eastbound_shiftsToRight_whichIsDownOnScreen() {
        val (x, y) = OffsetLine.offset(floatArrayOf(0f, 100f, 200f), floatArrayOf(50f, 50f, 50f), 4f)
        assertTrue(y.all { abs(it - 54f) < 1e-3f })   // 화면 아래 = 동쪽으로 갈 때 오른쪽
        assertEquals(100f, x[1], 1e-3f)
    }

    @Test
    fun oppositeDirections_endUpOnOppositeSides() {
        // 같은 도로를 반대로 가는 두 선 → 서로 다른 쪽으로 벌어짐 (겹치지 않음)
        val go = OffsetLine.offset(floatArrayOf(0f, 200f), floatArrayOf(50f, 50f), 4f).second
        val back = OffsetLine.offset(floatArrayOf(200f, 0f), floatArrayOf(50f, 50f), 4f).second
        assertTrue(go.all { it > 50f } && back.all { it < 50f })
        assertEquals(8f, go[0] - back[0], 1e-3f)
    }

    @Test
    fun corner_staysParallel_atDistanceD() {
        // 동쪽으로 가다 남쪽(화면 아래)으로 꺾임 → 모서리 점은 안쪽으로 4·√2 만큼
        val (x, y) = OffsetLine.offset(floatArrayOf(0f, 100f, 100f), floatArrayOf(0f, 0f, 100f), 4f)
        assertEquals(96f, x[1], 1e-2f)
        assertEquals(4f, y[1], 1e-2f)
        assertEquals(96f, x[2], 1e-2f) // 남쪽으로 갈 때 오른쪽 = 화면 왼쪽
    }

    @Test
    fun duplicatePoints_noNaN() {
        val (x, y) = OffsetLine.offset(floatArrayOf(0f, 0f, 100f), floatArrayOf(0f, 0f, 0f), 4f)
        assertTrue((x + y).none { it.isNaN() })
    }
}
