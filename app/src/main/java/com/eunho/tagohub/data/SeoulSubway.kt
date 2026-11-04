package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.SeoulXml
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 서울 열린데이터광장 실시간 지하철 (http://swopenapi.seoul.go.kr) */
interface SeoulSubwayApi {
    /** 노선별 운행 중 열차 위치. line 은 "1호선", "경의중앙선" 같은 한글 노선명 */
    suspend fun positions(key: String, start: Int, end: Int, line: String): String

    /** 역별 도착 예정 열차. station 은 "서울", "종각" 같은 역명 */
    suspend fun arrivals(key: String, start: Int, end: Int, station: String): String
}

/** [SeoulSubwayApi] 를 Ktor 로 (기본 주소·OkHttp 설정은 [httpGetter] 를 만드는 쪽이 정함) */
class SeoulSubwayApiHttp(private val get: HttpGetter) : SeoulSubwayApi {
    override suspend fun positions(key: String, start: Int, end: Int, line: String): String =
        get("${key}/xml/realtimePosition/${start.toString()}/${end.toString()}/${line}", listOf())

    override suspend fun arrivals(key: String, start: Int, end: Int, station: String): String =
        get("${key}/xml/realtimeStationArrival/${start.toString()}/${end.toString()}/${station}", listOf())
}

class SeoulSubwayRepository(private val api: SeoulSubwayApi) {
    private val key = BuildConfig.SEOUL_SUBWAY_KEY.trim()
    val hasKey: Boolean get() = key.isNotEmpty()

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> {
        if (!hasKey) return TagoResult.Error(com.eunho.tagohub.core.DevMode.msg("서울 실시간 지하철 키가 없습니다. local.properties 에 SEOUL_SUBWAY_KEY 를 추가하세요."))
        return try {
            TagoResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TagoApiException) {
            TagoResult.Error(e.message ?: "서울 API 오류")
        } catch (e: IOException) {
            TagoResult.Error("서울 실시간 API 연결 실패")
        } catch (e: Exception) {
            TagoResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun positions(line: SeoulLine) = run {
        SeoulXml.parse(api.positions(key, 0, 300, line.apiName)).map(SeoulMappers::train)
    }

    /**
     * 역 도착정보. 서울 API 역명은 괄호를 붙이는 경우가 있어(예: 총신대입구(이수)) 정규화한 이름으로 먼저 찾고,
     * 비면 원래 표기로 한 번 더 찾는다.
     */
    suspend fun arrivals(group: StationGroup) = run {
        val names = (listOf(group.name) + group.stations.map { it.name.removeSuffix("역") }).distinct()
        var rows = emptyList<SubwayArrival>()
        for (n in names) {
            rows = SeoulXml.parse(api.arrivals(key, 0, 60, n)).map(SeoulMappers::arrival)
            if (rows.isNotEmpty()) break
        }
        val lines = group.seoulLineIds
        rows.filter { it.lineId in lines } // 같은 이름 다른 역(양평 등) 제거
            .sortedWith(compareBy({ it.lineId }, { it.upDown }, { it.order ?: "" }, { it.seconds ?: Int.MAX_VALUE }))
    }

    companion object {
        fun create(): SeoulSubwayRepository {
            val client = Http.builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val api = SeoulSubwayApiHttp(httpGetter("http://swopenapi.seoul.go.kr/api/subway/", client))
            return SeoulSubwayRepository(api)
        }
    }
}
