package com.eunho.tagohub.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 노선 도로 선형. OSRM 공개 서버에서 받아 노선별로 메모리에 보관한다.
 * 카카오 경로(이 노선 번호 구간)를 먼저 쓰고, 안 되면 OSRM. 구간 요청이 실패하거나 결과가 이상하면 그 구간만 직선으로 둔다.
 * 공개 데모 서버라 대량 사용에는 맞지 않는다 → 배포 시 자체 OSRM 이나 상용 길찾기 API 로 교체.
 */
object RoadShapeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val cache = HashMap<String, List<LatLng>>()

    /**
     * 노선 모양 (지역 버스 API 가 실제 노선 모양을 안 주는 노선).
     * 1) 카카오 REST 키가 있으면: 정류장 6개씩 카카오 대중교통 경로를 받아 이 노선 번호를 타는 구간의 경로를 씀 (카카오 지도 도로를 따라감).
     *    구간 시작·끝이 정류장 200m 안이고, 사이 정류장들 곁(80m)을 지나고, 너무 돌아가지 않아야(정류장 직선 합의 2.2배 + 300m) 채택.
     * 2) 아니면 OSRM. 정류장 사이에서 블록을 한 바퀴 도는 구간은 직선으로 (Shapes.removeDetours).
     */
    /**
     * fallback: 지역 공식 노선 모양(서울시·경기·전주시). 카카오 키가 없으면 그대로 쓰고,
     * 있으면 카카오 경로를 못 받은 구간만 이 모양에서 잘라 채움 (그래도 안 되면 OSRM).
     */
    suspend fun shape(routeId: String, stops: List<LatLng>, routeNo: String? = null, fallback: List<LatLng>? = null): List<LatLng>? {
        if (stops.size < 2) return fallback
        synchronized(cache) { cache[routeId]?.let { return it } }
        if (fallback != null && (routeNo == null || !KakaoExtra.available)) return fallback
        val out = ArrayList<LatLng>()
        var anyRoad = false
        val kakao = routeNo != null && KakaoExtra.available
        val chunks = if (kakao) Osrm.chunks(stops, KAKAO_CHUNK) else Osrm.chunks(stops)
        for ((i, part) in chunks.withIndex()) {
            if (i > 0) delay(if (kakao) 120 else 250) // 서버 예의상 간격
            var piece: List<LatLng>? = null
            if (kakao) {
                val r = KakaoExtra.transit(part.first(), part.last())
                piece = r?.let { KakaoTransit.ridePath(it, routeNo!!, part.first(), part.last()) }
                    ?.takeIf { p -> Shapes.passesStops(p, part.drop(1).dropLast(1), 80.0) && Shapes.length(p) <= Shapes.length(part) * 2.2 + 300 }
            }
            if (piece == null && fallback != null) {
                // 카카오가 이 구간을 못 찾음 → 지역 공식 모양의 같은 구간 (사이 정류장 곁을 지나야)
                piece = Shapes.slice(fallback, part.first(), part.last())
                    ?.takeIf { p -> Shapes.passesStops(p, part.drop(1).dropLast(1), 80.0) }
            }
            if (piece == null) {
                piece = fetch(part)?.takeIf { Shapes.length(it) <= Shapes.length(part) * 3 + 300 }?.let { Shapes.removeDetours(it, part) }
            }
            if (piece != null) anyRoad = true
            val seg = piece ?: part
            out += if (out.isEmpty()) seg else seg.drop(1)
        }
        if (!anyRoad) return fallback
        synchronized(cache) { cache[routeId] = out }
        return out
    }

    /** 카카오 경로는 정류장 6개(5구간)씩 — 너무 길면 카카오가 다른 노선·환승을 먼저 제안함 */
    private const val KAKAO_CHUNK = 6

    private suspend fun fetch(points: List<LatLng>): List<LatLng>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(Osrm.url(points)).header("User-Agent", "TagoHub-Android").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else Osrm.parseGeometry(res.body?.string())
            }
        }.onFailure { Log.w("TagoMap", "도로 선형 요청 실패: ${it.message}") }.getOrNull()
    }
}
