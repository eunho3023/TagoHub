package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.sin

/**
 * 홈 위쪽에 보이는 지금 날씨·미세먼지 (공공데이터포털 키 = TAGO_SERVICE_KEY).
 *  - 날씨: 기상청 초단기실황 (기온·강수 형태, 5km 격자)
 *  - 미세먼지: 에어코리아 측정소정보로 가장 가까운 측정소(30km 안)의 실시간 값. 못 받으면 내 위치가 든 시·도 측정소들의 가운데 값
 */
object HomeWeather {
    data class Now(val temp: Double?, val rain: String?, val air: String?)

    /** 위경도 → 기상청 격자 (Lambert 정각원추, 기상청 안내 계산식) */
    fun grid(lat: Double, lng: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val deg = PI / 180.0
        val slat1 = 30.0 * deg; val slat2 = 60.0 * deg; val olon = 126.0 * deg; val olat = 38.0 * deg
        var sn = tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        val ro = re * sf / tan(PI * 0.25 + olat * 0.5).pow(sn)
        val ra = re * sf / tan(PI * 0.25 + lat * deg * 0.5).pow(sn)
        var theta = lng * deg - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn
        return floor(ra * sin(theta) + 43 + 0.5).toInt() to floor(ro - ra * cos(theta) + 136 + 0.5).toInt()
    }

    /** 초단기실황 강수 형태 → 말 (없으면 null) */
    fun rainName(code: String?) = when (code?.trim()) { "1" -> "비"; "2" -> "비·눈"; "3" -> "눈"; "5" -> "빗방울"; "6" -> "빗방울·눈날림"; "7" -> "눈날림"; else -> null }

    /** 미세먼지(PM10)·초미세먼지(PM2.5) 중 나쁜 쪽 등급 (환경부 기준) */
    fun airGrade(pm10: Double?, pm25: Double?): String? {
        fun g10(v: Double) = when { v <= 30 -> 1; v <= 80 -> 2; v <= 150 -> 3; else -> 4 }
        fun g25(v: Double) = when { v <= 15 -> 1; v <= 35 -> 2; v <= 75 -> 3; else -> 4 }
        val g = listOfNotNull(pm10?.let(::g10), pm25?.let(::g25)).maxOrNull() ?: return null
        return listOf("좋음", "보통", "나쁨", "매우 나쁨")[g - 1]
    }

    private fun median(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    /** 시·도 실시간 측정 응답 → 가운데 값 등급 */
    fun parseAir(body: String?): String? {
        val rows = body?.let { AirportLive.rows(it) }.orEmpty()
        val num = { k: String -> rows.mapNotNull { it[k]?.toString()?.toDoubleOrNull() } }
        return airGrade(median(num("pm10Value")), median(num("pm25Value")))
    }

    data class Station(val name: String, val lat: Double, val lng: Double)

    /** 측정소 목록(getMsrstnList) → 측정소들. dmX·dmY 중 33~39 인 쪽을 위도로 */
    fun parseStations(body: String?): List<Station> = body?.let { AirportLive.rows(it) }.orEmpty().mapNotNull { r ->
        val name = r["stationName"]?.toString()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
        val x = r["dmX"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
        val y = r["dmY"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
        val (lat, lng) = if (x in 33.0..39.0) x to y else y to x
        if (lat !in 33.0..39.0 || lng !in 124.0..132.0) null else Station(name, lat, lng)
    }

    /** 가장 가까운 측정소 (30km 안) */
    fun nearestStation(list: List<Station>, lat: Double, lng: Double): Station? =
        list.minByOrNull { com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat, it.lng) }
            ?.takeIf { com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat, it.lng) < 30_000 }

    /** 측정소 실시간(getMsrstnAcctoRltmMesureDnsty) → 맨 앞(가장 최근) 값 등급 */
    fun parseStationAir(body: String?): String? {
        val r = body?.let { AirportLive.rows(it) }.orEmpty().firstOrNull { it["pm10Value"]?.toString()?.toDoubleOrNull() != null || it["pm25Value"]?.toString()?.toDoubleOrNull() != null }
            ?: return null
        return airGrade(r["pm10Value"]?.toString()?.toDoubleOrNull(), r["pm25Value"]?.toString()?.toDoubleOrNull())
    }

    /** 초단기실황 응답 → (기온, 강수) */
    fun parseNow(body: String?): Pair<Double?, String?> {
        val rows = body?.let { AirportLive.rows(it) }.orEmpty()
        val v = { c: String -> rows.firstOrNull { it["category"]?.toString() == c }?.get("obsrValue")?.toString() }
        return v("T1H")?.toDoubleOrNull() to rainName(v("PTY"))
    }

    /** 에어코리아 시·도 이름과 대략의 범위 (남, 서, 북, 동). 겹치면 작은 쪽 */
    private val SIDO = listOf(
        "서울" to doubleArrayOf(37.41, 126.76, 37.72, 127.19), "부산" to doubleArrayOf(34.88, 128.76, 35.40, 129.32),
        "대구" to doubleArrayOf(35.60, 128.35, 36.02, 128.77), "인천" to doubleArrayOf(37.00, 124.60, 37.98, 126.80),
        "광주" to doubleArrayOf(35.05, 126.64, 35.26, 127.02), "대전" to doubleArrayOf(36.18, 127.24, 36.50, 127.56),
        "울산" to doubleArrayOf(35.32, 128.96, 35.73, 129.47), "세종" to doubleArrayOf(36.40, 127.17, 36.74, 127.40),
        "경기" to doubleArrayOf(36.89, 126.37, 38.29, 127.86), "강원" to doubleArrayOf(37.02, 127.08, 38.62, 129.37),
        "충북" to doubleArrayOf(36.00, 127.27, 37.27, 128.66), "충남" to doubleArrayOf(35.97, 125.90, 37.07, 127.65),
        "전북" to doubleArrayOf(35.28, 126.00, 36.16, 127.92), "전남" to doubleArrayOf(33.90, 125.00, 35.50, 127.90),
        "경북" to doubleArrayOf(35.57, 127.80, 37.55, 130.00), "경남" to doubleArrayOf(34.46, 127.57, 35.90, 129.23),
        "제주" to doubleArrayOf(33.10, 126.10, 33.60, 127.00),
    )

    fun sidoOf(lat: Double, lng: Double): String? = SIDO.filter { (_, b) -> lat in b[0]..b[2] && lng in b[1]..b[3] }
        .minByOrNull { (_, b) -> (b[2] - b[0]) * (b[3] - b[1]) }?.first

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
    private var at = 0L
    private var key: String? = null
    private var last: Now? = null

    private fun key(): String {
        val raw = com.eunho.tagohub.BuildConfig.TAGO_SERVICE_KEY.trim()
        return if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
    }

    private var stationsAt = 0L
    private var stations: List<Station> = emptyList()

    /** 측정소 목록 (하루 기억, 못 받으면 1시간 뒤 다시) */
    private fun stationList(): List<Station> {
        synchronized(this) { if (System.currentTimeMillis() - stationsAt < (if (stations.isEmpty()) 3_600_000L else 86_400_000L)) return stations }
        val got = parseStations(get("https://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList?serviceKey=${key()}&returnType=json&numOfRows=1000&pageNo=1"))
        synchronized(this) { stationsAt = System.currentTimeMillis(); if (got.isNotEmpty()) stations = got }
        return stations
    }

    /** 가장 가까운 측정소 값 등급 (못 받으면 null) */
    private fun nearestAir(lat: Double, lng: Double): String? {
        val st = nearestStation(stationList(), lat, lng) ?: return null
        val url = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("serviceKey", key()).addQueryParameter("returnType", "json").addQueryParameter("numOfRows", "3")
            .addQueryParameter("pageNo", "1").addQueryParameter("stationName", st.name).addQueryParameter("dataTerm", "DAILY").addQueryParameter("ver", "1.3").build()
        return parseStationAir(get(url.toString()))
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** 지금 날씨·미세먼지 (10분 기억, 같은 격자·시·도일 때) */
    suspend fun now(lat: Double, lng: Double): Now? = withContext(Dispatchers.IO) {
        val (nx, ny) = grid(lat, lng)
        val sido = sidoOf(lat, lng)
        val k = "%.2f,%.2f".format(lat, lng)
        synchronized(this@HomeWeather) { if (k == key && System.currentTimeMillis() - at < 10 * 60_000) return@withContext last }
        // 실황은 매시 정시 자료가 40분쯤 뒤에 나옴
        val base = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(40)
        coroutineScope {
            val w = async {
                parseNow(get("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst?serviceKey=${key()}&numOfRows=20&pageNo=1&dataType=JSON" +
                    "&base_date=${base.format(DateTimeFormatter.BASIC_ISO_DATE)}&base_time=${base.format(DateTimeFormatter.ofPattern("HH"))}00&nx=$nx&ny=$ny"))
            }
            val a = async {
                nearestAir(lat, lng) ?: sido?.let {
                    val url = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty".toHttpUrl().newBuilder()
                        .addEncodedQueryParameter("serviceKey", key()).addQueryParameter("returnType", "json").addQueryParameter("numOfRows", "200")
                        .addQueryParameter("pageNo", "1").addQueryParameter("sidoName", it).addQueryParameter("ver", "1.0").build()
                    parseAir(get(url.toString()))
                }
            }
            val (t, rain) = w.await()
            val now = Now(t, rain, a.await())
            if (now.temp == null && now.air == null) null
            else now.also { synchronized(this@HomeWeather) { key = k; at = System.currentTimeMillis(); last = it } }
        }
    }

    /** 홈에 보일 두 줄: "7° 비" / "미세먼지 좋음" */
    fun lines(n: Now): Pair<String?, String?> =
        listOfNotNull(n.temp?.let { "${Math.round(it)}°" }, n.rain).joinToString(" ").ifBlank { null } to n.air?.let { "미세먼지 $it" }
}
