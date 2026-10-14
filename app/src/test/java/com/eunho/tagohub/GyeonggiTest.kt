package com.eunho.tagohub

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.Gyeonggi
import com.eunho.tagohub.data.GyeonggiMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GBIS v2 명세의 필드명·JSON 구조(response → msgHeader / msgBody → 목록)로 만든 응답 */
class GyeonggiTest {
    private fun rows(json: String, key: String) = MiniJson.findRows(MiniJson.parse(json), key)

    private val arrivals = """{"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-10-11 10:00:00.0","resultCode":0,"resultMessage":"정상적으로 처리되었습니다."},
      "msgBody":{"busArrivalList":[
        {"crowded1":2,"crowded2":0,"flag":"PASS","locationNo1":3,"locationNo2":11,"lowPlate1":1,"lowPlate2":0,
         "plateNo1":"경기70사1234","plateNo2":"경기70사5678","predictTime1":4,"predictTime2":17,"predictTimeSec1":231,"predictTimeSec2":1012,
         "remainSeatCnt1":-1,"remainSeatCnt2":-1,"routeDestId":200000197,"routeDestName":"수원역","routeId":200000037,"routeName":"11",
         "routeTypeCd":13,"staOrder":19,"stateCd1":2,"stateCd2":0,"stationId":200000078,"stationNm1":"장안문","stationNm2":"북수원",
         "turnSeq":40,"vehId1":233000123,"vehId2":233000124},
        {"flag":"PASS","locationNo1":6,"lowPlate1":0,"plateNo1":"경기78아1111","predictTime1":9,"predictTimeSec1":540,
         "predictTime2":"","predictTimeSec2":"","routeDestName":"강남역","routeId":200000085,"routeName":"3000","routeTypeCd":11,"stationId":200000078},
        {"flag":"STOP","routeId":200000999,"routeName":"99","routeTypeCd":30,"predictTime1":"","stationId":200000078}]}}}"""

    @Test
    fun arrivals_twoVehiclesPerRow_secondsPrecision() {
        val a = rows(arrivals, "routeId").flatMap(GyeonggiMappers::arrivals)
        assertEquals(3, a.size) // 11번 두 대 + 3000번 한 대, 운행종료 99번 없음
        val first = a[0]
        assertEquals("GGB200000037", first.routeId)
        assertEquals("11", first.routeNo)
        assertEquals("일반형시내버스", first.routeType)
        assertEquals(231, first.arrSeconds)   // 초 단위 우선
        assertEquals(3, first.prevStops)
        assertEquals("저상버스", first.vehicleType)
        assertEquals("장안문", first.atStop)
        assertEquals("수원역", first.towards)
        assertEquals(1012, a[1].arrSeconds)
        assertEquals("직행좌석형시내버스", a[2].routeType)
    }

    @Test
    fun routeStops_withCoords_andLine() {
        val stops = GyeonggiMappers.routeStops(rows("""{"response":{"msgBody":{"busRouteStationList":[
            {"centerYn":"N","mobileNo":1151,"regionName":"수원","stationId":200000177,"stationName":"장안문","x":127.015433,"y":37.290133,"stationSeq":2,"turnYn":"N"},
            {"centerYn":"N","mobileNo":1109,"regionName":"수원","stationId":200000078,"stationName":"수일중학교","x":126.99985,"y":37.307067,"stationSeq":1,"turnYn":"N"}]}}}""", "stationId"))
        assertEquals(listOf("GGB200000078", "GGB200000177"), stops.map { it.nodeId }) // 순번 정렬
        assertEquals(37.307067, stops[0].lat!!, 1e-9)
        assertEquals("1109", stops[0].nodeNo)
        val line = GyeonggiMappers.line(rows("""{"response":{"msgBody":{"busRouteLineList":[
            {"lineSeq":2,"x":127.01,"y":37.29},{"lineSeq":1,"x":127.0,"y":37.30}]}}}""", "lineSeq"))
        assertEquals(37.30, line.first().lat, 1e-9)
    }

    @Test
    fun buses_stateAndLowFloor_singleObjectBody() {
        // 한 대뿐이면 배열이 아니라 객체 하나로 오는 경우도 처리
        val b = GyeonggiMappers.buses(rows("""{"response":{"msgBody":{"busLocationList":
            {"crowded":2,"lowPlate":1,"plateNo":"경기70사1234","remainSeatCnt":-1,"routeId":200000037,"routeTypeCd":13,
             "stateCd":1,"stationId":200000177,"stationSeq":19,"taglessCd":0,"vehId":233000123}}}}""", "plateNo")).single()
        assertEquals("경기70사1234", b.vehicleNo)
        assertEquals(19, b.ord)
        assertEquals(1, b.state)          // 정류소 도착
        assertEquals(true, b.lowFloor)
        assertEquals(null, b.lat)         // 좌표 없음 → 순번으로 추정
    }

    @Test
    fun routeInfo_times() {
        val r = GyeonggiMappers.route(rows("""{"response":{"msgBody":{"busRouteInfoItem":
            {"routeId":200000085,"routeName":"3000","routeTypeCd":11,"routeTypeName":"직행좌석형시내버스","startStationName":"수원역",
             "endStationName":"강남역","upFirstTime":"05:00","upLastTime":"23:30","peekAlloc":10,"satPeekAlloc":15,"sunPeekAlloc":20}}}}""", "routeId").single())
        assertEquals("GGB200000085", r.routeId)
        assertEquals("05:00", r.firstTime)
        assertEquals("23:30", r.lastTime)
        assertEquals(10, r.interval)
        assertTrue(Gyeonggi.isGyeonggiCity("31010"))
    }
}
