package com.eunho.tagohub.data

import android.content.Context

/** 앱 안 서울 노선 모양 (assets/bus_route_shapes.tsv, 서울시 TBIS 경유노드). 서울 API 를 부르지 않고 바로 그린다 */
object RouteShapes {
    private const val FILE = "bus_route_shapes.tsv"
    @Volatile private var cache: Map<String, List<LatLng>>? = null

    /** 줄: 노선ID \t 첫 점(위도,경도) \t 이어지는 점들의 변화량(1e-5도, 위도 경도 쌍) */
    fun parse(text: String): Map<String, List<LatLng>> {
        val out = HashMap<String, List<LatLng>>()
        for (line in text.lineSequence()) {
            val c = line.split('\t')
            if (c.size < 3) continue
            val first = c[1].split(',').mapNotNull { it.toDoubleOrNull() }
            val d = c[2].split(',').mapNotNull { it.toIntOrNull() }
            if (first.size != 2) continue
            var la = Math.round(first[0] * 1e5)
            var ln = Math.round(first[1] * 1e5)
            val pts = ArrayList<LatLng>(d.size / 2 + 1)
            pts.add(LatLng(la / 1e5, ln / 1e5))
            var i = 0
            while (i + 1 < d.size) {
                la += d[i]; ln += d[i + 1]
                pts.add(LatLng(la / 1e5, ln / 1e5))
                i += 2
            }
            if (pts.size >= 2) out[c[0]] = pts
        }
        return out
    }

    /** 앱 노선 id(SEB+노선ID) → 모양. 없으면 null */
    fun seoul(context: Context, routeId: String): List<LatLng>? {
        val all = cache ?: synchronized(this) {
            cache ?: runCatching { parse(context.assets.open(FILE).bufferedReader().readText()) }.getOrDefault(emptyMap()).also { cache = it }
        }
        return all[routeId.removePrefix("SEB")]
    }
}

/** 노선 배치도: 정류장을 칸에 놓는 위치. 서울시 배치 좌표가 있는 노선은 그대로, 없으면 지그재그로 자동 배치 */
object RouteDiagram {
    data class Cell(val nodeId: String, val x: Float, val y: Float)

    private const val FILE = "bus_route_layouts.tsv"
    @Volatile private var official: Map<String, Map<String, Pair<Int, Int>>>? = null

    /** 줄: 노선ID \t 노드ID:x:y,… */
    fun parseOfficial(text: String): Map<String, Map<String, Pair<Int, Int>>> {
        val out = HashMap<String, Map<String, Pair<Int, Int>>>()
        for (line in text.lineSequence()) {
            val c = line.split('\t')
            if (c.size < 2) continue
            val m = LinkedHashMap<String, Pair<Int, Int>>()
            for (e in c[1].split(',')) {
                val p = e.split(':')
                if (p.size != 3) continue
                val x = p[1].toIntOrNull() ?: continue
                val y = p[2].toIntOrNull() ?: continue
                m[p[0]] = x to y
            }
            if (m.isNotEmpty()) out[c[0]] = m
        }
        return out
    }

    /** 한 줄에 [perRow] 정류장씩, 줄마다 방향을 바꿔 이어 붙임(ㄹ자). 좌표는 칸 번호(0부터) */
    fun snake(nodeIds: List<String>, perRow: Int = 4): List<Cell> =
        nodeIds.mapIndexed { i, id ->
            val row = i / perRow
            val col = i % perRow
            Cell(id, (if (row % 2 == 0) col else perRow - 1 - col).toFloat(), row.toFloat())
        }

    /** 서울시 배치가 정류장 80% 이상을 덮으면 그것을 (0~1 칸 단위로 맞춰) 쓰고, 아니면 지그재그 */
    fun layout(routeId: String, nodeIds: List<String>, officialLayouts: Map<String, Map<String, Pair<Int, Int>>>, perRow: Int = 4): List<Cell> {
        val off = officialLayouts[routeId.removePrefix("SEB")]
        val ids = nodeIds.map { it.removePrefix("SEB") }
        if (off != null && ids.isNotEmpty() && ids.count { it in off } >= ids.size * 0.8) {
            val have = nodeIds.mapIndexedNotNull { i, id -> off[ids[i]]?.let { Cell(id, it.first.toFloat(), it.second.toFloat()) } }
            val minX = have.minOf { it.x }
            val minY = have.minOf { it.y }
            // 가로가 perRow-1 칸이 되게 같은 비율로 줄임 (세로도 같은 비율)
            val k = (perRow - 1) / (have.maxOf { it.x } - minX).coerceAtLeast(1f)
            return have.map { Cell(it.nodeId, (it.x - minX) * k, (it.y - minY) * k) }
        }
        return snake(nodeIds, perRow)
    }

    fun official(context: Context): Map<String, Map<String, Pair<Int, Int>>> =
        official ?: synchronized(this) {
            official ?: runCatching { parseOfficial(context.assets.open(FILE).bufferedReader().readText()) }.getOrDefault(emptyMap()).also { official = it }
        }
}
