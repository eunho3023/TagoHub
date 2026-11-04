package com.eunho.tagohub.data

data class KricSubwayCodes(
    val operatorCode: String,
    val lineCode: String,
    val stationCode: String,
) {
    companion object {
        fun candidates(stationId: String): List<KricSubwayCodes> {
            if (!stationId.startsWith("MTR") || stationId.length <= 6) return emptyList()
            val operatorCode = stationId.substring(3, 5)
            val remaining = stationId.substring(5)
            return (1 until remaining.length).map { split ->
                KricSubwayCodes(operatorCode, remaining.substring(0, split), remaining.substring(split))
            }
        }
    }
}

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
