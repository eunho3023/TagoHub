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
        sec < 180 -> "${sec / 60}분 %02d초".format(sec % 60)   // 3분 넘게 남으면 초는 보여주지 않음
        else -> "${sec / 60}분"
    }

    fun line(a: BusArrival, elapsedSec: Int = 0): String {
        val sec = a.arrSeconds?.minus(elapsedSec)?.coerceAtLeast(0)
        // 이번 정류장이면(0정류장 전) 이미 와 있는 것: "곧 도착 · 이번 정류장" 대신 한 마디로
        if (a.prevStops == 0 && (sec == null || sec <= 30)) {
            return listOfNotNull("정류장 도착", a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" }).joinToString(" · ")
        }
        return listOfNotNull(
            if (a.estimated && sec != null && sec > 30) "약 ${((sec + 30) / 60).coerceAtLeast(1)}분" else time(sec),
            a.prevStops?.let { if (it <= 0) "이번 정류장" else "${it}정류장 전" } ?: if (a.scheduled) (if (a.fromLog) "지난 기록 기준" else "배차 기준") else null,
            a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" },
        ).joinToString(" · ")
    }
}

/** 정류장을 이미 지나간 버스를 목록에서 뺄지 */
object PassedBus {
    /** 남은 시간이 이만큼(초) 지나 0 아래로 내려가면 지나간 것으로 봄 */
    const val GRACE_SEC = 3

    /**
     * 도착 예정 시간이 다 줄었고(0초를 [GRACE_SEC] 넘김) 바로 앞 정류장이거나 이번 정류장 차례였던 버스는 지나간 것.
     * 멀리 있던 버스(정류장 수가 많음)는 예측이 틀렸을 수 있어서 서버가 다시 알려줄 때까지 둔다.
     */
    fun passed(a: BusArrival, elapsedSec: Int): Boolean {
        val sec = a.arrSeconds ?: return false
        return sec - elapsedSec < -GRACE_SEC && (a.prevStops == null || a.prevStops <= 1)
    }
}

/**
 * "정류장 도착"이 계속 남는 것 막기. 정류장 순번만 아는 지역 API(전주 등)는 버스가 다음 정류장에 닿을 때까지
 * 이 정류장에 와 있다(0정류장 전)고 알려 줘서, 버스가 떠난 뒤에도 몇 분씩 "정류장 도착"이 떠 있었다.
 * 노선마다 "도착"이 처음 보인 시각을 기억해 [HOLD_SEC] 가 지나면 그 버스는 떠난 것으로 보고 뺀다.
 */
object ArrivedHold {
    const val HOLD_SEC = 90

    /** [since] 는 노선 → "도착"이 처음 보인 시각(ms). 정류장 화면이 들고 있음 */
    fun filter(routeId: String, arrivals: List<BusArrival>, since: MutableMap<String, Long>, nowMs: Long): List<BusArrival> {
        val first = arrivals.firstOrNull()
        if (first == null || first.prevStops != 0) {
            since.remove(routeId)
            return arrivals
        }
        val at = since.getOrPut(routeId) { nowMs }
        return if (nowMs - at > HOLD_SEC * 1000L) arrivals.drop(1) else arrivals
    }
}
