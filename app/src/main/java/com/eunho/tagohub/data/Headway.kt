package com.eunho.tagohub.data

/**
 * 도착정보가 한 대만 올 때 "다음 차"를 배차 간격으로 어림하는 규칙 (화면·네트워크와 따로 두어 시험하기 쉽게).
 * 정류장 화면은 한 노선에 버스 두 대를 보여 주는데, 버스 위치로도 둘째가 안 보이면 이 간격으로 채운다.
 */
object Headway {
    /** 이보다 긴 간격은 "다음 차"로 보여 주기엔 너무 멂 (하루 몇 번 다니는 농어촌 노선 등) */
    const val MAX_GAP_MIN = 120

    /**
     * 기점 출발 시각들([starts], 분)로 지금([nowMin]) 무렵의 배차 간격: 지금 앞뒤 1시간 30분 안의 이웃한 출발 사이 간격의 가운데 값.
     * 지금부터 앞으로 출발이 없으면(막차가 이미 떠남) null.
     */
    fun gapNear(starts: List<Int>, nowMin: Int): Int? {
        val s = starts.distinct().sorted()
        if (s.size < 2 || s.none { it >= nowMin - 5 }) return null
        val gaps = s.zipWithNext { a, b -> Triple(a, b, b - a) }.filter { (a, b, _) -> b >= nowMin - 90 && a <= nowMin + 90 }.map { it.third }
        val g = (gaps.ifEmpty { s.zipWithNext { a, b -> b - a } }).sorted()
        return g[g.size / 2].takeIf { it in 1..MAX_GAP_MIN }
    }

    /** 노선 정보의 배차간격([interval], 분). 첫차 전이나 막차가 떠난 뒤면 null (막차가 자정을 넘기면 다음 날로) */
    fun fromInterval(interval: Int, firstTime: String?, lastTime: String?, nowMin: Int): Int? {
        if (interval !in 1..MAX_GAP_MIN) return null
        val first = JeonjuTimetable.parseClock(firstTime)
        var last = JeonjuTimetable.parseClock(lastTime) ?: return interval
        var now = nowMin
        if (first != null && last < first) {
            last += 1440
            if (now < first) now += 1440
        }
        return interval.takeIf { now < last }
    }
}
