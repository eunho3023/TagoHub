package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 기상청 CCTV 기반 도로날씨정보 (RoadWthrInfoService, 공공데이터포털).
 * 관측 지점 861곳(assets/kma_road_stations.json, 가이드 붙임1)의 CCTV 영상 분석 결과(날씨명·수집 시각).
 * 지점마다 데이터가 있을 때만 응답이 옴(없으면 NO_DATA).
 */
data class KmaRoadStation(val id: String, val route: String, val name: String)

data class KmaRoadWeather(val weather: String, val baseDate: String, val baseTime: String) {
    /** "14:35 기준" */
    val timeLabel: String
        get() = if (baseTime.length >= 4) "${baseTime.take(2)}:${baseTime.substring(2, 4)} 기준" else ""
}

object KmaRoadParser {
    fun stations(json: String): List<KmaRoadStation> =
        (MiniJson.parse(json) as? List<*>).orEmpty().mapNotNull { m ->
            val r = m as? Map<*, *> ?: return@mapNotNull null
            val id = r["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            KmaRoadStation(id, r["route"]?.toString().orEmpty(), r["name"]?.toString().orEmpty())
        }

    /** 응답 JSON → 날씨. NO_DATA(03)는 null, 그 밖의 오류는 예외 */
    fun weather(body: String): KmaRoadWeather? {
        val text = body.trim()
        if (!text.startsWith("{")) throw IOException("기상청 도로날씨 API가 JSON이 아닌 응답을 보냈습니다.")
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IOException("응답 형식이 올바르지 않습니다.")
        (root["OpenAPI_ServiceResponse"] as? Map<*, *>)?.let { e ->
            val h = e["cmmMsgHeader"] as? Map<*, *>
            throw IOException("기상청 도로날씨 API 오류 (${h?.get("returnReasonCode")}) ${h?.get("returnAuthMsg") ?: ""}")
        }
        val response = root["response"] as? Map<*, *> ?: throw IOException("응답 형식이 올바르지 않습니다.")
        val header = response["header"] as? Map<*, *>
        val code = header?.get("resultCode")?.toString()
        if (code == "03") return null
        if (code != "00") throw IOException("기상청 도로날씨 API 오류 ($code) ${header?.get("resultMsg") ?: ""}")
        val items = (response["body"] as? Map<*, *>)?.get("items") as? Map<*, *> ?: return null
        val first = when (val item = items["item"]) {
            is List<*> -> item.firstOrNull() as? Map<*, *>
            is Map<*, *> -> item
            else -> null
        } ?: return null
        val weather = first["weatherNm"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return KmaRoadWeather(weather, first["baseDate"]?.toString().orEmpty(), first["baseTime"]?.toString().orEmpty())
    }
}

class KmaRoadRepository(
    private val key: String = BuildConfig.KMA_ROAD_KEY.trim(),
    private val client: OkHttpClient = httpClient,
) {
    suspend fun weather(stationId: String): KmaRoadWeather? = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IOException("local.properties에 KMA_ROAD_KEY를 설정하세요. (기상청 도로날씨 인증키)")
        val encoded = if ('%' in key) key else URLEncoder.encode(key, Charsets.UTF_8.name())
        val url = "$BASE/getCctvStnRoadWthr".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("serviceKey", encoded)
            .addQueryParameter("numOfRows", "1")
            .addQueryParameter("pageNo", "1")
            .addQueryParameter("dataType", "JSON")
            .addQueryParameter("eqmtId", stationId)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("기상청 도로날씨 API HTTP ${r.code}")
            KmaRoadParser.weather(text)
        }
    }

    private companion object {
        const val BASE = "https://apis.data.go.kr/1360000/RoadWthrInfoService"
        val httpClient: OkHttpClient = Http.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
