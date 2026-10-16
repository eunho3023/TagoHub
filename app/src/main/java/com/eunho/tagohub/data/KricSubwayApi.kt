package com.eunho.tagohub.data

import retrofit2.http.GET
import retrofit2.http.Query

interface KricSubwayApi {
    @GET("subwayRouteInfo")
    suspend fun routeInfo(
        @Query("mreaWideCd") regionCode: String,
        @Query("lnCd") lineCode: String,
    ): String

    @GET("subwayTimetable")
    suspend fun timetable(
        @Query("railOprIsttCd") operatorCode: String,
        @Query("dayCd") dayCode: String,
        @Query("lnCd") lineCode: String,
        @Query("stinCd") stationCode: String,
    ): String
}
