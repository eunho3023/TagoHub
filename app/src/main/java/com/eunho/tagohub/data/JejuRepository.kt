package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoXml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 제주 버스정보 OPEN API (제주_BIS_Open-API_Guide.xlsx). JSON, 인증키 없음 */
interface JejuApi {
    @GET("searchBusRouteList.do")
    suspend fun routes(): String

    @GET("searchBusRouteStationList.do")
    suspend fun routeStations(@Query("route_id") routeId: String): String

    @GET("searchArrivalInfoList.do")
    suspend fun arrivals(@Query("station_id") stationId: String): String

    @GET("searchRouteByStationList.do")
    suspend fun stopRoutes(@Query("station_id") stationId: String): String

    /** 전체 버스 위치 (노선 구분 없이 한 번에) */
    @GET("searchBusAllocList.do")
    suspend fun allBuses(): String
}

/** 정류소 좌표 보충용 (OpenAPI 정의서 Ver2.0 의 Station, XML). 내장 DB 에 없는 새 정류장만 */
interface JejuOpenApi {
    @GET("Station")
    suspend fun stations(@Query("pageNo") page: Int, @Query("numOfRows") rows: Int): String
}

/**
 * httpApi 가 연결에 실패하면 httpsApi 로 다시 시도하고, 되는 쪽을 기억한다.
 * (서버가 https 로만 제대로 답하거나 http→https 로 넘기는 경우 대비)
 */
/** http·https 둘 다 실패했을 때 각각의 원인 */
class JejuBothFailed(val http: Throwable, val https: Throwable) : IOException("http·https 모두 실패")

class JejuRepository(
    private val httpApi: JejuApi,
    private val httpsApi: JejuApi,
    private val open: JejuOpenApi,
    private val trust: com.eunho.tagohub.core.AiaTrustManager? = null,
) {
    @Volatile private var preferHttps = false

    private suspend fun call(block: suspend (JejuApi) -> String): String {
        val first = if (preferHttps) httpsApi else httpApi
        val second = if (preferHttps) httpApi else httpsApi
        return try {
            block(first)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w("TagoMap", "제주 API ${if (preferHttps) "https" else "http"} 실패 → 반대쪽으로 재시도: $e")
            try {
                block(second).also { preferHttps = !preferHttps }
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: IOException) {
                if (preferHttps) throw JejuBothFailed(e2, e) else throw JejuBothFailed(e, e2)
            }
        }
    }

    private val lock = Mutex()
    private var routeCache: List<BusRoute>? = null
    private var routeCacheAt = 0L
    private val stationRows = HashMap<String, List<Row>>()
    private var allBuses: Pair<Long, List<Row>>? = null
    private var openCoords: Map<String, LatLng>? = null

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> = try {
        TagoResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TagoApiException) {
        TagoResult.Error(e.message ?: "제주 버스 API 오류")
    } catch (e: JejuBothFailed) {
        Log.w("TagoMap", "제주 버스 API http: ${e.http} / https: ${e.https} / ${trust?.lastReport}")
        fun d(t: Throwable) = t.javaClass.simpleName + (t.message?.let { " ($it)" } ?: "")
        TagoResult.Error(
            "제주 버스 API 연결 실패\n" +
                "· http: ${d(e.http)}\n" +
                "· https: ${d(e.https)}" +
                (trust?.lastReport?.let { "\n· 인증서: $it" } ?: "")
        )
    } catch (e: javax.net.ssl.SSLHandshakeException) {
        Log.w("TagoMap", "제주 버스 API 인증서 오류", e)
        TagoResult.Error("제주 버스 서버 보안 인증서 확인 실패: ${e.message.orEmpty()}" + (trust?.lastReport?.let { "\n· 인증서: $it" } ?: ""))
    } catch (e: IOException) {
        // 실제 원인을 그대로 보여준다 (예: SocketTimeoutException, CLEARTEXT not permitted, Connection reset)
        Log.w("TagoMap", "제주 버스 API 연결 실패", e)
        TagoResult.Error("제주 버스 API 연결 실패: ${e.javaClass.simpleName}${e.message?.let { " ($it)" }.orEmpty()}")
    } catch (e: retrofit2.HttpException) {
        Log.w("TagoMap", "제주 버스 API HTTP ${e.code()}", e)
        TagoResult.Error("제주 버스 API 응답 오류: HTTP ${e.code()}")
    } catch (e: Exception) {
        Log.w("TagoMap", "제주 버스 API 오류", e)
        TagoResult.Error("제주 버스 API 오류: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun rows(body: String, key: String): List<Row> = MiniJson.findRows(MiniJson.parse(body), key)

    /** 전체 노선 (10분 캐시) */
    suspend fun routes() = run {
        lock.withLock {
            val now = System.currentTimeMillis()
            routeCache?.takeIf { now - routeCacheAt < 600_000 }
                ?: StaticCache.routes("jeju_all")?.also { routeCache = it; routeCacheAt = now } // 휴대폰에 저장된 목록 (7일)
                ?: rows(call { it.routes() }, "ROUTE_ID").map(JejuMappers::route)
                    .distinctBy { it.routeId }
                    .also { routeCache = it; routeCacheAt = now; StaticCache.putRoutes("jeju_all", it) }
        }
    }

    suspend fun routeInfo(routeTagoId: String): BusRoute? =
        (routes() as? TagoResult.Success)?.data?.firstOrNull { it.routeId == routeTagoId }

    suspend fun arrivals(stopTagoId: String) = run {
        rows(call { it.arrivals(Jeju.std(stopTagoId)) }, "ROUTE_ID").map(JejuMappers::arrival).sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
    }

    suspend fun stopRoutes(stopTagoId: String) = run {
        rows(call { it.stopRoutes(Jeju.std(stopTagoId)) }, "ROUTE_ID").map(JejuMappers::route).distinctBy { it.routeId }
    }

    /** 노선 정류장 (정류장 목록은 바뀌지 않아 캐시). 좌표: 내장 DB → 없으면 OpenAPI Station */
    suspend fun routeStops(routeTagoId: String) = run {
        val r = synchronized(stationRows) { stationRows[routeTagoId] }
            ?: rows(call { it.routeStations(Jeju.std(routeTagoId)) }, "STATION_ID").also { synchronized(stationRows) { stationRows[routeTagoId] = it } }
        val ids = r.mapNotNull { it.str("STATION_ID")?.let(Jeju::tago) }
        val db = StopDb.peek()
        val coords = HashMap<String, LatLng>()
        if (db != null) coords.putAll(withContext(Dispatchers.IO) { db.coords(ids) })
        if (ids.any { it !in coords }) coords.putAll(openApiCoords().filterKeys { it !in coords })
        JejuMappers.routeStops(r) { coords[it] }
    }

    /** 이 노선 버스 위치 (실시간 GPS). 전체 버스 응답은 여러 노선이 7초 동안 같이 쓴다 */
    suspend fun buses(routeTagoId: String, stops: List<RouteStop>) = run {
        val now = System.currentTimeMillis()
        val all = lock.withLock {
            allBuses?.takeIf { now - it.first < 7_000 }?.second
                ?: rows(call { it.allBuses() }, "ROUTE_ID").also { allBuses = now to it }
        }
        JejuMappers.buses(all, Jeju.std(routeTagoId), stops)
    }

    private suspend fun openApiCoords(): Map<String, LatLng> {
        openCoords?.let { return it }
        val out = HashMap<String, LatLng>()
        runCatching {
            for (page in 1..6) {
                val p = TagoXml.parse(open.stations(page, 1000))
                p.rows.forEach { r ->
                    val id = r.str("stationId") ?: return@forEach
                    val x = r.double("localX") ?: return@forEach
                    val y = r.double("localY") ?: return@forEach
                    out[Jeju.tago(id)] = LatLng(y, x)
                }
                if (out.size >= p.totalCount || p.rows.isEmpty()) break
            }
        }.onFailure { Log.w("TagoMap", "제주 정류소 좌표 보충 실패: ${it.message}") }
        if (out.isNotEmpty()) openCoords = out
        return out
    }

    companion object {
        /** 브라우저에서는 되는데 앱에서 끊기는 경우가 많아(서버 방화벽이 비브라우저 요청 차단) 브라우저처럼 보낸다 */
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun create(): JejuRepository {
            // 제주 서버 인증서에 중간 인증서가 빠져 있어 안드로이드 기본 검증이 실패함
            // ("Trust anchor for certification path not found") → 브라우저처럼 AIA 로 보충해서 검증
            // 안드로이드 공식 방식: 서버 이름을 함께 넘기는 검증 (도메인별 보안 설정이 있는 앱은 이것만 허용)
            val system = com.eunho.tagohub.core.AiaTrustManager.defaultTrustManager()
            val ext = android.net.http.X509TrustManagerExtensions(system)
            val trust = com.eunho.tagohub.core.AiaTrustManager(system, verify = { chain, authType, host ->
                if (host != null) ext.checkServerTrusted(chain, authType, host) else system.checkServerTrusted(chain, authType)
            })
            val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trust)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    chain.proceed(
                        req.newBuilder()
                            .header("User-Agent", UA)
                            .header("Accept", "application/json, text/plain, */*")
                            .header("Referer", "${req.url.scheme}://${req.url.host}/")
                            .build()
                    )
                }
                .build()
            fun retrofit(base: String) = Retrofit.Builder().baseUrl(base).client(client)
                .addConverterFactory(ScalarsConverterFactory.create()).build()
            return JejuRepository(
                retrofit("http://bus.jeju.go.kr/api/").create(JejuApi::class.java),
                retrofit("https://bus.jeju.go.kr/api/").create(JejuApi::class.java),
                retrofit("http://busopen.jeju.go.kr/OpenAPI/service/bis/").create(JejuOpenApi::class.java),
                trust,
            )
        }
    }
}
