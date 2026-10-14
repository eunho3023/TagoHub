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
        sec < 60 -> "곧 도착"
        else -> "${(sec + 59) / 60}분"
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
}

/** 노선번호 자연 정렬: 5 < 25 < 102 < 315 < 8541(맞춤버스) */
val naturalOrder: Comparator<String> = compareBy<String>(
    { it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
    { it }
)
