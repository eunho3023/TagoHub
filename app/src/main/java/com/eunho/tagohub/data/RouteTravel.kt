package com.eunho.tagohub.data

/**
 * 노선의 정류장 사이 소요 시간.
 *
 * 앱이 실제로 본 구간 통행시간([SegmentTimes], 같은 차량이 정류장 순번을 옮겨 간 간격의 중앙값)이 있으면 그것을 먼저 쓰고,
 * 없으면 거리로 어림한 값([TripPlanner.minutesBetween])을 쓴다. 정류장 시간표의 예상 시각, 지도에서 GPS 없는 버스의 움직임,
 * 도착 예정 문구가 모두 이 한 곳을 거쳐 같은 값을 보게 한다.
 */
object RouteTravel {
    /** 실측 구간 시간(초). 모르면 null */
    fun measuredSec(routeId: String, fromOrd: Int, toOrd: Int, nowMs: Long = System.currentTimeMillis()): Double? =
        SegmentTimes.seconds(routeId, fromOrd, toOrd, nowMs)

    /**
     * [fromOrd] → [toOrd] 소요(분). 실측이 있으면 그것, 없으면 [byDistance] (좌표·정류장 수로 어림).
     * 시간표는 하루치 예상 시각을 만들기 때문에 지금 도로 상황 보정([RouteSpeed])은 곱하지 않는다.
     */
    fun minutes(routeId: String, fromOrd: Int, toOrd: Int, byDistance: (Int, Int) -> Double): Double {
        measuredSec(routeId, fromOrd, toOrd)?.let { return it / 60.0 }
        return byDistance(fromOrd, toOrd)
    }

    /**
     * 노선 정류장 순서대로 한 구간씩 더해 가는 소요시간 함수. 구간마다 실측이 있으면 그것을, 없으면 거리 어림을 쓴다.
     * (한 번에 잰 기록이 없어도 일부 구간만 실측이면 그만큼 반영된다. 운행마다 같은 구간을 다시 묻지 않게 결과를 기억한다.)
     */
    fun minutesFn(routeId: String, stops: List<RouteStop>, byDistance: (Int, Int) -> Double): (Int, Int) -> Double {
        val index = HashMap<Int, Int>(stops.size * 2)
        stops.forEachIndexed { i, s -> index.putIfAbsent(s.ord, i) }
        val pairs = HashMap<Long, Double>()
        val steps = HashMap<Int, Double>()
        return { from, to ->
            pairs.getOrPut(from.toLong() * 100_000L + to) {
                val i = index[from]
                val j = index[to]
                if (i == null || j == null || j <= i) {
                    minutes(routeId, from, to, byDistance)
                } else {
                    var sum = 0.0
                    for (k in i until j) {
                        sum += steps.getOrPut(k) {
                            minutes(routeId, stops[k].ord, stops[k + 1].ord, byDistance)
                        }
                    }
                    sum
                }
            }
        }
    }

    /**
     * 정류장 시간표용 소요시간: 웹 버전(site/js/busdata.js offsetMinutes)과 똑같은 어림 —
     * 이웃 정류장 사이 직선거리 ÷ 300m/분(둘 다 좌표가 있을 때) + 정류장마다 0.3분.
     * 폰마다 다른 실측 구간 시간은 섞지 않아 앱과 웹의 시간표 시각이 같게 나온다.
     */
    fun timetableFn(stops: List<RouteStop>): (Int, Int) -> Double {
        val index = HashMap<Int, Int>(stops.size * 2)
        stops.forEachIndexed { i, s -> index.putIfAbsent(s.ord, i) }
        val cum = DoubleArray(stops.size)
        for (k in 1 until stops.size) {
            val a = stops[k - 1]
            val b = stops[k]
            val la = a.lat; val lo = a.lng; val lb = b.lat; val gb = b.lng
            val m = if (la != null && lo != null && lb != null && gb != null) webMeters(la, lo, lb, gb) / TIMETABLE_M_PER_MIN else 0.0
            cum[k] = cum[k - 1] + m + TIMETABLE_STOP_MIN
        }
        return { from, to ->
            val i = index[from]
            val j = index[to]
            if (i == null || j == null || j < i) 0.0 else cum[j] - cum[i]
        }
    }
    /** 웹 site/js/core.js meters 와 같은 식 (반올림 경계에서 1분씩 갈리지 않게) */
    fun webMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dy = (la2 - la1) * 111000
        val dx = (lo2 - lo1) * 111000 * Math.cos(la1 * Math.PI / 180)
        return Math.sqrt(dx * dx + dy * dy)
    }
    const val TIMETABLE_M_PER_MIN = 300.0
    const val TIMETABLE_STOP_MIN = 0.3

    /** [fromOrd] → [toOrd] 사이 구간 중 실측 시간이 쓰인 곳이 있는지 (시간표에 "실측 반영"을 보여 줄 때) */
    fun usesMeasured(routeId: String, stops: List<RouteStop>, fromOrd: Int, toOrd: Int): Boolean {
        val i = stops.indexOfFirst { it.ord == fromOrd }
        val j = stops.indexOfFirst { it.ord == toOrd }
        if (i < 0 || j <= i) return false
        return (i until j).any { k -> measuredSec(routeId, stops[k].ord, stops[k + 1].ord) != null }
    }

    /**
     * 지도에서 GPS 없는 버스가 두 정류장 사이를 움직이는 데 걸리는 시간(초).
     * 실측이 있으면 그것, 없으면 [byDistance] 분에 지금 도로 상황 보정([RouteSpeed])을 곱한다 (막히면 느리게).
     */
    fun travelSec(routeId: String, fromOrd: Int, toOrd: Int, byDistance: (Int, Int) -> Double): Double {
        measuredSec(routeId, fromOrd, toOrd)?.let { return it.coerceAtLeast(1.0) }
        return (byDistance(fromOrd, toOrd) * RouteSpeed.factor(routeId) * 60.0).coerceAtLeast(1.0)
    }
}
