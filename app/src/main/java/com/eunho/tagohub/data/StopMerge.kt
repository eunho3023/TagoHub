package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/** 같은 자리에 시군마다 따로 등록된 정류장을 하나로 묶은 것 */
data class MergedStop(val main: BusStop, val others: List<BusStop>) {
    val all: List<BusStop> get() = listOf(main) + others
    /** "30690 · 3110313" 처럼 번호를 모두 */
    val numbers: String get() = all.mapNotNull { it.nodeNo }.distinct().joinToString(" · ")
}

/**
 * 시군 경계 정류장 합치기.
 * 규칙(정류장 패널과 같음): 다른 시군 정류장 중 시군마다 가장 가까운 하나, 30m 안.
 * 같은 이름이라도 20~30m 떨어진 건 대개 길 건너편이라, 더 가까운 같은 시군 정류장이 있으면 그쪽만 묶인다.
 * 대표 정류장은 preferCity(지금 보고 있는 지역)가 있으면 그 시군 것, 없으면 번호가 있는 것.
 */
object StopMerge {
    const val RADIUS_M = 30.0

    /**
     * 같은 자리의 다른 시군 정류장 고르기 (주변 정류장 목록에서): 시군마다 가장 가까운 하나, 30m 안.
     * 전국 정류장 파일에 없는 정류장(예: 전주시 안의 전북 통합 정류장 TSB…)도 TAGO 주변 정류장으로 찾기 위해.
     */
    fun companions(stop: BusStop, nearby: List<BusStop>): List<BusStop> {
        val lat = stop.lat ?: return emptyList()
        val lng = stop.lng ?: return emptyList()
        return nearby.asSequence()
            .filter { it.cityCode != stop.cityCode && it.nodeId != stop.nodeId && it.lat != null && it.lng != null }
            .map { it to Geo.meters(lat, lng, it.lat!!, it.lng!!) }
            .filter { it.second <= RADIUS_M }
            .groupBy { it.first.cityCode }
            .map { (_, l) -> l.minBy { it.second }.first }
            .toList()
    }

    fun merge(stops: List<BusStop>, preferCity: String? = null): List<MergedStop> {
        val located = stops.filter { it.lat != null && it.lng != null }
        val byLat = located.sortedBy { it.lat }
        val dLat = RADIUS_M / 111_000.0
        // 각 정류장 → 다른 시군별 가장 가까운 후보
        val partner = HashMap<String, MutableMap<String, Pair<BusStop, Double>>>()
        for (i in byLat.indices) {
            val a = byLat[i]
            var j = i + 1
            while (j < byLat.size && byLat[j].lat!! - a.lat!! <= dLat) {
                val b = byLat[j]
                if (a.cityCode != b.cityCode) {
                    val d = Geo.meters(a.lat, a.lng!!, b.lat!!, b.lng!!)
                    if (d <= RADIUS_M) {
                        listOf(a to b, b to a).forEach { (x, y) ->
                            val m = partner.getOrPut(x.nodeId) { HashMap() }
                            val cur = m[y.cityCode]
                            if (cur == null || d < cur.second) m[y.cityCode] = y to d
                        }
                    }
                }
                j++
            }
        }
        // 서로가 서로의 가장 가까운 상대일 때만 묶는다 (한쪽만 가까운 길 건너편은 제외)
        val used = HashSet<String>()
        val out = ArrayList<MergedStop>()
        for (s in located) {
            if (s.nodeId in used) continue
            val group = linkedMapOf(s.nodeId to s)
            partner[s.nodeId]?.values?.forEach { (o, _) ->
                val back = partner[o.nodeId]?.get(s.cityCode)?.first
                if (back?.nodeId == s.nodeId && o.nodeId !in used) group[o.nodeId] = o
            }
            group.keys.forEach { used += it }
            val members = group.values.toList()
            val main = members.firstOrNull { it.cityCode == preferCity }
                ?: members.firstOrNull { it.nodeNo != null }
                ?: members.first()
            out += MergedStop(main, members.filter { it.nodeId != main.nodeId })
        }
        return out + stops.filter { it.lat == null || it.lng == null }.map { MergedStop(it, emptyList()) }
    }
}
