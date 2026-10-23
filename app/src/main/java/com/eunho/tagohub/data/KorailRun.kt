package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 한국철도공사 여객열차 운행정보 (공공데이터포털 B551457/run/v2/travelerTrainRunInfo2, 인증키는 TAGO 와 같은 키).
 * 지난 운행 실적이라 어제까지만 있다(오늘·미래는 비어 있음). 열차번호와 날짜 범위로 걸러 정차역마다 실제 도착·출발 시각을 받는다.
 * 요청: cond[run_ymd::GTE]·cond[run_ymd::LTE]·cond[trn_no::EQ] (열차번호는 5자리, 예 00001)
 */
object KorailRunClient {
    private const val URL = "https://apis.data.go.kr/B551457/run/v2/travelerTrainRunInfo2"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val ymd = DateTimeFormatter.BASIC_ISO_DATE

    /** [from]~[to] (포함) 동안 [trainNo] 열차가 선 역별 실제 시각 */
    suspend fun history(trainNo: String, from: LocalDate, to: LocalDate): List<RunStop> = withContext(Dispatchers.IO) {
        val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
        require(raw.isNotEmpty()) { "서비스키가 없습니다. local.properties 에 TAGO_SERVICE_KEY=... 를 추가하세요." }
        val key = if ('%' in raw) raw else URLEncoder.encode(raw, "UTF-8")
        val url = URL.toHttpUrl().newBuilder()
            .addEncodedQueryParameter("serviceKey", key)
            .addQueryParameter("pageNo", "1").addQueryParameter("numOfRows", "500")
            .addQueryParameter("cond[run_ymd::GTE]", from.format(ymd))
            .addQueryParameter("cond[run_ymd::LTE]", to.format(ymd))
            .addQueryParameter("cond[trn_no::EQ]", trainNo.padStart(5, '0'))
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        RunHistory.parse(body)
    }
}

/** 한 날 한 열차가 한 역에 선 실제 시각 (0시부터의 분, 없으면 null) */
data class RunStop(val day: LocalDate, val station: String, val arrivalMin: Int?, val departureMin: Int?)

/** 하루의 지연(분, 계획보다 늦으면 +): 출발역과 도착역 */
data class DayDelay(val day: LocalDate, val departure: Int?, val arrival: Int?)

data class RunSummary(val days: List<DayDelay>) {
    private val arrivals get() = days.mapNotNull { it.arrival }
    val average: Int? get() = arrivals.takeIf { it.isNotEmpty() }?.let { Math.round(it.average()).toInt() }
    val worst: Int? get() = arrivals.maxOrNull()
    /** 5분 이상 늦은 날 수 */
    val lateDays: Int get() = arrivals.count { it >= 5 }
}

object RunHistory {
    private fun minuteOfDay(text: String?): Int? {
        // "2026-10-22 05:14:00.0"
        if (text == null || text.length < 16) return null
        val h = text.substring(11, 13).toIntOrNull() ?: return null
        val m = text.substring(14, 16).toIntOrNull() ?: return null
        return h * 60 + m
    }

    fun parse(body: String): List<RunStop> {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>)?.get("items") as? Map<*, *> ?: return emptyList()
        val list = when (val item = items["item"]) {
            is List<*> -> item
            is Map<*, *> -> listOf(item)
            else -> emptyList<Any?>()
        }
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val day = (m["run_ymd"] as? String)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() } ?: return@mapNotNull null
            val name = (m["stn_nm"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) null else RunStop(day, name, minuteOfDay(m["trn_arvl_dt"] as? String), minuteOfDay(m["trn_dptre_dt"] as? String))
        }
    }

    /** 계획과 실제의 차(분). 자정을 걸쳐도 맞게 -12시간~+12시간 안으로 */
    internal fun delay(actualMin: Int?, plannedMin: Int?): Int? {
        if (actualMin == null || plannedMin == null) return null
        return Math.floorMod(actualMin - plannedMin + 720, 1440) - 720
    }

    /**
     * 날짜별 지연: [trip] 의 계획 출발·도착 시각(국토교통부)과 운행기록의 실제 시각을 출발역·도착역에서 비교.
     * 같은 날 기록이 없으면 그 날은 빠짐. 날짜 오름차순.
     */
    fun summarize(rows: List<RunStop>, trip: Trip): RunSummary {
        val dep = TrainTimetable.norm(trip.depName)
        val arr = TrainTimetable.norm(trip.arrName)
        val planDep = trip.dep?.let { it.hour * 60 + it.minute }
        val planArr = trip.arr?.let { it.hour * 60 + it.minute }
        val days = rows.groupBy { it.day }.toSortedMap().mapNotNull { (day, stops) ->
            val d = stops.firstOrNull { TrainTimetable.norm(it.station) == dep }
            val a = stops.firstOrNull { TrainTimetable.norm(it.station) == arr }
            if (d == null && a == null) return@mapNotNull null
            DayDelay(day, delay(d?.departureMin ?: d?.arrivalMin, planDep), delay(a?.arrivalMin ?: a?.departureMin, planArr))
        }
        return RunSummary(days)
    }
}
