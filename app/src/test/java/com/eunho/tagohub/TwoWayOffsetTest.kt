package com.eunho.tagohub

import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.PathMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 노선 선을 같은 길을 오가는 구간만 오른쪽으로 (한도 3m) — 웹 test/sideline.test.js 와 같은 경우 */
class TwoWayOffsetTest {
    private val step = 0.0005
    private val east = List(10) { LatLng(37.5, 127 + it * step) }
    private val outBack = east + east.reversed().drop(1)
    private val loop = listOf(LatLng(37.5, 127.0), LatLng(37.5, 127.001), LatLng(37.501, 127.001), LatLng(37.501, 127.0), LatLng(37.5, 127.00001))

    @Test fun onlyTwoWayStretchesAreMarked() {
        val m = PathMath.twoWayMask(outBack)
        assertTrue((1..4).all { m[it] } && (14..17).all { m[it] })
        assertTrue(PathMath.twoWayMask(loop).none { it })
    }

    @Test fun otherPartCounts() {
        assertTrue(PathMath.twoWayMask(east, listOf(east.reversed())).all { it })
        assertTrue(PathMath.twoWayMask(east).none { it })
    }

    @Test fun loopInAlleyStaysOnRoad() {
        assertEquals(loop, PathMath.offsetTwoWay(loop, 20.0))
    }

    @Test fun offsetIsCappedAtThreeMeters() {
        val out = PathMath.offsetTwoWay(east, 20.0, listOf(east.reversed()))
        val dLat = (east[5].lat - out[5].lat) * 110_540.0   // 동쪽으로 가면 오른쪽은 남쪽
        assertEquals(PathMath.MAX_SIDE_M, dLat, 0.05)
    }
}
