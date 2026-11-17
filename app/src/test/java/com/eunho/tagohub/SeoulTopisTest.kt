package com.eunho.tagohub

import com.eunho.tagohub.data.SeoulTopis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeoulTopisTest {
    /** 소통 등급: TOPIS 기준 (도시고속도로 70·30, 일반도로 25·15 km/h), 웹 topis.js speedColor 와 같음 */
    @Test
    fun grade_followsTopisThresholds() {
        assertEquals(1, SeoulTopis.grade("02", 70))
        assertEquals(2, SeoulTopis.grade("02", 69))
        assertEquals(3, SeoulTopis.grade("02", 29))
        assertEquals(1, SeoulTopis.grade("04", 25))
        assertEquals(2, SeoulTopis.grade("04", 15))
        assertEquals(3, SeoulTopis.grade("05", 14))
    }

    /** 서울 링크 근처(60m 안)만 "덮음" → 그 자리 고속도로 소통 선은 빼서 겹치지 않게 */
    @Test
    fun covers_onlyNearSeoulLinks() {
        val pts = listOf(com.eunho.tagohub.data.LatLng(37.50, 127.00), com.eunho.tagohub.data.LatLng(37.50, 127.01))
        val link = SeoulTopis.Link("1", "경부고속도로", "a", "b", 880, "02", pts, 37.50, 127.00, 37.50, 127.01)
        assertTrue(SeoulTopis.covers(listOf(link), 37.5003, 127.005))   // 약 33m
        assertFalse(SeoulTopis.covers(listOf(link), 37.5010, 127.005))  // 약 110m
        assertFalse(SeoulTopis.covers(emptyList(), 37.5, 127.005))
    }

    @Test
    fun inSeoul_onlyAroundSeoul() {
        assertTrue(SeoulTopis.inSeoul(37.49, 127.02, 37.51, 127.04))      // 강남역
        assertFalse(SeoulTopis.inSeoul(35.80, 127.10, 35.85, 127.15))     // 전주
    }

    /** 따릉이: 서버 /w/topis/bike 응답 → 지도 점 (id 두 번째 칸 = 대여 가능 대수, 지도 글자에 씀) */
    @Test
    fun bikes_parseAndFeature() {
        val list = SeoulTopis.parseBikes("""[{"id":"ST-4","name":"망원역 1번출구 앞","lat":37.555649,"lng":126.910629,"bikes":7,"racks":15},{"id":"","name":"x","lat":1,"lng":1}]""")
        assertEquals(1, list.size)
        val f = SeoulTopis.bikeFeature(list[0])
        assertEquals("seoul_bike:7:ST-4", f.id)
        assertEquals("망원역 1번출구 앞", f.title)
        assertEquals("대여 가능 7대 · 거치대 15", f.details)
        // 서울 밖 (전국 공영자전거): ID 에 지역코드가 붙고 거치대 수는 없음. 지도 글자는 여전히 두 번째 칸
        val dj = SeoulTopis.bikeFeature(SeoulTopis.parseBikes("""[{"id":"3000000000:ST0004","name":"탄방동 한가람아파트(2동)","lat":36.348201,"lng":127.390409,"bikes":2,"racks":0}]""").single())
        assertEquals("seoul_bike:2:3000000000:ST0004", dj.id)
        assertEquals("2", dj.id.split(':')[1])
        assertEquals("대여 가능 2대", dj.details)
        // 실시간 대수 없는 대여소 (표준데이터): 대수 칸 "-", 운영시간·요금을 덧붙임
        val std = SeoulTopis.bikeFeature(SeoulTopis.parseBikes("""[{"id":"std:12","name":"세종 대여소","lat":36.5,"lng":127.26,"bikes":null,"racks":10,"info":"24시간 · 무료"}]""").single())
        assertEquals("seoul_bike:-:std:12", std.id)
        assertEquals("실시간 대수 없음 · 거치대 10 · 24시간 · 무료", std.details)
        assertEquals(emptyList<SeoulTopis.Bike>(), SeoulTopis.parseBikes(null))
    }
}
