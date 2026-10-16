package com.eunho.tagohub.data

import java.time.LocalDateTime

/** 역·터미널·공항·도시처럼 "고르는 대상" 공통 모델 */
data class Place(val id: String, val name: String, val sub: String? = null)

/** 등급·차종·항공사처럼 시간표 필터로 쓰는 코드 */
data class Grade(val id: String, val name: String)

data class City(val code: String, val name: String)

// ── 시내버스 ──────────────────────────────────────────────
data class BusStop(
    val cityCode: String,
    val nodeId: String,
    val name: String,
    val nodeNo: String?,
    val lat: Double?,
    val lng: Double?,
)

data class BusRoute(
    val routeId: String,
    val routeNo: String,
    val routeType: String?,
    val startName: String?,
    val endName: String?,
    val firstTime: String?,
    val lastTime: String?,
    val interval: Int?,
    val intervalSat: Int?,
    val intervalSun: Int?,
    /** 이 노선의 TAGO 도시코드 (전국 검색 결과에서 노선을 열 때 사용) */
    val cityCode: String = "",
)

data class BusArrival(
    val routeId: String,
    val routeNo: String,
    val routeType: String?,
    val vehicleType: String?,
    val prevStops: Int?,
    val arrSeconds: Int?,
    /** 버스가 지금 있는 정류장 (전주시 API) */
    val atStop: String? = null,
    /** 방면 (전주시 API brtVianame 의 종점) */
    val towards: String? = null,
)

data class RouteStop(
    val nodeId: String,
    val name: String,
    val nodeNo: String?,
    val ord: Int,
    val upDown: String?,
    val lat: Double?,
    val lng: Double?,
)

data class BusLocation(
    val nodeId: String?,
    val nodeName: String?,
    val ord: Int?,
    val vehicleNo: String?,
    val lat: Double?,
    val lng: Double?,
    /** 저상버스 여부 (전주시 API busLow). 모르면 null */
    val lowFloor: Boolean? = null,
    /** 상태 (경기 stateCd: 0 교차로통과, 1 정류소 도착, 2 정류소 출발). 모르면 null */
    val state: Int? = null,
)

data class BusSearch(
    val stops: List<BusStop>,
    val routes: List<BusRoute>,
    val warning: String? = null,
    /** 내 주변 검색일 때 기준 좌표 */
    val origin: Pair<Double, Double>? = null,
)

// ── 기차·고속·시외·항공 공통 운행편 ─────────────────────────
data class Trip(
    /** 열차등급 / 버스등급 / 항공사 */
    val kind: String,
    /** 열차번호·편명. 버스는 없음 */
    val number: String?,
    val depName: String,
    val arrName: String,
    val dep: LocalDateTime?,
    val arr: LocalDateTime?,
    /** (라벨, 요금). 0원은 "정보 없음"으로 보고 제외 */
    val prices: List<Pair<String, Int>>,
)

// ── 고속버스 실시간 도착 ───────────────────────────────────
data class ExpArrival(
    val depName: String,
    val arrName: String,
    val depMinute: Int?,
    val arrPredict: LocalDateTime?,
    val remainText: String,
    val remainMinutes: Int?,
    val arrived: Boolean,
    val grade: String?,
    val corp: String?,
    val location: String?,
)

// ── 지하철 ────────────────────────────────────────────────
data class SubwayStation(
    val id: String,
    val name: String,
    val routeName: String,
    val routeId: String = "",
)

data class KricRouteStation(
    val regionCode: String,
    val routeCode: String,
    val routeName: String,
    val stationOrder: Int,
    val operatorCode: String,
    val lineCode: String,
    val stationCode: String,
    val stationName: String,
)

data class KricStationRoute(
    val codes: KricSubwayCodes,
    val stations: List<KricRouteStation>,
)

data class KricTrainStop(
    val operatorCode: String,
    val trainNo: String,
    val dayCode: String,
    val stationCode: String,
    val lineCode: String,
    val arrivalTime: String?,
    val departureTime: String?,
)

data class SubwayDeparture(
    /** 운행일 기준 분 (00~02시는 +24h) */
    val serviceMinute: Int,
    val endStationId: String,
    val endStationName: String,
    /** depTime 이 비고 arrTime 만 있는 경우 (종착) */
    val arrivalOnly: Boolean,
)

data class ExitInfo(val exitNo: String, val buses: List<String>, val facilities: List<String>)
