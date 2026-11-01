package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 서울 실시간 교차로 신호 (서울 교통빅데이터 플랫폼 T Data, C-ITS `v2xSignalPhaseTimingFusionCurrentInfo`, 키는 `SEOUL_SIGNAL_KEY`).
 *  · 교차로 하나(`itstId`)의 방향·종류별 신호 상태 `{방향}{종류}sgStatNm` 와 남은 시간 `{방향}{종류}sgRmdrCs` (1/10초 단위)
 *  · 응답에 교차로 이름·위치가 없어서, 「교차로 Map 정보」로 만든 표(assets/seoul_signals.tsv: ID·이름·위도·경도)로 지도에 찍음
 * 신호 상태 문구는 울산(한국지역정보개발원)과 같은 J2735 신호 단계라서 색 바꾸기는 [UlsanSignals.color] 를 같이 씀.
 * 서울시 안내: 호출 시점의 참고용 값이라 실제 신호와 차이가 있을 수 있음.
 */
object SeoulSignals {
    private const val URL = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/v2xSignalPhaseTimingFusionCurrentInfo/1.0"
    private const val FILE = "seoul_signals.tsv"

    /** 지도·창에서 서울 교차로를 울산 것과 구분하는 id 앞머리 */
    const val ID_PREFIX = "seoul:"

    /** 서울에만 있는 신호 종류: 버스·자전거 (울산은 뜻을 확인하지 못해 보여 주지 않음) */
    val EXTRA_KINDS = mapOf("Bs" to "버스", "Bc" to "자전거")

    /**
     * 남은 시간이 이 값(1/10초, 3600초) 이상이면 카운트다운이 없는 신호로 본다. 실제 호출(2026-11)에서 고정 주기 교차로 등이
     * 모든 방향에 36001(= 3600.1초)을 보내는 것을 확인했고, 이걸 그대로 보이면 "3601초"가 되어서 색만 보여 줌.
     */
    const val NO_COUNTDOWN = 36000.0

    private val FIELD = Regex("(nt|et|st|wt|ne|se|sw|nw)(Bs|Bc|Lt|Pd|St|Ut)sg(StatNm|RmdrCs)")

    /** 키가 있어야 서울 신호를 씀 (없으면 지도에 서울 교차로를 보이지 않음) */
    fun enabled(): Boolean = BuildConfig.SEOUL_SIGNAL_KEY.isNotBlank()

    fun isSeoulId(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun apiId(id: String): String = id.removePrefix(ID_PREFIX)

    /** 표(탭으로 나눈 "ID 이름 위도 경도") → 교차로 목록. 깨진 줄은 건너뜀. id 는 [ID_PREFIX] 가 붙음 */
    fun parseIntersections(text: String): List<SignalIntersection> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4) return@mapNotNull null
        val id = f[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SignalIntersection(
            id = ID_PREFIX + id,
            name = f[1].trim().ifBlank { "교차로" },
            lat = f[2].toDoubleOrNull() ?: return@mapNotNull null,
            lng = f[3].toDoubleOrNull() ?: return@mapNotNull null,
        )
    }.toList()

    @Volatile
    private var cached: List<SignalIntersection>? = null

    /** 앱에 든 서울 교차로 표 (한 번만 읽음). 못 읽으면 빈 목록 */
    fun intersections(context: Context): List<SignalIntersection> = cached ?: synchronized(this) {
        cached ?: runCatching {
            parseIntersections(context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrDefault(emptyList()).also { cached = it }
    }

    /** 응답 한 줄 모음(배열이거나 {…:[…]} 로 감싸진 것, 한 개짜리 객체도)을 줄 목록으로 */
    private fun rows(root: Any?): List<Map<*, *>> = when (root) {
        is List<*> -> root.filterIsInstance<Map<*, *>>()
        is Map<*, *> -> if (root.containsKey("itstId")) listOf(root) else root.values.firstNotNullOfOrNull { v -> rows(v).takeIf { it.isNotEmpty() } }.orEmpty()
        else -> emptyList()
    }

    /** 응답 → 지금 신호들(비어 있는 방향·종류는 뺌). 줄이 여럿이면 가장 늦게 보낸(trsmUtcTime) 줄. 방향·종류 순서로 정렬 */
    fun parsePhases(json: String): List<SignalPhase> = runCatching {
        val newest = rows(MiniJson.parse(json)).maxByOrNull { it["trsmUtcTime"]?.toString()?.toDoubleOrNull() ?: 0.0 } ?: return@runCatching emptyList()
        val states = HashMap<Pair<String, String>, String>()
        val remains = HashMap<Pair<String, String>, Int>()
        for ((k, v) in newest) {
            val f = FIELD.matchEntire(k?.toString() ?: continue) ?: continue
            val key = f.groupValues[1] to f.groupValues[2]
            val value = v?.toString()?.trim().orEmpty()
            if (value.isEmpty() || value == "null") continue
            if (f.groupValues[3] == "StatNm") states[key] = value
            else value.toDoubleOrNull()?.takeIf { it < NO_COUNTDOWN }?.let { remains[key] = (it * 100).toInt() }   // 1/10초 → 밀리초
        }
        val dirs = UlsanSignals.DIRECTIONS.keys.toList()
        val kinds = listOf("St", "Lt", "Pd", "Ut", "Bs", "Bc")
        states.keys.sortedWith(compareBy({ dirs.indexOf(it.first) }, { kinds.indexOf(it.second) }))
            .map { SignalPhase(it.first, it.second, UlsanSignals.color(states.getValue(it)), remains[it], states.getValue(it)) }
    }.getOrDefault(emptyList())

    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** 교차로 하나의 지금 신호. [id] 는 [SignalIntersection.id] (서울 표시 포함). 못 받으면 null */
    suspend fun phases(id: String): List<SignalPhase>? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        runCatching {
            val url = URL.toHttpUrl().newBuilder()
                .addQueryParameter("apikey", BuildConfig.SEOUL_SIGNAL_KEY.trim())
                .addQueryParameter("itstId", apiId(id))
                .addQueryParameter("type", "json").addQueryParameter("pageNo", "1").addQueryParameter("numOfRows", "5")
                .build()
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            if (body.isBlank()) null else parsePhases(body)
        }.getOrNull()
    }
}
