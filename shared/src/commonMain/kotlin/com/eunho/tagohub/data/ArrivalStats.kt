package com.eunho.tagohub.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 지난 운행 기록 계산 (폰·웹·PC 공용). 기록을 저장·불러오는 일은 각 플랫폼이 하고([ArrivalLog]), 여기는 계산만.
 * 버스가 정류장을 옮겨 간 것으로 도착 시각을 매기고([hits]), 같은 요일 기록을 시각별로 묶어 예상 시간표를 만든다([table]).
 */
object ArrivalStats {
    /** 한 번에 이보다 많이 건너뛴 이동은 기록하지 않음 (오래 못 받은 사이 생긴 큰 이동은 시각이 부정확) */
    const val MAX_STEP_STOPS = 6
    /** 직전 위치와 이만큼 넘게 떨어진 응답은 기록하지 않음 */
    const val MAX_GAP_MS = 4 * 60_000L
    /** 같은 운행으로 묶는 시각 차이(분) */
    const val CLUSTER_MIN = 5
    /** 이 수 이상의 날에 기록된 운행만 다음 차 어림에 씀 (하루 기록은 우연일 수 있음) */
    const val MIN_DAYS = 2
    /** 이보다 먼 다음 차는 보여 주지 않음 (분) */
    const val MAX_AHEAD_MIN = 120

    /** 한국 시각 (1988년 뒤로 서머타임 없음 → 고정 +9시간. 웹에서 시간대 자료를 따로 받지 않아도 됨) */
    val SEOUL: TimeZone = UtcOffset(hours = 9).asTimeZone()

    /** 정류장에 도착한 한 번 */
    data class Hit(val nodeId: String, val epochSec: Long)

    /** 시간표 한 줄: 예상(가운데 값)·가장 빠른·가장 늦은 시각(하루 중 분)과 기록된 날 수 */
    data class Slot(val expected: Int, val earliest: Int, val latest: Int, val days: Int)

    /** 이 요일에 기록이 있는 날 수와 시간표 */
    data class Table(val days: Int, val slots: List<Slot>)

    /**
     * 직전 응답([prevOrds], [prevAtMs])에서 이번 응답([buses], [nowMs])까지 차량이 정류장 순번 a → b 로 옮겨 갔으면,
     * a 다음부터 b 까지의 정류장에 도착한 시각을 두 응답 사이에서 순번 비율로 나눠 매긴다.
     */
    fun hits(prevOrds: Map<String, Int>, prevAtMs: Long, buses: List<BusLocation>, nowMs: Long, stops: List<RouteStop>): List<Hit> {
        if (nowMs - prevAtMs > MAX_GAP_MS || nowMs <= prevAtMs) return emptyList()
        val byOrd = stops.associateBy { it.ord }
        val out = ArrayList<Hit>()
        for (b in buses) {
            val to = b.ord ?: continue
            val from = prevOrds[b.vehicleNo ?: continue] ?: continue
            val step = to - from
            if (step <= 0 || step > MAX_STEP_STOPS) continue
            for (k in 1..step) {
                val s = byOrd[from + k] ?: continue
                val at = prevAtMs + (nowMs - prevAtMs) * k / step
                out += Hit(s.nodeId, at / 1000)
            }
        }
        return out
    }

    /**
     * [nodeId] 정류장의 [isoDay] 요일(월 1 … 일 7) 기록을 시각별로 묶는다. 같은 날의 같은 운행이 두 번 기록되면(정류장이 노선에 두 번) 한 번으로.
     * 묶음 안 시각이 [CLUSTER_MIN] 분 넘게 벌어지면 다음 운행으로 본다.
     */
    @OptIn(ExperimentalTime::class)
    fun table(hits: List<Hit>, nodeId: String, isoDay: Int, zone: TimeZone = SEOUL): Table {
        val times = hits.filter { it.nodeId == nodeId }.map { Instant.fromEpochSeconds(it.epochSec).toLocalDateTime(zone) }
            .filter { it.dayOfWeek.isoDayNumber == isoDay }
        if (times.isEmpty()) return Table(0, emptyList())
        val rows = times.map { it.date to (it.hour * 60 + it.minute) }.distinct().sortedBy { it.second }
        val groups = ArrayList<MutableList<Pair<LocalDate, Int>>>()
        for (r in rows) {
            val g = groups.lastOrNull()
            if (g != null && r.second - g.first().second <= CLUSTER_MIN && g.none { it.first == r.first }) g += r
            else groups += mutableListOf(r)
        }
        val slots = groups.map { g ->
            val m = g.map { it.second }.sorted()
            Slot(m[m.size / 2], m.first(), m.last(), g.map { it.first }.distinct().size)
        }
        return Table(times.map { it.date }.distinct().size, slots)
    }

    /**
     * [afterMin](오늘 하루 중 분) 뒤로 기록상 처음 오는 운행의 예상 시각(분). 여러 날 기록된 운행만, [MAX_AHEAD_MIN] 안에서. 없으면 null.
     */
    fun nextAfter(table: Table, afterMin: Int, nowMin: Int): Int? =
        table.slots.filter { it.days >= MIN_DAYS && it.expected > afterMin && it.expected - nowMin <= MAX_AHEAD_MIN }
            .minByOrNull { it.expected }?.expected
}
