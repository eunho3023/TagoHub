package com.eunho.tagohub.data

import java.time.LocalDateTime

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
    /** 기차: 조회한 출발·도착역 ID (정차역·환승 찾기에 씀) */
    val depId: String? = null,
    val arrId: String? = null,
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

/** 한 열차가 서는 역과 시각 (KRIC 역별 시각표에서 그 열차번호를 모아 만듦) */
data class KricStopTime(
    val stationName: String,
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
