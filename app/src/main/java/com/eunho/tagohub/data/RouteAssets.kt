package com.eunho.tagohub.data

import android.content.Context
import kotlin.math.roundToInt

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

    /**
     * 줄: 노선ID \t 노드ID:x:y,…
     * 앱에 들어 있는 기본 배치와 관리자 페이지가 저장한 배치(/w/layouts.tsv)가 같은 형식이라 함께 읽는다.
     * "SEB" 접두사·앞뒤 공백·윈도 줄바꿈이 섞여도 같은 노선·정류장으로 맞춘다.
     */
    fun parseOfficial(text: String): Map<String, Map<String, Pair<Int, Int>>> {
        val out = HashMap<String, Map<String, Pair<Int, Int>>>()
        for (line in text.lineSequence()) {
            val c = line.trim().split('\t')
            if (c.size < 2) continue
            val route = c[0].trim().removePrefix("SEB")
            if (route.isEmpty()) continue
            val m = LinkedHashMap<String, Pair<Int, Int>>()
            for (e in c[1].split(',')) {
                val p = e.split(':')
                if (p.size != 3) continue
                val id = p[0].trim().removePrefix("SEB")
                val x = p[1].trim().toIntOrNull() ?: continue
                val y = p[2].trim().toIntOrNull() ?: continue
                if (id.isEmpty()) continue
                m[id] = x to y
            }
            if (m.isNotEmpty()) out[route] = m
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
    /**
     * [officialLayouts] 에 이 노선의 배치가 있으면 그 좌표를 칸 단위로 맞춰 쓴다.
     * [acceptPartial] 이면 일부만 배치한 것도 그대로 쓴다 (관리자 페이지에서 저장한 배치).
     * 일부만 있어도 칸 수를 정류장 수와 맞춰 돌려주므로 라벨이 밀리지 않는다.
     */
    fun layout(
        routeId: String,
        nodeIds: List<String>,
        officialLayouts: Map<String, Map<String, Pair<Int, Int>>>,
        perRow: Int = 4,
        acceptPartial: Boolean = false,
    ): List<Cell> {
        val off = officialLayouts[routeId.removePrefix("SEB")]
        val ids = nodeIds.map { it.removePrefix("SEB") }
        val covered = if (off == null) 0 else ids.count { it in off }
        if (off != null && ids.isNotEmpty() && covered > 0 && (acceptPartial || covered >= ids.size * 0.8)) {
            val have = nodeIds.mapIndexedNotNull { i, id -> off[ids[i]]?.let { Cell(id, it.first.toFloat(), it.second.toFloat()) } }
            val minX = have.minOf { it.x }
            val minY = have.minOf { it.y }
            // 가로가 perRow-1 칸이 되게 같은 비율로 줄임 (세로도 같은 비율)
            val k = (perRow - 1) / (have.maxOf { it.x } - minX).coerceAtLeast(1f)
            val scaled = have.map { Cell(it.nodeId, (it.x - minX) * k, (it.y - minY) * k) }
            return if (scaled.size == nodeIds.size) scaled else fillGaps(nodeIds, scaled, perRow)
        }
        return snake(nodeIds, perRow)
    }

    /** 배치가 없는 정류장을 빈 칸에 채워 넣는다 (칸 번호와 정류장 순서를 맞추기 위해) */
    private fun fillGaps(nodeIds: List<String>, cells: List<Cell>, perRow: Int): List<Cell> {
        val byId = HashMap<String, Cell>(cells.size * 2)
        for (c in cells) byId[c.nodeId] = c
        val used = HashSet<Long>()
        for (c in cells) used.add(cellKey(c.x.roundToInt(), c.y.roundToInt()))
        val out = ArrayList<Cell>(nodeIds.size)
        var x = 0
        var y = 0
        for (id in nodeIds) {
            val c = byId[id]
            if (c != null) { out.add(c); continue }
            while (used.contains(cellKey(x, y))) {
                x++
                if (x >= perRow) { x = 0; y++ }
            }
            used.add(cellKey(x, y))
            out.add(Cell(id, x.toFloat(), y.toFloat()))
        }
        return out
    }

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    fun official(context: Context): Map<String, Map<String, Pair<Int, Int>>> =
        official ?: synchronized(this) {
            official ?: runCatching { parseOfficial(context.assets.open(FILE).bufferedReader().readText()) }.getOrDefault(emptyMap()).also { official = it }
        }
}
