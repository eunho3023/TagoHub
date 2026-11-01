package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 신호가 있는 교차로 한 곳 */
data class SignalIntersection(val id: String, val name: String, val lat: Double, val lng: Double)

/** 신호 색을 사람이 읽는 말로: 이름과 화면에서 쓸 색 구분 */
enum class SignalColor(val label: String) { Red("적색"), Yellow("황색"), Green("녹색"), YellowBlink("황색 점멸"), RedBlink("적색 점멸"), Off("정보 없음") }

/** 한 방향·한 신호(직진·좌회전 …)의 지금 상태와 남은 시간 */
data class SignalPhase(val direction: String, val kind: String, val color: SignalColor, val remainingMs: Int?, val rawState: String) {
    val directionLabel: String get() = UlsanSignals.DIRECTIONS[direction] ?: direction
    val kindLabel: String get() = UlsanSignals.KINDS[kind] ?: SeoulSignals.EXTRA_KINDS[kind] ?: kind
}

/**
 * 울산 실시간 교차로 신호 (한국지역정보개발원 `B551982/rti`, 키는 공공데이터포털 인증키).
 *  · crsrd_map_info?stdgCd=3100000000: 교차로 402곳(이름·위치)
 *  · tl_drct_info?stdgCd=3100000000: 교차로별·방향별 신호 상태 `{방향}{종류}sgSttsNm` 와 남은 시간 `{방향}{종류}sgRmndCs` (밀리초)
 * 서울·제주는 위치만 오고 신호 값은 없다(2026-11 확인). 호출 한 번에 울산 전체(약 400곳, 0.8MB)가 오고 교차로 하나만 따로 받을 수는 없다.
 */
object UlsanSignals {
    const val STDG_CD = "3100000000"
    private const val BASE = "https://apis.data.go.kr/B551982/rti/"

    /** 방향 코드 → 보여 줄 이름 (북·동·남·서·북동·남동·남서·북서) */
    val DIRECTIONS = linkedMapOf("nt" to "북쪽", "ne" to "북동쪽", "et" to "동쪽", "se" to "남동쪽", "st" to "남쪽", "sw" to "남서쪽", "wt" to "서쪽", "nw" to "북서쪽")

    /** 신호 종류: 직진·좌회전·보행자·유턴. (Bs·Bc 는 뜻을 확인하지 못해 보여 주지 않음) */
    val KINDS = linkedMapOf("St" to "직진", "Lt" to "좌회전", "Pd" to "보행자", "Ut" to "유턴")

    private val FIELD = Regex("(nt|et|st|wt|ne|se|sw|nw)(St|Lt|Pd|Ut)sg(SttsNm|RmndCs)")

    /** 응답의 상태 문구(J2735 신호 단계) → 색. 모르는 말은 정보 없음 */
    fun color(state: String): SignalColor = when (state.trim().lowercase()) {
        "stop-and-remain" -> SignalColor.Red
        "stop-then-proceed", "stop-then-processed" -> SignalColor.RedBlink
        "pre-movement" -> SignalColor.Red
        "protected-movement-allowed", "permissive-movement-allowed" -> SignalColor.Green
        "protected-clearance", "permissive-clearance" -> SignalColor.Yellow
        "caution-conflicting-traffic" -> SignalColor.YellowBlink
        else -> SignalColor.Off
    }

    private fun items(root: Any?): List<Map<*, *>> {
        val item = ((root as? Map<*, *>)?.get("body") as? Map<*, *>)?.let { (it["items"] as? Map<*, *>)?.get("item") }
        return when (item) {
            is List<*> -> item.filterIsInstance<Map<*, *>>()
            is Map<*, *> -> listOf(item)
            else -> emptyList()
        }
    }

    fun parseIntersections(json: String): List<SignalIntersection> = runCatching {
        items(MiniJson.parse(json)).mapNotNull { m ->
            SignalIntersection(
                id = m["crsrdId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                name = m["crsrdNm"]?.toString().orEmpty().ifBlank { "교차로" },
                lat = m["mapCtptIntLat"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
                lng = m["mapCtptIntLot"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
            )
        }
    }.getOrDefault(emptyList())

    /** 교차로 id → 그 교차로의 지금 신호들 (비어 있는 방향·종류는 뺌). 방향·종류 순서로 정렬 */
    fun parsePhases(json: String): Map<String, List<SignalPhase>> = runCatching {
        items(MiniJson.parse(json)).mapNotNull { m ->
            val id = m["crsrdId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val states = HashMap<Pair<String, String>, String>()
            val remains = HashMap<Pair<String, String>, Int>()
            for ((k, v) in m) {
                val f = FIELD.matchEntire(k?.toString() ?: continue) ?: continue
                val key = f.groupValues[1] to f.groupValues[2]
                val value = v?.toString()?.trim().orEmpty()
                if (value.isEmpty()) continue
                if (f.groupValues[3] == "SttsNm") states[key] = value else value.toDoubleOrNull()?.let { remains[key] = it.toInt() }
            }
            val order = DIRECTIONS.keys.toList()
            val kinds = KINDS.keys.toList()
            id to states.keys.sortedWith(compareBy({ order.indexOf(it.first) }, { kinds.indexOf(it.second) }))
                .map { SignalPhase(it.first, it.second, color(states.getValue(it)), remains[it], states.getValue(it)) }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 밀리초 → 보여 줄 초 (올림). 없거나 0 이하면 null */
    fun seconds(ms: Int?): Int? = ms?.takeIf { it > 0 }?.let { (it + 999) / 1000 }

    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(op: String): String {
        val raw = BuildConfig.KLID_BUS_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val url = (BASE + op).toHttpUrl().newBuilder().addEncodedQueryParameter("serviceKey", key)
            .addQueryParameter("type", "json").addQueryParameter("pageNo", "1").addQueryParameter("numOfRows", "1000")
            .addQueryParameter("stdgCd", STDG_CD).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
    }

    @Volatile
    private var cachedIntersections: List<SignalIntersection>? = null

    /** 울산 교차로 (한 번 받아 두고 계속 씀). 못 받으면 빈 목록 */
    suspend fun intersections(): List<SignalIntersection> = cachedIntersections ?: withContext(Dispatchers.IO) {
        runCatching { parseIntersections(get("crsrd_map_info")) }.getOrDefault(emptyList())
    }.also { if (it.isNotEmpty()) cachedIntersections = it }

    /** 지금 신호 상태 (울산 전체). 못 받으면 null */
    suspend fun phases(): Map<String, List<SignalPhase>>? = withContext(Dispatchers.IO) {
        runCatching { parsePhases(get("tl_drct_info")).takeIf { it.isNotEmpty() } }.getOrNull()
    }
}
