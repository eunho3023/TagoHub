package com.eunho.tagohub

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.PublicGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버 /w/pub/... (server/tago-proxy/src/pubgeo.js) 응답 읽기 */
class PublicGeoTest {
    private fun root(json: String) = MiniJson.parse(json) as Map<*, *>

    @Test
    fun places() {
        val r = PublicGeo.parsePlaces(root("""{"src":"vworld","places":[{"name":"서울역","address":"서울특별시 용산구 청파로 378","lat":37.5518,"lng":126.9708,"category":"승강장"},{"name":"","lat":1,"lng":2},{"name":"좌표 없음"}]}"""))
        assertEquals(1, r.size)
        assertEquals("서울역", r[0].name)
        assertEquals("서울특별시 용산구 청파로 378", r[0].address)
        assertEquals(37.5518, r[0].lat, 1e-9)
        assertEquals("승강장", r[0].category)
        assertTrue(PublicGeo.parsePlaces(null).isEmpty())
    }

    @Test
    fun gas() {
        val r = PublicGeo.parseGas(root("""{"stations":[{"id":"A","name":"탑주유소","brand":"자가상표","price":1803,"lat":36.640538,"lng":127.491862,"dist":176}]}"""))
        assertEquals("탑주유소", r[0].name)
        assertEquals("자가상표 · 휘발유 1,803원", r[0].category)
        assertEquals(176, r[0].distance)
    }

    @Test
    fun switchDay() {
        // 2026-11-23 0시(한국 시간) 전에는 쓰지 않음 (개인정보 처리방침 시행일)
        assertFalse(PublicGeo.on(PublicGeo.FROM_MS - 1))
        assertEquals(java.time.OffsetDateTime.parse("2026-11-23T00:00:00+09:00").toInstant().toEpochMilli(), PublicGeo.FROM_MS)
    }

    @Test
    fun stores() {
        // 서버 /w/pub/store: 상가정보, dist 는 거리(m)
        val r = PublicGeo.parsePlaces(root("""{"places":[{"name":"GS25 광장점","address":"도로 1","lat":35.8201,"lng":127.148,"category":"편의점","dist":11}]}"""))
        assertEquals(1, r.size)
        assertEquals(11, r[0].distance)
        assertEquals("편의점", r[0].category)
        assertTrue("BK9" !in PublicGeo.STORE_CODES && "CS2" in PublicGeo.STORE_CODES)   // 은행은 카카오
    }
}
