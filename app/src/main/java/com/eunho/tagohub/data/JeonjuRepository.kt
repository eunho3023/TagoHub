package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.JeonjuXml
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 전주시 버스정보 API (apis.data.go.kr/4641000). 파라미터는 사용자가 확인한 요청 URL 그대로 */
interface JeonjuApi {
    /** 정류장 도착 예정 (일반) */
    @GET("realtime/bus_location_busstop_infomation_common")
    suspend fun arrivals(@Query("stopStdid") stopStdid: String): String

    /** 노선 전체 정류장 + 버스 위치 */
    @GET("realtime/bus_location_bus_position_common")
    suspend fun positions(@Query("brtStdid") brtStdid: String): String

    /** 노선 모양 */
    @GET("nosun/bus_nosun_drawing_common")
    suspend fun drawing(@Query("brtStdid") brtStdid: String): String

    /**
     * 노선 운행 시각표 / 노선 정보. 이 두 API 는 요청 파라미터 이름이 확인되지 않아
     * 다른 노선 API 의 brtStdid 와 응답에 나오는 brtStdId·stdId 를 함께 보낸다 (모르는 파라미터는 무시됨).
     */
    @GET("nosun/bus_location_time_common")
    suspend fun courses(@Query("brtStdid") a: String, @Query("brtStdId") b: String, @Query("stdId") c: String): String

    @GET("nosun/bus_location_busstop_info_common")
    suspend fun routeInfo(@Query("brtStdid") a: String, @Query("brtStdId") b: String, @Query("stdId") c: String): String

    /**
     * 정류장 경유노선. 요청 이름은 응답의 빈 칸(stopStandardid) 규칙으로 추정 —
     * 다른 정류장 API 의 stopStdid 도 함께 보낸다 (모르는 파라미터는 무시됨)
     */
    @GET("busstop/general_busstop_common")
    suspend fun stopRoutes(@Query("stopStandardid") a: String, @Query("stopStdid") b: String): String

    /** 출발·도착 정류장 사이 환승 경로 */
    @GET("transfer/fromto_bus_search_transfer_detail_common")
    suspend fun transfer(
        @Query("sStandardId") from: String,
        @Query("eStandardId") to: String,
        @Query("maxCnt") maxCnt: Int,
    ): String
}

class JeonjuRepository(private val api: JeonjuApi) {
    private val gate = Semaphore(4)
    private val stopsCache = HashMap<String, List<Row>>()
    private val drawingCache = HashMap<String, List<LatLng>>()
    private val courseCache = HashMap<String, List<CourseStart>>()
    private val scheduleCache = HashMap<String, RouteSchedule>()

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> = try {
        TagoResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TagoApiException) {
        TagoResult.Error(e.message ?: "전주시 버스 API 오류")
    } catch (e: IOException) {
        TagoResult.Error("전주시 버스 API 연결 실패")
    } catch (e: Exception) {
        TagoResult.Error(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun rows(call: suspend () -> String): List<Row> = gate.withPermit { JeonjuXml.parse(call()) }

    suspend fun arrivals(stopTagoId: String) = run {
        rows { api.arrivals(Jeonju.std(stopTagoId)) }.map(JeonjuMappers::arrival).sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
    }

    /** 노선 정류장 + 버스 위치 (매번 새로). 정류장 목록만 필요하면 [routeStopRows] */
    suspend fun positions(routeTagoId: String) = run {
        rows { api.positions(Jeonju.std(routeTagoId)) }.also { synchronized(stopsCache) { stopsCache[routeTagoId] = it } }
    }

    /** 정류장 목록은 바뀌지 않으므로 캐시 (길찾기에서 여러 노선을 볼 때) */
    suspend fun routeStopRows(routeTagoId: String) = run {
        synchronized(stopsCache) { stopsCache[routeTagoId] } ?: rows { api.positions(Jeonju.std(routeTagoId)) }
            .also { synchronized(stopsCache) { stopsCache[routeTagoId] = it } }
    }

    suspend fun drawing(routeTagoId: String) = run {
        synchronized(drawingCache) { drawingCache[routeTagoId] } ?: JeonjuMappers.drawing(rows { api.drawing(Jeonju.std(routeTagoId)) })
            .also { if (it.size >= 2) synchronized(drawingCache) { drawingCache[routeTagoId] = it } }
    }

    /** 정류장 경유노선 (TAGO 대신). 같은 번호도 기점·종점별로 따로 */
    suspend fun stopRoutes(stopTagoId: String) = run {
        val id = Jeonju.std(stopTagoId)
        rows { api.stopRoutes(id, id) }.map(JeonjuMappers::stopRoute).distinctBy { it.routeId }
    }

    /** 노선 정보: 첫차·막차, 기점·종점 (TAGO 대신) */
    suspend fun routeInfo(routeTagoId: String) = run {
        val id = Jeonju.std(routeTagoId)
        rows { api.routeInfo(id, id, id) }.firstOrNull()?.let(JeonjuMappers::routeInfo)
    }

    /** 평일 운행별 출발 시각 (노선별 캐시) */
    suspend fun courses(routeTagoId: String) = run {
        synchronized(courseCache) { courseCache[routeTagoId] } ?: run2 {
            val id = Jeonju.std(routeTagoId)
            JeonjuTimetable.courses(rows { api.courses(id, id, id) })
        }.also { if (it.isNotEmpty()) synchronized(courseCache) { courseCache[routeTagoId] = it } }
    }

    /** 첫차·막차·주말 출발 시각 (노선별 캐시) */
    suspend fun schedule(routeTagoId: String) = run {
        synchronized(scheduleCache) { scheduleCache[routeTagoId] } ?: run2 {
            val id = Jeonju.std(routeTagoId)
            JeonjuTimetable.schedule(rows { api.routeInfo(id, id, id) }.firstOrNull())
        }.also { synchronized(scheduleCache) { scheduleCache[routeTagoId] = it } }
    }

    private suspend fun <T> run2(block: suspend () -> T): T = block()

    suspend fun transfers(fromTagoId: String, toTagoId: String) = run {
        JeonjuMappers.transfers(rows { api.transfer(Jeonju.std(fromTagoId), Jeonju.std(toTagoId), 25) })
    }

    companion object {
        fun create(): JeonjuRepository {
            val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
            val key = if ('%' in raw) raw else URLEncoder.encode(raw, "UTF-8")
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    chain.proceed(req.newBuilder().url(req.url.newBuilder().addEncodedQueryParameter("serviceKey", key).build()).build())
                }
                .build()
            return JeonjuRepository(
                Retrofit.Builder()
                    .baseUrl("https://apis.data.go.kr/4641000/")
                    .client(client)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()
                    .create(JeonjuApi::class.java)
            )
        }
    }
}
