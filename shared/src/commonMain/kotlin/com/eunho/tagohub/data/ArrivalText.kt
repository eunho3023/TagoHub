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
        sec < 180 -> "${sec / 60}분 ${(sec % 60).toString().padStart(2, '0')}초"   // 3분 넘게 남으면 초는 보여주지 않음
        else -> "${sec / 60}분"
    }

    fun line(a: BusArrival, elapsedSec: Int = 0): String {
        val sec = a.arrSeconds?.minus(elapsedSec)?.coerceAtLeast(0)
        if (a.waiting) return listOfNotNull("출발 대기", a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" }, a.crowd).joinToString(" · ")
        // 이번 정류장이면(0정류장 전) 이미 와 있는 것: "곧 도착 · 이번 정류장" 대신 한 마디로
        if (a.prevStops == 0 && (sec == null || sec <= 30)) {
            return listOfNotNull("정류장 도착", a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" }, a.crowd).joinToString(" · ")
        }
        return listOfNotNull(
            if (a.estimated && sec != null && sec > 30) "약 ${((sec + 30) / 60).coerceAtLeast(1)}분" else time(sec),
            a.prevStops?.let { if (it <= 0) "이번 정류장" else "${it}정류장 전" } ?: if (a.scheduled) (if (a.fromLog) "지난 기록 기준" else "배차 기준") else null,
            a.vehicleType?.takeIf { it.contains("저상") }?.let { "저상" },
            a.crowd,
        ).joinToString(" · ")
    }
}

/**
 * 차 안 혼잡·빈자리 한 마디. 정보가 없으면 null (그러면 아무것도 안 보임).
 * 웹 busdata.js crowdSeoul·crowdGyeonggi 와 같은 규칙.
 */
object Crowd {
    /** 서울 getStationByUid: congestion 0 정보없음 3 여유 4 보통 5 혼잡 6 매우혼잡, isFullFlag 1 만차 */
    fun seoul(congestion: String?, full: String?): String? = when {
        full?.trim() == "1" -> "만차"
        else -> when (congestion?.trim()) { "3" -> "여유"; "4" -> "보통"; "5" -> "혼잡"; "6" -> "매우혼잡"; else -> null }
    }

    /**
     * 경기 getBusArrivalListv2: remainSeatCnt 빈자리(-1 정보없음, 좌석 없는 시내버스는 0), crowded 1 여유 2 보통 3 혼잡 4 매우혼잡.
     * 빈자리 수가 있으면(1 이상) 그쪽이 더 쓸모 있어 그것만 보임
     */
    fun gyeonggi(crowded: Int?, seats: Int?): String? = when {
        seats != null && seats > 0 -> "빈자리 $seats"
        else -> when (crowded) { 1 -> "여유"; 2 -> "보통"; 3 -> "혼잡"; 4 -> "매우혼잡"; else -> null }
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
        if (a.waiting) return false   // 기점에서 출발을 기다리는 버스는 시간이 지나도 그대로 있음
        val sec = a.arrSeconds ?: return false
        return sec - elapsedSec < -GRACE_SEC && (a.prevStops == null || a.prevStops!! <= 1)
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
        if (first == null || first.prevStops != 0 || first.waiting) {
            since.remove(routeId)
            return arrivals
        }
        val at = since.getOrPut(routeId) { nowMs }
        return if (nowMs - at > HOLD_SEC * 1000L) arrivals.drop(1) else arrivals
    }
}

/**
 * 기점에서 출발을 기다리는 버스. 도착정보 API 는 기점에 서 있는 버스를 "0정류장 전 · 곧 도착"으로 줘서,
 * 기점 정류장 화면에 출발도 안 한 버스가 "정류장 도착"으로 보였다. 정류장 이름이 노선 기점과 같으면 "출발 대기"로 표시.
 * 기점과 종점 이름이 같은 노선(회차)은 그 정류장에 닿는 버스일 수도 있어 그대로 둔다. 웹 busdata.js atOrigin 과 같은 규칙.
 */
object OriginWait {
    private val bracket = Regex("""\[[^\]]*]|\([^)]*\)""")
    private val space = Regex("""[\s.·]""")
    private val tail = Regex("(종점|기점|정류장|정류소|가상)$")

    /** 괄호 안·띄어쓰기·끝의 종점/기점 표시를 뺀 이름 */
    fun norm(s: String?): String = (s ?: "").replace(bracket, "").replace(space, "").replace(tail, "")

    fun atOrigin(stopName: String?, startName: String?, endName: String?): Boolean {
        val s = norm(stopName)
        val a = norm(startName)
        return s.isNotEmpty() && s == a && a != norm(endName)
    }

    fun mark(stopName: String?, startName: String?, endName: String?, arrivals: List<BusArrival>): List<BusArrival> =
        if (!atOrigin(stopName, startName, endName)) arrivals
        else arrivals.map { if (it.prevStops == 0 && !it.estimated && !it.scheduled) it.copy(waiting = true) else it }
}
