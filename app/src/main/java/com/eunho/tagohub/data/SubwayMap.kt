package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/** 노선도를 따로 그리는 권역 */
enum class SubwayRegion(val label: String) {
    Capital("수도권"), Busan("부산"), Daegu("대구"), Gwangju("광주"), Daejeon("대전");

    companion object {
        /** 위치로 권역 판별 (도시철도가 있는 권역의 대략적인 범위). 어느 권역도 아니면 null */
        fun of(lat: Double, lng: Double): SubwayRegion? = when {
            lat in 36.55..38.35 && lng in 126.3..127.95 -> Capital
            lat in 34.9..35.7 && lng in 128.7..129.5 -> Busan
            lat in 35.6..36.0 && lng in 128.3..128.85 -> Daegu
            lat in 34.95..35.3 && lng in 126.65..127.05 -> Gwangju
            lat in 36.2..36.5 && lng in 127.2..127.6 -> Daejeon
            else -> null
        }
    }
}

/** 한 노선의 역들과, 그 역들을 잇는 선(역과 역 사이) */
class LineGeo(val name: String, val stations: List<MetroStation>, val edges: List<Pair<MetroStation, MetroStation>>)

/**
 * 지하철 노선도 그리기용 자료. 표준데이터에는 역 순서가 없고(코레일 구간은 역번호도 뒤죽박죽)
 * 위치만 있어서, 노선마다 역들을 가장 짧게 잇는 나무(최소 신장 트리)로 역과 역 사이 선을 만든다.
 * 한 줄로 이어지는 노선은 실제 순서대로, 갈라지는 노선(1호선 등)은 갈라진 가지로 이어진다.
 */
object SubwayMap {
    /** 이보다 먼 두 역은 이어지지 않은 별개 구간으로 봄 (m) */
    const val MAX_LINK_M = 40_000.0

    fun lines(stations: List<MetroStation>, region: SubwayRegion): List<LineGeo> =
        stations.filter { SubwayRegion.of(it.lat, it.lng) == region }
            .groupBy { it.displayLine }
            .map { (name, list) ->
                // 같은 역이 한 노선에 두 번 실린 경우(용산 경부선/경원선) 하나로
                val unique = list.distinctBy { it.norm to (it.lat * 1000).toInt() to (it.lng * 1000).toInt() }
                LineGeo(name, unique, tree(unique))
            }
            .sortedWith(compareBy(com.eunho.tagohub.core.naturalOrder) { it.name })

    /** Prim 방식 최소 신장 트리. 너무 먼 연결은 끊음 */
    fun tree(stations: List<MetroStation>): List<Pair<MetroStation, MetroStation>> {
        if (stations.size < 2) return emptyList()
        val n = stations.size
        val done = BooleanArray(n)
        val best = DoubleArray(n) { Double.MAX_VALUE }
        val from = IntArray(n) { -1 }
        val out = ArrayList<Pair<MetroStation, MetroStation>>()
        var added = 0
        // 한 덩어리로 이어지지 않는 노선(떨어진 구간)도 모두 다루도록, 남은 역 중 하나에서 다시 시작
        while (added < n) {
            val start = (0 until n).first { !done[it] }
            best[start] = 0.0
            from[start] = -1
            while (true) {
                var u = -1
                for (i in 0 until n) if (!done[i] && best[i] < Double.MAX_VALUE && (u == -1 || best[i] < best[u])) u = i
                if (u == -1) break
                done[u] = true
                added++
                if (from[u] >= 0 && best[u] <= MAX_LINK_M) out += stations[from[u]] to stations[u]
                for (v in 0 until n) {
                    if (done[v]) continue
                    val d = Geo.meters(stations[u].lat, stations[u].lng, stations[v].lat, stations[v].lng)
                    if (d < best[v]) { best[v] = d; from[v] = u }
                }
            }
        }
        return out
    }
}
