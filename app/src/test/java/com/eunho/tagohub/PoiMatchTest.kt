package com.eunho.tagohub

import com.eunho.tagohub.data.PoiMatch
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
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

    @Test
    fun stationNameWithLineOrExitTail() {
        // 지도의 역 POI 는 노선·출구가 붙어 와서 두 글자 역(강남·신촌 …)이 매칭되지 않던 것
        assertTrue(PoiMatch.similar("강남역 신분당선", "강남"))
        assertTrue(PoiMatch.similar("신촌역 2번출구", "신촌"))
        assertTrue(PoiMatch.similar("서울역 1호선 경부선", "서울"))
        assertTrue(PoiMatch.similar("인천공항1터미널역 공항철도", "인천공항1터미널"))
        // 장소 이름은 역으로 보지 않음
        assertTrue(!PoiMatch.similar("서울역 롯데마트", "서울"))
        assertTrue(!PoiMatch.similar("강남역 맛집", "강남"))
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

    @Test
    fun busStopPoiPrefersNearestMatchingStopAmongNearbyCandidates() {
        val stops = listOf(
            S("시청", 36.3510, 127.3850),
            S("시청", 36.3520, 127.3850),
            S("시청앞", 36.3511, 127.3850),
        )
        assertEquals(
            stops[0],
            PoiMatch.pick("시청", 36.35101, 127.385, stops, { it.name }, { it.lat to it.lng }, 25.0),
        )
    }

    @Test
    fun stationPoiMatchesNearbyStationByNameWithinEntranceOffset() {
        val station = MetroStation("1", "샘플", "1호선", 37.0, 127.0, "테스트 운영기관")
        val index = StationIndex(listOf(station))
        val group = index.groups.single()

        assertEquals(group, index.matchPoi("샘플역 1호선", 37.0045, 127.0))
        assertEquals(null, index.matchPoi("근처 카페", 37.0045, 127.0))
        assertEquals(null, index.matchPoi("샘플역", 37.02, 127.0))
    }
}
