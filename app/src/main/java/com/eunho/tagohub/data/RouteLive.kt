package com.eunho.tagohub.data

/**
 * 노선 목록(정류장 줄)에 보여 줄 실시간 도착 문구.
 * 정류장의 도착정보 화면과 같은 말로 보이게, 그 정류장의 도착정보가 있으면 그것을 그대로 쓰고,
 * 없으면 버스 위치로 "몇 정류장 전"을 세고 걸리는 시간을 어림한다(노선 거리 기준이라 막히면 틀림).
 */
object RouteLive {
    /**
     * [stopIndex] 번째 정류장에 가장 가까이 오는 버스(그 정류장 앞쪽, 같은 방향)까지의 정류장 수. 정차 중이면 0, 오는 버스가 없으면 null.
     * [busIndexes] 는 각 버스가 있는 정류장의 목록 안 번호.
     */
    fun stopsAway(stopIndex: Int, busIndexes: List<Int>, left: List<Int> = emptyList()): Int? =
        allAway(stopIndex, busIndexes, left).firstOrNull()

    /** 버스 위치 API 는 마지막으로 지난 정류장만 알려 줘서, 정류장을 떠난 뒤에도 같은 정류장으로 잡힘: GPS 로 본 버스 위치 */
    class Positions(
        /** 정류장에 서 있는(떠나지 않은) 버스가 있는 정류장 번호 */
        val at: List<Int>,
        /** 그 정류장을 이미 떠나 다음 정류장으로 가는 중인 버스의 정류장 번호 */
        val left: List<Int>,
    )

    /** 이 정류장 반경 안은 정차 중으로 봄 */
    const val AT_STOP_M = 60.0

    /**
     * 버스가 [here] 정류장을 떠나 [next] 로 가는 중인가: 경기 상태값(정류소 출발)이거나, GPS 가 [here] 에서 [AT_STOP_M] 넘게 떨어져
     * [next] 쪽으로 가는 선분 위(정류장 사이 거리의 1.2배 안)에 있을 때. 좌표를 모르면 false.
     */
    fun departed(bus: BusLocation, here: RouteStop, next: RouteStop?): Boolean = departedAlong(bus, here, next) != null

    /**
     * [departed] 이면 [here]→[next] 사이 어디쯤인지(0 = here, 1 = next)를, 아니면 null.
     * GPS 가 없이 상태값(정류소 출발)만 있으면 막 출발한 것으로 보고 0.3.
     */
    fun departedAlong(bus: BusLocation, here: RouteStop, next: RouteStop?): Double? {
        if (bus.state == 2) return JUST_LEFT
        if (bus.state == 1) return null
        val bLat = bus.lat ?: return null
        val bLng = bus.lng ?: return null
        val hLat = here.lat ?: return null
        val hLng = here.lng ?: return null
        val nLat = next?.lat ?: return null
        val nLng = next.lng ?: return null
        if (com.eunho.tagohub.core.Geo.meters(hLat, hLng, bLat, bLng) <= AT_STOP_M) return null
        // 정류장 사이 선분에 내린 그림자(0 = here, 1 = next): 평면으로 근사해도 이 거리에서는 충분함
        val cos = Math.cos(Math.toRadians(hLat))
        val sx = (nLng - hLng) * cos
        val sy = nLat - hLat
        val len2 = sx * sx + sy * sy
        if (len2 == 0.0) return null
        val t = ((bLng - hLng) * cos * sx + (bLat - hLat) * sy) / len2
        return if (t > 0.0 && t <= 1.2) t else null
    }

    /** 막 출발했다고만 아는 버스를 정류장 사이 어디에 그릴지 */
    private const val JUST_LEFT = 0.3

    /** 정류장 목록(노선 상세)에서 버스를 그릴 자리: 정류장에 서 있는 버스와, 그 정류장을 떠나 다음 정류장으로 가는 중인 버스 */
    class Placement(
        /** 정류장 번호 → 그 정류장에 서 있는 버스 */
        val at: Map<Int, List<BusLocation>>,
        /** 정류장 번호 → 그 정류장을 떠나 다음 정류장으로 가는 중인 버스 */
        val between: Map<Int, List<BusLocation>>,
        /** 배치도용: 정류장 번호 + 정류장 사이 비율(떠난 버스는 0.15~0.85) */
        val fractions: List<Float>,
    )

    fun placement(buses: List<BusLocation>, stops: List<RouteStop>): Placement {
        val at = HashMap<Int, MutableList<BusLocation>>()
        val between = HashMap<Int, MutableList<BusLocation>>()
        val fractions = ArrayList<Float>()
        for (b in buses) {
            val i = stops.indexOfFirst { s -> if (b.ord != null) b.ord == s.ord else b.nodeId == s.nodeId }
            if (i < 0) continue
            val t = departedAlong(b, stops[i], stops.getOrNull(i + 1))
            if (t != null) {
                between.getOrPut(i) { ArrayList() } += b
                fractions += i + t.coerceIn(0.15, 0.85).toFloat()
            } else {
                at.getOrPut(i) { ArrayList() } += b
                fractions += i.toFloat()
            }
        }
        return Placement(at, between, fractions)
    }

    /** [buses] 각각이 [stops] 목록 안 어느 정류장에 있는지, 그리고 그 정류장을 떠났는지 */
    fun positions(buses: List<BusLocation>, stops: List<RouteStop>): Positions {
        val at = ArrayList<Int>()
        val left = ArrayList<Int>()
        for (b in buses) {
            val i = stops.indexOfFirst { s -> if (b.ord != null) b.ord == s.ord else b.nodeId == s.nodeId }
            if (i < 0) continue
            if (departed(b, stops[i], stops.getOrNull(i + 1))) left += i else at += i
        }
        return Positions(at, left)
    }

    /** 위치상 가장 가까운 버스가 도착정보의 첫 버스보다 이만큼(정류장) 이상 더 가까우면 무조건 도착정보에 빠진 버스로 봄 */
    const val NEARER_GAP = 3

    /** 정류장 [stopIndex] 앞쪽에 달리는 모든 버스까지의 정류장 수 (가까운 순). 0 = 이미 와 있음 */
    fun allAway(stopIndex: Int, busIndexes: List<Int>, left: List<Int> = emptyList()): List<Int> =
        (busIndexes.filter { it in 0..stopIndex } + left.filter { it in 0 until stopIndex }).map { stopIndex - it }.sorted()

    /**
     * 버스 위치([aways], 가까운 순)와 도착정보([apiPrevStops] 정류장 전) 중 어느 쪽을 보일지: 위치가 더 믿을 만한 경우 true.
     * 도착 예측 API 는 일부 버스만 주기도 하고 위치보다 늦게 갱신돼서:
     *  - 이미 와 있거나(0), API 값이 없거나, 3정류장 이상 더 가까운 버스가 보이면 위치를 따른다.
     *  - 그보다 가까운 버스가 있어도(1~2정류장) API 의 버스와 같은 거리에 따로 버스가 또 보이면, API 의 버스는 그 버스이고
     *    더 가까운 버스는 API 가 빠뜨린 것이라 위치를 따른다 (2번 노선: API 5정류장 전, 위치엔 3정류장 전과 5정류장 전 버스가 있음).
     *  - 위치에 그런 버스가 없으면 같은 버스를 두 자료가 약간 다르게 센 것이라 도착정보를 그대로 둔다.
     */
    fun preferPosition(apiPrevStops: Int?, aways: List<Int>): Boolean {
        val nearest = aways.minOrNull() ?: return false
        if (apiPrevStops == null || nearest == 0 || nearest <= apiPrevStops - NEARER_GAP) return true
        return nearest < apiPrevStops && aways.any { it != nearest && kotlin.math.abs(it - apiPrevStops) <= 1 }
    }

    fun text(stopsAway: Int, minutes: Int?): String = when {
        stopsAway <= 0 -> "정류장 도착"
        minutes != null -> "${stopsAway}정류장 전 · 약 ${minutes.coerceAtLeast(1)}분"
        else -> "${stopsAway}정류장 전"
    }

    /** 노선 상세의 위치 기반 근사 도착 시간: 초마다 줄어드는 것을 보여 주되 30초 이내면 '곧 도착'으로 단순화한다. */
    fun countdownText(stopsAway: Int, seconds: Int?): String {
        if (stopsAway <= 0) return "정류장 도착"
        val eta = when {
            seconds == null -> null
            seconds <= 30 -> "곧 도착"
            seconds < 60 -> "약 ${seconds}초"
            else -> "약 ${seconds / 60}분 %02d초".format(java.util.Locale.ROOT, seconds % 60)
        }
        return listOfNotNull("${stopsAway}정류장 전", eta).joinToString(" · ")
    }
}
