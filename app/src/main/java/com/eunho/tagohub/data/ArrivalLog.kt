package com.eunho.tagohub.data

import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 버스가 정류장에 처음 보인 시각 (노선·차량별). 정류장 순번만 주는 API 의 버스가 그 정류장에 얼마나 머물렀는지 알아서
 * 떠났는지 어림하는 데 쓴다 ([RouteLive.timedAlong]). 노선 패널과 정류장 화면의 위치 어림이 같은 기록을 본다.
 */
object BusSeen {
    private class Seen(val ord: Int, val sinceMs: Long, val lastMs: Long)

    private val seen = ConcurrentHashMap<String, Seen>()

    /** 이만큼 소식이 없던 차량은 다시 보일 때 처음 본 것으로 (언제 왔는지 모름) */
    private const val FORGET_MS = 5 * 60_000L

    fun update(routeId: String, buses: List<BusLocation>, nowMs: Long = System.currentTimeMillis()) {
        for (b in buses) {
            val ord = b.ord ?: continue
            val key = routeId + "|" + (b.vehicleNo ?: continue)
            val old = seen[key]
            seen[key] = if (old != null && old.ord == ord && nowMs - old.lastMs < FORGET_MS) Seen(ord, old.sinceMs, nowMs) else Seen(ord, nowMs, nowMs)
        }
        if (seen.size > 2_000) seen.entries.removeIf { nowMs - it.value.lastMs > FORGET_MS }
    }

    /** 차량이 지금 정류장 순번 [ord] 에 처음 보인 시각. 모르면 null */
    fun since(routeId: String, vehicleNo: String?, ord: Int?): Long? {
        val s = seen[routeId + "|" + (vehicleNo ?: return null)] ?: return null
        return s.sinceMs.takeIf { ord != null && s.ord == ord }
    }

    internal fun reset() = seen.clear()
}

/**
 * 지난 운행 기록으로 만드는 요일별 예상 시간표.
 * 앱이 버스 위치를 받을 때마다(노선 패널, 정류장 화면의 위치 어림) 버스가 정류장을 옮겨 간 것을 보면,
 * 그 사이 지난 정류장마다 "몇 시 몇 분에 도착했다"를 휴대폰에 저장한다. 중계 서버를 쓰면 노선·정류장·시각만
 * 서버에도 모아서 다른 사용자들의 기록과 함께 쓴다 ([SharedArrivalLog]).
 * 같은 요일의 기록을 시각별로 묶어 예상 시각·가장 빠른·가장 늦은 시각을 보여 준다.
 */
object ArrivalLog {
    /** 한 번에 이보다 많이 건너뛴 이동은 기록하지 않음 (오래 못 받은 사이 생긴 큰 이동은 시각이 부정확) */
    const val MAX_STEP_STOPS = 6
    /** 직전 위치와 이만큼 넘게 떨어진 응답은 기록하지 않음 */
    const val MAX_GAP_MS = 4 * 60_000L
    /** 이보다 오래된 기록은 버림 */
    const val KEEP_DAYS = 42L
    /** 같은 운행으로 묶는 시각 차이(분) */
    const val CLUSTER_MIN = 5
    private const val MAX_LINES = 30_000

    @Volatile private var dir: File? = null
    @Volatile var zone: ZoneId = ZoneId.of("Asia/Seoul")

    private class Snap(val atMs: Long, val ords: Map<String, Int>)

    private val last = ConcurrentHashMap<String, Snap>()

    fun init(root: File) {
        dir = File(root, "arrival_log").apply { mkdirs() }
    }

    /** 정류장에 도착한 한 번 */
    data class Hit(val nodeId: String, val epochSec: Long)

    /**
     * 직전 응답([prevOrds], [prevAtMs])에서 이번 응답([buses], [nowMs])까지 차량이 정류장 순번 a → b 로 옮겨 갔으면,
     * a 다음부터 b 까지의 정류장에 도착한 시각을 두 응답 사이에서 순번 비율로 나눠 매긴다.
     */
    fun hits(prevOrds: Map<String, Int>, prevAtMs: Long, buses: List<BusLocation>, nowMs: Long, stops: List<RouteStop>): List<Hit> {
        if (nowMs - prevAtMs > MAX_GAP_MS || nowMs <= prevAtMs) return emptyList()
        val byOrd = stops.associateBy { it.ord }
        val out = ArrayList<Hit>()
        for (b in buses) {
            val to = b.ord ?: continue
            val from = prevOrds[b.vehicleNo ?: continue] ?: continue
            val step = to - from
            if (step <= 0 || step > MAX_STEP_STOPS) continue
            for (k in 1..step) {
                val s = byOrd[from + k] ?: continue
                val at = prevAtMs + (nowMs - prevAtMs) * k / step
                out += Hit(s.nodeId, at / 1000)
            }
        }
        return out
    }

    /** 이번에 받은 버스 위치를 보고, 정류장을 옮겨 간 버스가 있으면 기록 */
    fun observe(routeId: String, buses: List<BusLocation>, stops: List<RouteStop>, nowMs: Long = System.currentTimeMillis()) {
        val ords = buses.mapNotNull { b -> b.vehicleNo?.takeIf { !it.startsWith("#") }?.let { v -> b.ord?.let { v to it } } }.toMap()
        val prev = last.put(routeId, Snap(nowMs, ords)) ?: return
        val found = hits(prev.ords, prev.atMs, buses, nowMs, stops)
        if (found.isNotEmpty()) {
            append(routeId, found)
            onHits?.invoke(routeId, found)
        }
    }

    /** 새 기록을 남길 때마다 부름 (앱은 [SharedArrivalLog.enqueue] 로 서버에 모음) */
    @Volatile var onHits: ((String, List<Hit>) -> Unit)? = null

    private fun file(routeId: String): File? = dir?.let { File(it, routeId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv") }

    @Synchronized
    private fun append(routeId: String, hits: List<Hit>) {
        val f = file(routeId) ?: return
        runCatching {
            f.appendText(hits.joinToString("") { "${it.nodeId}\t${it.epochSec}\n" })
            // 가끔 오래된 기록을 지우고 크기를 제한
            if (f.length() > MAX_LINES * 24L) {
                val keep = read(routeId).takeLast(MAX_LINES / 2)
                f.writeText(keep.joinToString("") { "${it.nodeId}\t${it.epochSec}\n" })
            }
        }
    }

    @Synchronized
    fun read(routeId: String, nowMs: Long = System.currentTimeMillis()): List<Hit> {
        val f = file(routeId)?.takeIf { it.exists() } ?: return emptyList()
        val oldest = nowMs / 1000 - KEEP_DAYS * 86_400
        return runCatching {
            f.readLines().mapNotNull { line ->
                val t = line.split('\t')
                val sec = t.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                if (sec < oldest) null else Hit(t[0], sec)
            }
        }.getOrDefault(emptyList())
    }

    /** 시간표 한 줄: 예상(가운데 값)·가장 빠른·가장 늦은 시각(하루 중 분)과 기록된 날 수 */
    data class Slot(val expected: Int, val earliest: Int, val latest: Int, val days: Int)

    /** 이 요일에 기록이 있는 날 수와 시간표 */
    data class Table(val days: Int, val slots: List<Slot>)

    /**
     * [nodeId] 정류장의 [day] 요일 기록을 시각별로 묶는다. 같은 날의 같은 운행이 두 번 기록되면(정류장이 노선에 두 번) 한 번으로.
     * 묶음 안 시각이 [CLUSTER_MIN] 분 넘게 벌어지면 다음 운행으로 본다.
     */
    fun table(hits: List<Hit>, nodeId: String, day: DayOfWeek, zone: ZoneId = this.zone): Table {
        val times = hits.filter { it.nodeId == nodeId }.map { Instant.ofEpochSecond(it.epochSec).atZone(zone) }
            .filter { it.dayOfWeek == day }
        if (times.isEmpty()) return Table(0, emptyList())
        val rows = times.map { it.toLocalDate() to (it.hour * 60 + it.minute) }.distinct().sortedBy { it.second }
        val groups = ArrayList<MutableList<Pair<java.time.LocalDate, Int>>>()
        for (r in rows) {
            val g = groups.lastOrNull()
            if (g != null && r.second - g.first().second <= CLUSTER_MIN && g.none { it.first == r.first }) g += r
            else groups += mutableListOf(r)
        }
        val slots = groups.map { g ->
            val m = g.map { it.second }.sorted()
            Slot(m[m.size / 2], m.first(), m.last(), g.map { it.first }.distinct().size)
        }
        return Table(times.map { it.toLocalDate() }.distinct().size, slots)
    }
}
