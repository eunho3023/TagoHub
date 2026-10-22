package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.core.Geo
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 전국 기차역 목록·위치·운행 여부 (assets/train_stations.tsv).
 * 국토교통부(TAGO) 역 목록을 앱을 켤 때마다 받아오면 느려서, 미리 만들어 둔 파일을 쓴다.
 *  - 위치: 한국철도공사 역 위치 정보(공식) 우선, 없는 역은 카카오 장소검색
 *  - 운행 여부: 한국철도공사 열차시간표(KTX·일반열차)·KTX 노선별 역정보·일반열차 여객발착 정보 중 어디에도 안 나오는 역은 '운행하지 않음'(폐지·휴지 등)
 */
object TrainStationGeo {
    data class Point(val lat: Double, val lng: Double)

    /** [served] 가 false 면 어느 열차도 서지 않는 역 */
    data class Station(val id: String, val name: String, val city: String, val point: Point?, val served: Boolean)

    @Volatile private var all: List<Station> = emptyList()
    @Volatile private var points: Map<String, Point> = emptyMap()
    @Volatile private var unserved: Set<String> = emptySet()

    fun init(context: Context) {
        if (all.isNotEmpty()) return
        val text = runCatching { context.assets.open("train_stations.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
        set(parse(text))
    }

    internal fun set(list: List<Station>) {
        all = list
        points = list.mapNotNull { s -> s.point?.let { s.id to it } }.toMap()
        unserved = list.filter { !it.served }.map { it.id }.toSet()
    }

    /** "ID \t 이름 \t 시·도 \t 위도 \t 경도 \t 운행(1/0)" 줄들 */
    fun parse(text: String): List<Station> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6 || f[0].isBlank() || f[1].isBlank()) return@mapNotNull null
        val lat = f[3].toDoubleOrNull()
        val lng = f[4].toDoubleOrNull()
        Station(f[0].trim(), f[1].trim(), f[2].trim(), if (lat != null && lng != null) Point(lat, lng) else null, f[5].trim() != "0")
    }.toList()

    /** 파일에 있는 역 (운행하지 않는 역은 뺌), 이름순. 파일이 없으면 빈 목록 */
    fun places(): List<Place> =
        all.filter { it.served }.map { Place(it.id, it.name, it.city.ifBlank { null }) }.sortedBy { it.name }

    fun isUnserved(id: String) = id in unserved

    fun pointOf(id: String): Point? = points[id]

    /** 내 위치에서 가까운 역 [n] 개 (위치를 아는 역만, 운행하지 않는 역 제외) — (역, 거리 m) */
    fun nearest(places: List<Place>, lat: Double, lng: Double, n: Int = 2): List<Pair<Place, Double>> =
        places.mapNotNull { p -> if (isUnserved(p.id)) null else points[p.id]?.let { p to Geo.meters(lat, lng, it.lat, it.lng) } }
            .sortedBy { it.second }.take(n)

    /**
     * 출발역~도착역 사이 길목에 있는 역만 (직선에서 [widthKm] 이내, 기본은 구간 길이의 20% 이상 25km).
     * 위치를 모르는 역은 빼지 않고 남긴다. 출발·도착역 위치를 모르면 전체.
     */
    fun corridor(places: List<Place>, dep: Place, arr: Place, widthKm: Double? = null): List<Place> {
        val a = points[dep.id] ?: return places
        val b = points[arr.id] ?: return places
        val length = kmBetween(a, a, b)
        val width = widthKm ?: maxOf(25.0, length * 0.2)
        return places.filter { p ->
            val q = points[p.id] ?: return@filter true
            distanceToSegmentKm(q, a, b) <= width
        }
    }

    private fun kmBetween(origin: Point, a: Point, b: Point): Double {
        val (ax, ay) = xy(origin, a)
        val (bx, by) = xy(origin, b)
        return hypot(ax - bx, ay - by)
    }

    /** 점 [p] 에서 선분 a–b 까지 거리(km). 위도 보정한 평면 근사 (나라 크기에서는 충분) */
    internal fun distanceToSegmentKm(p: Point, a: Point, b: Point): Double {
        val (px, py) = xy(a, p)
        val (bx, by) = xy(a, b)
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    private fun xy(origin: Point, p: Point): Pair<Double, Double> {
        val kmPerLat = 111.0
        val kmPerLng = 111.0 * cos(Math.toRadians(origin.lat))
        return ((p.lng - origin.lng) * kmPerLng) to ((p.lat - origin.lat) * kmPerLat)
    }
}
