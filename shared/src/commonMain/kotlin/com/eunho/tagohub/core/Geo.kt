package com.eunho.tagohub.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun radians(degrees: Double): Double = degrees * PI / 180.0
private fun degrees(radians: Double): Double = radians * 180.0 / PI

/** Android/JVM Math에 의존하지 않는 공용 지리 계산. 좌표 순서는 (위도, 경도). */
object Geo {
    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = radians(lat2 - lat1)
        val dLng = radians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(radians(lat1)) * cos(radians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** (lat1,lng1) 에서 (lat2,lng2) 를 보는 방위(도, 북 0 · 동 90 · 0~360) */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = radians(lat1)
        val p2 = radians(lat2)
        val dl = radians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (degrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** (lat,lng) 에서 [bearingDeg] 방향으로 [meters] 미터 간 지점: (위도, 경도) */
    fun offset(lat: Double, lng: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val r = radians(bearingDeg)
        return (lat + meters * cos(r) / 110_540.0) to
            (lng + meters * sin(r) / (111_320.0 * cos(radians(lat))))
    }
}
