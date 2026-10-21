package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 한국도로공사 고속도로 공공데이터 포털(data.ex.co.kr) OpenAPI.
 * 목록·요청변수·출력변수는 assets/ex_catalog.json (pipeline/build_ex_catalog.py 가 사이트에서 모음).
 */
data class ExParam(val code: String, val label: String, val required: Boolean)

data class ExApi(
    val id: String,
    val service: String,
    val name: String,
    val url: String,
    val params: List<ExParam>,
    /** 출력변수 코드 → 한글 이름 */
    val outputs: Map<String, String>,
)

data class ExResult(val rows: List<Map<String, String>>, val total: Int?)

object ExCatalog {
    /** assets/ex_catalog.json 내용 → API 목록 */
    fun parse(json: String): List<ExApi> {
        val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"]?.toString()?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val req = (m["req"] as? List<*>).orEmpty().mapNotNull { r ->
                val s = r?.toString() ?: return@mapNotNull null
                val code = s.substringBefore(':')
                ExParam(code.removeSuffix("*"), s.substringAfter(':', ""), code.endsWith("*"))
            }
            val out = (m["out"] as? List<*>).orEmpty().mapNotNull { o ->
                val s = o?.toString() ?: return@mapNotNull null
                s.substringBefore(':') to s.substringAfter(':', "")
            }.toMap()
            ExApi(
                id = m["id"]?.toString().orEmpty(),
                service = m["svc"]?.toString().orEmpty().ifBlank { "기타" },
                name = m["name"]?.toString().orEmpty(),
                url = url,
                params = req,
                outputs = out,
            )
        }
    }

    /** 응답에 같이 오는 공통 항목 (카드에서는 숨김) */
    val META_FIELDS = setOf("code", "message", "count", "pageNo", "numOfRows", "pageSize", "pagingYn")
}

object ExParser {
    /** 응답 JSON → 첫 번째 배열(행 목록). code 가 SUCCESS 가 아니면 메시지와 함께 오류 */
    fun parse(body: String): ExResult {
        val text = body.trim()
        if (!text.startsWith("{")) {
            throw IOException("한국도로공사 API가 JSON이 아닌 응답을 보냈습니다. (필수 요청변수가 빠졌을 수 있어요)")
        }
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IOException("응답 형식이 올바르지 않습니다.")
        val code = root["code"]?.toString()
        val message = root["message"]?.toString().orEmpty()
        if (code != null && !code.equals("SUCCESS", ignoreCase = true)) {
            throw IOException(message.ifBlank { "한국도로공사 API 오류 ($code)" })
        }
        val rows = root.values.firstNotNullOfOrNull { v ->
            (v as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is Map<*, *> } }
        }.orEmpty().map { r ->
            (r as Map<*, *>).entries.mapNotNull { (k, v) ->
                val value = v?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                k.toString() to value
            }.toMap()
        }
        return ExResult(rows, root["count"]?.toString()?.toDoubleOrNull()?.toInt())
    }
}

class ExRepository(
    private val key: String = BuildConfig.EX_API_KEY.trim(),
    private val client: OkHttpClient = httpClient,
) {
    suspend fun call(api: ExApi, params: Map<String, String>, page: Int = 1, rows: Int = 30): ExResult =
        call(api.url, params, page, rows)

    suspend fun call(path: String, params: Map<String, String> = emptyMap(), page: Int = 1, rows: Int = 30): ExResult =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw IOException("local.properties에 EX_API_KEY를 설정하세요. (한국도로공사 인증키)")
            val url = (BASE + path).toHttpUrl().newBuilder().apply {
                addQueryParameter("key", key)
                addQueryParameter("type", "json")
                addQueryParameter("numOfRows", rows.toString())
                addQueryParameter("pageNo", page.toString())
                params.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v.trim()) }
            }.build()
            client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("한국도로공사 API HTTP ${r.code}")
                ExParser.parse(text)
            }
        }

    /**
     * 전체 목록 모으기. 이 API 들은 한 쪽에 최대 99건만 주고(요청 개수를 키워도 같음), 일부(IC 등)는 한 번에 다 줌.
     * → count 만큼 모일 때까지 쪽을 넘기되, 새 행이 없거나 [MAX_PAGES] 쪽이면 멈춤.
     */
    suspend fun all(path: String, params: Map<String, String> = emptyMap()): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        var page = 1
        while (page <= MAX_PAGES) {
            val res = call(path, params, page = page, rows = PAGE_ROWS)
            if (res.rows.isEmpty()) break
            out += res.rows
            val total = res.total ?: break
            if (out.size >= total || res.rows.size >= total) break
            page++
        }
        return out
    }

    private companion object {
        const val PAGE_ROWS = 99
        const val MAX_PAGES = 60
        const val BASE = "https://data.ex.co.kr"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}

/** 지도에 점으로 찍을 수 있는 한국도로공사 데이터 (휴게소·영업소·IC/JCT·전광표지판) */
object ExMapLayers {
    const val REST = "ex_rest"
    const val TOLL = "ex_toll"
    const val IC = "ex_ic"
    const val VMS = "ex_vms"
    val IDS = listOf(REST, TOLL, IC, VMS)

    private const val CACHE_MS = 10 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<ItsRoadFeature>>>()

    /** 큰 목록(수백~천 건)이라 10분 동안 메모리에 둠 */
    suspend fun features(layer: String, repo: ExRepository = ExRepository()): List<ItsRoadFeature> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[layer]?.takeIf { now - it.first < CACHE_MS }?.let { return it.second } }
        val list = when (layer) {
            REST -> rest(repo.all("/openapi/locationinfo/locationinfoRest"))
            TOLL -> toll(repo.all("/openapi/locationinfo/locationinfoUnit"))
            IC -> ic(repo.all("/openapi/locationinfo/locationinfoIc"))
            VMS -> vms(repo.all("/openapi/vms/vmsMessageSrch"))
            else -> emptyList()
        }
        synchronized(cache) { cache[layer] = now to list }
        return list
    }

    private fun lat(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 30.0..40.0 }
    private fun lng(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 123.0..133.0 }

    fun rest(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$REST:${r["stdRestCd"] ?: r["unitCode"]}",
            layer = REST,
            title = r["unitName"].orEmpty().ifBlank { "휴게소" },
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    fun toll(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$TOLL:${r["unitCode"]?.trim()}",
            layer = TOLL,
            title = r["unitName"].orEmpty().ifBlank { "영업소" } + " 영업소",
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    fun ic(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$IC:${r["icCode"]}",
            layer = IC,
            title = r["icName"].orEmpty().ifBlank { "IC·JCT" },
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    /** 전광표지판: 이 API 는 xCoord 가 위도, yCoord 가 경도 (이름과 반대) */
    fun vms(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["xCoord"]) ?: return@mapNotNull null
        val lng = lng(r["yCoord"]) ?: return@mapNotNull null
        val message = listOfNotNull(r["vmsMessage"], r["vmsMessage2"]).map { it.trim() }.filter { it.isNotEmpty() }
        ItsRoadFeature(
            id = "$VMS:${r["vmsId"]}",
            layer = VMS,
            title = message.firstOrNull() ?: "전광표지판",
            details = listOfNotNull(
                message.drop(1).takeIf { it.isNotEmpty() }?.joinToString("\n"),
                r["routeName"]?.let { n -> "노선: $n" + (r["updownType"]?.let { d -> if (d == "E") " (하행)" else " (상행)" }.orEmpty()) },
                r["centerName"],
            ).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }
}
