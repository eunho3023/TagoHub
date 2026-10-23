package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.core.Geo

/**
 * 역·터미널·공항 선택 화면에서 '거리순'·'지역별'로 보여 주려면 각 장소의 위치와 시·도가 필요하다.
 * 목록 API(TAGO)에는 없어서 앱에 함께 든 파일/표로 채운다.
 */
interface PlaceGeo {
    /** 위치 (위도, 경도). 모르면 null */
    fun pointOf(p: Place): Pair<Double, Double>?

    /** 시·도 (목록에 이미 있으면 그걸 쓰고, 없을 때만 불림). 모르면 null */
    fun regionOf(p: Place): String?

    /** 목록의 장소에 시·도가 비어 있으면 채움 */
    fun enrich(list: List<Place>): List<Place> =
        list.map { p -> if (p.sub.isNullOrBlank()) regionOf(p)?.let { p.copy(sub = it) } ?: p else p }

    /** [lat],[lng] 에서 가까운 순 (위치를 모르는 곳은 맨 뒤, 이름순). 거리는 m, 모르면 null */
    fun byDistance(list: List<Place>, lat: Double, lng: Double): List<Pair<Place, Double?>> =
        list.map { p -> p to pointOf(p)?.let { Geo.meters(lat, lng, it.first, it.second) } }
            .sortedWith(compareBy({ it.second == null }, { it.second ?: 0.0 }, { it.first.name }))
}

/** 기차역: 앱에 든 역 파일(train_stations.tsv)의 위치. 시·도는 목록에 이미 있음 */
object TrainPlaceGeo : PlaceGeo {
    override fun pointOf(p: Place) = TrainStationGeo.pointOf(p.id)?.let { it.lat to it.lng }
    override fun regionOf(p: Place): String? = null
}

/** 고속·시외버스 터미널: pipeline/build_terminal_geo.py 가 만든 coach_terminals.tsv (ID·이름·시·도·위도·경도) */
object CoachTerminalGeo : PlaceGeo {
    private class Row(val region: String?, val point: Pair<Double, Double>?)

    private var appContext: Context? = null
    private var rows: Map<String, Row>? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 파일 내용에서 표를 만듦 (시험에서도 씀) */
    fun parse(text: String): Map<String, Pair<String?, Pair<Double, Double>?>> =
        text.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 2 || f[0].isBlank()) return@mapNotNull null
            val lat = f.getOrNull(3)?.toDoubleOrNull()
            val lng = f.getOrNull(4)?.toDoubleOrNull()
            f[0].trim() to (f.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } to if (lat != null && lng != null) lat to lng else null)
        }.toMap()

    @Synchronized
    private fun table(): Map<String, Row> {
        rows?.let { return it }
        val text = appContext?.let { c -> runCatching { c.assets.open("coach_terminals.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }
        return (text?.let { parse(it).mapValues { (_, v) -> Row(v.first, v.second) } } ?: emptyMap()).also { rows = it }
    }

    override fun pointOf(p: Place) = table()[p.id]?.point
    override fun regionOf(p: Place) = table()[p.id]?.region
}

/** 국내선 공항: 이름의 한 토막으로 찾는 작은 표 */
object AirportGeo : PlaceGeo {
    private class Airport(val key: String, val region: String, val lat: Double, val lng: Double)

    private val AIRPORTS = listOf(
        Airport("김포", "서울", 37.5583, 126.7906), Airport("인천", "인천", 37.4602, 126.4407), Airport("제주", "제주", 33.5113, 126.4930),
        Airport("김해", "부산", 35.1795, 128.9382), Airport("대구", "대구", 35.8941, 128.6589), Airport("청주", "충북", 36.7166, 127.4991),
        Airport("광주", "광주", 35.1264, 126.8089), Airport("울산", "울산", 35.5935, 129.3518), Airport("여수", "전남", 34.8423, 127.6169),
        Airport("사천", "경남", 35.0886, 128.0704), Airport("포항", "경북", 35.9878, 129.4204), Airport("양양", "강원", 38.0613, 128.6692),
        Airport("원주", "강원", 37.4381, 127.9604), Airport("무안", "전남", 34.9914, 126.3828), Airport("군산", "전북", 35.9038, 126.6159),
    )

    private fun find(p: Place) = AIRPORTS.firstOrNull { p.name.contains(it.key) }

    override fun pointOf(p: Place) = find(p)?.let { it.lat to it.lng }
    override fun regionOf(p: Place) = find(p)?.region
}
