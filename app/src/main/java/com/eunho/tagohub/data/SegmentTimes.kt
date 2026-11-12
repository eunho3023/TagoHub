package com.eunho.tagohub.data

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 노선의 정류장 구간별 실제 통행 시간(초)을 앱이 본 버스 위치에서 모은다.
 *
 * 같은 차량이 한 정류장에서 다음 정류장으로 넘어가는 데 걸린 시간을 기록해 두면,
 * 거리 × 평균속도로 어림하는 것보다 정체·신호 대기를 반영한 도착 예정을 만들 수 있다.
 * 자료는 `filesDir/seg_times/<노선>.tsv` 에 `fromOrd \t toOrd \t 초 \t epoch초` 로 쌓고 14일치만 남긴다.
 *
 * 파일은 버스 위치를 받을 때마다 열지 않고 잠깐 모았다가 한 번에 덧붙이고([append]),
 * 읽은 표본은 구간·출발 정류장으로 색인해 둬서 [seconds] 가 매번 전체를 훑지 않게 한다.
 */
object SegmentTimes {
    private const val KEEP_DAYS = 14L
    private const val MAX_LINES = 20_000
    private const val MIN_SEC = 10
    private const val MAX_SEC = 1_800
    /** 한 번에 이보다 많은 정류장을 건너뛰면 데이터가 빠진 것으로 보고 버린다 */
    private const val MAX_JUMP = 3
    /** 모아 둔 기록을 파일로 내리는 간격. 버스 위치 갱신 주기(10초)보다 짧게 잡아 오래 쌓이지 않게 한다 */
    private const val FLUSH_DELAY_MS = 1_500L
    /** 이만큼 쌓이면 기다리지 않고 바로 내린다 */
    private const val FLUSH_BYTES = 8_192

    @Volatile private var dir: File? = null
    private class At(val ord: Int, val atMs: Long)
    private val last = ConcurrentHashMap<String, At>()

    class Sample(val from: Int, val to: Int, val sec: Int, val epochSec: Long)

    /**
     * 한 노선의 표본과 색인.
     *  - [exact]: (출발, 도착) 구간 → 초 목록 (같은 구간을 바로 찾음)
     *  - [byFrom]: 출발 정류장 → 도착 순으로 정렬한 표본 (여러 정류장을 건너뛴 기록·이어 붙이기에 씀)
     */
    private class Index(
        val samples: MutableList<Sample> = ArrayList(),
        val exact: MutableMap<Long, MutableList<Int>> = HashMap(),
        val byFrom: MutableMap<Int, MutableList<Sample>> = HashMap(),
    ) {
        fun add(s: Sample) {
            samples.add(s)
            exact.getOrPut(segKey(s.from, s.to)) { ArrayList() }.add(s.sec)
            val list = byFrom.getOrPut(s.from) { ArrayList() }
            var i = list.size
            while (i > 0 && list[i - 1].to > s.to) i--
            list.add(i, s)
        }
    }

    private class Cached(val atMs: Long, val index: Index)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 30_000L
    private const val MAX_CACHED_ROUTES = 200

    /** 아직 파일에 안 쓴 줄. [lock] 안에서만 만진다 */
    private val lock = Any()
    private val pending = HashMap<String, StringBuilder>()
    private val flushScheduled = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "segment-times-writer").apply { isDaemon = true }
    }

    fun init(root: File) {
        synchronized(lock) {
            dir = File(root, "seg_times").apply { mkdirs() }
            pending.clear()
            cache.clear()
            last.clear()
        }
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
            append(routeId, Sample(prev.ord, ord, sec, nowMs / 1000))
        }
        if (last.size > 3_000) last.entries.removeIf { nowMs - it.value.atMs > 3_600_000L }
    }

    private fun file(routeId: String): File? =
        dir?.let { File(it, routeId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv") }

    /**
     * 새 표본을 기억해 두고 파일에는 잠깐 모아서 쓴다.
     * 이미 읽어 둔 노선이면 색인에 바로 넣어, 다음 조회가 파일을 다시 읽지 않게 한다.
     */
    private fun append(routeId: String, s: Sample) {
        val line = "${s.from}\t${s.to}\t${s.sec}\t${s.epochSec}\n"
        val later = synchronized(lock) {
            // 캐시가 있으면 바로 반영해 다음 조회가 파일을 다시 읽지 않게 한다 (없으면 조회 때 파일에서 읽음)
            cache[routeId]?.index?.add(s)
            val sb = pending.getOrPut(routeId) { StringBuilder() }.append(line)
            if (sb.length >= FLUSH_BYTES) {
                pending.remove(routeId)
                write(routeId, sb)
                false
            } else {
                true
            }
        }
        if (later) scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        io.execute {
            try {
                Thread.sleep(FLUSH_DELAY_MS)
            } catch (_: InterruptedException) {
            }
            flushScheduled.set(false)
            synchronized(lock) {
                if (pending.isEmpty()) return@synchronized
                val batch = pending.toMap()
                pending.clear()
                batch.forEach { (routeId, sb) -> write(routeId, sb) }
            }
        }
    }

    /** 이 노선의 모아 둔 줄을 지금 파일에 쓴다 (읽기 전에 최신 상태를 보장) */
    private fun flush(routeId: String) {
        synchronized(lock) {
            pending.remove(routeId)?.let { write(routeId, it) }
        }
    }

    /** [lock] 안에서 부른다 */
    private fun write(routeId: String, sb: StringBuilder) {
        if (sb.isEmpty()) return
        val f = file(routeId) ?: return
        runCatching {
            FileOutputStream(f, true).use { out -> out.write(sb.toString().toByteArray()) }
            if (f.length() > MAX_LINES * 16L) compact(routeId, f)
        }
    }

    /** 커진 파일을 최근 절반만 남겨 다시 쓴다. 쓰는 도중에 앱이 죽어도 원본이 깨지지 않게 임시 파일에 쓴 뒤 바꾼다 */
    private fun compact(routeId: String, f: File) {
        val keep = (cache[routeId]?.index?.samples ?: readFromFile(f, System.currentTimeMillis()))
            .takeLast(MAX_LINES / 2)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.bufferedWriter().use { w ->
                keep.forEach { s -> w.write("${s.from}\t${s.to}\t${s.sec}\t${s.epochSec}\n") }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }

    fun read(routeId: String, nowMs: Long = System.currentTimeMillis()): List<Sample> {
        flush(routeId)
        val f = file(routeId)?.takeIf { it.exists() } ?: return emptyList()
        return readFromFile(f, nowMs)
    }

    private fun readFromFile(f: File, nowMs: Long): List<Sample> {
        val oldest = nowMs / 1000 - KEEP_DAYS * 86_400
        return runCatching {
            val out = ArrayList<Sample>()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.split('\t')
                    if (t.size < 4) continue
                    val from = t[0].toIntOrNull() ?: continue
                    val to = t[1].toIntOrNull() ?: continue
                    val sec = t[2].toIntOrNull() ?: continue
                    val at = t[3].toLongOrNull() ?: continue
                    if (at >= oldest) out.add(Sample(from, to, sec, at))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * [read] 를 [CACHE_MS] 동안 재사용한다. 도착 예정·시간표는 같은 노선을 여러 번(정류장마다, 운행마다) 묻기 때문에
     * 매번 파일을 읽으면 느려진다. 새 기록은 [append] 가 캐시에 바로 넣으므로 캐시를 버리지 않는다.
     */
    fun samples(routeId: String, nowMs: Long = System.currentTimeMillis()): List<Sample> {
        cache[routeId]?.takeIf { nowMs - it.atMs in 0 until CACHE_MS }?.let { return it.index.samples }
        val fresh = read(routeId, nowMs)
        val index = Index()
        fresh.forEach { index.add(it) }
        if (cache.size >= MAX_CACHED_ROUTES) cache.clear()
        cache[routeId] = Cached(nowMs, index)
        return index.samples
    }

    private fun index(routeId: String, nowMs: Long): Index {
        cache[routeId]?.takeIf { nowMs - it.atMs in 0 until CACHE_MS }?.let { return it.index }
        samples(routeId, nowMs)
        return cache[routeId]?.index ?: Index()
    }

    /**
     * [fromOrd] 에서 [toOrd] 까지 걸리는 실측 초(중앙값). 모르면 null.
     *  - 같은 구간 기록이 있으면 그것
     *  - 여러 정류장을 한 번에 지나간 기록만 있으면 정류장 수로 나눠서
     *  - 아니면 한 정류장씩 이어 붙인다
     */
    fun seconds(routeId: String, fromOrd: Int, toOrd: Int, nowMs: Long = System.currentTimeMillis()): Double? {
        if (toOrd <= fromOrd) return null
        val idx = index(routeId, nowMs)
        if (idx.samples.isEmpty()) return null
        idx.exact[segKey(fromOrd, toOrd)]?.medianOfInts()?.let { return it }
        idx.byFrom[fromOrd]?.filter { it.to > toOrd }
            ?.map { it.sec.toDouble() * (toOrd - fromOrd) / (it.to - it.from) }
            ?.medianOfDoubles()?.let { return it }
        var sum = 0.0
        var cur = fromOrd
        while (cur < toOrd) {
            // byFrom 은 도착 정류장 순으로 정렬돼 있어 첫 항목이 가장 가까운 다음 정류장
            val next = idx.byFrom[cur]?.firstOrNull { it.to > cur } ?: return null
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
        synchronized(lock) {
            last.clear()
            cache.clear()
            pending.clear()
        }
    }
}

/** (출발, 도착) 구간을 하나의 Long 키로 */
private fun segKey(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)
