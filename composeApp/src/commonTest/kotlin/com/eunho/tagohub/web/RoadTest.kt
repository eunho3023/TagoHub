package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RoadTest {
    @Test fun parsesRowsAndErrors() {
        val (rows, total) = RoadRepo.parse("""{"code":"SUCCESS","count":2,"list":[{"a":"1","b":" ","c":null},{"a":"2"}]}""")
        assertEquals(2, total)
        assertEquals(listOf(mapOf("a" to "1"), mapOf("a" to "2")), rows)
        assertFailsWith<IllegalStateException> { RoadRepo.parse("""{"code":"ERROR","message":"키 오류"}""") }
    }

    @Test fun incidentsSkipPromoAndUseAltitudeAsLng() {
        val list = RoadRepo.incidents(
            listOf(
                mapOf("accType" to "사고", "roadNM" to "경부선", "smsText" to "**사고 처리중", "latitude" to "36.5", "altitude" to "127.4", "accDate" to "20261105", "accHour" to "10:20:00", "lateLength" to "1500"),
                mapOf("accType" to "이벤트/홍보", "smsText" to "안내"),
                mapOf("accType" to "작업", "smsText" to "차로 통제", "latitude" to "0", "altitude" to "0", "accDate" to "20261105", "accHour" to "09:00:00"),
            ),
        )
        assertEquals(listOf("사고", "작업"), list.map { it.kind })
        assertEquals("사고 처리중", list[0].text)
        assertEquals(127.4, list[0].lng)
        assertEquals(1500, list[0].jamMeters)
        assertNull(list[1].lat)
        assertEquals("1.5km", distance(1500))
    }

    @Test fun jamsKeepSlowestPerZone() {
        val coords = mapOf("V1" to (36.0 to 127.0))
        val list = RoadRepo.jams(
            listOf(
                mapOf("conzoneId" to "Z1", "updownTypeCode" to "S", "vdsId" to "V1", "speed" to "30", "routeName" to "경부선", "conzoneName" to "A-B", "stdHour" to "1020"),
                mapOf("conzoneId" to "Z1", "updownTypeCode" to "S", "vdsId" to "V2", "speed" to "12"),
                mapOf("conzoneId" to "Z2", "updownTypeCode" to "E", "vdsId" to "V3", "speed" to "25"),
            ),
            coords,
        )
        assertEquals(listOf(12, 25), list.map { it.speed })
        assertEquals(36.0, list[0].lat)     // 가장 느린 검지기 위치를 모르면 같은 구간의 다른 검지기 위치
        assertEquals("종점 방향", list[1].dir)
    }

    @Test fun gasPrices() {
        val g = RoadRepo.gas(listOf(mapOf("serviceAreaName" to "서울만남(부산)주유소", "gasolinePrice" to "1,689원", "diselPrice" to "1,559원", "lpgYn" to "N", "lpgPrice" to "1,000원")))
        assertEquals(1689, g[0].gasoline)
        assertEquals(1559, g[0].diesel)
        assertNull(g[0].lpg)
    }
}
