package com.eunho.tagohub.data

import android.content.Context

/** 신호등 한 곳: 위치와, 그 자리에 잔여시간표시기가 있는지 */
data class TrafficLightPoint(val lat: Double, val lng: Double, val timer: Boolean)

/**
 * 신호등 위치 (전국신호등표준데이터 API 전체 + 전북 신호등 현황 API + 서울시 신호등 부착대 API + 부산 신호제어기 파일, 앱에 넣은 assets/traffic_lights.csv: "위도,경도,잔여시간표시기" 위도 순).
 * 사거리 하나의 신호등 여러 개는 점 하나로 합쳐져 있고 점은 그 신호등 중 가운데에 가장 가까운 실제 신호등 자리다(pipeline/build_traffic_lights.py, 45m 안).
 * 실시간 신호 상태는 아니고 위치만이며, 자료를 낸 지자체만 들어 있어 전국 전부는 아니다 (인천·울산·제주는 없음 등).
 * 화면 범위 안 점만 이진 탐색으로 골라 지도에 그린다.
 */
object TrafficLights {
    private const val FILE = "traffic_lights.csv"

    @Volatile
    private var points: List<TrafficLightPoint>? = null

    fun parse(text: String): List<TrafficLightPoint> = text.lineSequence().mapNotNull { line ->
        val f = line.split(',')
        if (f.size < 2) return@mapNotNull null
        val lat = f[0].toDoubleOrNull() ?: return@mapNotNull null
        val lng = f[1].toDoubleOrNull() ?: return@mapNotNull null
        TrafficLightPoint(lat, lng, f.getOrNull(2)?.trim() == "1")
    }.sortedBy { it.lat }.toList()

    /** 파일을 한 번만 읽음. 못 읽으면 빈 목록 */
    fun load(context: Context): List<TrafficLightPoint> = points ?: synchronized(this) {
        points ?: runCatching {
            parse(context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrDefault(emptyList()).also { points = it }
    }

    /**
     * 남(S)·서(W)·북(N)·동(E) 범위 안의 신호등. 너무 많으면 [max] 개만(화면 가운데에 가까운 것부터).
     * [all] 은 위도 순으로 정렬돼 있어야 함. [skip] 이 true 인 점은 뺌(개수를 자르기 전에 빼서 그만큼 다른 점이 채워짐).
     */
    fun inBox(
        all: List<TrafficLightPoint>, s: Double, w: Double, n: Double, e: Double, max: Int = 400,
        skip: (TrafficLightPoint) -> Boolean = { false },
    ): List<TrafficLightPoint> {
        if (all.isEmpty()) return emptyList()
        var lo = 0
        var hi = all.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (all[mid].lat < s) lo = mid + 1 else hi = mid }
        val found = ArrayList<TrafficLightPoint>()
        var i = lo
        while (i < all.size && all[i].lat <= n) { val p = all[i]; if (p.lng in w..e && !skip(p)) found += p; i++ }
        if (found.size <= max) return found
        val cLat = (s + n) / 2
        val cLng = (w + e) / 2
        return found.sortedBy { (it.lat - cLat) * (it.lat - cLat) + (it.lng - cLng) * (it.lng - cLng) }.take(max)
    }

    /**
     * 실시간 신호가 있는 교차로 가운데([centers]) 에서 [meters] 안에 있는 점인지 가르는 함수. 실시간 신호 마커와 겹쳐 두 개로 보이지 않게
     * 신호등 위치에서 뺄 때 씀 ([inBox] 의 skip). 가운데가 없으면 아무것도 빼지 않음.
     */
    fun nearAny(centers: List<Pair<Double, Double>>, meters: Double): (TrafficLightPoint) -> Boolean {
        if (centers.isEmpty()) return { false }
        val limit = meters * meters
        return { p ->
            val mPerLng = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
            centers.any { (lat, lng) ->
                val dy = (p.lat - lat) * 111_320.0
                val dx = (p.lng - lng) * mPerLng
                dx * dx + dy * dy <= limit
            }
        }
    }
}
