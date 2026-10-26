package com.eunho.tagohub.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 서울교통공사 지하철 혼잡도(assets/subway_congestion.tsv, pipeline/build_subway_congestion.py 가 만듦).
 * 1~8호선 역마다·방향(상선·하선·내선·외선)마다·요일(평일·토·일)마다 5시30분~0시30분 30분 단위(39칸) 혼잡도(%, 정원 대비)이다.
 * 실시간이 아니라 과거 평균(2026-06-30 기준 자료)이라 "보통 이 시간엔 이만큼 붐빈다"는 참고용.
 */
object SubwayCongestion {
    enum class Day(val code: String, val label: String) {
        Weekday("W", "평일"), Saturday("S", "토요일"), Sunday("U", "일요일");

        companion object {
            fun of(d: DayOfWeek): Day = when (d) {
                DayOfWeek.SATURDAY -> Saturday
                DayOfWeek.SUNDAY -> Sunday
                else -> Weekday
            }
        }
    }

    /** 방향 하나의 하루치: 30분 단위 39칸 (5:30 ~ 0:30). 값이 없는 칸은 -1 */
    class Series(val direction: String, val values: IntArray)

    private const val SLOTS = 39
    private const val FIRST_MINUTE = 5 * 60 + 30

    private var appContext: Context? = null
    private var table: Map<String, List<Series>> = emptyMap()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun key(day: Day, line: String, station: String) = "${day.code}|${line.trim()}|${StationName.norm(station)}"

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = appContext?.let { c -> runCatching { c.assets.open("subway_congestion.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }
            if (text != null) table = parse(text)
            loaded = true
        }
    }

    internal fun set(t: Map<String, List<Series>>) { synchronized(this) { table = t; loaded = true } }

    fun parse(text: String): Map<String, List<Series>> {
        val out = HashMap<String, MutableList<Series>>()
        text.lineSequence().forEach { line ->
            val f = line.split('\t')
            if (f.size < 5) return@forEach
            val day = Day.entries.firstOrNull { it.code == f[0] } ?: return@forEach
            val values = IntArray(SLOTS) { i -> f[4].split(',').getOrNull(i)?.toIntOrNull() ?: -1 }
            out.getOrPut(key(day, f[1], f[2])) { ArrayList() } += Series(f[3], values)
        }
        return out
    }

    /** 이 역·노선·요일의 방향별 혼잡도 (자료에 없으면 빈 목록) */
    fun forStation(day: Day, line: String, station: String): List<Series> {
        ensure()
        return table[key(day, line, station)].orEmpty()
    }

    /** 자료에 이 역이 있는 노선들 (요일은 평일 기준) */
    fun linesOf(station: String, candidates: List<String>): List<String> {
        ensure()
        return candidates.filter { table.containsKey(key(Day.Weekday, it, station)) }
    }

    /** [time] 이 들어 있는 30분 칸 번호 (영업시간 밖이면 가장 가까운 칸) */
    fun slotOf(time: LocalTime): Int {
        var m = time.hour * 60 + time.minute
        if (m < 60) m += 24 * 60                                  // 0시대는 전날 막차 쪽 칸(0:00, 0:30)
        else if (m < FIRST_MINUTE) return 0                       // 1시~5시30분 영업 전: 첫차 칸
        return ((m - FIRST_MINUTE) / 30).coerceIn(0, SLOTS - 1)
    }

    /** 칸 번호 → "05:30" */
    fun slotLabel(slot: Int): String {
        val m = (FIRST_MINUTE + slot * 30) % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 혼잡도(정원 대비 %) → 말. 공식 등급이 아니라 보기 쉽게 나눈 기준 */
    fun describe(percent: Int): String = when {
        percent < 0 -> "정보 없음"
        percent < 60 -> "여유"
        percent < 100 -> "보통"
        percent < 130 -> "다소 혼잡"
        percent < 150 -> "혼잡"
        else -> "매우 혼잡"
    }
}
