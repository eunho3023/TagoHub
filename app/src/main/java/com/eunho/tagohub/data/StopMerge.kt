package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/** 같은 자리에 시군마다 따로 등록된 정류장을 하나로 묶은 것 */
data class MergedStop(val main: BusStop, val others: List<BusStop>) {
    val all: List<BusStop> get() = listOf(main) + others
    /** "30690 · 3110313" 처럼 번호를 모두 */
    val numbers: String get() = all.mapNotNull { it.nodeNo }.distinct().joinToString(" · ")
}

/**
 * 같은 자리 정류장 묶기 (예: 전주 시내버스 정류장과 그 자리에 서는 진안·완주 버스 정류장).
 * 웹(site/js/busdata.js spotGroups)·정류장 짝 표(pipeline/build_stop_companions.js)와 같은 규칙:
 * - 다른 시군(또는 다른 기관) 정류장이 [RADIUS_M] 안이거나, 이름이 같고 [SAME_NAME_M] 안이면 같은 자리 후보
 * - 가까운 짝부터 묶고, 한 묶음에는 시군마다 하나씩만 (길 건너편 같은 이름 정류장은 그쪽 짝과 따로 묶임)
 * 대표 정류장은 preferCity(지금 보고 있는 지역)가 있으면 그 시군 것, 없으면 번호가 있는 것.
 */
object StopMerge {
    const val RADIUS_M = 30.0
    const val SAME_NAME_M = 60.0

    /**
     * 관리자가 손으로 고친 묶음 (지도 고치기 → 묶기, 서버 /w/edits 의 group).
     * joins: 정류장 id → 함께 묶을 정류장 id 들. 빈 목록이면 그 정류장은 자동으로 묶지 않음(따로 떼기).
     */
    data class Manual(val joins: Map<String, List<String>> = emptyMap()) {
        private val keyed: Map<String, List<String>> = joins.entries.associate { (k, v) -> TagoIds.legacy(k) to v.map { TagoIds.legacy(it) } }
        val split: Set<String> = keyed.filterValues { it.isEmpty() }.keys
        /**
         * 손으로 묶은 짝 (id 쌍). 따로 뗀 정류장이 낀 짝은 뺌: A 에서 B 를 묶은 뒤 B 를 골라 "따로 떼기"하면
         * A 쪽 기록 때문에 계속 묶여 있었음 (웹 spotGroups 와 같게)
         */
        val pairs: List<Pair<String, String>> = keyed.flatMap { (k, v) -> v.map { k to it } }.filter { (a, b) -> a !in split && b !in split }
        /** 다른 정류장 기록에 [id] 를 함께 묶을 정류장으로 적어 둔 정류장 id 들 (원래 id 그대로) */
        fun listedBy(id: String): List<String> {
            val k = TagoIds.legacy(id)
            return joins.filter { (o, v) -> TagoIds.legacy(o) != k && v.any { TagoIds.legacy(it) == k } }.keys.toList()
        }
        /** 이 정류장과 손으로 묶은 정류장 id 들 (어느 쪽에 적혀 있든, 이어진 것까지: A-B, B-C 면 A 는 B·C) */
        fun joinedWith(id: String): Set<String> {
            val k = TagoIds.legacy(id)
            val out = LinkedHashSet<String>()
            val todo = ArrayDeque(listOf(k))
            while (todo.isNotEmpty()) {
                val x = todo.removeFirst()
                pairs.forEach { (a, b) ->
                    if (a == x && out.add(b)) todo += b
                    if (b == x && out.add(a)) todo += a
                }
            }
            return out - k
        }
    }
    @Volatile var manual = Manual()

    private fun key(id: String) = TagoIds.legacy(id)

    /** 이름 비교용: 공백·문장부호·괄호를 뺌 ("부산대학병원(토성역)" = "부산대학병원.토성역") */
    fun nameKey(name: String): String = name.replace(Regex("[\\s.·,()\\[\\]\\-_]"), "")

    /** 같은 자리로 볼 수 있는 거리 한계 (두 정류장 이름에 따라) */
    private fun limit(a: BusStop, b: BusStop): Double {
        val ka = nameKey(a.name)
        return if (ka.isNotEmpty() && ka == nameKey(b.name)) SAME_NAME_M else RADIUS_M
    }

    /** 같은 자리 묶음 (좌표 있는 정류장만). 묶음 안 순서는 들어온 순서 */
    fun groups(stops: List<BusStop>): List<List<BusStop>> {
        val located = stops.filter { it.lat != null && it.lng != null }.distinctBy { it.nodeId }
        val byLat = located.withIndex().sortedBy { it.value.lat }
        val dLat = SAME_NAME_M / 111_000.0
        data class Edge(val a: Int, val b: Int, val d: Double)
        val edges = ArrayList<Edge>()
        for (i in byLat.indices) {
            val (ia, a) = byLat[i]
            var j = i + 1
            while (j < byLat.size && byLat[j].value.lat!! - a.lat!! <= dLat) {
                val (ib, b) = byLat[j]
                if (a.cityCode != b.cityCode) {
                    val d = Geo.meters(a.lat!!, a.lng!!, b.lat!!, b.lng!!)
                    if (d <= limit(a, b)) edges += Edge(ia, ib, d)
                }
                j++
            }
        }
        val parent = IntArray(located.size) { it }
        val cities = Array(located.size) { mutableSetOf(located[it].cityCode) }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val n = parent[y]; parent[y] = r; y = n }; return r }
        fun join(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra == rb) return
            val (keep, gone) = if (ra < rb) ra to rb else rb to ra
            parent[gone] = keep
            cities[keep].addAll(cities[gone])
        }
        // 1) 손으로 묶은 것 먼저 (거리·시군 상관없이)
        val m = manual
        val at = HashMap<String, Int>().apply { located.forEachIndexed { i, s -> put(key(s.nodeId), i) } }
        m.pairs.forEach { (a, b) -> val ia = at[a]; val ib = at[b]; if (ia != null && ib != null) join(ia, ib) }
        // 2) 가까운 짝부터 묶음을 합침. 합칠 두 묶음에 같은 시군이 있으면 안 합침. 따로 떼어 둔 정류장은 빼고
        val apart = located.indices.filter { key(located[it].nodeId) in m.split }.toSet()
        edges.sortWith(compareBy<Edge> { it.d }.thenBy { minOf(it.a, it.b) }.thenBy { maxOf(it.a, it.b) })
        for (e in edges) {
            if (e.a in apart || e.b in apart) continue
            val ra = root(e.a)
            val rb = root(e.b)
            if (ra == rb || cities[ra].any { it in cities[rb] }) continue
            join(e.a, e.b)
        }
        val out = LinkedHashMap<Int, MutableList<BusStop>>()
        located.indices.forEach { out.getOrPut(root(it)) { ArrayList() } += located[it] }
        return out.values.toList()
    }

    /**
     * 같은 자리의 다른 시군 정류장 (주변 정류장 목록에서): [groups] 로 묶었을 때 [stop] 과 같은 묶음.
     * 전국 정류장 파일에 없는 정류장(예: 전주시 안의 전북 통합 정류장 TSB…)도 TAGO 주변 정류장으로 찾기 위해.
     */
    fun companions(stop: BusStop, nearby: List<BusStop>): List<BusStop> {
        if (stop.lat == null || stop.lng == null) return emptyList()
        val g = groups(listOf(stop) + nearby.filter { it.nodeId != stop.nodeId }).firstOrNull { l -> l.any { it.nodeId == stop.nodeId } }
        return g.orEmpty().filter { it.nodeId != stop.nodeId }
    }

    fun merge(stops: List<BusStop>, preferCity: String? = null): List<MergedStop> {
        val out = groups(stops).map { members ->
            val main = members.firstOrNull { it.cityCode == preferCity }
                ?: members.firstOrNull { it.nodeNo != null }
                ?: members.first()
            MergedStop(main, members.filter { it.nodeId != main.nodeId })
        }
        return out + stops.filter { it.lat == null || it.lng == null }.map { MergedStop(it, emptyList()) }
    }
}
