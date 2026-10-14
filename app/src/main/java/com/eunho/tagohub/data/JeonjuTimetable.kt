package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import java.time.DayOfWeek
import java.time.LocalDate

enum class DayKind(val label: String) {
    Weekday("평일"), Saturday("토요일"), Holiday("일요일·공휴일");

    companion object {
        fun today(): DayKind = when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> Saturday
            DayOfWeek.SUNDAY -> Holiday
            else -> Weekday
        }
    }
}

/** 한 번의 운행: 출발 시각(분) + 출발 정류장 순번 (노선 중간에서 시작하는 운행이 있음) */
data class CourseStart(val minute: Int, val startSeq: Int)

/** 노선 운행 정보 (첫차·막차, 주말 출발 시각) */
data class RouteSchedule(
    val firstMinute: Int?,
    val lastMinute: Int?,
    val saturday: List<Int>,
    val holiday: List<Int>,
    val note: String?,
)

/** 정류장 기준 노선별 예상 시각 */
data class RouteTimes(
    val routeId: String,
    val routeNo: String,
    val routeType: String?,
    val minutes: List<Int>,
    val startName: String? = null,
    val endName: String? = null,
    /** 노선 정보를 받아 계산까지 끝났는지 (전국 노선은 고를 때 계산) */
    val loaded: Boolean = true,
    /** 첫차·막차·배차간격으로 만든 예상 시각인지 (전주는 회차별 실제 출발시각) */
    val estimated: Boolean = false,
    /** TAGO 조회용 도시코드 */
    val cityCode: String? = null,
)

/**
 * 전주 시내버스 예상 시간표.
 * 운행별 출발 시각(nosun/bus_location_time_common) + 출발 정류장에서 이 정류장까지 걸리는 시간(노선 정류장 거리로 추정).
 * 토·일요일은 노선 정보의 satNlist / holiNlist (첫 정류장 출발 시각).
 */
object JeonjuTimetable {
    /** "600 " → 360, "2033" → 1233, "06:15" → 375 */
    fun minute(raw: String?): Int? {
        val d = raw?.filter(Char::isDigit) ?: return null
        if (d.length !in 3..4) return null
        val v = d.padStart(4, '0')
        val h = v.substring(0, 2).toInt()
        val m = v.substring(2, 4).toInt()
        return if (m < 60 && h < 30) h * 60 + m else null
    }

    fun courses(rows: List<Row>): List<CourseStart> = rows.mapNotNull { r ->
        CourseStart(minute(r.str("courseStime")) ?: return@mapNotNull null, r.int("startSeq") ?: 1)
    }.distinct().sortedBy { it.minute }

    /** " 06:15 07:02 08:10\n 09:02" → 분 목록 */
    fun parseList(text: String?): List<Int> =
        Regex("""(\d{1,2}):(\d{2})""").findAll(text.orEmpty())
            .map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            .distinct().sorted().toList()

    fun schedule(row: Row?): RouteSchedule = RouteSchedule(
        firstMinute = minute(row?.str("brtFirstTime", "brtFirsttime")),
        lastMinute = minute(row?.str("brtLastTime", "brtLasttime")),
        saturday = parseList(row?.str("satNlist")),
        holiday = parseList(row?.str("holiNlist")),
        note = row?.str("brtText")?.replace("\\n", " ")?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null },
    )

    /** 이 요일의 운행 목록 (주말은 첫 정류장 출발로 본다) */
    fun startsFor(day: DayKind, weekday: List<CourseStart>, schedule: RouteSchedule?): List<CourseStart> = when (day) {
        DayKind.Weekday -> weekday
        DayKind.Saturday -> schedule?.saturday?.takeIf { it.isNotEmpty() }?.map { CourseStart(it, 1) } ?: weekday
        DayKind.Holiday -> schedule?.holiday?.takeIf { it.isNotEmpty() }?.map { CourseStart(it, 1) } ?: weekday
    }

    /**
     * 정류장(순번 stopSeqs 중 하나, 순환 노선은 두 번 나올 수 있음)에 도착하는 예상 시각들.
     * 운행마다 출발 정류장 뒤에 처음 나오는 이 정류장까지의 소요시간을 더한다.
     */
    fun stopTimes(starts: List<CourseStart>, stopSeqs: List<Int>, offsetMinutes: (fromSeq: Int, toSeq: Int) -> Double): List<Int> =
        starts.mapNotNull { c ->
            val seq = stopSeqs.filter { it >= c.startSeq }.minOrNull() ?: return@mapNotNull null
            c.minute + Math.round(offsetMinutes(c.startSeq, seq)).toInt()
        }.sorted()

    /** "05:30" / "0530" → 330 */
    fun parseClock(t: String?): Int? {
        val m = Regex("(\\d{1,2}):?(\\d{2})").find(t ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        return if (h < 30 && mm < 60) h * 60 + mm else null
    }

    /**
     * 전국 노선 예상 출발 시각: 첫차부터 배차간격마다, 막차까지 (기점 출발).
     * 막차가 자정을 넘기면 다음 날로. 배차간격을 모르면 첫차·막차만.
     */
    fun fromInterval(first: Int, last: Int, interval: Int?, startSeq: Int = 1): List<CourseStart> {
        var end = last
        if (end < first) end += 1440
        if (interval == null || interval <= 0) {
            return listOf(first, end).distinct().map { CourseStart(it, startSeq) }
        }
        return generateSequence(first) { it + interval }.takeWhile { it <= end }.take(300)
            .map { CourseStart(it, startSeq) }.toList()
            .let { l -> if (l.last().minute != end && end - l.last().minute < interval) l + CourseStart(end, startSeq) else l }
    }

    /** 요일별 배차간격: 토요일 → 없으면 평일, 일요일·공휴일 → 없으면 토요일 → 평일 */
    fun intervalFor(day: DayKind, weekday: Int?, saturday: Int?, holiday: Int?): Int? = when (day) {
        DayKind.Weekday -> weekday
        DayKind.Saturday -> saturday ?: weekday
        DayKind.Holiday -> holiday ?: saturday ?: weekday
    }

    fun clock(m: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", (m / 60) % 24, m % 60)
}
