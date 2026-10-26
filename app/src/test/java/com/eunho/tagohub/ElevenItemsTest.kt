package com.eunho.tagohub

import com.eunho.tagohub.data.DataGoError
import com.eunho.tagohub.data.ExMapLayers
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.data.TrainTimetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ElevenItemsTest {
    private val mon = LocalDate.of(2026, 10, 26)
    private val sat = mon.plusDays(5)
    private val sun = mon.plusDays(6)

    @Test
    fun runDaysFollowTheTimetableRemarks() {
        assertTrue(TrainTimetable.runsOn("실적평일", mon))
        assertFalse(TrainTimetable.runsOn("실적평일", sat))
        assertTrue(TrainTimetable.runsOn("실적토", sat))
        assertTrue(TrainTimetable.runsOn("실적일", sun))
        assertTrue(TrainTimetable.runsOn("월화수", mon.plusDays(1)))
        assertFalse(TrainTimetable.runsOn("월화수", mon.plusDays(3)))
        assertTrue(TrainTimetable.runsOn("금토일", sat))
        assertFalse(TrainTimetable.runsOn("이상한말", mon))
    }

    @Test
    fun tripsBetweenBuildsTodaysTrainsFromTheTimetable() {
        TrainTimetable.set(listOf(
            TrainTimetable.Train("101", "KTX", "실적평일", listOf("서울" to 480, "대전" to 535, "부산" to 640)),
            TrainTimetable.Train("102", "", "실적토", listOf("서울" to 500, "부산" to 660)),
            TrainTimetable.Train("103", "KTX", "실적평일", listOf("부산" to 500, "서울" to 650)),   // 반대 방향
        ))
        val list = TrainTimetable.tripsBetween("서울", "부산", mon)
        assertEquals(listOf("101"), list.map { it.number })
        assertEquals(8, list[0].dep!!.hour)
        assertEquals(40, list[0].arr!!.minute)   // 640분 = 10:40
        assertEquals(listOf("102"), TrainTimetable.tripsBetween("서울", "부산", sat).map { it.number })
        assertEquals("열차", TrainTimetable.tripsBetween("서울", "부산", sat)[0].kind)
    }

    @Test
    fun dataGoErrorBodiesAreNotMistakenForEmptyResults() {
        val body = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnReasonCode":"30"}}}"""
        try {
            DataGoError.check(body, "한국공항공사 국내선 운항 스케줄")
            fail("오류여야 함")
        } catch (e: IOException) {
            assertTrue("활용신청" in e.message.orEmpty())
        }
        DataGoError.check("""{"response":{"header":{"resultCode":"00"},"body":{"items":""}}}""", "x")   // 정상 응답은 통과
        DataGoError.check("그냥 글", "x")
    }

    @Test
    fun flowKeepsTheSlowestDetectorPerSectionAndDirection() {
        val coords = mapOf("v1" to (37.0 to 127.0), "v2" to (37.1 to 127.1))
        val rows = listOf(
            mapOf("vdsId" to "v1", "conzoneId" to "C1", "updownTypeCode" to "E", "speed" to "80", "grade" to "1", "routeName" to "경부선", "conzoneName" to "A-B"),
            mapOf("vdsId" to "v2", "conzoneId" to "C1", "updownTypeCode" to "E", "speed" to "30", "grade" to "3", "routeName" to "경부선", "conzoneName" to "A-B"),
            mapOf("vdsId" to "v1", "conzoneId" to "C1", "updownTypeCode" to "S", "speed" to "90", "grade" to "1", "routeName" to "경부선", "conzoneName" to "A-B"),
            mapOf("vdsId" to "x", "conzoneId" to "C9", "updownTypeCode" to "E", "speed" to "10", "grade" to "3"),   // 위치 모름
            mapOf("vdsId" to "v1", "conzoneId" to "C2", "grade" to "0"),                                        // 판정 불가
        )
        val out = ExMapLayers.flow(rows, coords)
        assertEquals(2, out.size)
        assertTrue(out.any { it.id.startsWith("ex_flow:3:C1:E") && it.lat == 37.1 })
        assertTrue(out.any { it.id.startsWith("ex_flow:1:C1:S") })
    }

    @Test
    fun interchangeStationsWithShiftedCoordinatesStayOneStation() {
        fun st(code: String, name: String, line: String, lat: Double, lng: Double) = MetroStation(code, name, line, lat, lng, "서울교통공사")
        // 6호선 좌표가 약 800m 어긋난 환승역(약수)과, 멀리 떨어진 같은 이름의 다른 도시 역
        val index = StationIndex(listOf(
            st("a", "약수", "3호선", 37.5546, 127.0102),
            st("b", "약수", "6호선", 37.5546 + 0.0072, 127.0102),
            st("c", "중앙", "1호선", 35.1, 129.0),
            st("d", "중앙", "4호선", 37.3, 126.8),
        ))
        val yaksu = index.groups.filter { it.name == "약수" }
        assertEquals(1, yaksu.size)
        assertEquals(2, yaksu[0].stations.size)
        assertEquals(2, index.groups.count { it.name == "중앙" })
    }
}
