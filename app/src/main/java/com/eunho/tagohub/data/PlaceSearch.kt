package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Place2(val name: String, val address: String?, val lat: Double, val lng: Double,
    val category: String? = null, val phone: String? = null, val url: String? = null, val distance: Int? = null)

/**
 * 장소 이름 검색 (카카오 로컬 API, 키워드로 장소 검색). 키는 같은 카카오 앱의 REST API 키(KAKAO_REST_KEY).
 * 키가 없거나 카카오맵 사용 설정이 꺼져 있으면 빈 결과 → 길찾기는 정류장 이름으로만 목적지를 고른다.
 */
object PlaceSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val available: Boolean get() = BuildConfig.KAKAO_REST_KEY.isNotBlank()

    suspend fun search(query: String, near: LatLng?): List<Place2> =
        searchResult(query, near).getOrElse {
            Log.w("TagoMap", "장소 검색 실패: ${it.message}")
            emptyList()
        }

    suspend fun searchResult(query: String, near: LatLng?): Result<List<Place2>> = withContext(Dispatchers.IO) {
        val key = BuildConfig.KAKAO_REST_KEY.trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalStateException("카카오 REST API 키가 설정되지 않았습니다."))
        if (query.isBlank()) return@withContext Result.success(emptyList())
        try {
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
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val detail = KakaoLocal.error(body)
                    Result.failure(IllegalStateException("카카오 장소 검색 실패 (HTTP ${res.code})${detail?.let { ": $it" }.orEmpty()}"))
                } else {
                    Result.success(KakaoLocal.parse(body))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
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
    private val addressClient = client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val addressCache = android.util.LruCache<String, String>(128)
    private fun key() = BuildConfig.KAKAO_REST_KEY.trim()
    val available: Boolean get() = key().isNotEmpty()

    private suspend fun get(url: okhttp3.HttpUrl, http: OkHttpClient = client): String? = withContext(Dispatchers.IO) {
        if (key().isEmpty()) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).header("Authorization", "KakaoAK ${key()}").build()).execute().use { res ->
                val body = res.body?.string()
                if (!res.isSuccessful) {
                    Log.w("TagoMap", "카카오 ${url.encodedPath} ${res.code}: ${body?.let { KakaoLocal.error(it) } ?: ""}")
                    null
                } else body
            }
        }.onFailure { Log.w("TagoMap", "카카오 ${url.encodedPath} 실패: ${it.message}") }.getOrNull()
    }

    /** 좌표의 법정동 코드 10자리 (카카오 좌표로 행정구역) */
    suspend fun legalCode(lat: Double, lng: Double): String? = get(
        KakaoLocal.REGION_URL.toHttpUrl().newBuilder().addQueryParameter("x", lng.toString()).addQueryParameter("y", lat.toString()).build()
    )?.let(KakaoLocal::legalCode)

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

    /** 주소로 좌표 변환 */
    suspend fun address(q: String): List<Place2> = get(
        KakaoLocal.ADDRESS_URL.toHttpUrl().newBuilder().addQueryParameter("query", q).addQueryParameter("size", "5").build()
    )?.let(KakaoLocal::parseAddress).orEmpty()

    /** 카테고리로 장소 검색 (주변 1.5km, 가까운 순) */
    suspend fun category(code: String, lat: Double, lng: Double): List<Place2> = get(
        KakaoLocal.CATEGORY_URL.toHttpUrl().newBuilder().addQueryParameter("category_group_code", code)
            .addQueryParameter("x", lng.toString()).addQueryParameter("y", lat.toString())
            .addQueryParameter("radius", "1500").addQueryParameter("sort", "distance").addQueryParameter("size", "15").build()
    )?.let(KakaoLocal::parse).orEmpty()

    /** 자전거(bicycle=true)·도보 경로: (경로, 거리 m, 시간 초) */
    suspend fun ride(a: LatLng, b: LatLng, bicycle: Boolean): Triple<List<LatLng>, Int, Int>? {
        val body = get(
            (if (bicycle) KakaoWalk.BICYCLE_URL else KakaoWalk.URL).toHttpUrl().newBuilder()
                .addQueryParameter("start_x", a.lng.toString()).addQueryParameter("start_y", a.lat.toString())
                .addQueryParameter("end_x", b.lng.toString()).addQueryParameter("end_y", b.lat.toString()).build()
        ) ?: return null
        val (status, path) = KakaoWalk.parse(body)
        val (d, t) = KakaoWalk.totals(body) ?: return null
        return if (status == "OK" && path.size >= 2) Triple(path, d, t) else null
    }

    /** Kakao Mobility car route; uses the Kakao REST key and the same origin/destination coordinates. */
    suspend fun drive(a: LatLng, b: LatLng): Pair<String, KakaoDriveRoute?> = withContext(Dispatchers.IO) {
        val key = key()
        if (key.isEmpty()) return@withContext "NO_KEY" to null
        runCatching {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("apis-navi.kakaomobility.com")
                .addPathSegments("v1/directions")
                .addQueryParameter("origin", "${a.lng},${a.lat}")
                .addQueryParameter("destination", "${b.lng},${b.lat}")
                .addQueryParameter("priority", "RECOMMEND")
                .addQueryParameter("car_fuel", "GASOLINE")
                .addQueryParameter("car_hipass", "false")
                .addQueryParameter("alternatives", "false")
                .addQueryParameter("road_details", "false")
                .build()
            client.newCall(Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) "HTTP ${response.code}" to null
                else KakaoDrive.parse(body)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("TagoMap", "카카오 자동차 경로 요청 실패: ${it.message}")
        }
            .getOrElse { "ERROR" to null }
    }

    /** 지도에서 꾹 누른 곳 이름: 건물 이름 → 도로명 주소 → 지번 주소 */
    suspend fun placeName(lat: Double, lng: Double): String? {
        val cacheKey = "%.5f,%.5f".format(java.util.Locale.ROOT, lat, lng)
        addressCache.get(cacheKey)?.let { return it }
        val name = get(
            KakaoLocal.COORD2ADDRESS_URL.toHttpUrl().newBuilder()
                .addQueryParameter("x", lng.toString()).addQueryParameter("y", lat.toString()).build(),
            addressClient,
        )?.let(KakaoLocal::addressName)
        if (name != null) addressCache.put(cacheKey, name)
        return name
    }
}

/** 한국공항공사 국제선 운항 스케줄 (공공데이터포털 인증키 = TAGO_SERVICE_KEY) */
object KacIntlClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun fetch(dep: String, arr: String, date: java.time.LocalDate): List<KacIntl.Flight> = withContext(Dispatchers.IO) {
        val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val out = ArrayList<KacIntl.Flight>()
        for (page in 1..5) {
            val url = KacIntl.URL.toHttpUrl().newBuilder()
                .addEncodedQueryParameter("serviceKey", key)
                .addQueryParameter("type", "json").addQueryParameter("pageNo", page.toString()).addQueryParameter("numOfRows", "1000")
                .addQueryParameter("schDate", date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                .addQueryParameter("schDeptCityCode", dep).addQueryParameter("schArrvCityCode", arr)
                .build()
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            val got = KacIntl.parse(body)
            out += got
            val total = Regex("\"totalCount\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (got.isEmpty() || out.size >= total) break
        }
        out
    }

    /** dep→arr 의 date 운항편. 출발이 한국공항공사 공항이면 출발편(OUT), 아니면 도착편(IN) */
    suspend fun trips(dep: String, arr: String, date: java.time.LocalDate): com.eunho.tagohub.core.TagoResult<List<Trip>> = runCatching {
        if (dep == "ICN" || arr == "ICN") return@runCatching icn(dep, arr, date)
        val outbound = KacIntl.isKac(dep)
        if (!outbound && !KacIntl.isKac(arr)) error("출발·도착 중 하나는 인천·김포·김해·제주·청주·대구·무안·양양이어야 합니다")
        var rows = fetch(dep, arr, date)
        if (rows.isEmpty() && !outbound) rows = fetch(arr, dep, date)   // 도착편 조회 방식이 다를 때를 대비
        val mine = rows.filter { f -> if (outbound) f.out && f.airportCode == dep && f.cityCode == arr else !f.out && f.airportCode == arr && f.cityCode == dep }
        KacIntl.toTrips(KacIntl.forDate(mine, date), date)
    }.fold({ com.eunho.tagohub.core.TagoResult.Success(it) }, { com.eunho.tagohub.core.TagoResult.Error(it.message ?: "국제선 조회 실패") })

    /** 인천공항 실시간 운항 현황 (조회일 기준 -3일 ~ +6일) */
    private suspend fun icn(dep: String, arr: String, date: java.time.LocalDate): List<Trip> = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now()
        if (date.isBefore(today.minusDays(3)) || date.isAfter(today.plusDays(6))) error("인천공항은 오늘 기준 3일 전부터 6일 후까지만 조회됩니다")
        val departure = dep == "ICN"
        val other = if (departure) arr else dep
        val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val url = (IcnFlights.BASE + if (departure) "/getFltDeparturesDeOdp" else "/getFltArrivalsDeOdp").toHttpUrl().newBuilder()
            .addEncodedQueryParameter("serviceKey", key)
            .addQueryParameter("type", "json").addQueryParameter("numOfRows", "1000").addQueryParameter("pageNo", "1")
            .addQueryParameter("searchdtCode", "S").addQueryParameter("searchDate", date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
            .addQueryParameter("searchFrom", "0000").addQueryParameter("searchTo", "2400")
            .addQueryParameter("passengerOrCargo", "P").addQueryParameter("airportCode", other)
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        IcnFlights.toTrips(IcnFlights.parse(body).filter { it.airportCode == other }, departure, date)
    }
}

/** 공지사항 받기 (깃허브 notices.json) */
object NoticeClient {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    suspend fun load(): List<Notices.Notice> = withContext(Dispatchers.IO) {
        runCatching { client.newCall(Request.Builder().url(Notices.URL).build()).execute().use { Notices.parse(it.body?.string().orEmpty()) } }
            .getOrDefault(emptyList())
    }
}
