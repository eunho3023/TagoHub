package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.core.HeadingMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 국토교통부 표준 노드·링크(구간단위)에서 뽑은 도로 선분 (assets/road_links.bin, pipeline/build_link_speed.py 가 만듦).
 * 지금 위치가 어느 도로 위인지 찾아 그 도로의 제한속도·차로 수를 알려 준다.
 * 차로마다의 정보는 없다 — 도로 전체 차로 수만 있다.
 */
class RoadLinkIndex(private val buf: ByteBuffer) {
    /** [speedKmh] 는 모르면 null. [lanes] 는 도로 전체 차로 수(모르면 0). [rank] 1 고속국도 · 2 도시고속 · 3 일반국도 · 4 특별/광역시도 · 5 지원지방도 · 6 지방도 */
    class Hit(val speedKmh: Int?, val lanes: Int, val rank: Int, val distM: Double)

    val count: Int = if (buf.limit() >= HEADER && buf.getInt(0) == MAGIC) buf.getInt(4) else 0

    private fun lat1(i: Int) = buf.getInt(HEADER + i * REC)
    private fun lng1(i: Int) = buf.getInt(HEADER + i * REC + 4)

    private fun cellOf(lat: Int, lng: Int): Long = Math.floorDiv(lat, 1000).toLong() * 100_000L + Math.floorDiv(lng, 1000)

    /** 격자 [cell] 이 시작되는 첫 선분 번호 */
    private fun lowerBound(cell: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cellOf(lat1(mid), lng1(mid)) < cell) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * [lat],[lng] 에서 [maxM] 미터 안의 도로 중 가장 가까운 것.
     * [bearing] (진행 방향, 도)이 있으면 그 방향으로 난 도로만 (반대 차선·직각으로 만나는 길 제외).
     */
    fun find(lat: Double, lng: Double, bearing: Double?, maxM: Double = 25.0): Hit? {
        if (count == 0) return null
        val kx = 111_000.0 * cos(Math.toRadians(lat))
        val ky = 111_000.0
        val la = (lat * 1e5).toInt()
        val ln = (lng * 1e5).toInt()
        var best: Hit? = null
        for (dy in -1..1) for (dx in -1..1) {
            val cell = cellOf(la + dy * 1000, ln + dx * 1000)
            var i = lowerBound(cell)
            while (i < count && cellOf(lat1(i), lng1(i)) == cell) {
                val o = HEADER + i * REC
                val ax = (buf.getInt(o + 4) - ln) / 1e5 * kx
                val ay = (buf.getInt(o) - la) / 1e5 * ky
                val bx = ax + buf.getShort(o + 10) / 1e5 * kx
                val by = ay + buf.getShort(o + 8) / 1e5 * ky
                i++
                val vx = bx - ax
                val vy = by - ay
                val len2 = vx * vx + vy * vy
                val t = if (len2 == 0.0) 0.0 else ((-ax) * vx + (-ay) * vy).div(len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + vx * t, ay + vy * t)
                if (d > maxM || (best != null && d >= best.distM)) continue
                if (bearing != null && len2 > 4.0) {
                    val seg = Math.toDegrees(atan2(vx, vy))
                    if (abs(HeadingMath.delta(bearing, seg)) > MAX_TURN_DEG) continue
                }
                val spd = buf.get(o + 12).toInt() and 0xFF
                best = Hit(spd.takeIf { it > 0 }, buf.get(o + 13).toInt() and 0xFF, buf.get(o + 14).toInt(), d)
            }
        }
        return best
    }

    companion object {
        const val ASSET = "road_links.bin"
        const val MAGIC = 0x4C4E4B31
        private const val HEADER = 8
        private const val REC = 15
        private const val MAX_TURN_DEG = 50.0

        @Volatile private var cached: RoadLinkIndex? = null

        /** 앱 안 파일을 한 번만 읽음 (약 14MB). 없거나 깨졌으면 null */
        suspend fun get(context: Context): RoadLinkIndex? = cached ?: withContext(Dispatchers.IO) {
            cached ?: runCatching {
                val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
                RoadLinkIndex(ByteBuffer.wrap(bytes)).takeIf { it.count > 0 }
            }.getOrNull()?.also { cached = it }
        }
    }
}

/** 지금 도로 제한속도를 넘어 달릴 때 한 번 알려 주는 판단 (지점 안내와 겹치지 않게 간격을 둠) */
class SpeedLimitWatch {
    private var armed = true
    private var lastAt = 0L

    /** 알릴 말이 있으면 문구, 아니면 null. 제한속도를 모르면 아무 말 없음 */
    fun update(speedKmh: Int, limitKmh: Int?, nowMs: Long): String? {
        if (limitKmh == null) { armed = true; return null }
        if (speedKmh <= limitKmh) { armed = true; return null }
        if (speedKmh < limitKmh + MARGIN_KMH || !armed || nowMs - lastAt < MIN_GAP_MS) return null
        armed = false
        lastAt = nowMs
        return "제한속도 ${limitKmh}킬로미터입니다. 속도를 줄이세요."
    }

    private companion object {
        const val MARGIN_KMH = 10
        const val MIN_GAP_MS = 90_000L
    }
}
