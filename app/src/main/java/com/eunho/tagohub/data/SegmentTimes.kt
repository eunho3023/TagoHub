package com.eunho.tagohub.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 노선의 정류장 구간별 실제 통행 시간(초)을 앱이 본 버스 위치에서 모은다.
 *
 * 같은 차량이 한 정류장에서 다음 정류장으로 넘어가는 데 걸린 시간을 기록해 두면,
 * 거리 × 평균속도로 어림하는 것보다 정체·신호 대기를 반영한 도착 예정을 만들 수 있다.
 * 자료는 `filesDir/seg_times/<노선>.tsv` 에 `fromOrd \t toOrd \t 초 \t epoch초` 로 쌓고 14일치만 남긴다.
 */
object SegmentTimes {
    private const val KEEP_DAYS = 14L
    private const val MAX_LINES = 20_000
    private const val MIN_SEC = 10
    private const val MAX_SEC = 1_800
    /** 한 번에 이보다 많은 정류장을 건너뛰면 데이터가 빠진 것으로 보고 버린다 */
    private const val MAX_JUMP = 3

    @Volatile private var dir: File? = null
    private class At(val ord: Int, val atMs: Long)
    private val last = ConcurrentHashMap<String, At>()

    /** 최근 읽은 표본. 시간표는 한 번 계산할 때 같은 노선 구간을 수백 번 묻는다 (파일을 그때마다 읽으면 느림) */
    private class Cached(val atMs: Long, val samples: List<Sample>)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 30_000L
    private const val MAX_CACHED_ROUTES = 200

    fun init(root: File) {
        dir = File(root, "seg_times").apply { mkdirs() }
        // 저장 위치가 바뀌면 이전 위치에서 읽은 표본·차량 기록은 버린다
        cache.clear()
        last.clear()
    }

    /** 버스 위치를 받을 때마다 호출: 같은 차량이 다음 정류장으로 넘어간 것을 보면 구간 시간을 남긴다 */
    fun observe(routeId: String, buses: List<BusLocation>, nowMs: Long = System.currentTimeMillis()) {
        if (dir == null) return
        for (b in buses) {
            val v = b.vehicleNo?.takeIf { !it.startsWith("#") } ?: continue
            val ord = b.ord ?: continue
            val prev = last.put(routeId + "|" + v, At(ord, nowMs)) ?: continue
            if (ord <= prev.ord || ord - prev.ord > MAX_JUMP) continue
            val sec = ((nowMs - prev.atMs) / 1000).toInt()
            if (sec !in MIN_SEC..MAX_SEC) continue
            append(routeId, prev.ord, ord, sec, nowMs)
        }
        if (last.size > 3_000) last.entries.removeIf { nowMs - it.value.atMs > 3_600_000L }
    }

    private fun file(routeId: String): File? =
        dir?.let { File(it, routeId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv") }

    @Synchronized
    private fun append(routeId: String, from: Int, to: Int, sec: Int, nowMs: Long) {
        val f = file(routeId) ?: return
        runCatching {
            f.appendText("$from\t$to\t$sec\t${nowMs / 1000}\n")
            cache.remove(routeId)
            if (f.length() > MAX_LINES * 16L) {
                val keep = read(routeId, nowMs).takeLast(MAX_LINES / 2)
                f.writeText(keep.joinToString("") { "${it.from}\t${it.to}\t${it.sec}\t${it.epochSec}\n" })
            }
        }
    }

    class Sample(val from: Int, val to: Int, val sec: Int, val epochSec: Long)

    @Synchronized
    fun read(routeId: String, nowMs: Long = System.currentTimeMillis()): List<Sample> {
        val f = file(routeId)?.takeIf { it.exists() } ?: return emptyList()
        val oldest = nowMs / 1000 - KEEP_DAYS * 86_400
        return runCatching {
            f.readLines().mapNotNull { line ->
                val t = line.split('\t')
                if (t.size < 4) return@mapNotNull null
                val from = t[0].toIntOrNull() ?: return@mapNotNull null
                val to = t[1].toIntOrNull() ?: return@mapNotNull null
                val sec = t[2].toIntOrNull() ?: return@mapNotNull null
                val at = t[3].toLongOrNull() ?: return@mapNotNull null
                if (at < oldest) null else Sample(from, to, sec, at)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * [read] 를 [CACHE_MS] 동안 재사용한다. 도착 예정·시간표는 같은 노선을 여러 번(정류장마다, 운행마다) 묻기 때문에
     * 매번 파일을 읽으면 느려진다. 새 기록이 쌓이면 [append] 가 캐시를 지운다.
     */
    fun samples(routeId: String, nowMs: Long = System.currentTimeMillis()): List<Sample> {
        cache[routeId]?.takeIf { nowMs - it.atMs in 0 until CACHE_MS }?.let { return it.samples }
        val fresh = read(routeId, nowMs)
        if (cache.size >= MAX_CACHED_ROUTES) cache.clear()
        cache[routeId] = Cached(nowMs, fresh)
        return fresh
    }

    /**
     * [fromOrd] 에서 [toOrd] 까지 걸리는 실측 초(중앙값). 모르면 null.
     *  - 같은 구간 기록이 있으면 그것
     *  - 여러 정류장을 한 번에 지나간 기록만 있으면 정류장 수로 나눠서
     *  - 아니면 한 정류장씩 이어 붙인다
     */
    fun seconds(routeId: String, fromOrd: Int, toOrd: Int, nowMs: Long = System.currentTimeMillis()): Double? {
        if (toOrd <= fromOrd) return null
        val samples = samples(routeId, nowMs)
        if (samples.isEmpty()) return null
        samples.filter { it.from == fromOrd && it.to == toOrd }.map { it.sec }.medianOfInts()?.let { return it }
        samples.filter { it.from == fromOrd && it.to > toOrd }
            .map { it.sec.toDouble() * (toOrd - fromOrd) / (it.to - it.from) }.medianOfDoubles()?.let { return it }
        var sum = 0.0
        var cur = fromOrd
        while (cur < toOrd) {
            val next = samples.filter { it.from == cur && it.to > cur }.minByOrNull { it.to } ?: return null
            val step = minOf(next.to, toOrd) - cur
            sum += next.sec.toDouble() * step / (next.to - next.from)
            cur = minOf(next.to, toOrd)
        }
        return sum.takeIf { it > 0 }
    }

    private fun List<Int>.medianOfInts(): Double? {
        if (isEmpty()) return null
        return sorted()[size / 2].toDouble()
    }

    private fun List<Double>.medianOfDoubles(): Double? {
        if (isEmpty()) return null
        return sorted()[size / 2]
    }

    internal fun reset() {
        last.clear()
        cache.clear()
    }
}
