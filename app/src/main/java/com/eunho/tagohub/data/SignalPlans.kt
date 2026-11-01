package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 한 시간대의 신호 계획: 시작 시각(하루 중 분), 주기·옵셋(초), A링·B링 현시별 시간(초, 0인 뒤쪽은 뺌) */
data class PlanSlot(val planNo: Int, val startMin: Int, val cycleSec: Int, val offsetSec: Int, val ringA: List<Int>, val ringB: List<Int>) {
    val startLabel: String get() = "%02d:%02d".format(startMin / 60, startMin % 60)
}

/** 오늘 이 교차로의 신호 계획: 쓰는 계획 번호, 그 계획의 시간대들, 지금 시간대와 다음 시간대 */
data class TodayPlan(val planNo: Int, val holiday: Boolean, val slots: List<PlanSlot>, val current: PlanSlot?, val next: PlanSlot?)

/**
 * 교차로 신호 운영계획 (경찰청·도로교통공단 UTIC 「신호 개방 데이터」, 키는 UTIC_API_KEY, 하루 한 번 갱신).
 *  · getPlanCROPInfo: 계획 번호·시간대별 주기·옵셋·A/B링 1~8현시 시간
 *  · getPlanCRWDInfo: 요일 → 계획 번호,  getPlanCRHDInfo: 특수일(월·일) → 계획 번호
 * 지역코드(인천 L02, 대구 L29)와 교차로 이름으로 부르고 교차로 번호로 고른다. 교차로 위치는 앱에 든 표(assets/utic_signals.tsv).
 * 실시간 신호가 아니라 계획이라 감응·점멸 제어 등으로 실제와 다를 수 있고, 현시가 어느 방향인지는 이 자료에 없다.
 */
object SignalPlans {
    private const val BASE = "http://tsihub.utic.go.kr/tsi/api/PlanCrossRoadInfoService/"
    private const val FILE = "utic_signals.tsv"

    /** 지도·창에서 신호 계획 교차로를 다른 교차로와 구분하는 id 앞머리 ("utic:지역코드:교차로번호") */
    const val ID_PREFIX = "utic:"

    val REGIONS = mapOf("L02" to "인천", "L29" to "대구")

    fun isPlanId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** id → (지역코드, 교차로번호). 형식이 아니면 null */
    fun parts(id: String): Pair<String, String>? =
        id.removePrefix(ID_PREFIX).split(':').takeIf { isPlanId(id) && it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }

    /** 표("지역코드 교차로번호 이름 위도 경도", 탭) → 교차로 목록 */
    fun parseIntersections(text: String): List<SignalIntersection> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 5 || f[0].isBlank() || f[1].isBlank()) return@mapNotNull null
        SignalIntersection(
            id = "$ID_PREFIX${f[0].trim()}:${f[1].trim()}",
            name = f[2].trim().ifBlank { "교차로" },
            lat = f[3].toDoubleOrNull() ?: return@mapNotNull null,
            lng = f[4].toDoubleOrNull() ?: return@mapNotNull null,
        )
    }.toList()

    @Volatile
    private var cached: List<SignalIntersection>? = null

    fun intersections(context: Context): List<SignalIntersection> = cached ?: synchronized(this) {
        cached ?: runCatching {
            parseIntersections(context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrDefault(emptyList()).also { cached = it }
    }

    /** 응답(첫 칸은 머리, 그다음이 줄들) → 이 교차로 번호의 줄만 */
    fun rows(json: String, intNo: String): List<Map<*, *>> = runCatching {
        (MiniJson.parse(json) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().filter { it["INT_NO"]?.toString()?.trim() == intNo }
    }.getOrDefault(emptyList())

    private fun Map<*, *>.int(k: String): Int? = this[k]?.toString()?.trim()?.toDoubleOrNull()?.toInt()

    private fun ring(m: Map<*, *>, r: Char): List<Int> = (1..8).map { m.int("${r}_RING_${it}_PHASE_VAL") ?: 0 }.dropLastWhile { it <= 0 }

    fun parseSlots(json: String, intNo: String): List<PlanSlot> = rows(json, intNo).mapNotNull { m ->
        val plan = m.int("INT_PLAN_NO") ?: return@mapNotNull null
        val hh = m.int("OPER_PLAN_HH") ?: return@mapNotNull null
        val mi = m.int("OPER_PLAN_MI") ?: 0
        PlanSlot(plan, hh * 60 + mi, m.int("INT_OPER_CYCLE_VAL") ?: 0, m.int("INT_OPER_OFFSET_VAL") ?: 0, ring(m, 'A'), ring(m, 'B'))
    }.sortedWith(compareBy({ it.planNo }, { it.startMin }))

    /** 요일 코드 → 계획 번호 */
    fun parseWeekdays(json: String, intNo: String): Map<Int, Int> =
        rows(json, intNo).mapNotNull { m -> (m.int("PLAN_DY") ?: return@mapNotNull null) to (m.int("INT_PLAN_NO") ?: return@mapNotNull null) }.toMap()

    /** 특수일 (월, 일) → 계획 번호 (0 이나 빈 날짜는 뺌) */
    fun parseHolidays(json: String, intNo: String): Map<Pair<Int, Int>, Int> = rows(json, intNo).mapNotNull { m ->
        val mm = m.int("HOLY_PLAN_MM") ?: return@mapNotNull null
        val dd = m.int("HOLY_PLAN_DD") ?: return@mapNotNull null
        val plan = m.int("INT_PLAN_NO") ?: return@mapNotNull null
        if (mm !in 1..12 || dd !in 1..31 || plan <= 0) null else (mm to dd) to plan
    }.toMap()

    /**
     * 날짜 → 요일 코드. 명세서의 코드표는 1=월요일이지만, 실제 자료는 1=일요일 … 7=토요일이다
     * (2026-11 확인: 인천·대구 교차로에서 1번 요일의 계획이 특수일 계획과 같고, 2~6번이 같은 평일 계획).
     */
    fun dayCode(date: LocalDate): Int = date.dayOfWeek.value % 7 + 1

    /** 오늘 쓰는 계획 번호: 특수일이면 그 계획, 아니면 요일 계획. 없으면 null */
    fun planFor(date: LocalDate, weekdays: Map<Int, Int>, holidays: Map<Pair<Int, Int>, Int>): Pair<Int, Boolean>? =
        holidays[date.monthValue to date.dayOfMonth]?.let { it to true } ?: weekdays[dayCode(date)]?.takeIf { it > 0 }?.let { it to false }

    /** 그 계획의 시간대 중 지금(하루 중 분) 쓰는 것과 다음 것. 자정 뒤 첫 시간대 전이면 전날 마지막 시간대가 이어짐 */
    fun today(slots: List<PlanSlot>, planNo: Int, holiday: Boolean, minuteOfDay: Int): TodayPlan {
        val mine = slots.filter { it.planNo == planNo }.sortedBy { it.startMin }
        val current = mine.lastOrNull { it.startMin <= minuteOfDay } ?: mine.lastOrNull()
        val next = mine.firstOrNull { it.startMin > minuteOfDay }
        return TodayPlan(planNo, holiday, mine, current, next)
    }

    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun get(op: String, region: String, name: String): String {
        val raw = BuildConfig.UTIC_API_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val url = (BASE + op).toHttpUrl().newBuilder().addEncodedQueryParameter("serviceKey", key)
            .addQueryParameter("type", "json").addQueryParameter("srchCTId", region).addQueryParameter("srchCRNm", name)
            .addQueryParameter("pageNo", "1").addQueryParameter("numOfRows", "500").build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }

    /** 교차로의 오늘 신호 계획 (세 가지를 같이 받음). 못 받거나 계획이 없으면 null */
    suspend fun load(intersection: SignalIntersection, now: LocalDateTime = LocalDateTime.now()): TodayPlan? = withContext(Dispatchers.IO) {
        val (region, intNo) = parts(intersection.id) ?: return@withContext null
        if (BuildConfig.UTIC_API_KEY.isBlank()) return@withContext null
        runCatching {
            coroutineScope {
                val op = async { get("getPlanCROPInfo", region, intersection.name) }
                val wd = async { get("getPlanCRWDInfo", region, intersection.name) }
                val hd = async { get("getPlanCRHDInfo", region, intersection.name) }
                val slots = parseSlots(op.await(), intNo)
                val (plan, holiday) = planFor(now.toLocalDate(), parseWeekdays(wd.await(), intNo), parseHolidays(hd.await(), intNo))
                    ?: return@coroutineScope null
                today(slots, plan, holiday, now.hour * 60 + now.minute).takeIf { it.slots.isNotEmpty() }
            }
        }.getOrNull()
    }
}
