package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 한국공항공사 노선별 소요시간·거리 (GET https://apis.data.go.kr/B551178/flight-routes/info, type=json).
 * 응답: ODP(출발)·ARP(도착) 공항 IATA · TOF(D 국내/I 국제) · KM(거리) · TIME(비행 소요 분). 한 번에 100행까지 → 약 8쪽(741건)을 한 번만 받아 둠.
 */
object AirRoutes {
    data class Route(val km: Int, val minutes: Int) {
        /** "1시간 5분" */
        fun time(): String {
            val h = minutes / 60
            val m = minutes % 60
            return when { h == 0 -> "${m}분"; m == 0 -> "${h}시간"; else -> "${h}시간 ${m}분" }
        }

        /** "비행 1시간 5분 · 449km" */
        fun text(): String = "비행 ${time()} · ${km}km"
    }

    fun parse(body: String): Map<Pair<String, String>, Route> = buildMap {
        for (m in AirportLive.rows(body)) {
            val from = m["ODP"]?.toString()?.trim().orEmpty()
            val to = m["ARP"]?.toString()?.trim().orEmpty()
            val km = m["KM"]?.toString()?.toDoubleOrNull()?.toInt()
            val min = m["TIME"]?.toString()?.toDoubleOrNull()?.toInt()
            if (from.isNotEmpty() && to.isNotEmpty() && km != null && min != null && min > 0) put(from to to, Route(km, min))
        }
    }

    /** [dep]→[arr] 노선. 표에 이 방향이 없으면 반대 방향(거리는 같고 시간만 다를 수 있음)을 씀 */
    fun find(table: Map<Pair<String, String>, Route>, dep: String, arr: String): Route? = table[dep to arr] ?: table[arr to dep]
}

object AirRoutesClient {
    private const val PAGE_ROWS = 100
    private const val MAX_PAGES = 12
    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    @Volatile private var table: Map<Pair<String, String>, AirRoutes.Route>? = null

    private suspend fun load(): Map<Pair<String, String>, AirRoutes.Route> = table ?: lock.withLock {
        table ?: withContext(Dispatchers.IO) {
            val raw = BuildConfig.KAC_API_KEY.trim()
            val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
            val out = HashMap<Pair<String, String>, AirRoutes.Route>()
            var seen = 0
            for (page in 1..MAX_PAGES) {
                val url = "https://apis.data.go.kr/B551178/flight-routes/info".toHttpUrl().newBuilder().addEncodedQueryParameter("serviceKey", key)
                    .addQueryParameter("type", "json").addQueryParameter("pageNo", page.toString()).addQueryParameter("numOfRows", PAGE_ROWS.toString()).build()
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                DataGoError.check(body, "한국공항공사 노선 소요시간")
                val rows = AirportLive.rows(body)
                out += AirRoutes.parse(body)
                seen += rows.size
                if (rows.isEmpty() || seen >= AirportLive.totalCount(body)) break
            }
            out.also { if (it.isNotEmpty()) table = it }
        }
    }

    /** 못 받으면 null (비행시간 표시만 빠짐) */
    suspend fun find(dep: String, arr: String): AirRoutes.Route? =
        runCatching { AirRoutes.find(load(), dep, arr) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
}
