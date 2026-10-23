package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.HeadingMath
import kotlin.math.abs
import kotlin.math.sin

/**
 * 목적지 없이 달릴 때(자유주행)의 계산. 경로가 없으므로 "지금 가는 방향 앞쪽"에 있는 안전 지점만 골라 알려 준다.
 */
object FreeDrive {
    /** 앞쪽 몇 m 까지 볼지 */
    const val LOOKAHEAD_M = 1500.0

    /** 진행 방향에서 이 각도 안에 있어야 앞쪽 */
    private const val HALF_ANGLE_DEG = 25.0

    /** 진행 방향 옆으로 이만큼(m) 안에 있어야 같은 길 위의 것으로 봄 */
    private const val MAX_SIDE_M = 45.0

    /** 이 거리(m) 안은 방향이 흔들려도 앞이라고 봄 (지나치기 직전) */
    private const val CLOSE_M = 40.0

    class Ahead(val zone: SafetyZone, val distance: Double)

    /** [bearing] 방향으로 달리는 [lat],[lng] 앞쪽의 지점들을 가까운 순으로 */
    fun zonesAhead(
        lat: Double,
        lng: Double,
        bearing: Double,
        zones: List<SafetyZone>,
        maxM: Double = LOOKAHEAD_M,
    ): List<Ahead> {
        val box = maxM / 111_000.0 * 1.3          // 위도 1도 ≈ 111km, 경도는 한반도에서 더 짧으니 넉넉히 거름
        val out = ArrayList<Ahead>()
        for (z in zones) {
            if (abs(z.lat - lat) > box || abs(z.lng - lng) > box * 1.4) continue
            val d = Geo.meters(lat, lng, z.lat, z.lng)
            if (d > maxM) continue
            if (d > CLOSE_M) {
                val toward = Geo.bearing(lat, lng, z.lat, z.lng)
                val diff = abs(HeadingMath.delta(bearing, toward))
                if (diff > HALF_ANGLE_DEG) continue
                if (d * sin(Math.toRadians(diff)) > MAX_SIDE_M) continue
            }
            out += Ahead(z, d)
        }
        out.sortBy { it.distance }
        return out
    }

    private val POINTS = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")

    /** 방위(도)를 "북동" 같은 말로 */
    fun compassWord(deg: Double): String = POINTS[(((HeadingMath.normalize(deg) + 22.5) / 45.0).toInt()) % 8]
}

/** 앞쪽 지점을 한 곳당 한 번만 말하게 해 주는 기록 */
class FreeDriveAnnouncer {
    private val said = HashSet<String>()

    /** 말해야 할 지점이면 문구를, 이미 말했으면 null */
    fun speakFor(a: FreeDrive.Ahead): String? {
        val z = a.zone
        val key = "${z.kind.code}:${"%.5f".format(z.lat)}:${"%.5f".format(z.lng)}"
        if (a.distance > announceDistance(z)) return null
        if (!said.add(key)) return null
        val limit = z.limitKmh?.takeIf { z.kind.isCamera }?.let { " 제한속도 ${it}킬로미터" }.orEmpty()
        return "${NavText.distance(a.distance)} 앞 ${z.kind.label}${limit}"
    }

    /** 지나간 지점의 기록을 비워 같은 길을 다시 달릴 때 또 말하게 함 */
    fun forget() = said.clear()

    private fun announceDistance(z: SafetyZone): Double = when (z.kind.group) {
        ZoneGroup.Camera -> 700.0
        ZoneGroup.Protect -> 400.0
        else -> 200.0
    }
}
