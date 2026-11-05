package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AirportInfoTest {
    @Test fun parsesStatusRows() {
        val body = """{"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":{"item":[
            {"airFln":"KE1201","airlineKorean":"대한항공","std":"202611050700","etd":"202611050725","rmkKor":"지연","io":"O","city":"CJU","gate":"5","cdsrYn":"N"},
            {"airFln":"OZ8901","std":"202611050800","io":"I","city":"PUS","cdsrYn":"Y"}]}}}}"""
        val r = AirportInfoRepo.parseStatus(body)
        assertEquals(2, AirportInfoRepo.totalCount(body))
        assertEquals("07:25", r[0].estimated)
        assertEquals(true, r[0].departure)
        assertEquals(true, r[1].codeshare)
        assertEquals("KE1201", AirportInfoRepo.norm("KE 1201"))
        // item 이 하나면 객체로 옴
        assertEquals(1, AirportInfoRepo.rows("""{"response":{"body":{"items":{"item":{"airFln":"7C101"}}}}}""").size)
    }

    @Test fun parsesIncheon() {
        val body = """{"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":[
            {"flightId":"KE081","airline":"대한항공","airport":"뉴욕","airportCode":"JFK","scheduleDatetime":"202611051000","estimatedDatetime":"202611051020","gateNumber":"250","terminalId":"P03","remark":"출발","codeshare":"Master"},
            {"flightId":"DL7861","airline":"델타항공","airport":"뉴욕","scheduleDatetime":"202611051000","terminalId":"P03","codeshare":"Slave"}]}}}"""
        val r = AirportInfoRepo.parseIcn(body, departure = true)
        assertEquals("10:20", r[0].estimated)
        assertEquals("T2 250", r[0].gate)
        assertEquals("뉴욕", r[0].other)
        assertEquals(true, r[1].codeshare)
    }

    @Test fun parsesStatic() {
        val s = AirportInfoRepo.parseStatic(
            """{"airports":{"김포":{"code":"GMP","name":"김포국제공항","phone":"1661-2626","transport":[{"type":"시내버스","routes":"종로"}],
               "shops":[{},{}],"delay":{"국내":{"지연율(퍼센트)":"17"}},"cancel":{"국내":{"결항률(퍼센트)":"0.7"}}}}}""",
        )["GMP"]!!
        assertEquals("김포국제공항", s.name)
        assertEquals(listOf("시내버스" to "종로"), s.transport)
        assertEquals(2, s.shopCount)
        assertEquals("17", s.delay["지연율(퍼센트)"])
        assertNull(s.address)
        assertEquals("매우 혼잡", AirportInfoRepo.levelLabel(4))
    }
}
