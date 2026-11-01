package com.eunho.tagohub.core

import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

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

/** 노선번호 자연 정렬: 5 < 25 < 102 < 315 < 8541(맞춤버스) */
val naturalOrder: Comparator<String> = compareBy<String>(
    { it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
    { it }
)
