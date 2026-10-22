package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.core.Geo
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 전국 기차역의 위치와 운행 여부 (assets 에 들어 있는 조사 결과).
 * - train_stations_geo.tsv : 역 ID, 위도, 경도 (카카오 장소검색으로 구함)
 * - train_unserved.txt     : 국토교통부 시간표에 어떤 열차도 나오지 않는 것으로 확인된 역 ID (목록에서 뺌)
 * 국토교통부 역 목록에는 위치가 없고 폐지·휴지 역도 섞여 있어서 이 파일로 보충한다.
 */
object TrainStationGeo {
    data class Point(val lat: Double, val lng: Double)

    @Volatile private var points: Map<String, Point> = emptyMap()
    @Volatile private var unserved: Set<String> = emptySet()

    fun init(context: Context) {
        if (points.isNotEmpty()) return
        fun read(name: String) = runCatching { context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
        points = parsePoints(read("train_stations_geo.tsv"))
        unserved = parseIds(read("train_unserved.txt"))
    }

    /** 시험용: 파일 없이 직접 채움 */
    internal fun setForTest(points: Map<String, Point>, unserved: Set<String>) {
        this.points = points
        this.unserved = unserved
    }

    /** "ID \t 위도 \t 경도" 줄들 */
    fun parsePoints(text: String): Map<String, Point> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        val lat = f.getOrNull(1)?.toDoubleOrNull()
        val lng = f.getOrNull(2)?.toDoubleOrNull()
        if (f[0].isBlank() || lat == null || lng == null) null else f[0].trim() to Point(lat, lng)
    }.toMap()

    /** 줄마다 첫 칸이 ID (뒤는 설명) */
    fun parseIds(text: String): Set<String> =
        text.lineSequence().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() }.toSet()

    fun isUnserved(id: String) = id in unserved

    fun pointOf(id: String): Point? = points[id]

    /** 내 위치에서 가까운 역 [n] 개 (위치를 아는 역만, 운행하지 않는 역 제외) — (역, 거리 m) */
    fun nearest(places: List<Place>, lat: Double, lng: Double, n: Int = 2): List<Pair<Place, Double>> =
        places.mapNotNull { p -> if (isUnserved(p.id)) null else points[p.id]?.let { p to Geo.meters(lat, lng, it.lat, it.lng) } }
            .sortedBy { it.second }.take(n)

    /**
     * 출발역~도착역 사이 길목에 있는 역만 (직선에서 [widthKm] 이내, 기본은 구간 길이의 20% 이상 25km).
     * 정차역을 찾을 때 전국 역을 다 조회하지 않고 이 안의 역만 조회해서 TAGO 호출을 줄인다.
     * 위치를 모르는 역은 빼지 않고 남긴다 (정차역을 놓치지 않게). 출발·도착역 위치를 모르면 전체.
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
