package com.eunho.tagohub.data

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 서울시 버스 API (ws.bus.go.kr/api/rest). 변수명 대소문자 구분 */
interface SeoulBusApi {
    @GET("stationinfo/getStationByUid")
    suspend fun stationArrivals(@Query("arsId") arsId: String): String

    @GET("buspos/getBusPosByRtid")
    suspend fun busPositions(@Query("busRouteId") busRouteId: String): String

    @GET("busRouteInfo/getRouteInfo")
    suspend fun routeInfo(@Query("busRouteId") busRouteId: String): String

    @GET("busRouteInfo/getRoutePath")
    suspend fun routePath(@Query("busRouteId") busRouteId: String): String

    /** 노선 정류장 (회차지 여부 transYn 으로 방면 나누기) */
    @GET("busRouteInfo/getStaionByRoute")
    suspend fun stationsByRoute(@Query("busRouteId") busRouteId: String): String

    /** 대중교통 환승경로: 버스+지하철 / 지하철만 (좌표 WGS84) */
    @GET("pathinfo/getPathInfoByBusNSub")
    suspend fun pathBusSub(@Query("startX") sx: String, @Query("startY") sy: String, @Query("endX") ex: String, @Query("endY") ey: String): String

    @GET("pathinfo/getPathInfoBySubway")
    suspend fun pathSubway(@Query("startX") sx: String, @Query("startY") sy: String, @Query("endX") ex: String, @Query("endY") ey: String): String
}

class SeoulBusRepository(private val api: SeoulBusApi) {
    private val gate = Semaphore(4)
    private val infoCache = HashMap<String, BusRoute?>()
    private val pathCache = HashMap<String, List<LatLng>>()

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> = try {
        TagoResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TagoApiException) {
        TagoResult.Error(e.message ?: "서울 버스 API 오류")
    } catch (e: IOException) {
        TagoResult.Error("서울 버스 API 연결 실패: ${e.javaClass.simpleName}${e.message?.let { " ($it)" }.orEmpty()}")
    } catch (e: Exception) {
        TagoResult.Error("서울 버스 API 오류: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun rows(call: suspend () -> String): List<Row> = gate.withPermit { SeoulBusXml.parse(call()) }

    /** 정류장 도착 (정류장 번호 arsId 로) */
    suspend fun arrivals(arsId: String) = run {
        rows { api.stationArrivals(SeoulBus.ars(arsId)) }.flatMap(SeoulMappers2::arrivals).sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
    }

    /** 노선 버스 위치 (맵매칭 GPS) */
    suspend fun buses(routeAppId: String) = run { SeoulMappers2.buses(rows { api.busPositions(SeoulBus.std(routeAppId)) }) }

    /** 노선 정보: 첫차·막차·배차 (노선별 캐시) */
    suspend fun routeInfo(routeAppId: String) = run {
        synchronized(infoCache) { if (infoCache.containsKey(routeAppId)) return@run infoCache[routeAppId] }
        rows { api.routeInfo(SeoulBus.std(routeAppId)) }.firstOrNull()?.let(SeoulMappers2::routeInfo)
            .also { synchronized(infoCache) { infoCache[routeAppId] = it } }
    }

    /** 노선 정류장 (서울시 API, 상·하행 나눔). 앱 안 서울 노선 표에 없는 노선에 씀 */
    suspend fun routeStops(routeAppId: String) = run { SeoulMappers2.routeStops(rows { api.stationsByRoute(SeoulBus.std(routeAppId)) }) }

    /** 회차 정류장 (앱 정류장 ID) — 처음 한 번만 받고 저장. 없으면 null */
    suspend fun turnStation(routeAppId: String): String? {
        withContext(Dispatchers.IO) { StaticCache.seoulTurn(routeAppId) }?.let { return it.ifEmpty { null } }
        val r = run { SeoulMappers2.turnStation(rows { api.stationsByRoute(SeoulBus.std(routeAppId)) }) }
        if (r is TagoResult.Success) withContext(Dispatchers.IO) { StaticCache.putSeoulTurn(routeAppId, r.data) }
        return (r as? TagoResult.Success)?.data
    }

    suspend fun path(routeAppId: String) = run {
        synchronized(pathCache) { pathCache[routeAppId] } ?: SeoulMappers2.path(rows { api.routePath(SeoulBus.std(routeAppId)) })
            .also { if (it.size >= 2) synchronized(pathCache) { pathCache[routeAppId] = it } }
    }

    /** 서울시 경로검색: 버스+지하철과 지하철만 경로를 함께 받아 앱 길찾기 결과로 */
    suspend fun paths(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) = run {
        val a = listOf(fromLng, fromLat, toLng, toLat).map { "%.7f".format(java.util.Locale.US, it) }
        val both = runCatching { gate.withPermit { SeoulPathParser.parse(api.pathBusSub(a[0], a[1], a[2], a[3])) } }.getOrDefault(emptyList())
        val sub = runCatching { gate.withPermit { SeoulPathParser.parse(api.pathSubway(a[0], a[1], a[2], a[3])) } }.getOrDefault(emptyList())
        val db = StopDb.peek()
        val count: (String, String, String) -> Int? = { routeId, board, alight ->
            db?.routeStops(routeId)?.let { stops ->
                val i = stops.indexOfFirst { it.nodeId == board }
                val j = stops.indexOfFirst { it.nodeId == alight && stops.indexOf(it) > i }
                if (i >= 0 && j > i) j - i else null
            }
        }
        (both + sub).mapNotNull { SeoulPathParser.toPlan(it, fromLat, fromLng, toLat, toLng, count) }
            .distinctBy { p -> p.legs.joinToString("|") { it.routeNo + it.boardName } }
    }

    companion object {
        fun create(): SeoulBusRepository {
            val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
            val key = if ('%' in raw) raw else URLEncoder.encode(raw, "UTF-8")
            val client = Http.builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    chain.proceed(req.newBuilder().url(req.url.newBuilder().addEncodedQueryParameter("serviceKey", key).build()).build())
                }
                .build()
            return SeoulBusRepository(
                Retrofit.Builder().baseUrl("http://ws.bus.go.kr/api/rest/").client(client)
                    .addConverterFactory(ScalarsConverterFactory.create()).build().create(SeoulBusApi::class.java)
            )
        }
    }
}
