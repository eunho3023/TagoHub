package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoXml
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

/** 경기도 버스정보 GBIS v2. serviceKey·format=json 은 인터셉터가 붙인다 */
interface GyeonggiApi {
    @GET("busrouteservice/v2/getBusRouteListv2")
    suspend fun routes(@Query("keyword") keyword: String): String

    @GET("busrouteservice/v2/getBusRouteInfoItemv2")
    suspend fun routeInfo(@Query("routeId") routeId: String): String

    @GET("busrouteservice/v2/getBusRouteStationListv2")
    suspend fun routeStations(@Query("routeId") routeId: String): String

    @GET("busrouteservice/v2/getBusRouteLineListv2")
    suspend fun routeLine(@Query("routeId") routeId: String): String

    @GET("busstationservice/v2/getBusStationViaRouteListv2")
    suspend fun stopRoutes(@Query("stationId") stationId: String): String

    @GET("buslocationservice/v2/getBusLocationListv2")
    suspend fun locations(@Query("routeId") routeId: String): String

    @GET("busarrivalservice/v2/getBusArrivalListv2")
    suspend fun arrivals(@Query("stationId") stationId: String): String
}

class GyeonggiRepository(private val api: GyeonggiApi) {
    private val gate = Semaphore(4)
    private val stationCache = HashMap<String, List<RouteStop>>()
    private val lineCache = HashMap<String, List<LatLng>>()

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> = try {
        TagoResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TagoApiException) {
        TagoResult.Error(e.message ?: "경기 버스 API 오류")
    } catch (e: IOException) {
        TagoResult.Error("경기 버스 API 연결 실패: ${e.javaClass.simpleName}${e.message?.let { " ($it)" }.orEmpty()}")
    } catch (e: Exception) {
        TagoResult.Error("경기 버스 API 오류: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 응답 해석. resultCode 0 = 정상, 4 = 결과 없음(빈 목록), 그 외는 오류.
     * data.go.kr 게이트웨이 오류(키 미등록 등)는 XML 로 와서 TagoXml 이 메시지를 만든다.
     */
    /** JSON(format=json) 과 XML 응답 모두 해석 (브라우저로 연 실제 응답은 XML) */
    private suspend fun rows(key: String, call: suspend () -> String): List<Row> = gate.withPermit {
        GbisParser.rows(call(), key)
    }

    suspend fun arrivals(stopTagoId: String) = run {
        rows("routeId") { api.arrivals(Gyeonggi.std(stopTagoId)) }.flatMap(GyeonggiMappers::arrivals)
            .sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
    }

    suspend fun stopRoutes(stopTagoId: String) = run {
        rows("routeId") { api.stopRoutes(Gyeonggi.std(stopTagoId)) }.map(GyeonggiMappers::route).distinctBy { it.routeId }
    }

    suspend fun searchRoutes(keyword: String) = run {
        rows("routeId") { api.routes(keyword) }.map(GyeonggiMappers::route).distinctBy { it.routeId }
    }

    suspend fun routeInfo(routeTagoId: String) = run {
        rows("routeId") { api.routeInfo(Gyeonggi.std(routeTagoId)) }.firstOrNull()?.let(GyeonggiMappers::route)
    }

    /** 경유 정류소 (좌표 포함, 노선별 캐시) */
    suspend fun routeStops(routeTagoId: String) = run {
        synchronized(stationCache) { stationCache[routeTagoId] }
            ?: GyeonggiMappers.routeStops(rows("stationId") { api.routeStations(Gyeonggi.std(routeTagoId)) })
                .also { if (it.isNotEmpty()) synchronized(stationCache) { stationCache[routeTagoId] = it } }
    }

    /** 노선 형상 (노선별 캐시) */
    suspend fun line(routeTagoId: String) = run {
        synchronized(lineCache) { lineCache[routeTagoId] }
            ?: GyeonggiMappers.line(rows("lineSeq") { api.routeLine(Gyeonggi.std(routeTagoId)) })
                .also { if (it.size >= 2) synchronized(lineCache) { lineCache[routeTagoId] = it } }
    }

    suspend fun buses(routeTagoId: String) = run {
        GyeonggiMappers.buses(rows("plateNo") { api.locations(Gyeonggi.std(routeTagoId)) })
    }

    companion object {
        fun create(): GyeonggiRepository {
            val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
            val key = if ('%' in raw) raw else URLEncoder.encode(raw, "UTF-8")
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.newBuilder()
                        .addEncodedQueryParameter("serviceKey", key)
                        .addQueryParameter("format", "json")
                        .build()
                    chain.proceed(req.newBuilder().url(url).build())
                }
                .build()
            return GyeonggiRepository(
                Retrofit.Builder()
                    .baseUrl("https://apis.data.go.kr/6410000/")
                    .client(client)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()
                    .create(GyeonggiApi::class.java)
            )
        }
    }
}
