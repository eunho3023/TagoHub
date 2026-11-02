package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 주행 기록: 자유주행·경로 안내 동안 지나온 자리와 그때 속도, 단속카메라 부근을 지날 때의 속도.
 * 끝나면 [last] 에 요약이 들어가 화면에 "어디서 몇 km/h 로 달렸는지, 카메라 앞에서 과속했는지"를 띄운다.
 * 기록은 메모리에만 있고(저장·전송하지 않음) 다음 주행을 시작하면 지워진다.
 */
object DriveLog {
    /** 지나온 한 점 */
    data class Point(val lat: Double, val lng: Double, val kmh: Int, val atMs: Long)

    /** 단속카메라 하나를 지난 기록: 카메라 반경 안에서 가장 빨랐던 속도 */
    data class CameraPass(val name: String, val lat: Double, val lng: Double, val limitKmh: Int, val speedKmh: Int, val atMs: Long) {
        /** 제한속도를 넘은 만큼(km/h). 넘지 않았으면 0 */
        val overBy: Int get() = (speedKmh - limitKmh).coerceAtLeast(0)
        val speeding: Boolean get() = overBy > 0
    }

    data class Summary(
        /** "자유주행" / "경로 안내" */
        val title: String,
        val points: List<Point>,
        val cameras: List<CameraPass>,
        val meters: Double,
        val startMs: Long,
        val endMs: Long,
    ) {
        val maxKmh: Int get() = points.maxOfOrNull { it.kmh } ?: 0
        /** 움직인 시간 기준 평균 (멈춘 시간 포함한 전체 시간으로 나누면 너무 낮게 나옴) */
        val avgKmh: Int get() {
            val sec = (endMs - startMs) / 1000.0
            return if (sec <= 0) 0 else (meters / sec * 3.6).toInt()
        }
        val speedingCount: Int get() = cameras.count { it.speeding }
    }

    /** 이 반경(m) 안에 들어오면 그 카메라 부근으로 봄 */
    const val CAMERA_RADIUS_M = 60.0
    /** 점은 이만큼 움직였거나 시간이 지났을 때만 남김 (기록이 너무 커지지 않게) */
    const val MIN_STEP_M = 15.0
    const val MIN_STEP_MS = 5_000L
    /** 이보다 짧은 주행은 요약을 띄우지 않음 */
    const val MIN_TRIP_M = 300.0

    /** 끝난 주행의 요약. 화면이 보여 준 뒤 null 로 지움 */
    val last = MutableStateFlow<Summary?>(null)

    @Volatile private var recorder: Recorder? = null

    /** 새 주행 시작. [zones] 중 제한속도가 있는 단속카메라만 씀 */
    fun start(title: String, zones: List<SafetyZone>, nowMs: Long = System.currentTimeMillis()) {
        recorder = Recorder(title, zones, nowMs)
    }

    /** 단속카메라 목록이 늦게 오면 넣어 줌 */
    fun setZones(zones: List<SafetyZone>) {
        recorder?.setZones(zones)
    }

    fun add(lat: Double, lng: Double, kmh: Int, nowMs: Long = System.currentTimeMillis()) {
        recorder?.add(lat, lng, kmh, nowMs)
    }

    /** 주행 끝: 충분히 달렸으면 [last] 에 요약 */
    fun finish(nowMs: Long = System.currentTimeMillis()) {
        val r = recorder ?: return
        recorder = null
        r.finish(nowMs)?.let { last.value = it }
    }

    class Recorder(private val title: String, zones: List<SafetyZone>, private val startMs: Long) {
        private var cameras: List<SafetyZone> = cams(zones)
        private val points = ArrayList<Point>()
        private val passes = ArrayList<CameraPass>()
        private var meters = 0.0
        private var lastLat = Double.NaN
        private var lastLng = Double.NaN
        /** 지금 반경 안에 있는 카메라와 그 안에서 가장 빨랐던 속도 */
        private var near: SafetyZone? = null
        private var nearMax = 0
        private var nearAt = 0L

        private fun cams(zones: List<SafetyZone>) = zones.filter { it.kind.isCamera && it.limitKmh != null }

        fun setZones(zones: List<SafetyZone>) { cameras = cams(zones) }

        @Synchronized
        fun add(lat: Double, lng: Double, kmh: Int, nowMs: Long) {
            if (!lastLat.isNaN()) meters += Geo.meters(lastLat, lastLng, lat, lng)
            lastLat = lat
            lastLng = lng
            val prev = points.lastOrNull()
            if (prev == null || nowMs - prev.atMs >= MIN_STEP_MS || Geo.meters(prev.lat, prev.lng, lat, lng) >= MIN_STEP_M) {
                points += Point(lat, lng, kmh, nowMs)
            }
            trackCamera(lat, lng, kmh, nowMs)
        }

        private fun trackCamera(lat: Double, lng: Double, kmh: Int, nowMs: Long) {
            val cur = near
            if (cur != null && Geo.meters(cur.lat, cur.lng, lat, lng) <= CAMERA_RADIUS_M) {
                if (kmh > nearMax) nearMax = kmh
                return
            }
            if (cur != null) closeCamera()
            // 위도·경도 상자로 먼저 거른 뒤 거리 계산 (카메라가 수만 개라도 가볍게)
            val dLat = CAMERA_RADIUS_M / 111_000.0
            val dLng = dLat / Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1)
            val hit = cameras.asSequence()
                .filter { kotlin.math.abs(it.lat - lat) <= dLat && kotlin.math.abs(it.lng - lng) <= dLng }
                .minByOrNull { Geo.meters(it.lat, it.lng, lat, lng) }
                ?.takeIf { Geo.meters(it.lat, it.lng, lat, lng) <= CAMERA_RADIUS_M }
            if (hit != null) {
                near = hit
                nearMax = kmh
                nearAt = nowMs
            }
        }

        private fun closeCamera() {
            val c = near ?: return
            near = null
            // 같은 카메라를 바로 다시 지나면(신호 대기 중 GPS 흔들림) 한 번으로
            val dup = passes.lastOrNull()?.takeIf { it.lat == c.lat && it.lng == c.lng }
            if (dup != null) {
                if (nearMax > dup.speedKmh) passes[passes.lastIndex] = dup.copy(speedKmh = nearMax)
                return
            }
            passes += CameraPass(c.name, c.lat, c.lng, c.limitKmh ?: return, nearMax, nearAt)
        }

        @Synchronized
        fun finish(nowMs: Long): Summary? {
            closeCamera()
            if (meters < MIN_TRIP_M || points.size < 2) return null
            return Summary(title, points.toList(), passes.toList(), meters, startMs, nowMs)
        }
    }
}
