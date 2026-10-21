package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 운전 중 미리 알려 줄 안전 지점의 종류 */
enum class ZoneKind(val label: String, val code: String) {
    SpeedCamera("과속단속카메라", "speed"),
    SectionCamera("구간단속", "section"),
    SignalCamera("신호위반 단속카메라", "signal"),
    OtherCamera("단속카메라", "cam"),
    ChildZone("어린이 보호구역", "child");

    val isCamera: Boolean get() = this != ChildZone

    companion object {
        fun of(code: String): ZoneKind? = entries.firstOrNull { it.code == code }
    }
}

/** 단속카메라·어린이 보호구역 한 곳. [limitKmh] 는 제한속도(모르면 null) */
data class SafetyZone(val kind: ZoneKind, val name: String, val lat: Double, val lng: Double, val limitKmh: Int?)

/**
 * pipeline/build_overlays.py 가 공공데이터(전국무인교통단속카메라·어린이보호구역 표준데이터)를 모아 만든 점 목록:
 * [[종류코드, 이름, 위도, 경도, 제한속도 또는 null], ...]
 */
object SafetyZoneSnapshot {
    const val NAME = "ov_safety_zones.json"

    fun parse(json: String): List<SafetyZone> {
        val rows = MiniJson.parse(json) as? List<*> ?: throw IOException("안전구간 저장본 형식이 올바르지 않습니다.")
        return rows.mapNotNull { r ->
            val f = r as? List<*> ?: return@mapNotNull null
            if (f.size < 4) return@mapNotNull null
            val kind = ZoneKind.of(f[0]?.toString().orEmpty()) ?: return@mapNotNull null
            val lat = f[2]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = f[3]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in 33.0..39.5 || lng !in 124.0..132.0) return@mapNotNull null
            val limit = f.getOrNull(4)?.toString()?.toDoubleOrNull()?.toInt()?.takeIf { it in 10..130 }
            SafetyZone(kind, f[1]?.toString().orEmpty(), lat, lng, limit)
        }
    }
}

object SafetyZones {
    /** 저장본에서 읽음. 아직 없으면(수집 전) 빈 목록 — 안내는 그대로 되고 이 알림만 없다 */
    suspend fun load(): List<SafetyZone> =
        runCatching {
            OverlaySnapshot.get(SafetyZoneSnapshot.NAME, maxAgeSec = 3 * 24 * 3600L, staleOkSec = 30 * 24 * 3600L) { SafetyZoneSnapshot.parse(it) }
        }.getOrNull().orEmpty()
}

/** 경로 위에 있는 안전 지점: [along] 은 출발점부터의 거리(m) */
class RouteZone(val zone: SafetyZone, val along: Double)

/**
 * 경로에서 [maxOffM] 미터 안에 있는 지점만 골라 경로 순서로 놓는다.
 * 반대 방향 차선의 카메라처럼 같은 곳에서 겹치는 같은 종류는 하나로 합친다([MERGE_M] 안).
 */
fun matchRoute(route: NavRoute, zones: List<SafetyZone>, maxOffM: Double = 30.0): List<RouteZone> {
    val pts = route.points
    if (pts.size < 2 || zones.isEmpty()) return emptyList()
    val pad = 0.0006 // 약 65m: 선분 하나하나를 정확히 재기 전에 거르는 여유
    val minLat = pts.minOf { it.lat } - pad
    val maxLat = pts.maxOf { it.lat } + pad
    val minLng = pts.minOf { it.lng } - pad
    val maxLng = pts.maxOf { it.lng } + pad
    val found = ArrayList<RouteZone>()
    for (z in zones) {
        if (z.lat < minLat || z.lat > maxLat || z.lng < minLng || z.lng > maxLng) continue
        var best: NavRoute.Projection? = null
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (z.lat < min(a.lat, b.lat) - pad || z.lat > max(a.lat, b.lat) + pad) continue
            if (z.lng < min(a.lng, b.lng) - pad || z.lng > max(a.lng, b.lng) + pad) continue
            val p = route.project(z.lat, z.lng, i)
            if (p.distance <= maxOffM && (best == null || p.distance < best.distance)) best = p
        }
        if (best != null) found += RouteZone(z, best.along)
    }
    found.sortBy { it.along }
    val out = ArrayList<RouteZone>()
    for (rz in found) {
        val dup = out.lastOrNull { it.zone.kind.isCamera == rz.zone.kind.isCamera && abs(it.along - rz.along) < MERGE_M }
        if (dup == null) out += rz
        else if (dup.zone.limitKmh == null && rz.zone.limitKmh != null) out[out.indexOf(dup)] = RouteZone(rz.zone, dup.along)
    }
    return out
}

private const val MERGE_M = 150.0

/** 앞으로 만날 안전 지점의 상태: 가장 가까운 것과 거리, 지금 말할 문구, 과속 여부 */
data class ZoneStatus(val next: RouteZone?, val distance: Double, val speak: List<String>, val overspeed: Boolean)

/** 달리면서 다음 안전 지점을 알려줌. 한 지점당 같은 거리의 안내는 한 번만 한다 */
class ZoneTracker(private val zones: List<RouteZone>) {
    private val said = HashSet<Long>()
    private var index = 0

    fun update(progress: Double, speedKmh: Double): ZoneStatus {
        while (index < zones.size && zones[index].along < progress - PASSED_M) index++
        val rz = zones.getOrNull(index) ?: return ZoneStatus(null, 0.0, emptyList(), false)
        val dist = max(0.0, rz.along - progress)
        val say = ArrayList<String>()
        for (lead in leads(rz.zone.kind, speedKmh)) {
            if (dist <= lead && said.add(key(index, lead))) {
                say += sentence(rz.zone, dist)
                break
            }
        }
        // 과속단속카메라 앞 600m 안에서 제한속도를 넘으면 한 번 알림
        val limit = rz.zone.limitKmh
        val over = rz.zone.kind.isCamera && limit != null && dist <= WARN_OVERSPEED_M && speedKmh > limit + 3
        if (over && said.add(key(index, OVERSPEED_KEY))) say += "과속입니다. 속도를 줄이세요."
        return ZoneStatus(rz, dist, say, over)
    }

    private fun key(i: Int, lead: Int): Long = i.toLong() * 100_000L + lead

    private fun leads(kind: ZoneKind, speedKmh: Double): IntArray = when {
        kind == ZoneKind.ChildZone -> intArrayOf(400)
        speedKmh >= 70 -> intArrayOf(1000, 400)
        else -> intArrayOf(500, 200)
    }

    private fun sentence(z: SafetyZone, dist: Double): String {
        val lead = "${NavText.distance(dist)} 앞"
        return when (z.kind) {
            ZoneKind.ChildZone -> "$lead 어린이 보호구역입니다. 속도를 줄이세요."
            ZoneKind.SectionCamera -> "$lead 구간단속 구간입니다." + (z.limitKmh?.let { " 제한속도 ${it}킬로미터." } ?: "")
            ZoneKind.SpeedCamera -> "$lead 과속단속카메라가 있습니다." + (z.limitKmh?.let { " 제한속도 ${it}킬로미터." } ?: "")
            ZoneKind.SignalCamera -> "$lead 신호위반 단속카메라가 있습니다."
            ZoneKind.OtherCamera -> "$lead 단속카메라가 있습니다."
        }
    }

    private companion object {
        const val PASSED_M = 20.0
        const val WARN_OVERSPEED_M = 600.0
        const val OVERSPEED_KEY = 99_999
    }
}
