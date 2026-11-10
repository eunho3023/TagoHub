package com.eunho.tagohub.ui.map

import com.eunho.tagohub.data.DayKind
import java.time.DayOfWeek

/**
 * 정류장 시간표 한 칸 — 몇 분에 어느 노선이 오는지.
 *
 * 웹 정류장 시간표(server/tago-proxy/public/js/bus.js 의 stopTable)와 같은 규칙을 쓴다.
 * 1) 지난 운행 기록을 먼저 쓴다. 같은 요일에 여러 날([StopBoard.MIN_DAYS]일 이상) 같은 무렵에 온 운행만.
 * 2) 기록이 하나도 없는 노선만 공식 배차시간표·첫차막차로 채우고 화면에서 `~` 로 표시한다.
 * 3) 시간대(00시·01시…)로 묶어 한 줄에 여러 시각을 보여 준다.
 */
data class BoardTime(
    val minute: Int,
    val routeId: String,
    val routeNo: String,
    val routeType: String?,
    val source: Source,
    /** 기록에서 가장 빨랐던 시각 (기록일 때만 의미) */
    val early: Int = minute,
    /** 기록에서 가장 늦었던 시각 */
    val late: Int = minute,
    /** 이 시각이 나온 기록 일수 */
    val days: Int = 0,
) {
    enum class Source {
        /** 지난 운행 기록 (여러 날 같은 무렵) */
        Recorded,

        /** 지자체 공식 배차시간표 + 이 정류장까지 걸리는 시간 */
        Official,

        /** 첫차·막차·배차간격으로 계산한 예상 */
        Planned,
    }

    /** 화면에서 `~` 를 붙이는지 (기록이 아니면 예상) */
    val estimated: Boolean get() = source != Source.Recorded
}

object StopBoard {
    /** 웹과 같은 문턱: 같은 요일에 이만큼 다른 날 기록이 있어야 시간표로 인정 */
    const val MIN_DAYS = 2

    /** 한 화면에서 계산할 노선 수 상한 (웹 stopTable 과 같은 15) */
    const val MAX_ROUTES = 15

    /** 월~일 → 앱의 요일 구분 (평일/토/일·공휴일) */
    fun dayKind(day: DayOfWeek): DayKind = when (day) {
        DayOfWeek.SATURDAY -> DayKind.Saturday
        DayOfWeek.SUNDAY -> DayKind.Holiday
        else -> DayKind.Weekday
    }

    /** 하루 중 분 → "08:10" (자정을 넘긴 시각은 24로 나눈 나머지) */
    fun clock(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 기록과 예상을 합친다. 기록이 있는 노선은 예상 시각을 섞지 않는다 (웹 stopTable 과 같음).
     * 같은 시각이면 노선 번호 순.
     */
    fun merge(recorded: List<BoardTime>, planned: List<BoardTime>): List<BoardTime> {
        val covered = recorded.map { it.routeId }.toSet()
        return (recorded + planned.filter { it.routeId !in covered })
            .sortedWith(compareBy({ it.minute }, { naturalKey(it.routeNo) }))
    }

    /** 00시·01시… 로 묶는다. 시각은 묶음 안에서 오름차순 */
    fun byHour(entries: List<BoardTime>): List<Pair<Int, List<BoardTime>>> =
        entries.groupBy { (it.minute / 60) % 24 }
            .toSortedMap()
            .map { (hour, list) -> hour to list.sortedWith(compareBy({ it.minute }, { naturalKey(it.routeNo) })) }

    /** 노선 번호 자연 정렬용 키: "2" < "10" < "100-1" */
    internal fun naturalKey(no: String): String =
        Regex("(\\d+)").replace(no) { m -> m.value.padStart(6, '0') }
}
