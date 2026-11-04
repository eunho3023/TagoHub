package com.eunho.tagohub.data

/**
 * 승차 알림을 언제 울릴지: 기다리는 버스가 [stops] 정류장 전 이내로 오거나, 남은 시간이 [minutes] 분 이내가 되면.
 * 배차 간격으로 어림한 값("약 N분 · 배차 기준")은 실제 버스가 아니라서 울리지 않는다.
 */
data class BoardRule(val stops: Int? = null, val minutes: Int? = null) {
    val label: String get() = stops?.let { "${it}정류장 전" } ?: minutes?.let { "${it}분 전" } ?: ""

    fun reached(a: BusArrival?, elapsedSec: Int = 0): Boolean {
        if (a == null || a.scheduled) return false
        if (stops != null && a.prevStops != null && a.prevStops <= stops) return true
        val sec = a.arrSeconds?.minus(elapsedSec) ?: return false
        return minutes != null && sec <= minutes * 60
    }

    companion object {
        /** 알림 문구: "3정류장 전, 약 4분 뒤 도착" / "곧 도착" / "정류장 도착" */
        fun phrase(a: BusArrival): String {
            if (a.prevStops == 0) return "정류장 도착"
            val time = a.arrSeconds?.let { if (it <= 30) "곧 도착" else "약 ${(it + 30) / 60}분 뒤 도착" }
            return listOfNotNull(a.prevStops?.let { "${it}정류장 전" }, time).joinToString(", ").ifEmpty { "곧 도착" }
        }

        val OPTIONS = listOf(BoardRule(stops = 2), BoardRule(stops = 3), BoardRule(minutes = 5), BoardRule(minutes = 10))

        /** 이 노선의 가장 빨리 오는 버스 (배차 기준 어림은 뺌) */
        fun next(list: List<BusArrival>, routeId: String): BusArrival? =
            list.filter { it.routeId == routeId && !it.scheduled }.minByOrNull { it.arrSeconds ?: Int.MAX_VALUE }
    }
}
