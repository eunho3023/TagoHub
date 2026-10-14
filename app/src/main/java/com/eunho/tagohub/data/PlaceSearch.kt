package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class Place2(val name: String, val address: String?, val lat: Double, val lng: Double)

/**
 * 장소 이름 검색 (카카오 로컬 API, 키워드로 장소 검색). 키는 같은 카카오 앱의 REST API 키(KAKAO_REST_KEY).
 * 키가 없거나 카카오맵 사용 설정이 꺼져 있으면 빈 결과 → 길찾기는 정류장 이름으로만 목적지를 고른다.
 */
object PlaceSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, near: LatLng?): List<Place2> = withContext(Dispatchers.IO) {
        val key = BuildConfig.KAKAO_REST_KEY.trim()
        if (key.isEmpty() || query.isBlank()) return@withContext emptyList()
        runCatching {
            val url = KakaoLocal.KEYWORD_URL.toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("size", "15")
                .apply {
                    // 지금 있는 곳을 중심으로 (정확도순이라 먼 곳도 나옴, 거리 정보만 붙음)
                    near?.let {
                        addQueryParameter("x", it.lng.toString())
                        addQueryParameter("y", it.lat.toString())
                    }
                }
                .build()
            val req = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@use emptyList()
                if (!res.isSuccessful) {
                    Log.w("TagoMap", "장소 검색 ${res.code}: ${KakaoLocal.error(body) ?: body.take(200)}")
                    return@use emptyList()
                }
                KakaoLocal.parse(body)
            }
        }.onFailure { Log.w("TagoMap", "장소 검색 실패: ${it.message}") }.getOrDefault(emptyList())
    }
}

/** 카카오맵 REST API 대중교통 경로 조회 (키는 KAKAO_REST_KEY, 카카오맵 사용 설정 ON 필요) */
object KakaoRoutes {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val available: Boolean get() = BuildConfig.KAKAO_REST_KEY.isNotBlank()

    /** 경로 없거나 실패하면 status 와 함께 빈 목록 (HTTP 오류는 "HTTP 403" 처럼) */
    suspend fun transit(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Pair<String, List<TripPlan>> = withContext(Dispatchers.IO) {
        val key = BuildConfig.KAKAO_REST_KEY.trim()
        if (key.isEmpty()) return@withContext "NO_KEY" to emptyList()
        runCatching {
            val url = KakaoTransit.URL.toHttpUrl().newBuilder()
                .addQueryParameter("start_x", fromLng.toString())
                .addQueryParameter("start_y", fromLat.toString())
                .addQueryParameter("end_x", toLng.toString())
                .addQueryParameter("end_y", toLat.toString())
                .build()
            client.newCall(Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    Log.w("TagoMap", "카카오 길찾기 ${res.code}: ${KakaoLocal.error(body) ?: body.take(200)}")
                    return@use "HTTP ${res.code}" to emptyList()
                }
                val r = KakaoTransit.parse(body)
                r.status to r.routes.mapNotNull(KakaoTransit::toPlan)
            }
        }.onFailure { Log.w("TagoMap", "카카오 길찾기 실패: ${it.message}") }.getOrDefault("ERROR" to emptyList())
    }
}

/** 카카오 키 점검용 REST 호출 (키워드 장소 검색 1건) — 응답 코드와 본문 그대로 */
object KakaoProbe {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun probe(key: String): KakaoDiag.Probe = withContext(Dispatchers.IO) {
        runCatching {
            val url = KakaoLocal.KEYWORD_URL.toHttpUrl().newBuilder().addQueryParameter("query", "서울역").addQueryParameter("size", "1").build()
            client.newCall(Request.Builder().url(url).header("Authorization", "KakaoAK ${key.trim()}").build()).execute().use { res ->
                KakaoDiag.Probe(res.code, res.body?.string())
            }
        }.getOrElse { KakaoDiag.Probe(null, it.message) }
    }
}

/** 카카오 REST: 대중교통 결과 원본(노선 모양용), 도보 경로, 좌표 → 이름 */
object KakaoExtra {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private fun key() = BuildConfig.KAKAO_REST_KEY.trim()
    val available: Boolean get() = key().isNotEmpty()

    private suspend fun get(url: okhttp3.HttpUrl): String? = withContext(Dispatchers.IO) {
        if (key().isEmpty()) return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).header("Authorization", "KakaoAK ${key()}").build()).execute().use { res ->
                val body = res.body?.string()
                if (!res.isSuccessful) {
                    Log.w("TagoMap", "카카오 ${url.encodedPath} ${res.code}: ${body?.let { KakaoLocal.error(it) } ?: ""}")
                    null
                } else body
            }
        }.onFailure { Log.w("TagoMap", "카카오 ${url.encodedPath} 실패: ${it.message}") }.getOrNull()
    }

    suspend fun transit(a: LatLng, b: LatLng): KakaoTransit.Result? = get(
        KakaoTransit.URL.toHttpUrl().newBuilder()
            .addQueryParameter("start_x", a.lng.toString()).addQueryParameter("start_y", a.lat.toString())
            .addQueryParameter("end_x", b.lng.toString()).addQueryParameter("end_y", b.lat.toString()).build()
    )?.let(KakaoTransit::parse)

    /** 도보 경로 좌표 (실패하면 null → 직선) */
    suspend fun walk(a: LatLng, b: LatLng): List<LatLng>? = get(
        KakaoWalk.URL.toHttpUrl().newBuilder()
            .addQueryParameter("start_x", a.lng.toString()).addQueryParameter("start_y", a.lat.toString())
            .addQueryParameter("end_x", b.lng.toString()).addQueryParameter("end_y", b.lat.toString()).build()
    )?.let(KakaoWalk::parse)?.takeIf { it.first == "OK" && it.second.size >= 2 }?.second

    /** 지도에서 꾹 누른 곳 이름: 건물 이름 → 도로명 주소 → 지번 주소 */
    suspend fun placeName(lat: Double, lng: Double): String? = get(
        KakaoLocal.COORD2ADDRESS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("x", lng.toString()).addQueryParameter("y", lat.toString()).build()
    )?.let(KakaoLocal::addressName)
}
