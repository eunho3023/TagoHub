package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.core.MiniJson

/**
 * 한국공항공사 공항 정보(assets/airport_info.json, pipeline/build_air_data.py 가 공공데이터 파일에서 만듦):
 * 주소·좌표·대표 전화, 시설 규모, 이용시설(카운터·키오스크), 지연·결항 통계, 주차, 대중교통 노선, 라운지, 층별 입점업체, 문화 행사.
 * 14개 공항(김포·김해·제주·대구·울산·청주·양양·무안·광주·여수·사천·포항경주·군산·원주).
 */
data class AirportInfo(
    val short: String,
    val code: String,
    val name: String,
    val address: String?,
    val lat: Double?,
    val lng: Double?,
    val phone: String?,
    val facility: Map<String, String>,
    /** 국내·국제 → (카운터·컨베이어벨트·발권데스크·셀프체크인 키오스크 → 개수) */
    val counters: Map<String, Map<String, String>>,
    /** 국내·국제 → (운항편수·지연편수·지연율(퍼센트) …) */
    val delay: Map<String, Map<String, String>>,
    val cancel: Map<String, Map<String, String>>,
    val parking: Map<String, String>,
    val transport: List<Transport>,
    val events: Map<String, String>,
    val lounges: List<Lounge>,
    val shops: List<Shop>,
) {
    data class Transport(val type: String, val count: String, val routes: String)
    data class Lounge(val name: String, val place: String, val hours: String, val phone: String, val services: String)
    data class Shop(val terminal: String, val floor: String, val type: String, val name: String)

    companion object {
        @Volatile private var cache: List<AirportInfo>? = null

        fun all(context: Context): List<AirportInfo> = cache ?: synchronized(this) {
            cache ?: runCatching {
                context.applicationContext.assets.open("airport_info.json").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            }.getOrDefault(emptyList()).also { cache = it }
        }

        private fun Any?.map(): Map<*, *>? = this as? Map<*, *>
        private fun Any?.str(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        private fun Any?.strMap(): Map<String, String> = map().orEmpty().mapNotNull { (k, v) -> v.str()?.let { k.toString() to it } }.toMap()
        private fun Any?.nested(): Map<String, Map<String, String>> = map().orEmpty().mapNotNull { (k, v) -> k.toString() to v.strMap() }.toMap()

        fun parse(json: String): List<AirportInfo> {
            val airports = (MiniJson.parse(json) as? Map<*, *>)?.get("airports").map() ?: return emptyList()
            return airports.mapNotNull { (k, v) ->
                val m = v.map() ?: return@mapNotNull null
                AirportInfo(
                    short = k.toString(),
                    code = m["code"].str().orEmpty(),
                    name = m["name"].str() ?: "${k}공항",
                    address = m["address"].str(),
                    lat = m["lat"].str()?.toDoubleOrNull(),
                    lng = m["lng"].str()?.toDoubleOrNull(),
                    phone = m["phone"].str(),
                    facility = m["facility"].strMap(),
                    counters = m["counters"].nested(),
                    delay = m["delay"].nested(),
                    cancel = m["cancel"].nested(),
                    parking = m["parking"].strMap(),
                    transport = (m["transport"] as? List<*>).orEmpty().mapNotNull { t ->
                        val tm = t.map() ?: return@mapNotNull null
                        Transport(tm["type"].str().orEmpty(), tm["count"].str().orEmpty(), tm["routes"].str().orEmpty())
                    },
                    events = m["events"].strMap(),
                    lounges = (m["lounges"] as? List<*>).orEmpty().mapNotNull { l ->
                        val lm = l.map() ?: return@mapNotNull null
                        Lounge(lm["name"].str().orEmpty(), lm["place"].str().orEmpty(), lm["hours"].str().orEmpty(), lm["phone"].str().orEmpty(), lm["services"].str().orEmpty())
                    },
                    shops = (m["shops"] as? List<*>).orEmpty().mapNotNull { s ->
                        val sm = s.map() ?: return@mapNotNull null
                        Shop(sm["terminal"].str().orEmpty(), sm["floor"].str().orEmpty(), sm["type"].str().orEmpty(), sm["name"].str().orEmpty())
                    },
                )
            }.sortedBy { PREFERRED.indexOf(it.short).let { i -> if (i < 0) PREFERRED.size else i } }
        }

        /** 많이 쓰는 공항부터 */
        private val PREFERRED = listOf("김포", "제주", "김해", "대구", "청주", "광주", "무안", "양양", "울산", "여수", "사천", "포항경주", "군산", "원주")

        /** 공항 이름("김포국제공항", "서울/김포" …)에 맞는 공항 정보 */
        fun match(list: List<AirportInfo>, airportName: String): AirportInfo? =
            list.firstOrNull { airportName.contains(it.short) }
    }
}
