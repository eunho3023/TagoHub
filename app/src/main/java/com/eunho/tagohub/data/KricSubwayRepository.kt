package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.TagoXml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

data class KricStationCode(
    val operatorCode: String,
    val operatorName: String,
    val lineCode: String,
    val lineName: String,
    val stationCode: String,
    val stationName: String,
)

class KricStationCodeIndex private constructor(private val rows: List<KricStationCode>) {
    fun find(
        stationName: String,
        lineNames: Collection<String>,
        operatorNames: Collection<String>,
        regionCode: String,
    ): KricSubwayCodes? {
        val stationNorm = StationName.norm(stationName)
        val candidates = rows.asSequence()
            .filter { KricSubwayRegion.fromOperator(it.operatorName) == regionCode }
            .filter { StationName.norm(it.stationName) == stationNorm }
            .mapNotNull { row ->
                val lineMatch = lineNames.any { StationName.sameLine(it, row.lineName) }
                val operatorMatch = operatorNames.any { sameOperator(it, row.operatorName) }
                val score = (if (lineMatch) 2 else 0) + (if (operatorMatch) 4 else 0)
                if (score == 0) null else row to score
            }
            .toList()
        val bestScore = candidates.maxOfOrNull { it.second } ?: return null
        val best = candidates.filter { it.second == bestScore }.map { (row, _) ->
            KricSubwayCodes(row.operatorCode, row.lineCode, row.stationCode)
        }.distinct()
        return best.singleOrNull()
    }

    private fun sameOperator(a: String, b: String): Boolean {
        fun key(value: String) = value
            .replace("코레일", "한국철도")
            .replace("(주)", "")
            .replace("㈜", "")
            .replace("주식회사", "")
            .replace("공사", "")
            .replace(Regex("[\\s·()]"), "")
        val left = key(a)
        val right = key(b)
        return left == right || (left.length >= 3 && right.contains(left)) || (right.length >= 3 && left.contains(right))
    }

    companion object {
        fun parseTsv(text: String): KricStationCodeIndex {
            val rows = text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val columns = line.split('\t')
                if (columns.size < 6) return@mapNotNull null
                KricStationCode(
                    operatorCode = columns[0],
                    operatorName = columns[1],
                    lineCode = columns[2],
                    lineName = columns[3],
                    stationCode = columns[4],
                    stationName = columns[5],
                )
            }.toList()
            return KricStationCodeIndex(rows)
        }

        fun empty() = KricStationCodeIndex(emptyList())
    }
}

object KricSubwayRegion {
    fun fromOperator(operator: String): String? = when {
        operator.contains("부산") || operator.contains("김해") -> "02"
        operator.contains("대구") -> "03"
        operator.contains("광주") -> "04"
        operator.contains("대전") -> "05"
        listOf(
            "서울", "인천", "경기", "한국철도", "코레일", "공항", "에스지레일", "지티엑스", "구리", "남양주",
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
    @Volatile private var stationCodeIndex = KricStationCodeIndex.empty()

    fun updateStationCodeIndex(index: KricStationCodeIndex) {
        stationCodeIndex = index
    }

    fun stationCodes(
        stationName: String,
        lineNames: Collection<String>,
        operatorNames: Collection<String>,
        regionCode: String,
    ): KricSubwayCodes? = stationCodeIndex.find(stationName, lineNames, operatorNames, regionCode)

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> {
        if (key.isEmpty()) {
            return TagoResult.Error(com.eunho.tagohub.core.DevMode.msg("KRIC 도시철도 서비스 키가 없습니다. local.properties 에 KRIC_SUBWAY_KEY 를 추가하세요."))
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

    suspend fun routeForStation(
        station: SubwayStation,
        regionCode: String,
        preferredCodes: KricSubwayCodes? = null,
    ): TagoResult<KricStationRoute> = run {
        val candidates = (listOfNotNull(preferredCodes) + KricSubwayCodes.candidates(station.id)).distinct()
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

    /** (운영기관:노선:요일:역) → 그 역을 지나는 열차번호별 시각. 같은 노선의 다른 열차를 펼칠 때 다시 받지 않게 */
    private val stationTables = ConcurrentHashMap<String, Map<String, KricTrainStop>>()

    private suspend fun stationTable(operator: String, line: String, dayCode: String, station: String): Map<String, KricTrainStop> {
        val key = "$operator:$line:$dayCode:$station"
        stationTables[key]?.let { return it }
        val rows = Mappers.cleanKricTimetable(TagoXml.parse(api.timetable(operator, dayCode, line, station)).rows)
        return rows.groupBy { it.trainNo }
            .mapValues { (_, list) -> list.first().copy(arrivalTime = list.firstNotNullOfOrNull { it.arrivalTime }, departureTime = list.firstNotNullOfOrNull { it.departureTime }) }
            .also { stationTables[key] = it }
    }

    /**
     * 한 열차의 정차역(시각순). KRIC 시각표는 역마다 따로라서, 이 노선의 역([stations])마다 시각표를 받아(동시에 8개, 한 번 받으면 기억)
     * 그 열차번호가 있는 역을 모은다. 처음 한 열차는 역 수만큼(노선 하나 30~60회) 걸리고 그다음 열차부터는 바로 나온다.
     */
    suspend fun trainStops(codes: KricSubwayCodes, dayCode: String, trainNo: String, stations: List<KricRouteStation>): TagoResult<List<KricStopTime>> = run {
        val mine = stations.filter { it.operatorCode == codes.operatorCode && it.lineCode == codes.lineCode }.distinctBy { it.stationCode }
        if (mine.isEmpty()) throw TagoApiException("EMPTY", "이 노선의 역 목록을 찾지 못했습니다.")
        val gate = Semaphore(8)
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val found = coroutineScope {
            mine.map { st ->
                async {
                    gate.withPermit {
                        runCatching { stationTable(codes.operatorCode, codes.lineCode, dayCode, st.stationCode)[trainNo] }
                            .onFailure { if (it is CancellationException) throw it; failed.incrementAndGet() }
                            .getOrNull()?.let { KricStopTime(st.stationName, it.arrivalTime, it.departureTime) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (found.size < 2) {
            throw TagoApiException("EMPTY", if (failed.get() > 0) "정차역을 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요." else "이 열차의 정차역을 시각표에서 찾지 못했습니다.")
        }
        found.sortedBy { TagoTime.minutesOfDay(it.departureTime ?: it.arrivalTime)?.let(TagoTime::serviceMinute) ?: Int.MAX_VALUE }
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
            val client = Http.builder()
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
