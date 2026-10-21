package com.eunho.tagohub.core

import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Fmt {
    fun won(v: Int): String = String.format(Locale.KOREA, "%,d원", v)

    fun minutes(m: Int): String = when {
        m <= 0 -> "0분"
        m < 60 -> "${m}분"
        m % 60 == 0 -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }

    fun duration(dep: LocalDateTime?, arr: LocalDateTime?): String? {
        if (dep == null || arr == null) return null
        val m = Duration.between(dep, arr).toMinutes().toInt()
        return if (m > 0) minutes(m) else null
    }

    /** 버스 arrtime(초) → 표시. 403초 → "7분" (올림: 놓치지 않게 보수적으로) */
    fun arrival(sec: Int?): String = when {
        sec == null -> "정보 없음"
        sec <= 30 -> "곧 도착"
        sec < 60 -> "${sec}초"
        else -> "${sec / 60}분 %02d초".format(sec % 60)
    }

    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()}m" else String.format(Locale.ROOT, "%.1fkm", meters / 1000)
}

object Geo {
    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** (lat1,lng1) 에서 (lat2,lng2) 를 보는 방위(도, 북 0 · 동 90 · 0~360) */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** (lat,lng) 에서 [bearingDeg] 방향으로 [meters] 미터 간 지점: (위도, 경도) */
    fun offset(lat: Double, lng: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val r = Math.toRadians(bearingDeg)
        return (lat + meters * cos(r) / 110_540.0) to (lng + meters * sin(r) / (111_320.0 * cos(Math.toRadians(lat))))
    }
}

/** 노선번호 자연 정렬: 5 < 25 < 102 < 315 < 8541(맞춤버스) */
val naturalOrder: Comparator<String> = compareBy<String>(
    { it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
    { it }
)
