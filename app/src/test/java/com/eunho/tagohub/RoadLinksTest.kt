package com.eunho.tagohub

import com.eunho.tagohub.data.RoadLinkIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** 표준 노드·링크에서 뽑은 도로 선분으로 지금 도로의 제한속도·차로 수 찾기 */
class RoadLinksTest {
    private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.exists() }

    /** 동쪽으로 난 도로(80km/h, 3차로) 하나짜리 파일 */
    private fun tiny(): RoadLinkIndex {
        val b = ByteBuffer.allocate(8 + 15)
        b.putInt(RoadLinkIndex.MAGIC).putInt(1)
        b.putInt(3_750_000).putInt(12_700_000)          // 37.5, 127.0
        b.putShort(0).putShort(400)                      // 동쪽으로 0.004도
        b.put(80).put(3).put(3)
        b.rewind()
        return RoadLinkIndex(b)
    }

    @Test
    fun findsRoadAheadWithinRadiusAndDirection() {
        val idx = tiny()
        val hit = idx.find(37.5000, 127.002, bearing = 90.0)!!
        assertEquals(80, hit.speedKmh)
        assertEquals(3, hit.lanes)
        assertEquals(3, hit.rank)
        assertNotNull(idx.find(37.5000, 127.002, bearing = null))
        assertNull(idx.find(37.5000, 127.002, bearing = 270.0))   // 반대 방향으로 가는 중이면 이 링크가 아님
        assertNull(idx.find(37.5005, 127.002, bearing = 90.0))    // 50m 떨어짐
    }

    @Test
    fun emptyOrBrokenFileGivesNothing() {
        assertEquals(0, RoadLinkIndex(ByteBuffer.allocate(4)).count)
        assertNull(RoadLinkIndex(ByteBuffer.allocate(8)).find(37.5, 127.0, null))
    }

    @Test
    fun bundledFileKnowsMajorRoads() {
        val idx = RoadLinkIndex(ByteBuffer.wrap(asset(RoadLinkIndex.ASSET).readBytes()))
        assertTrue("선분 ${idx.count}개", idx.count > 500_000)
        // 경부고속도로 서초 구간 부근 (양방향 중 하나는 잡혀야 함)
        val hit = listOf(0.0, 180.0).mapNotNull { idx.find(37.4838, 127.0345, it, 60.0) }.minByOrNull { it.distM }
        assertNotNull(hit)
        println("서초 부근: 속도 ${hit!!.speedKmh} 차로 ${hit.lanes} 등급 ${hit.rank} 거리 ${hit.distM}")
    }
}

class SpeedLimitWatchTest {
    @Test
    fun warnsOnceWhenClearlyOverThenAgainAfterSlowingAndGap() {
        val w = com.eunho.tagohub.data.SpeedLimitWatch()
        assertNull(w.update(100, null, 0))                      // 제한속도 모르면 조용
        assertNull(w.update(65, 60, 1_000))                     // 조금 넘은 건 말하지 않음
        assertNotNull(w.update(75, 60, 2_000))                  // 10 이상 넘으면 한 번
        assertNull(w.update(80, 60, 3_000))                     // 계속 넘어도 또 말하지 않음
        assertNull(w.update(50, 60, 4_000))                     // 줄이면 다시 준비
        assertNull(w.update(80, 60, 5_000))                     // 하지만 90초 안에는 다시 말하지 않음
        assertNotNull(w.update(80, 60, 95_000))
    }
}
