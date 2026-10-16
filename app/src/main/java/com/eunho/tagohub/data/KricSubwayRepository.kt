package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoXml
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class KricSubwayCodes(
    val operatorCode: String,
    val lineCode: String,
    val stationCode: String,
) {
    companion object {
        fun candidates(stationId: String): List<KricSubwayCodes> {
            if (!stationId.startsWith("MTR") || stationId.length <= 6) return emptyList()
            val operatorCode = stationId.substring(3, 5)
            val remaining = stationId.substring(5)
            return (1 until remaining.length).map { split ->
                KricSubwayCodes(operatorCode, remaining.substring(0, split), remaining.substring(split))
            }
        }
    }
}

object KricSubwayRegion {
    fun fromOperator(operator: String): String? = when {
        operator.contains("부산") || operator.contains("김해") -> "02"
        operator.contains("대구") -> "03"
        operator.contains("광주") -> "04"
        operator.contains("대전") -> "05"
        listOf(
            "서울", "인천", "경기", "한국철도", "공항", "에스지레일", "지티엑스", "구리", "남양주",
            "남서울", "김포", "의정부", "용인", "우이신설", "네오트랜스", "서해철도",
        )
            .any(operator::contains) -> "01"
        else -> null
    }
}

class KricSubwayRepository(
    private val api: KricSubwayApi,
    private val key: String = BuildConfig.KRIC_SUBWAY_KEY.trim(),
) {
    private val routeCache = ConcurrentHashMap<String, List<KricRouteStation>>()

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> {
        if (key.isEmpty()) {
            return TagoResult.Error("KRIC 도시철도 서비스 키가 없습니다. local.properties 에 KRIC_SUBWAY_KEY 를 추가하세요.")
        }
        return try {
            TagoResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TagoApiException) {
            TagoResult.Error(e.message ?: "KRIC 도시철도 API 오류")
        } catch (e: IOException) {
            TagoResult.Error("KRIC 도시철도 API 연결에 실패했습니다.")
        } catch (e: Exception) {
            TagoResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun routeForStation(station: SubwayStation, regionCode: String): TagoResult<KricStationRoute> = run {
        val candidates = KricSubwayCodes.candidates(station.id)
        if (candidates.isEmpty()) throw TagoApiException("FORMAT", "KRIC 역 코드를 확인할 수 없습니다.")
        for (codes in candidates) {
            val route = loadRouteInfo(regionCode, codes.lineCode)
            val found = route.any {
                it.operatorCode == codes.operatorCode &&
                    it.lineCode == codes.lineCode &&
                    it.stationCode == codes.stationCode
            }
            if (found) {
                return@run KricStationRoute(
                    codes = codes,
                    stations = route.filter { it.operatorCode == codes.operatorCode && it.lineCode == codes.lineCode },
                )
            }
        }
        throw TagoApiException("EMPTY", "KRIC 노선정보에서 이 역의 코드를 찾지 못했습니다.")
    }

    private suspend fun loadRouteInfo(regionCode: String, lineCode: String): List<KricRouteStation> {
        val cacheKey = "$regionCode:$lineCode"
        return routeCache[cacheKey] ?: TagoXml.parse(api.routeInfo(regionCode, lineCode)).rows
            .map(Mappers::kricRouteStation)
            .sortedBy { it.stationOrder }
            .also { if (it.isNotEmpty()) routeCache[cacheKey] = it }
    }

    suspend fun timetable(codes: KricSubwayCodes, dayCode: String): TagoResult<List<KricTrainStop>> = run {
        Mappers.cleanKricTimetable(
            TagoXml.parse(
                api.timetable(codes.operatorCode, dayCode, codes.lineCode, codes.stationCode)
            ).rows
        )
    }

    companion object {
        fun create(): KricSubwayRepository {
            val rawKey = BuildConfig.KRIC_SUBWAY_KEY.trim()
            val encodedKey = if ('%' in rawKey) rawKey else URLEncoder.encode(rawKey, "UTF-8")
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val url = chain.request().url.newBuilder()
                        .addEncodedQueryParameter("serviceKey", encodedKey)
                        .addQueryParameter("format", "xml")
                        .build()
                    chain.proceed(chain.request().newBuilder().url(url).build())
                }
                .build()
            val api = Retrofit.Builder()
                .baseUrl("https://openapi.kric.go.kr/openapi/trainUseInfo/")
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(KricSubwayApi::class.java)
            return KricSubwayRepository(api)
        }
    }
}
