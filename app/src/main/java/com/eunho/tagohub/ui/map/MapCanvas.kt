package com.eunho.tagohub.ui.map

import com.eunho.tagohub.data.LatLng

/** 카카오맵 지도 종류: 기본(NORMAL) / 위성(SKYVIEW + 도로명·지명) */
enum class BaseMap(val label: String) {
    Base("기본지도"),
    Satellite("위성지도"),
}

/** ViewModel → 지도. 구현은 KakaoCanvas (카카오맵 SDK v2). 색은 ARGB Int. */
data class StopPin(val id: String, val name: String, val lat: Double, val lng: Double, val selected: Boolean)
data class RoutePin(val id: String, val name: String, val lat: Double, val lng: Double, val focus: Boolean)
data class StationPin(val id: String, val name: String, val lat: Double, val lng: Double, val colors: List<Int>)
data class UticIncidentPin(val id: String, val title: String, val lat: Double, val lng: Double)
/**
 * 움직이는 차량. path 위에서 startFrac(지금) → endFrac 까지 durationMs 동안 이동한다.
 * 정지 차량은 path 가 점 하나.
 */
data class VehiclePin(
    val id: String,
    val path: List<LatLng>,
    val startFrac: Double,
    val endFrac: Double,
    val durationMs: Long,
    val color: Int,
    val label: String,
    val glyph: String,
)

/** 길찾기 경로 한 구간 (걷기는 점선) */
data class TripSegment(val points: List<LatLng>, val color: Int, val dashed: Boolean)

/** 길찾기 표시점 (출발·환승·도착) */
data class TripMark(val lat: Double, val lng: Double, val label: String, val color: Int)

interface MapCanvas {
    /** 지도 겹쳐 보기: 카카오 오버레이 id ("traffic_info" 교통정보, "bicycle_road" 자전거도로, "hill_shading" 지형도) */
    fun setOverlays(ids: Set<String>) {}
    /** 지도에서 꾹 누르거나 고른 곳 표시 (null 이면 지움) */
    fun setPin(lat: Double?, lng: Double?) {}
    /** 길찾기 경로 (빈 목록이면 지움) */
    fun setTrip(segments: List<TripSegment>, marks: List<TripMark>)
    /** 이 점들이 다 보이게 */
    fun fitTo(points: List<LatLng>)

    fun setStops(list: List<StopPin>)
    /**
     * shape: 도로 선형 (없으면 정류소를 직선으로 잇는다).
     * parts: 상·하행처럼 방향별로 나눈 선. 2개 이상이면 각 선을 진행 방향 오른쪽으로 조금 띄워 나란히 그린다.
     * strong: 진하게 그릴 방향 (나머지는 흐리게). null 이면 모두 진하게
     */
    fun setRoute(color: Int?, stops: List<RoutePin>, shape: List<LatLng>?, parts: List<List<LatLng>>? = null, strong: Int? = null)
    fun setBuses(list: List<VehiclePin>)
    fun setStations(list: List<StationPin>)
    fun setUticIncidents(list: List<UticIncidentPin>) {}
    fun setTrains(list: List<VehiclePin>)
    fun selectStation(id: String?)
    fun selectTrain(id: String?)
    fun setMe(lat: Double, lng: Double)
    /** zoom 이 null 이면 현재 배율 유지 */
    fun moveTo(lat: Double, lng: Double, zoom: Double?)
    /** 배율은 그대로, 아래 패널(화면의 panelFrac)에 가리지 않는 자리 가운데에 오도록 이동 */
    fun centerOn(lat: Double, lng: Double, panelFrac: Float)

    /** 정류소·역이 보이는 배율까지 확대하며 이동 */
    fun focus(lat: Double, lng: Double)
    fun setBase(type: BaseMap)
}
