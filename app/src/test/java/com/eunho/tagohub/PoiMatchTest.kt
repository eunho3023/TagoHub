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
    fun dottedStopNamesMatchTheirHead() {
        // 정류장 이름은 "전주역.전주역사거리"처럼 점 뒤에 사거리·건물 이름이 붙는다.
        // 점 뒤를 함께 비교하면 "전주역" POI 와 같다고 보지 못해 정류장 대신 주소 카드가 떴다.
        assertTrue(PoiMatch.similar("전주역", "전주역.전주역사거리"))
        assertTrue(PoiMatch.similar("시청", "시청.호남빌딩"))
        val stops = listOf(S("전주역.전주역사거리", 35.8400, 127.1200))
        // 40m 밖이어도 이름이 같으면 그 정류장
        assertEquals(stops[0], PoiMatch.pick("전주역", 35.84030, 127.12030, stops, { it.name }, { it.lat to it.lng }, 40.0))
    }

    @Test
    fun exactNameBeatsACloserDifferentName() {
        // 전국 정류소 좌표가 있어도, 이름이 정확히 같은 정류장을 먼저 고른다
        // (길 건너 "시청앞"이 5m, "시청"이 70m 여도 "시청" POI 는 "시청" 정류장)
        val stops = listOf(
            S("시청앞", 36.35100, 127.38500),
            S("시청", 36.35160, 127.38500),
        )
        assertEquals(stops[1], PoiMatch.pick("시청", 36.35105, 127.385, stops, { it.name }, { it.lat to it.lng }, 25.0))
        // 이름이 안 맞으면 좌표가 25m 안일 때만
        assertEquals(stops[0], PoiMatch.pick("모르는곳", 36.35102, 127.385, stops, { it.name }, { it.lat to it.lng }, 25.0))
        assertEquals(null, PoiMatch.pick("모르는곳", 36.35200, 127.385, stops, { it.name }, { it.lat to it.lng }, 25.0))
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
