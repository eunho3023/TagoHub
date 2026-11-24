package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.Row
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class KlidBusRegion(val stdgCode: String, val name: String)
data class KlidBusPosition(
    val id: String,
    val routeNo: String,
    val vehicleNo: String,
    val latitude: Double,
    val longitude: Double,
    /** 위치를 보낸 시각 (gthrDt, epoch ms). 모르면 null — 지금 안 다니는 차의 며칠 전 마지막 위치도 함께 와서 이걸로 거름 */
    val atMs: Long? = null,
    /** 노선 종류 (노선 기준정보 rteType: 간선버스·지선버스 …) */
    val routeType: String? = null,
    /** 기점·종점 (노선 기준정보) — 눌러서 노선을 열 때 같은 번호 중 고르는 데 씀 */
    val startName: String? = null,
    val endName: String? = null,
)

/** 노선 기준정보 한 줄 (위치 자료의 rteNo 가 비어 있어 rteId 로 번호·종류를 채움, 2026-11-24 울산 2,675대 모두 비어 있었음) */
data class KlidRoute(val routeNo: String, val routeType: String?, val startName: String?, val endName: String?)

internal data class KlidBusPage(val rows: List<Row>, val totalCount: Int)

class KlidBusRepository(
    private val serviceKey: String = BuildConfig.KLID_BUS_KEY.trim(),
    private val client: OkHttpClient = httpClient,
) {
    @Volatile
    private var cachedRegions: List<KlidBusRegion>? = null

    fun regions(): List<KlidBusRegion> {
        cachedRegions?.let { return it }
        val regions = fetchAll("mst_info")
            .mapNotNull { row ->
                val code = row.text("stdgCd")
                val name = row.text("lclgvNm")
                if (code.isBlank() || name.isBlank()) null else KlidBusRegion(code, name)
            }
            .distinctBy { it.stdgCode }
        if (regions.isEmpty()) throw IOException("지역별 노선 기준정보가 비어 있습니다.")
        cachedRegions = regions
        return regions
    }

    /**
     * 법정동 코드(10자리)로 초정밀 버스 제공 지역 찾기: 시군구(앞 5자리+00000) → 시도(앞 2자리+00000000) 순으로 1건만 물어봄.
     * mst_info 는 stdgCd 가 필수라 지역 코드 없이 전체 목록을 받으려 하면 HTTP 400. 제공 안 하는 지역은 K3(데이터 없음) → null.
     */
    fun resolveByLegalCode(legalCode: String): KlidBusRegion? {
        val candidates = listOf(legalCode.take(5) + "00000", legalCode.take(2) + "00000000").distinct()
        for (code in candidates) {
            val page = request("mst_info", code, 1, rows = 1)
            if (page.totalCount > 0) return KlidBusRegion(code, page.rows.firstOrNull()?.text("lclgvNm").orEmpty().ifBlank { code })
        }
        return null
    }

    @Volatile
    private var routeCache: Pair<String, Map<String, KlidRoute>>? = null

    /** 이 지역 노선 기준정보 {rteId: 번호·종류·기점·종점} (지역마다 한 번 받아 둠) */
    fun routes(stdgCode: String): Map<String, KlidRoute> {
        routeCache?.takeIf { it.first == stdgCode }?.let { return it.second }
        val map = fetchAll("mst_info", stdgCode).mapNotNull { row ->
            val id = row.text("rteId").ifBlank { return@mapNotNull null }
            id to KlidRoute(row.text("rteNo"), row.text("rteType").ifBlank { null }, row.text("stpnt").ifBlank { null }, row.text("edpnt").ifBlank { null })
        }.toMap()
        routeCache = stdgCode to map
        return map
    }

    fun positions(stdgCode: String): List<KlidBusPosition> {
        val routes = runCatching { routes(stdgCode) }.getOrDefault(emptyMap())
        return fetchAll("rtm_loc_info", stdgCode).mapNotNull { row ->
            val lat = row.double("lat") ?: return@mapNotNull null
            val lng = row.double("lot") ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val routeId = row.text("rteId")
            val vehicleNo = row.text("vhclNo")
            val id = if (vehicleNo.isNotBlank()) "$stdgCode:$routeId:$vehicleNo" else "$stdgCode:$routeId:$lat:$lng"
            val r = routes[routeId]
            KlidBusPosition(id, row.text("rteNo").ifBlank { r?.routeNo.orEmpty() }, vehicleNo, lat, lng,
                atMs = gatheredAt(row.text("gthrDt")), routeType = r?.routeType, startName = r?.startName, endName = r?.endName)
        }.distinctBy { it.id }
    }

    private fun fetchAll(endpoint: String, stdgCode: String? = null): List<Row> {
        val first = request(endpoint, stdgCode, 1)
        val rows = first.rows.toMutableList()
        val pageCount = ceil(first.totalCount.toDouble() / PAGE_SIZE).toInt()
        for (page in 2..pageCount) rows += request(endpoint, stdgCode, page).rows
        return rows
    }

    private fun request(endpoint: String, stdgCode: String?, page: Int, rows: Int = PAGE_SIZE): KlidBusPage {
        if (serviceKey.isBlank()) throw IOException(com.eunho.tagohub.core.DevMode.msg("local.properties에 KLID_BUS_KEY 또는 TAGO_SERVICE_KEY를 설정하세요."))
        val encodedKey = if ('%' in serviceKey) serviceKey else URLEncoder.encode(serviceKey, Charsets.UTF_8.name())
        val url = "$BASE_URL$endpoint".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("serviceKey", encodedKey)
            .addQueryParameter("pageNo", page.toString())
            .addQueryParameter("numOfRows", rows.toString())
            .addQueryParameter("type", "json")
            .apply { stdgCode?.let { addQueryParameter("stdgCd", it) } }
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("초정밀 버스 API 응답이 비어 있습니다.")
            if (!response.isSuccessful) {
                throw IOException(KlidBusJson.httpError(response.code, text))
            }
            return KlidBusJson.parsePage(text, endpointRowKey(endpoint))
        }
    }

    private fun endpointRowKey(endpoint: String): String =
        when (endpoint) {
            "mst_info" -> "rteId"
            "rtm_loc_info" -> "rteId"
            else -> throw IllegalArgumentException("지원하지 않는 KLID API: $endpoint")
        }

    private companion object {
        const val BASE_URL = "https://apis.data.go.kr/B551982/rte/"
        /** "2026-11-24 21:00:34.099502" (한국 시각) → epoch ms. 못 읽으면 null */
        fun gatheredAt(s: String): Long? = runCatching {
            java.time.LocalDateTime.parse(s.trim().take(19).replace(' ', 'T')).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        }.getOrNull()
        // 한 번에 받는 개수: 1000 (2026-11-24 울산 위치·노선 기준정보 모두 1000 줄씩 받아짐. 100 씩이면 위치만 27번 물어 5초 갱신이 안 됨)
        const val PAGE_SIZE = 1000
        val httpClient: OkHttpClient = Http.builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

internal object KlidBusJson {
    fun httpError(status: Int, text: String): String {
        val root = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        val response = root?.get("OpenAPI_ServiceResponse") as? Map<*, *>
        val header = (response?.get("cmmMsgHeader") ?: root?.get("header")) as? Map<*, *>
        val reasonCode = header?.get("returnReasonCode")?.toString()
            ?: header?.get("resultCode")?.toString()
        val message = header?.get("returnAuthMsg")?.toString()
            ?: header?.get("errMsg")?.toString()
            ?: header?.get("resultMsg")?.toString()
        return if (!reasonCode.isNullOrBlank() || !message.isNullOrBlank()) {
            "초정밀 버스 API HTTP $status, 오류 코드 ${reasonCode ?: "미상"}: ${message ?: "오류 상세 없음"}"
        } else {
            "초정밀 버스 API HTTP $status: ${text.replace(Regex("\\s+"), " ").trim().take(240)}"
        }
    }

    fun parsePage(text: String, rowKey: String): KlidBusPage {
        val root = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (e: Exception) {
            throw IOException("초정밀 버스 API JSON을 해석하지 못했습니다.", e)
        }
            ?: throw IOException("초정밀 버스 API JSON 응답 형식이 올바르지 않습니다.")
        val header = root["header"] as? Map<*, *>
            ?: throw IOException("초정밀 버스 API 응답에 header가 없습니다.")
        val resultCode = header["resultCode"]?.toString().orEmpty()
        // K3 = 이 지역은 해당 데이터가 없음 (오류 아님) → 빈 결과
        if (resultCode == "K3") return KlidBusPage(emptyList(), 0)
        if (resultCode != "K0") {
            val message = header["resultMsg"]?.toString().orEmpty()
            throw IOException("초정밀 버스 API 오류 $resultCode: $message")
        }
        val body = root["body"] as? Map<*, *>
            ?: throw IOException("초정밀 버스 API 응답에 body가 없습니다.")
        val totalCount = body["totalCount"]?.toString()?.toIntOrNull()
            ?: throw IOException("초정밀 버스 API totalCount가 올바르지 않습니다.")
        return KlidBusPage(MiniJson.findRows(body, rowKey), totalCount)
    }
}
