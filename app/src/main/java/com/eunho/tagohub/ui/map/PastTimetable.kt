package com.eunho.tagohub.ui.map

import com.eunho.tagohub.data.ArrivalStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 과거 시간표: 고른 요일의 지난 1·2·3주 날짜에 이 정류장을 지난 시각을 나란히 놓는다.
 *
 * 사진(참고 화면)의 "과거 시간표" 처럼 3주를 열로 비교한다. 기록은 최근 6주만 남으므로
 * 3주보다 더 과거는 보여 주지 않는다. 요일은 월~일 7개이고, 일요일은 화면에서 "휴일" 로 부른다.
 */
data class PastDay(
    /** "1주 전" */
    val label: String,
    val date: LocalDate,
    /** 그날 이 정류장을 지난 시각 (하루 중 분, 오름차순) */
    val minutes: List<Int>,
)

object PastTimetable {
    /** 한국 시각 (서머타임 없음) */
    private val KST: ZoneOffset = ZoneOffset.ofHours(9)

    /** 화면의 요일 이름 (월~일, 일요일은 휴일) */
    fun dayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "휴일"
    }

    /**
     * [today] 에서 거슬러 올라가 [day] 요일인 날짜 3개 (1주 전 → 3주 전).
     * 오늘이 그 요일이면 오늘은 빼고 지난주부터 센다 (오늘은 아직 끝나지 않았으므로).
     */
    fun weeks(day: DayOfWeek, today: LocalDate): List<LocalDate> {
        // 오늘이 그 요일이면 오늘은 빼고 지난주부터 (0 이면 7일 전)
        val back = ((today.dayOfWeek.value - day.value + 7) % 7).let { if (it == 0) 7 else it }
        return (1..3).map { today.minusDays(back.toLong() + 7L * (it - 1)) }
    }

    /** 기록 → 3주 열. 각 열은 그날 기록된 시각만 (같은 시각 중복은 한 번) */
    fun columns(
        hits: List<ArrivalStats.Hit>,
        nodeId: String,
        day: DayOfWeek,
        today: LocalDate = LocalDate.now(),
    ): List<PastDay> {
        val byDate = hits.asSequence()
            .filter { it.nodeId == nodeId }
            .map { Instant.ofEpochSecond(it.epochSec).atZone(KST).toLocalDateTime() }
            .groupBy { it.toLocalDate() }
        return weeks(day, today).mapIndexed { i, date ->
            val minutes = byDate[date].orEmpty()
                .map { it.hour * 60 + it.minute }
                .distinct()
                .sorted()
            PastDay("${i + 1}주 전", date, minutes)
        }
    }

    /** 세 열에 시각이 하나라도 있는지 */
    fun isEmpty(columns: List<PastDay>): Boolean = columns.all { it.minutes.isEmpty() }

    /** 시간대 칩 범위: 기록이 있는 시(hour)의 최소~최대. 없으면 null */
    fun hourRange(columns: List<PastDay>): IntRange? {
        val hours = columns.flatMap { it.minutes }.map { it / 60 }
        if (hours.isEmpty()) return null
        return hours.min()..hours.max()
    }

    /** "11.3.(화)" — 사진의 열 머리글과 같은 형식 */
    fun dateLabel(date: LocalDate): String {
        val day = when (date.dayOfWeek) {
            DayOfWeek.SUNDAY -> "일"
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
        }
        return "${date.monthValue}.${date.dayOfMonth}.($day)"
    }
}
