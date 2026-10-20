package com.eunho.tagohub.data

/**
 * 버스 도착 문구 (TAGO 버스도착정보: arrtime 초, arrprevstationcnt 남은 정류장 수, vehicletp 차량유형).
 * 시간은 항상 초까지 (elapsedSec 만큼 줄여서 매초 줄어듦): "12분 05초 · 3정류장 전 · 저상"
 */
object ArrivalText {
    fun time(sec: Int?): String = when {
        sec == null -> "정보없음"
        sec <= 30 -> "곧 도착"
        sec < 60 -> "${sec}초"
        else -> "${sec / 60}분 %02d초".format(sec % 60)
    }

    fun line(a: BusArrival, elapsedSec: Int = 0): String {
        val sec = a.arrSeconds?.minus(elapsedSec)?.coerceAtLeast(0)
        return listOfNotNull(
            time(sec),
            a.prevStops?.let { if (it <= 0) "이번 정류장" else "${it}정류장 전" },
            a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" },
        ).joinToString(" · ")
    }
}
