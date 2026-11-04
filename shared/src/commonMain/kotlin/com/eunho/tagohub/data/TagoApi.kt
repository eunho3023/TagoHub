package com.eunho.tagohub.data

/**
 * 국토교통부 TAGO (apis.data.go.kr/1613000) 전체 (폰·웹·PC 공용).
 * 모든 응답은 String(XML) 으로 받아 TagoXml 로 파싱한다.
 * [get] 은 플랫폼이 넣는다: 경로·질의 값(null 은 빼고)으로 묻고 본문을 돌려줌. serviceKey·중계 서버는 그쪽이 붙인다.
 */
class TagoApi(private val get: suspend (path: String, query: List<Pair<String, String?>>) -> String) {


    // ───────── 시내버스: 정류소 ─────────
    suspend fun busCities(page: Int, rows: Int): String =
        get("BusSttnInfoInqireService/getCtyCodeList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun busStops(cityCode: String, nodeNm: String?, nodeNo: String?, page: Int, rows: Int): String =
        get("BusSttnInfoInqireService/getSttnNoList", listOf("cityCode" to cityCode, "nodeNm" to nodeNm, "nodeNo" to nodeNo, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun busStopsNear(lat: Double, lng: Double, page: Int, rows: Int): String =
        get("BusSttnInfoInqireService/getCrdntPrxmtSttnList", listOf("gpsLati" to lat.toString(), "gpsLong" to lng.toString(), "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun busStopRoutes(cityCode: String, nodeId: String, page: Int, rows: Int): String =
        get("BusSttnInfoInqireService/getSttnThrghRouteList", listOf("cityCode" to cityCode, "nodeid" to nodeId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 시내버스: 노선 ─────────
    suspend fun busRoutes(cityCode: String, routeNo: String, page: Int, rows: Int): String =
        get("BusRouteInfoInqireService/getRouteNoList", listOf("cityCode" to cityCode, "routeNo" to routeNo, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun busRouteInfo(cityCode: String, routeId: String): String =
        get("BusRouteInfoInqireService/getRouteInfoIem", listOf("cityCode" to cityCode, "routeId" to routeId))

    suspend fun busRouteStops(cityCode: String, routeId: String, page: Int, rows: Int): String =
        get("BusRouteInfoInqireService/getRouteAcctoThrghSttnList", listOf("cityCode" to cityCode, "routeId" to routeId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 시내버스: 위치·도착 ─────────
    suspend fun busLocations(cityCode: String, routeId: String, page: Int, rows: Int): String =
        get("BusLcInfoInqireService/getRouteAcctoBusLcList", listOf("cityCode" to cityCode, "routeId" to routeId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun busArrivals(cityCode: String, nodeId: String, page: Int, rows: Int): String =
        get("ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", listOf("cityCode" to cityCode, "nodeId" to nodeId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 지하철 ─────────
    suspend fun subwayStations(name: String, page: Int, rows: Int): String =
        get("SubwayInfo/GetKwrdFndSubwaySttnList", listOf("subwayStationName" to name, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    /** dailyTypeCode 01 평일 / 02 토 / 03 일·공휴일, upDownTypeCode U 상행 / D 하행 */
    suspend fun subwaySchedule(stationId: String, dailyType: String, upDown: String, page: Int, rows: Int): String =
        get("SubwayInfo/GetSubwaySttnAcctoSchdulList", listOf("subwayStationId" to stationId, "dailyTypeCode" to dailyType, "upDownTypeCode" to upDown, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun subwayExitBuses(stationId: String, page: Int, rows: Int): String =
        get("SubwayInfo/GetSubwaySttnExitAcctoBusRouteList", listOf("subwayStationId" to stationId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun subwayExitFacilities(stationId: String, page: Int, rows: Int): String =
        get("SubwayInfo/GetSubwaySttnExitAcctoCfrFcltyList", listOf("subwayStationId" to stationId, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 기차 ─────────
    suspend fun trainCities(page: Int, rows: Int): String =
        get("TrainInfo/GetCtyCodeList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun trainStations(cityCode: String, page: Int, rows: Int): String =
        get("TrainInfo/GetCtyAcctoTrainSttnList", listOf("cityCode" to cityCode, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun trainKinds(page: Int, rows: Int): String =
        get("TrainInfo/GetVhcleKndList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun trainTrips(dep: String, arr: String, ymd: String, grade: String?, page: Int, rows: Int): String =
        get("TrainInfo/GetStrtpntAlocFndTrainInfo", listOf("depPlaceId" to dep, "arrPlaceId" to arr, "depPlandTime" to ymd, "trainGradeCode" to grade, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 국내선 항공 ─────────
    suspend fun airlines(page: Int, rows: Int): String =
        get("DmstcFlightNvgInfo/GetAirmanList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun flights(dep: String, arr: String, ymd: String, airline: String?, page: Int, rows: Int): String =
        get("DmstcFlightNvgInfo/GetFlightOpratInfoList", listOf("depAirportId" to dep, "arrAirportId" to arr, "depPlandTime" to ymd, "airlineId" to airline, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 고속버스 시간표 (터미널 ID: NAEKxxx) ─────────
    suspend fun expTerminals(name: String?, page: Int, rows: Int): String =
        get("ExpBusInfo/GetExpBusTrminlList", listOf("terminalNm" to name, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun expGrades(page: Int, rows: Int): String =
        get("ExpBusInfo/GetExpBusGradList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun expTrips(dep: String, arr: String, ymd: String, grade: String?, page: Int, rows: Int): String =
        get("ExpBusInfo/GetStrtpntAlocFndExpbusInfo", listOf("depTerminalId" to dep, "arrTerminalId" to arr, "depPlandTime" to ymd, "busGradeId" to grade, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 시외버스 시간표 (터미널 ID: NAIxxxx) ─────────
    suspend fun suburbTerminals(name: String?, page: Int, rows: Int): String =
        get("SuburbsBusInfo/GetSuberbsBusTrminlList", listOf("terminalNm" to name, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun suburbGrades(page: Int, rows: Int): String =
        get("SuburbsBusInfo/GetSuberbsBusGradList", listOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun suburbTrips(dep: String, arr: String, ymd: String, grade: String?, page: Int, rows: Int): String =
        get("SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo", listOf("depTerminalId" to dep, "arrTerminalId" to arr, "depPlandTime" to ymd, "busGradeId" to grade, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    // ───────── 고속버스 실시간 도착 (터미널 코드: 숫자 3자리, ExpBusInfo 와 별개 체계) ─────────
    suspend fun expArrTerminals(name: String?, page: Int, rows: Int): String =
        get("ExpBusArrInfo/GetExpBusTmnList", listOf("tmnNm" to name, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    /** ⚠ 파라미터명 depTmnCd 는 응답 필드명 기반 추정 – README 참고 */
    suspend fun expArrDestinations(depCode: String, page: Int, rows: Int): String =
        get("ExpBusArrInfo/GetArrTmnFromDepTmn", listOf(EXP_ARR_DEP to depCode, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    suspend fun expArrivals(depCode: String, arrCode: String, page: Int, rows: Int): String =
        get("ExpBusArrInfo/GetExpBusArrPrdtInfo", listOf(EXP_ARR_DEP to depCode, EXP_ARR_ARR to arrCode, "pageNo" to page.toString(), "numOfRows" to rows.toString()))

    companion object {
        const val EXP_ARR_DEP = "depTmnCd"
        const val EXP_ARR_ARR = "arrTmnCd"
    }
}
