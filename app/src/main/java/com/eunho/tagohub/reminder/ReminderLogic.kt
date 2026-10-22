package com.eunho.tagohub.reminder

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min

/**
 * 막차·출근 알림의 시각 계산 (순수 함수라 테스트할 수 있음).
 * "분" 은 그날 자정부터의 분이고, 25:10(=다음 날 01:10) 같은 24시 넘는 표기도 그대로 쓴다.
 */
object ReminderLogic {
    /**
     * [fireMinutes] (운행일 자정 기준 분) 에 울릴 알림의 다음 시각: [now] 이후 가장 가까운 것.
     * 운행일은 어제부터 일주일 뒤까지 살펴서 새벽 막차(어제 운행일의 연장)도 잡는다.
     * [weekdaysOnly] 면 운행일이 월~금인 날만 (토·일 운행일은 건너뜀).
     */
    fun nextFire(now: ZonedDateTime, fireMinutes: Int, weekdaysOnly: Boolean = false): ZonedDateTime {
        val zone: ZoneId = now.zone
        for (offset in -1..8) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (weekdaysOnly && (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY)) continue
            val t = day.atStartOfDay(zone).plusMinutes(fireMinutes.toLong())
            if (t.isAfter(now.plusSeconds(5))) return t
        }
        return now.plusDays(1) // 닿지 않음(방어용)
    }

    /** 막차 알림을 울릴 시각(분): 막차 [lastMinutes] 의 [leadMin] 분 전 */
    fun lastBusFireMinutes(lastMinutes: Int, leadMin: Int): Int = lastMinutes - leadMin

    fun clock(minutes: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60)

    fun lastBusText(routeNo: String, leadMin: Int, lastMinutes: Int): String =
        "${routeNo}번 막차가 약 ${leadMin}분 뒤입니다 (기점 ${clock(lastMinutes)} 출발). 정류장에 따라 시각이 다를 수 있으니 서두르세요."

    /** 출근 알림의 다음 행동 */
    sealed class Decision {
        /** 곧 나가야 함: [inMinutes] 분 뒤 출발 (0 이면 지금) */
        data class Notify(val inMinutes: Int) : Decision()

        /** 아직 일러서 [minuteOfDay] 에 다시 확인 */
        data class CheckAt(val minuteOfDay: Int) : Decision()

        /** 이미 늦음: 나가야 할 시각에서 [minutesLate] 분 지남 */
        data class Late(val minutesLate: Int) : Decision()
    }

    const val BUFFER_MIN = 5          // 현관에서 정류장까지 등 여유
    const val NOTIFY_WINDOW_MIN = 5   // 출발 5분 전부터는 알림
    const val RECHECK_MAX_MIN = 20    // 이보다 먼 시각이면 이 간격으로 다시 확인(소요시간이 바뀔 수 있어서)
    const val LATE_AFTER_MIN = 15     // 출발 시각을 이만큼 넘기면 "늦음"

    /**
     * [nowMin] 지금, [arriveByMin] 도착해야 하는 시각, [travelMin] 지금 기준 걸리는 시간으로 다음 행동을 정한다.
     * 나갈 시각 = 도착 시각 - 소요시간 - 여유([BUFFER_MIN]).
     */
    fun decide(nowMin: Int, arriveByMin: Int, travelMin: Int): Decision {
        val leaveBy = arriveByMin - travelMin - BUFFER_MIN
        val gap = leaveBy - nowMin
        return when {
            gap > NOTIFY_WINDOW_MIN -> Decision.CheckAt(if (gap <= RECHECK_MAX_MIN) leaveBy - 2 else nowMin + min(RECHECK_MAX_MIN, gap - NOTIFY_WINDOW_MIN))
            gap >= -LATE_AFTER_MIN -> Decision.Notify(max(0, gap))
            else -> Decision.Late(-gap)
        }
    }

    /** 출근 알림의 첫 확인 시각(분): 도착 시각의 [FIRST_CHECK_AHEAD_MIN] 분 전 (자정 앞으로 넘어가지 않게 0 이상) */
    const val FIRST_CHECK_AHEAD_MIN = 150

    fun firstCheckMinutes(arriveByMin: Int): Int = max(0, arriveByMin - FIRST_CHECK_AHEAD_MIN)

    fun departText(inMinutes: Int, travelMin: Int, arriveByMin: Int): String = when {
        inMinutes <= 0 -> "지금 나가야 해요. 소요 약 ${travelMin}분, ${clock(arriveByMin)}까지 도착"
        else -> "${inMinutes}분 뒤에 나가세요. 소요 약 ${travelMin}분, ${clock(arriveByMin)}까지 도착"
    }

    fun lateText(minutesLate: Int, travelMin: Int, arriveByMin: Int): String =
        "이미 나갈 시각이 ${minutesLate}분 지났어요. 지금 나가면 소요 약 ${travelMin}분 (${clock(arriveByMin)} 도착 목표)"
}
