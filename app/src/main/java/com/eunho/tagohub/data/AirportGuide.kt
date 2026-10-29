package com.eunho.tagohub.data

import com.eunho.tagohub.data.AirportLive.n
import com.eunho.tagohub.data.AirportLive.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 한국공항공사 공항 안내 자료 (공공데이터포털 B551178, 인증키 = KAC_API_KEY, type=json). 실시간이 아니라 요금표·노선표.
 *  · parking-fee/info: 전국 21개 주차장 요금 (공항 코드 파라미터를 무시하고 전부 옴 → 공항 이름으로 거름)
 *  · airport-bus/info?schAirport=: 공항 버스노선 (승하차 위치·요금·첫/막차·간격·운수회사)
 */
object AirportGuide {
    /** 소형차 기준. 요금 단위는 원, 시간은 분 */
    data class ParkingFee(
        val lot: String, val basicMin: Int?, val basicWon: Int?, val unitMin: Int?, val unitWon: Int?,
        val dayMaxWon: Int?, val holidayDayMaxWon: Int?, val freeMin: Int?,
    ) {
        /** "최초 30분 500원 · 이후 10분마다 200원 · 하루 최대 9,000원(주말 10,000원) · 10분 무료" */
        fun text(): String = listOfNotNull(
            if (basicMin != null && basicWon != null) "최초 ${basicMin}분 ${won(basicWon)}" else null,
            if (unitMin != null && unitWon != null) "이후 ${unitMin}분마다 ${won(unitWon)}" else null,
            dayMaxWon?.let { d -> "하루 최대 ${won(d)}" + (holidayDayMaxWon?.takeIf { it != d }?.let { "(주말 ${won(it)})" } ?: "") },
            freeMin?.takeIf { it > 0 }?.let { "${it}분 무료" },
        ).joinToString(" · ")
    }

    data class BusRoute(
        val category: String, val number: String, val boarding: String?, val cash: String?, val card: String?,
        val route: String?, val first: String?, val last: String?, val company: String?, val interval: String?,
    ) {
        fun title(): String = if (category.isEmpty()) number else "$number · $category"

        /** "첫차 07:20 · 막차 21:30 · 배차 35~70분 · 현금 3,000원 · 카드 2,000원" */
        fun detail(): String = listOfNotNull(
            first?.let { "첫차 $it" }, last?.let { "막차 $it" }, interval?.let { "배차 $it" },
            cash?.let { "현금 ${it}원" }, card?.let { "카드 ${it}원" },
        ).joinToString(" · ")
    }

    fun won(v: Int): String = String.format(java.util.Locale.KOREA, "%,d원", v)

    fun fees(iata: String, text: String): List<ParkingFee> {
        val site = AirportLive.LOT_AIRPORT[iata] ?: return emptyList()
        return AirportLive.rows(text).filter { it.s("SITE_NAME")?.startsWith(site) == true }.mapNotNull { m ->
            ParkingFee(
                lot = m.s("PARKING_PARKING_NAME") ?: return@mapNotNull null,
                basicMin = m.n("PARKING_BASIC_M"), basicWon = m.n("PARKING_BASIC_ACCOUNT"),
                unitMin = m.n("PARKING_MINUTE_M"), unitWon = m.n("PARKING_MINUTE_ACCOUNT"),
                dayMaxWon = m.n("PARKING_MAX_ACCOUNT"), holidayDayMaxWon = m.n("PARKING_HOLI_MAX_ACCOUNT"),
                freeMin = m.n("PARKING_FREE_M"),
            )
        }
    }

    fun buses(text: String): List<BusRoute> = AirportLive.rows(text).mapNotNull { m ->
        BusRoute(
            category = m.s("BUS_CATEGORY_KOR_NAME").orEmpty(), number = m.s("BUS_DATA_BUS_NUM") ?: return@mapNotNull null,
            boarding = m.s("BUS_DATA_GETON_KOR"), cash = m.s("BUS_DATA_MONEY"), card = m.s("BUS_DATA_CARD"),
            route = m.s("BUS_DATA_ROUTE_KOR"), first = m.s("BUS_DATA_FIRST_TIME"), last = m.s("BUS_DATA_END_TIME"),
            company = m.s("BUS_DATA_COMNAME_KOR"), interval = m.s("BUS_DATA_ETC_KOR"),
        )
    }
}

/** 한 공항의 요금표·버스노선. 못 받은 항목은 빈 목록, 이유는 [errors] */
data class AirportGuideInfo(
    val fees: List<AirportGuide.ParkingFee>,
    val buses: List<AirportGuide.BusRoute>,
    val errors: List<String>,
)

object AirportGuideClient {
    private const val PAGE_ROWS = 100
    private const val MAX_PAGES = 5

    suspend fun load(iata: String): AirportGuideInfo = withContext(Dispatchers.IO) {
        coroutineScope {
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            fun <T> guarded(block: () -> T): T? = runCatching(block).onFailure { errors += it.message ?: "조회 실패" }.getOrNull()

            val fees = async {
                guarded { AirportGuide.fees(iata, AirportLiveClient.get("parking-fee/info", "한국공항공사 주차요금", "pageNo" to "1", "numOfRows" to PAGE_ROWS.toString())) }.orEmpty()
            }
            val buses = async {
                guarded {
                    // 한 번에 300행부터는 거절하는 API 라 100행씩. 한 공항은 많아야 70여 개 노선이라 보통 한 쪽
                    fun page(n: Int) = AirportLiveClient.get("airport-bus/info", "한국공항공사 공항 버스노선", "pageNo" to n.toString(), "numOfRows" to PAGE_ROWS.toString(), "schAirport" to iata)
                    val first = page(1)
                    val last = ((AirportLive.totalCount(first) + PAGE_ROWS - 1) / PAGE_ROWS).coerceIn(1, MAX_PAGES)
                    val rest = (2..last).mapNotNull { n -> runCatching { page(n) }.getOrNull() }
                    (listOf(first) + rest).flatMap { AirportGuide.buses(it) }
                }.orEmpty()
            }
            AirportGuideInfo(fees.await(), buses.await(), errors.distinct())
        }
    }
}
