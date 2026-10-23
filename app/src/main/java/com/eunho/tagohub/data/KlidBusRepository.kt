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
)

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

    fun positions(stdgCode: String): List<KlidBusPosition> =
        fetchAll("rtm_loc_info", stdgCode).mapNotNull { row ->
            val lat = row.double("lat") ?: return@mapNotNull null
            val lng = row.double("lot") ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val routeId = row.text("rteId")
            val vehicleNo = row.text("vhclNo")
            val id = if (vehicleNo.isNotBlank()) "$stdgCode:$routeId:$vehicleNo" else "$stdgCode:$routeId:$lat:$lng"
            KlidBusPosition(id, row.text("rteNo"), vehicleNo, lat, lng)
        }.distinctBy { it.id }

    private fun fetchAll(endpoint: String, stdgCode: String? = null): List<Row> {
        val first = request(endpoint, stdgCode, 1)
        val rows = first.rows.toMutableList()
        val pageCount = ceil(first.totalCount.toDouble() / PAGE_SIZE).toInt()
        for (page in 2..pageCount) rows += request(endpoint, stdgCode, page).rows
        return rows
    }

    private fun request(endpoint: String, stdgCode: String?, page: Int, rows: Int = PAGE_SIZE): KlidBusPage {
        if (serviceKey.isBlank()) throw IOException("local.properties에 KLID_BUS_KEY 또는 TAGO_SERVICE_KEY를 설정하세요.")
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
        // 한 번에 받는 개수: 1000 은 허용 범위를 넘을 수 있어 100 씩 (여러 쪽으로 나눠 받음)
        const val PAGE_SIZE = 100
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
