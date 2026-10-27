package com.eunho.tagohub

import com.eunho.tagohub.data.AirportLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirportLiveTest {
    private fun wrap(items: String, total: Int = 1) =
        """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":10,"pageNo":1,"totalCount":$total,"items":$items}}}"""

    @Test
    fun congestionLevels() {
        val body = wrap("""{"item":[{"IATA_APCD":"GMP","PRC_HR":"08:35","CGDR_A_LVL":1.0,"CGDR_B_LVL":2.0,"CGDR_C_LVL":"1.0","CGDR_ALL_LVL":3.0},{"IATA_APCD":"CJU","PRC_HR":"08:35","CGDR_ALL_LVL":4.0}]}""", 2)
        val gmp = AirportLive.congestion(body).first { it.airport == "GMP" }
        assertEquals(listOf(1, 2, 1), gmp.levels)
        assertEquals("혼잡", AirportLive.Congestion.label(gmp.all))
        assertEquals("매우 혼잡", AirportLive.Congestion.label(AirportLive.congestion(body).last().all))
        assertEquals("정보 없음", AirportLive.Congestion.label(null))
    }

    @Test
    fun singleItemObjectAndEmptyItems() {
        assertEquals(1, AirportLive.taxi(wrap("""{"item":{"prcdtm":"20260812130651","witTaxiCT":8,"witPaxCT":12,"xptBdgMi":2}}"""))?.taxis?.let { 1 })
        val taxi = AirportLive.taxi(wrap("""{"item":{"prcdtm":"20260812130651","witTaxiCT":8,"witPaxCT":12,"xptBdgMi":2}}"""))!!
        assertEquals("08-12 13:06", taxi.time)
        assertEquals(12, taxi.passengers)
        assertNull(AirportLive.taxi(wrap("\"\"", 0)))
        assertEquals(emptyList<Any>(), AirportLive.lots("not json"))
    }

    @Test
    fun parkingLots() {
        val body = wrap("""{"item":[{"airportKor":"김포국제공항","parkingAirportCodeName":"국내선 제1주차장","parkingCongestionDegree":"99.25%","parkingCongestion":"만차","parkingOccupiedSpace":"2262.0","parkingTotalSpace":"2279.0","sysGetdate":"2026-08-13","sysGettime":"16:26:03"}]}""")
        val lot = AirportLive.lots(body).single()
        assertEquals("만차", lot.label)
        assertEquals(2262, lot.used)
        assertEquals(2279, lot.total)
        assertEquals("2026-08-13 16:26:03", lot.time)
    }

    @Test
    fun valetPicksTheNewestRowAndProcessTimeReadsSeconds() {
        val v = AirportLive.valet(wrap("""{"item":[{"crtDt":"20260812","crtTime":"044921","cam1":6,"cam2":4,"cam3":1,"total":11,"cdgr":"여유(원활)"},{"crtDt":"20260812","crtTime":"101500","cam1":9,"cam2":9,"cam3":9,"total":27,"cdgr":"혼잡(정체)"}]}"""))!!
        assertEquals(27, v.total)
        assertEquals("08-12 10:15", v.time)
        val p = AirportLive.processTime(wrap("""{"item":[{"IATA_APCD":"GMP","PRC_HR":"13:30","OPR_STS_CD":1,"STY_TCT_AVG_A":1260.0,"STY_TCT_AVG_B":217.0,"STY_TCT_AVG_C":180.0,"STY_TCT_AVG_D":1472.0,"STY_TCT_AVG_ALL":1657.0}]}""")).single()
        assertEquals(true, p.open)
        assertEquals(1657, p.totalSec)
        assertEquals("약 28분", AirportLive.minutes(p.totalSec))
    }

    @Test
    fun indoorCountsEmptyCellsPerFloor() {
        val rows = AirportLive.rows(wrap("""{"item":[{"parkingName":"국제선","levelName":"지하 02층","cellStatus":"EMPTY"},{"parkingName":"국제선","levelName":"지하 02층","cellStatus":"IN"},{"parkingName":"국제선","levelName":"지하 01층","cellStatus":"IN"}]}""", 3))
        val floors = AirportLive.indoor(rows)
        assertEquals(listOf("지하 01층", "지하 02층"), floors.map { it.level })
        assertEquals(1, floors.last().empty)
        assertEquals(2, floors.last().total)
    }
}
