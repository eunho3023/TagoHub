package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/**
 * 노선 상·하행 나누기. 노선 정류장 목록은 보통 기점 → 회차점 → 기점으로 한 줄인데,
 * 같은 도로를 오가면 선·화살표가 겹쳐 보기 불편해서 한 방향씩 보여준다.
 *
 * 회차점: 정류장마다 방향 코드(회차 표시)가 있을 때만 — TAGO updowncd, 경기 turnYn, 경기 노선경유정류소 상행·하행.
 * 코드가 바뀌기 직전 정류장이 회차점. 회차 표시가 없으면 추측(가장 먼 정류장 등)하지 않고 나누지 않음 → 방면 버튼 없이 전체.
 * 회차 정류장은 두 방향 모두에 들어간다.
 */
object RouteDirections {
    /** 회차 노선(되돌아오는 노선)인지: 첫·끝 정류장이 같거나 이름이 같거나 300m 안 (길 건너편 포함) */
    fun isRoundTrip(stops: List<RouteStop>): Boolean {
        if (stops.size < 4) return false
        val a = stops.first()
        val b = stops.last()
        return a.nodeId == b.nodeId || a.name == b.name || near(a, b, 300.0)
    }

    /** 노선 이름으로 회차 노선인지 (기점 = 종점, 예: 4000 전주대캠퍼스-전주대캠퍼스) */
    fun isRoundTrip(startName: String?, endName: String?): Boolean =
        !startName.isNullOrBlank() && startName.trim() == endName?.trim()

    /**
     * 서울처럼 정류장 방향 코드가 없는 노선에 회차 정류장(서울 getStaionByRoute 의 transYn=Y)을 표시:
     * 첫 정류장 다음에 처음 나오는 회차 정류장까지 "0"(상행), 그 뒤 "1"(하행). 못 찾으면 그대로(→ 전체).
     */
    fun markTurn(stops: List<RouteStop>, turnNodeId: String?): List<RouteStop> {
        if (turnNodeId.isNullOrBlank() || stops.size < 3) return stops
        val t = (1 until stops.size).firstOrNull { stops[it].nodeId == turnNodeId } ?: return stops
        if (t >= stops.lastIndex) return stops // 마지막 정류장이 회차면 편도 → 나누지 않음
        return stops.mapIndexed { i, s -> s.copy(upDown = if (i <= t) "0" else "1") }
    }

    fun turnIndex(stops: List<RouteStop>): Int? {
        // 편도 노선은 나누지 않음 → 방면 버튼 없이 그대로
        if (!isRoundTrip(stops)) return null
        val ud = stops.map { it.upDown?.trim()?.ifEmpty { null } }
        if (ud.all { it != null } && ud.toSet().size > 1) {
            val change = ud.indexOfFirst { it != ud[0] }
            return (change - 1).coerceIn(1, stops.lastIndex - 1)
        }
        return null // 회차 표시 없음 → 전체
    }

    /** dir 0 = 기점 → 회차점, 1 = 회차점 → 끝 */
    fun range(n: Int, turn: Int?, dir: Int): IntRange = when {
        turn == null -> 0 until n
        dir == 0 -> 0..turn
        else -> turn until n
    }

    /** 이 정류장이 처음 나오는 방향 (정류장에서 노선을 열었을 때 그 방향을 보여주려고) */
    fun dirOf(stops: List<RouteStop>, turn: Int?, nodeId: String?): Int {
        if (turn == null || nodeId == null) return 0
        val i = stops.indexOfFirst { it.nodeId == nodeId }
        return if (i > turn) 1 else 0
    }

    /** 방향 이름 = 그 방향 마지막 정류장 */
    fun label(stops: List<RouteStop>, turn: Int?, dir: Int): String? =
        range(stops.size, turn, dir).lastOrNull()?.let { stops[it].name }

    private fun dist(a: RouteStop, b: RouteStop): Double {
        if (a.lat == null || a.lng == null || b.lat == null || b.lng == null) return 0.0
        return Geo.meters(a.lat, a.lng, b.lat, b.lng)
    }
    private fun near(a: RouteStop, b: RouteStop, m: Double) = a.lat != null && b.lat != null && dist(a, b) <= m
}
