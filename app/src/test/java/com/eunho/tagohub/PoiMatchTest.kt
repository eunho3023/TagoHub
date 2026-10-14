package com.eunho.tagohub

import com.eunho.tagohub.data.PoiMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiMatchTest {
    @Test
    fun names() {
        assertTrue(PoiMatch.similar("서울역 1호선", "서울역"))
        assertTrue(PoiMatch.similar("전북대학교구정문", "전북대학교 구정문"))
        assertTrue(PoiMatch.similar("총신대입구(이수)역", "총신대입구"))
        assertTrue(PoiMatch.similar("평화동주민센터 정류장", "평화동주민센터"))
        assertTrue(!PoiMatch.similar("서울역", "서울대입구"))
        assertTrue(!PoiMatch.similar("역", "역"))                       // 빈 이름끼리는 아님
    }

    data class S(val name: String, val lat: Double, val lng: Double)

    @Test
    fun pickNearestSimilarElseNearest() {
        val c = listOf(S("평화동주민센터", 35.7952, 127.1310), S("평화동주민센터", 35.7949, 127.1316), S("다른정류장", 35.79525, 127.13105))
        // 이름이 같은 두 개(길 건너편 포함) 중 가까운 것
        assertEquals(c[0], PoiMatch.pick("평화동주민센터", 35.79521, 127.13101, c, { it.name }, { it.lat to it.lng }, 30.0))
        // 이름이 다르면 30m 안의 가장 가까운 것, 없으면 null
        assertEquals(c[2], PoiMatch.pick("모르는곳", 35.79526, 127.13106, c, { it.name }, { it.lat to it.lng }, 30.0))
        assertEquals(null, PoiMatch.pick("모르는곳", 35.80, 127.14, c, { it.name }, { it.lat to it.lng }, 30.0))
    }
}
