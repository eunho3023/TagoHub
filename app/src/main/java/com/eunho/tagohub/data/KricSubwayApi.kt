package com.eunho.tagohub.data


interface KricSubwayApi {
    suspend fun routeInfo(regionCode: String, lineCode: String): String

    suspend fun timetable(operatorCode: String, dayCode: String, lineCode: String, stationCode: String): String
}

/** [KricSubwayApi] 를 Ktor 로 (기본 주소·OkHttp 설정은 [httpGetter] 를 만드는 쪽이 정함) */
class KricSubwayApiHttp(private val get: HttpGetter) : KricSubwayApi {
    override suspend fun routeInfo(regionCode: String, lineCode: String): String =
        get("subwayRouteInfo", listOf("mreaWideCd" to regionCode, "lnCd" to lineCode))

    override suspend fun timetable(operatorCode: String, dayCode: String, lineCode: String, stationCode: String): String =
        get("subwayTimetable", listOf("railOprIsttCd" to operatorCode, "dayCd" to dayCode, "lnCd" to lineCode, "stinCd" to stationCode))
}
