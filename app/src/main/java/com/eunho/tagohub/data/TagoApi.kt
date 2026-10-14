package com.eunho.tagohub.data

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 국토교통부 TAGO (apis.data.go.kr/1613000) 전체.
 * 모든 응답은 String(XML) 으로 받아 TagoXml 로 파싱한다.
 * serviceKey 는 Network 인터셉터가 붙인다.
 */
interface TagoApi {

    // ───────── 시내버스: 정류소 ─────────
    @GET("BusSttnInfoInqireService/getCtyCodeList")
    suspend fun busCities(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("BusSttnInfoInqireService/getSttnNoList")
    suspend fun busStops(
        @Query("cityCode") cityCode: String,
        @Query("nodeNm") nodeNm: String?,
        @Query("nodeNo") nodeNo: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("BusSttnInfoInqireService/getCrdntPrxmtSttnList")
    suspend fun busStopsNear(
        @Query("gpsLati") lat: Double,
        @Query("gpsLong") lng: Double,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("BusSttnInfoInqireService/getSttnThrghRouteList")
    suspend fun busStopRoutes(
        @Query("cityCode") cityCode: String,
        @Query("nodeid") nodeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 시내버스: 노선 ─────────
    @GET("BusRouteInfoInqireService/getRouteNoList")
    suspend fun busRoutes(
        @Query("cityCode") cityCode: String,
        @Query("routeNo") routeNo: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("BusRouteInfoInqireService/getRouteInfoIem")
    suspend fun busRouteInfo(@Query("cityCode") cityCode: String, @Query("routeId") routeId: String): String

    @GET("BusRouteInfoInqireService/getRouteAcctoThrghSttnList")
    suspend fun busRouteStops(
        @Query("cityCode") cityCode: String,
        @Query("routeId") routeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 시내버스: 위치·도착 ─────────
    @GET("BusLcInfoInqireService/getRouteAcctoBusLcList")
    suspend fun busLocations(
        @Query("cityCode") cityCode: String,
        @Query("routeId") routeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("BusLcInfoInqireService/getRouteAcctoSpcifySttnAccesBusLcInfo")
    suspend fun busLocationsNearStop(
        @Query("cityCode") cityCode: String,
        @Query("routeId") routeId: String,
        @Query("nodeId") nodeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList")
    suspend fun busArrivals(
        @Query("cityCode") cityCode: String,
        @Query("nodeId") nodeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList")
    suspend fun busArrivalsForRoute(
        @Query("cityCode") cityCode: String,
        @Query("nodeId") nodeId: String,
        @Query("routeId") routeId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 지하철 ─────────
    @GET("SubwayInfo/GetKwrdFndSubwaySttnList")
    suspend fun subwayStations(
        @Query("subwayStationName") name: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    /** dailyTypeCode 01 평일 / 02 토 / 03 일·공휴일, upDownTypeCode U 상행 / D 하행 */
    @GET("SubwayInfo/GetSubwaySttnAcctoSchdulList")
    suspend fun subwaySchedule(
        @Query("subwayStationId") stationId: String,
        @Query("dailyTypeCode") dailyType: String,
        @Query("upDownTypeCode") upDown: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("SubwayInfo/GetSubwaySttnExitAcctoBusRouteList")
    suspend fun subwayExitBuses(
        @Query("subwayStationId") stationId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("SubwayInfo/GetSubwaySttnExitAcctoCfrFcltyList")
    suspend fun subwayExitFacilities(
        @Query("subwayStationId") stationId: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 기차 ─────────
    @GET("TrainInfo/GetCtyCodeList")
    suspend fun trainCities(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("TrainInfo/GetCtyAcctoTrainSttnList")
    suspend fun trainStations(
        @Query("cityCode") cityCode: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("TrainInfo/GetVhcleKndList")
    suspend fun trainKinds(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("TrainInfo/GetStrtpntAlocFndTrainInfo")
    suspend fun trainTrips(
        @Query("depPlaceId") dep: String,
        @Query("arrPlaceId") arr: String,
        @Query("depPlandTime") ymd: String,
        @Query("trainGradeCode") grade: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 국내선 항공 ─────────
    @GET("DmstcFlightNvgInfo/GetArprtList")
    suspend fun airports(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("DmstcFlightNvgInfo/GetAirmanList")
    suspend fun airlines(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("DmstcFlightNvgInfo/GetFlightOpratInfoList")
    suspend fun flights(
        @Query("depAirportId") dep: String,
        @Query("arrAirportId") arr: String,
        @Query("depPlandTime") ymd: String,
        @Query("airlineId") airline: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 고속버스 시간표 (터미널 ID: NAEKxxx) ─────────
    @GET("ExpBusInfo/GetExpBusTrminlList")
    suspend fun expTerminals(
        @Query("terminalNm") name: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("ExpBusInfo/GetExpBusGradList")
    suspend fun expGrades(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("ExpBusInfo/GetStrtpntAlocFndExpbusInfo")
    suspend fun expTrips(
        @Query("depTerminalId") dep: String,
        @Query("arrTerminalId") arr: String,
        @Query("depPlandTime") ymd: String,
        @Query("busGradeId") grade: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 시외버스 시간표 (터미널 ID: NAIxxxx) ─────────
    @GET("SuburbsBusInfo/GetSuberbsBusTrminlList")
    suspend fun suburbTerminals(
        @Query("terminalNm") name: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("SuburbsBusInfo/GetSuberbsBusGradList")
    suspend fun suburbGrades(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String

    @GET("SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo")
    suspend fun suburbTrips(
        @Query("depTerminalId") dep: String,
        @Query("arrTerminalId") arr: String,
        @Query("depPlandTime") ymd: String,
        @Query("busGradeId") grade: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    // ───────── 고속버스 실시간 도착 (터미널 코드: 숫자 3자리, ExpBusInfo 와 별개 체계) ─────────
    @GET("ExpBusArrInfo/GetExpBusTmnList")
    suspend fun expArrTerminals(
        @Query("tmnNm") name: String?,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    /** ⚠ 파라미터명 depTmnCd 는 응답 필드명 기반 추정 – README 참고 */
    @GET("ExpBusArrInfo/GetArrTmnFromDepTmn")
    suspend fun expArrDestinations(
        @Query(EXP_ARR_DEP) depCode: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    @GET("ExpBusArrInfo/GetExpBusArrPrdtInfo")
    suspend fun expArrivals(
        @Query(EXP_ARR_DEP) depCode: String,
        @Query(EXP_ARR_ARR) arrCode: String,
        @Query("pageNo") page: Int,
        @Query("numOfRows") rows: Int,
    ): String

    companion object {
        const val EXP_ARR_DEP = "depTmnCd"
        const val EXP_ARR_ARR = "arrTmnCd"
    }
}
