package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 공공기관 위치 자료 (타볼까 서버 /w/pub/..., server/tago-proxy/src/pubgeo.js): 카카오 대신
 * - 장소 이름 검색: 브이월드(국토교통부), 못 찾으면 서버가 카카오로 한 번 더
 * - 주소 → 좌표, 좌표 → 주소·법정동: 브이월드
 * - 주변 주유소: 오피넷(한국석유공사), 주변 주차장: 앱 주차장 자료(공공데이터)
 * 검색어·좌표를 받는 곳이 바뀌어 개인정보 처리방침 7일 전 공지(2026-11-23) 뒤 [FROM_MS](2026-11-30 0시, 한국 시간)부터 씀.
 * 서버 주소가 없거나 실패하면 null → 부르는 쪽이 예전처럼 카카오로.
 */
object PublicGeo {
    /** 2026-11-30T00:00:00+09:00 (웹 site/js/core.js PUB_FROM 과 같은 때) */
    const val FROM_MS = 1_795_964_400_000L

    fun on(now: Long = System.currentTimeMillis()): Boolean = now >= FROM_MS && DataProxy.current != null

    private val client by lazy { Http.builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }

    /** 꾹 누른 곳 이름·법정동 (좌표 → 주소) */
    data class Where(val name: String?, val legal: String?)

    private suspend fun get(path: String, vararg q: Pair<String, String?>): Map<*, *>? = withContext(Dispatchers.IO) {
        val base = DataProxy.current?.url?.trimEnd('/') ?: return@withContext null
        val url = "$base/w/pub/$path".toHttpUrl().newBuilder().apply { q.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }.build()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) { Log.w("TagoMap", "공공 위치 자료 $path ${r.code}"); null }
                else MiniJson.parse(r.body?.string().orEmpty()) as? Map<*, *>
            }
        }.onFailure { Log.w("TagoMap", "공공 위치 자료 $path 실패: ${it.message}") }.getOrNull()
    }

    private fun Any?.str(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun Any?.dbl(): Double? = this?.toString()?.toDoubleOrNull()

    /** 서버 장소 목록 → [Place2] */
    fun parsePlaces(root: Map<*, *>?, key: String = "places"): List<Place2> =
        (root?.get(key) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val name = m["name"].str() ?: return@mapNotNull null
            val lat = m["lat"].dbl() ?: return@mapNotNull null
            val lng = m["lng"].dbl() ?: return@mapNotNull null
            Place2(name, m["address"].str(), lat, lng, category = m["category"].str())
        }

    /** 주유소 목록 → [Place2] (분류 칸에 상표·휘발유 값) */
    fun parseGas(root: Map<*, *>?): List<Place2> =
        (root?.get("stations") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val name = m["name"].str() ?: return@mapNotNull null
            val lat = m["lat"].dbl() ?: return@mapNotNull null
            val lng = m["lng"].dbl() ?: return@mapNotNull null
            val price = m["price"].dbl()?.toInt()?.let { "휘발유 %,d원".format(java.util.Locale.ROOT, it) }
            Place2(name, null, lat, lng, category = listOfNotNull(m["brand"].str(), price).joinToString(" · ").ifEmpty { null },
                distance = m["dist"].dbl()?.toInt())
        }

    /** 장소 이름 검색 (null: 서버에 못 물음) */
    suspend fun places(q: String, near: LatLng?): List<Place2>? =
        get("place", "q" to q, "x" to near?.lng?.toString(), "y" to near?.lat?.toString())?.let { parsePlaces(it) }

    /** 주소 → 좌표 (null: 서버에 못 물음) */
    suspend fun addresses(q: String): List<Place2>? = get("address", "q" to q)?.let { parsePlaces(it) }

    /** 좌표 → 이름·법정동 (null: 서버에 못 물음) */
    suspend fun where(lat: Double, lng: Double): Where? = get("where", "lat" to "%.6f".format(java.util.Locale.ROOT, lat), "lng" to "%.6f".format(java.util.Locale.ROOT, lng))
        ?.let { Where(it["name"].str(), it["legal"].str()?.takeIf { c -> c.length == 10 && c.all(Char::isDigit) }) }

    /** 주변 주유소 1.5km 가까운 순 (null: 서버에 못 물음) */
    suspend fun gas(lat: Double, lng: Double): List<Place2>? =
        get("gas", "lat" to "%.5f".format(java.util.Locale.ROOT, lat), "lng" to "%.5f".format(java.util.Locale.ROOT, lng), "r" to "1500")?.let(::parseGas)

    /** 주변 주차장 1.5km (앱 주차장 자료: 공공데이터 표준데이터·한국교통안전공단), 가까운 순 */
    suspend fun parking(lat: Double, lng: Double): List<Place2> {
        val dLat = 1500 / 111_000.0
        val dLng = 1500 / (111_000.0 * cos(Math.toRadians(lat)))
        return Parking.features(lat - dLat, lng - dLng, lat + dLat, lng + dLng).map { f ->
            val d = hypot((f.lat - lat) * 111_000, (f.lng - lng) * 111_000 * cos(Math.toRadians(lat))).toInt()
            Place2(f.title, null, f.lat, f.lng, category = f.details.ifBlank { null }, distance = d)
        }.filter { (it.distance ?: 0) <= 1500 }.sortedBy { it.distance }
    }
}
