package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 지자체 버스정보시스템 공식 배차시간표 (중계 서버 /w/bus/time/<지역>/<노선>, 웹 버전과 같은 자료).
 * 울산·천안·창원·대구·제주·진주·구미. 전주는 앱이 전주시 API 로 따로 받음 ([JeonjuTimetable]).
 * - 출발 시각 목록(weekday/sat/holi, 종점 출발 …Down) → 출발 정류장에서 이 정류장까지 걸리는 시간을 더함
 * - 대구·제주는 회차별 주요 정류장 통과 시각표(tables) → 앞뒤 주요 정류장 시각을 걸리는 시간 비율로 나눔
 * 천안·구미는 서버(클라우드)에서 접속이 막혀 배포 때 받아 둔 자료 조각(/data/times/<지역>.json)을 씀.
 */
object CityTimetable {
    /** 노선 ID 앞 글자 → 지역 (서버 src/timetable.js 와 같음) */
    private val AREAS = mapOf(
        "USB" to "ulsan", "CAB" to "cheonan", "CWB" to "changwon", "DGB" to "daegu",
        "JEB" to "jeju", "JJB" to "jinju", "GMB" to "gumi",
    )
    /** 노선 번호가 있어야 받을 수 있는 지역 */
    private val NEEDS_NO = setOf("daegu", "jeju")
    /** 서버가 못 받아 배포 때 받아 둔 자료 조각이 있는 지역 */
    private val STATIC = setOf("cheonan", "gumi")
    private const val TTL_MS = 60 * 60_000L

    data class Table(val days: Set<DayKind>, val points: List<String>, val trips: List<List<Int?>>)

    data class Deps(
        val starts: Map<DayKind, List<Int>>,
        val downStarts: Map<DayKind, List<Int>>,
        val tables: List<Table>,
        val source: String?,
    )

    private val client: OkHttpClient by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    private val cache = ConcurrentHashMap<String, Pair<Long, Deps?>>()
    private val staticCache = ConcurrentHashMap<String, Pair<Long, JSONObject?>>()

    fun areaOf(routeId: String): String? {
        if (!Regex("^[A-Z]{3}\\d{1,12}$").matches(routeId)) return null
        return AREAS[routeId.take(3)]
    }

    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { v -> v >= 0 } }.distinct().sorted()

    /** 서버 응답(JSON) → [Deps]. 없음({none:true})·형식이 다르면 null */
    fun parse(json: String): Deps? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val weekday = ints(o.optJSONArray("weekday"))
        if (weekday.isEmpty()) return null
        val sat = ints(o.optJSONArray("sat"))
        val holi = ints(o.optJSONArray("holi"))
        val starts = mapOf(DayKind.Weekday to weekday, DayKind.Saturday to sat.ifEmpty { weekday }, DayKind.Holiday to holi.ifEmpty { sat.ifEmpty { weekday } })
        val wd = ints(o.optJSONArray("weekdayDown"))
        val down = if (wd.isEmpty()) emptyMap() else mapOf(DayKind.Weekday to wd, DayKind.Saturday to ints(o.optJSONArray("satDown")), DayKind.Holiday to ints(o.optJSONArray("holiDown")))
        val tables = o.optJSONArray("tables")?.let { arr ->
            (0 until arr.length()).mapNotNull { k ->
                val t = arr.optJSONObject(k) ?: return@mapNotNull null
                val days = t.optJSONArray("days")?.let { d -> (0 until d.length()).mapNotNull { when (d.optString(it)) { "weekday" -> DayKind.Weekday; "sat" -> DayKind.Saturday; "holi" -> DayKind.Holiday; else -> null } }.toSet() }.orEmpty()
                val points = t.optJSONArray("points")?.let { p -> (0 until p.length()).map { p.optString(it) } }.orEmpty()
                val trips = t.optJSONArray("trips")?.let { tr ->
                    (0 until tr.length()).mapNotNull { i -> tr.optJSONArray(i)?.let { row -> (0 until row.length()).map { c -> if (row.isNull(c)) null else row.optInt(c, -1).takeIf { it >= 0 } } } }
                }.orEmpty()
                if (points.size < 2 || trips.isEmpty()) null else Table(days, points, trips)
            }
        }.orEmpty()
        return Deps(starts, down, tables, o.optString("source").ifBlank { null })
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
    }.getOrNull()

    /** 이 노선의 공식 시간표 (1시간 기억). 지역이 아니거나 서버를 안 쓰거나 못 받으면 null */
    suspend fun fetch(routeId: String, routeNo: String?, nowMs: Long = System.currentTimeMillis()): Deps? {
        val area = areaOf(routeId) ?: return null
        val proxy = DataProxy.current ?: return null
        cache[routeId]?.takeIf { nowMs - it.first < TTL_MS }?.let { return it.second }
        val num = routeId.drop(3)
        val base = proxy.url.trimEnd('/')
        val deps = withContext(Dispatchers.IO) {
            val q = if (area in NEEDS_NO && !routeNo.isNullOrBlank()) "?no=" + URLEncoder.encode(routeNo, "UTF-8") else ""
            if (area in NEEDS_NO && q.isEmpty()) return@withContext null
            get("$base/w/bus/time/$area/$num$q")?.let(::parse)
                ?: if (area in STATIC) {
                    val file = staticCache[area]?.takeIf { nowMs - it.first < TTL_MS }?.second
                        ?: get("$base/data/times/$area.json")?.let { runCatching { JSONObject(it) }.getOrNull() }.also { staticCache[area] = nowMs to it }
                    file?.optJSONObject(num)?.toString()?.let(::parse)
                } else null
        }
        cache[routeId] = nowMs to deps
        return deps
    }

    /** 시간표 주요 정류장 이름 ↔ 노선 정류장 이름 맞추기용: 괄호 안·띄어쓰기·종점 표시를 빼고 학교 이름은 줄임말로 */
    fun normName(s: String?): String = (s ?: "").replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), "").replace(Regex("[\\s.·]"), "")
        .replace("초등학교", "초교").replace("중학교", "중").replace("고등학교", "고").replace(Regex("(종점|기점|정류장|정류소|가상)$"), "")

    fun sameName(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val n = minOf(a.length, b.length)
        return n >= 2 && (a.startsWith(b) || b.startsWith(a) || (n >= 3 && (a.contains(b) || b.contains(a))))
    }

    /**
     * 주요 정류장 통과 시각표 → 노선 정류장 목록의 i 번째 정류장 도착 시각들.
     * 주요 정류장을 노선 순서대로 이름으로 맞추고, 앞뒤 주요 정류장 시각을 걸리는 시간 비율로 나눔.
     * 방향이 반대인 표는 맞는 정류장이 2개가 안 돼 빈 목록. 앞뒤 한쪽 시각이 없는 회차(짧은 운행)는 뺌
     */
    fun timesAtStop(table: Table, stops: List<RouteStop>, i: Int, minutesBetween: (fromOrd: Int, toOrd: Int) -> Double): List<Int> {
        if (i !in stops.indices) return emptyList()
        var from = 0
        val at = table.points.map { p ->
            val n = normName(p)
            var k = -1
            for (j in from until stops.size) if (sameName(normName(stops[j].name), n)) { k = j; break }
            if (k >= 0) from = k + 1
            k
        }
        if (at.count { it >= 0 } < 2) return emptyList()
        val out = sortedSetOf<Int>()
        for (trip in table.trips) {
            var a: Pair<Int, Int>? = null
            var b: Pair<Int, Int>? = null
            trip.forEachIndexed { c, m ->
                val k = at.getOrElse(c) { -1 }
                if (m == null || k < 0) return@forEachIndexed
                if (k <= i && (a == null || k >= a!!.first)) a = k to m
                if (k >= i && (b == null || k < b!!.first)) b = k to m
            }
            val aa = a ?: continue
            val bb = b ?: continue
            if (aa.first == bb.first) { out += aa.second; continue }
            val span = minutesBetween(stops[aa.first].ord, stops[bb.first].ord)
            val part = minutesBetween(stops[aa.first].ord, stops[i].ord)
            out += Math.round(aa.second + (bb.second - aa.second) * (if (span > 0) part / span else 0.0)).toInt()
        }
        return out.toList()
    }

    /**
     * 이 요일, 정류장(순번 seqs 중 하나) 도착 시각들.
     * 주요 정류장 시각표가 있으면 그것으로, 없으면 기점 출발 시각 + 걸리는 시간 (종점 출발은 회차점 뒤 정류장에만).
     */
    fun stopTimes(deps: Deps, day: DayKind, stops: List<RouteStop>, seqs: List<Int>, endName: String?, minutesBetween: (fromOrd: Int, toOrd: Int) -> Double): List<Int> {
        if (stops.isEmpty() || seqs.isEmpty()) return emptyList()
        val tables = deps.tables.filter { day in it.days }
        if (tables.isNotEmpty()) {
            val out = sortedSetOf<Int>()
            for (s in seqs) {
                val i = stops.indexOfFirst { it.ord == s }
                tables.forEach { out += timesAtStop(it, stops, i, minutesBetween) }
            }
            if (out.isNotEmpty()) return out.toList()
        }
        val first = stops.first().ord
        val out = sortedSetOf<Int>()
        val seq = seqs.min()
        val off = Math.round(minutesBetween(first, seq)).toInt()
        deps.starts[day].orEmpty().forEach { out += it + off }
        val down = deps.downStarts[day].orEmpty()
        if (down.isNotEmpty() && !endName.isNullOrBlank()) {
            val end = normName(endName)
            val turn = stops.drop(1).firstOrNull { sameName(normName(it.name), end) }?.ord
            val after = seqs.filter { turn != null && it >= turn }.minOrNull()
            if (turn != null && after != null) {
                val dOff = Math.round(minutesBetween(turn, after)).toInt()
                down.forEach { out += it + dOff }
            }
        }
        return out.toList()
    }
}
