package com.eunho.tagohub.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.PathMath
import com.eunho.tagohub.data.Shapes
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapOverlay
import com.kakao.vectormap.MapType
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelLayer
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextBuilder
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLinePattern
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import kotlin.math.roundToInt
import com.kakao.vectormap.LatLng as KLatLng

/**
 * 카카오맵 SDK v2 (KakaoMaps SDK for Android 2.15.2) 지도.
 * 앱의 유일한 지도. MapCanvas 를 구현하고 ViewModel 이 내용을 넘긴다.
 * - 정류장·역·버스·열차 = Label, 노선·길찾기 = RouteLine
 * - 상·하행 나란히: 카카오맵은 화면 픽셀로 선을 띄울 수 없어 지금 배율의 4dp 를 실제 거리로 바꿔 띄우고, 배율이 바뀌면 다시 그림
 * - 지도 변경 API 는 비동기라 화면 범위는 카메라 이동이 끝난 이벤트에서 읽는다 (개발 주의사항)
 * - 인증 실패(키 해시 미등록·카카오맵 사용 설정 꺼짐 등)면 onFail → 화면이 안내하고 새로고침 버튼으로 다시 시도
 */
class KakaoCanvas(
    ctx: Context,
    private val vm: MapViewModel,
    private val onFail: (String) -> Unit,
) : MapEngine {

    private val density = ctx.resources.displayMetrics.density
    private val appContext = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val icons = MarkerBitmaps(density)
    private val mapView = MapView(ctx)
    override val view get() = mapView

    private var map: KakaoMap? = null
    private var lastZoom = -1

    // ── 지도가 준비되기 전에 받은 내용 (준비되면 한꺼번에 그림) ──
    private var stops: List<StopPin> = emptyList()
    private var stations: List<StationPin> = emptyList()
    private var selectedStation: String? = null
    private var selectedTrain: String? = null
    private var routeArgs: RouteArgs? = null
    private var trip: Pair<List<TripSegment>, List<TripMark>> = emptyList<TripSegment>() to emptyList()
    private var me: LatLng? = null
    private var base: BaseMap = BaseMap.Base
    private var pendingCamera: (() -> Unit)? = null
    private var overlays: Set<String> = emptySet()
    private var pin: LatLng? = null
    private var pinLabel: Label? = null
    private var uticIncidents: List<UticIncidentPin> = emptyList()
    private var uticRoadRisks: List<UticRoadRiskPin> = emptyList()
    private var uticSafetyPoints: List<UticSafetyPin> = emptyList()
    private var itsCctvs: List<ItsCctvPin> = emptyList()
    private var itsRoadFeatures: List<ItsRoadFeaturePin> = emptyList()

    private data class RouteArgs(val color: Int?, val stops: List<RoutePin>, val shape: List<LatLng>?, val parts: List<List<LatLng>>?, val strong: Int?)

    // ── 그려 둔 것 ──
    private val stopLabels = HashMap<String, Label>()
    private val uticIncidentLabels = ArrayList<Label>()
    private val uticRoadRiskLabels = ArrayList<Label>()
    private val uticSafetyLabels = ArrayList<Label>()
    private val itsCctvLabels = ArrayList<Label>()
    private val itsRoadLabels = ArrayList<Label>()
    private val stationLabels = HashMap<String, Label>()
    private val routeLines = ArrayList<RouteLine>()
    private val routeLabels = ArrayList<Label>()
    private val tripLines = ArrayList<RouteLine>()
    private val tripLabels = ArrayList<Label>()
    private var meLabel: Label? = null
    private val styleCache = HashMap<String, LabelStyles>()

    /** 움직이는 차량 (버스·열차) */
    private class Mover(
        var label: Label, var path: List<LatLng>, var startFrac: Double, var endFrac: Double, var durMs: Long,
        var glideFrom: LatLng?, var glideMs: Long, var t0: Long, var styleKey: String,
    )
    private val buses = HashMap<String, Mover>()
    private val klidBuses = HashMap<String, Mover>()
    private val trains = HashMap<String, Mover>()
    private var busPins: List<VehiclePin> = emptyList()
    private var klidBusPins: List<VehiclePin> = emptyList()
    private var trainPins: List<VehiclePin> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var moving = false
            for (m in buses.values + trains.values) {
                val elapsed = now - m.t0
                val p = PathMath.vehicleAt(m.path, m.startFrac, m.endFrac, m.durMs, m.glideFrom, m.glideMs, elapsed)
                runCatching { m.label.moveTo(KLatLng.from(p.lat, p.lng)) }
                if (elapsed < m.glideMs + m.durMs) moving = true
            }
            if (moving) main.postDelayed(this, FRAME_MS)
        }
    }

    init {
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() = Unit
            override fun onMapError(error: Exception) {
                // 인증 오류 문구: "MapAuthException(401): Unauthorized" (키 해시·패키지명 미등록이면 401)
                val code = com.eunho.tagohub.data.MapErrors.kakaoCode(error.toString())
                Log.w(TAG, "카카오맵 오류 (코드 $code)", error)
                main.post {
                    val hash = keyHash(appContext) ?: "(확인 실패)"
                    onFail(
                        when (code) {
                            // 403 = 권한 오류: 2024-12 부터 새 앱은 카카오맵을 따로 켜야 함
                            403 -> "카카오맵 권한이 없어 지도를 표시하지 못했습니다. 카카오디벨로퍼스 [제품 설정] > [카카오맵]에서 사용 설정을 ON 하고, " +
                                "[앱] > [플랫폼 키] > [네이티브 앱 키] > [Android 앱 정보]에 패키지명 ${appContext.packageName} · 키 해시 $hash 를 등록하세요. (코드 403) 설정 후 새로고침 버튼을 누르세요."
                            // 401 = 인증 오류: 키 해시·패키지명 불일치 (2025-12 개편 후 네이티브 앱 키 아래에서 등록)
                            401 -> "카카오맵 인증에 실패해 지도를 표시하지 못했습니다. 카카오디벨로퍼스 [앱] > [플랫폼 키] > [네이티브 앱 키] > [Android 앱 정보]에 " +
                                "패키지명 ${appContext.packageName} · 키 해시 $hash 를 등록하세요. (코드 401) 설정 후 새로고침 버튼을 누르세요."
                            else -> "카카오맵을 시작하지 못했습니다. (${code ?: error.message})"
                        }
                    )
                }
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                main.post { setup(kakaoMap) }
            }
            override fun getPosition(): KLatLng = KLatLng.from(36.3, 127.8)
            override fun getZoomLevel(): Int = 7
        })
    }

    private fun setup(km: KakaoMap) {
        map = km
        km.labelManager?.let { lm ->
            LAYERS.forEach { (id, z) -> lm.addLayer(LabelLayerOptions.from(id).setZOrder(z)) }
        }
        km.routeLineManager?.addLayer(ROUTE_LAYER, 10)
        km.routeLineManager?.addLayer(TRIP_LAYER, 5)
        km.setOnCameraMoveEndListener { _, position, _ ->
            main.post {
                // 방위표시: 지도가 북쪽에서 조금이라도 돌아갔을 때만 (각도가 라디안·도 어느 쪽이어도 0.01 넘으면)
                runCatching { if (kotlin.math.abs(position.rotationAngle) > 0.01) map?.compass?.show() else map?.compass?.hide() }
                vm.onMapTouched()
                val z = position.zoomLevel
                if (z != lastZoom) {
                    lastZoom = z
                    if ((routeArgs?.parts?.size ?: 0) >= 2) drawRouteLines() // 배율이 바뀌면 상·하행 간격 다시
                }
                report()
            }
        }
        km.setOnLabelClickListener { _, layer, label ->
            val id = label.tag as? String
            if (id != null) main.post {
                when (layer.layerId) {
                    STOP_LAYER, ROUTE_PIN_LAYER -> vm.onStopClick(id)
                    STATION_LAYER -> vm.onStationClick(id)
                    TRAIN_LAYER -> vm.onTrainClick(id)
                    UTIC_INCIDENT_LAYER, UTIC_ROAD_RISK_LAYER, UTIC_SAFETY_LAYER, ITS_CCTV_LAYER, ITS_ROAD_LAYER -> showCallout(layer.layerId, id)
                    CALLOUT_LAYER -> openMarker(id)
                }
            }
            true
        }
        // 지도에 원래 있는 정류장·역·장소(POI) 누르기 → 앱 데이터의 정류장·역으로, 없으면 출발·도착 고르기
        km.setPoiClickable(true)
        km.setOnMapClickListener { _, position, _, poi ->
            vm.onMapTouched()
            clearCallout()
            if (poi.isPoi()) {
                val name = poi.getName().orEmpty()
                main.post { vm.onMapPoi(name, position.latitude, position.longitude) }
            }
        }
        // 바닥 꾹 누르기 → 여기서 출발 / 여기로 도착
        km.setOnTerrainLongClickListener { _, position, _ ->
            main.post { vm.onMapTouched(); vm.onMapLongPress(position.latitude, position.longitude) }
        }
        // 나침반: 기본 자리가 검색창에 가려져서 검색창 아래 왼쪽으로 (지도를 돌리면 방위가 보임, 누르면 북쪽으로)
        km.compass?.let {
            it.setBackToNorthOnClick(true)
            it.setPosition(com.kakao.vectormap.MapGravity.TOP or com.kakao.vectormap.MapGravity.LEFT, 16 * density, 104 * density)
            it.hide()   // 북쪽이면 숨김, 돌리면 보임 (카메라 이동 끝에서)
        }
        km.scaleBar?.let { it.setAutoHide(true); it.show() }
        applyBase()
        applyOverlays()
        vm.onMapMode("kakao")
        lastZoom = km.zoomLevel
        drawStops(); drawStations(); drawRoute(); drawTrip(); drawMe(); drawPin()
        drawVehicles(busPins, buses, BUS_LAYER, null)
        drawVehicles(klidBusPins, klidBuses, KLID_BUS_LAYER, null)
        drawVehicles(trainPins, trains, TRAIN_LAYER, selectedTrain)
        drawUticIncidents()
        drawUticRoadRisks()
        drawUticSafetyPoints()
        drawItsCctvs()
        drawItsRoadFeatures()
        pendingCamera?.invoke()
        pendingCamera = null
        main.postDelayed({ report() }, 300)
    }

    private fun layer(id: String): LabelLayer? = map?.labelManager?.getLayer(id)

    /** 같은 그림·글자 스타일은 한 번만 등록해서 다시 씀 */
    private fun styles(key: String, make: () -> LabelStyles): LabelStyles =
        styleCache.getOrPut(key) { map?.labelManager?.addLabelStyles(make()) ?: make() }

    private fun icon(b: Bitmap, ax: Float, ay: Float, zoom: Int = 0) =
        LabelStyle.from(b).setAnchorPoint(ax, ay).setApplyDpScale(false).setZoomLevel(zoom)

    /** 라벨 글자 (2.10.3 부터 문자열 대신 LabelTextBuilder 로 받음) */
    private fun text(s: String): LabelTextBuilder = LabelTextBuilder().setTexts(s)

    private fun textPx(sp: Float) = (sp * density).roundToInt()

    private fun withText(s: LabelStyle, sp: Float = 11f) =
        s.setTextStyles(textPx(sp), Color.rgb(34, 34, 34), (2 * density).roundToInt(), Color.WHITE)

    // ───────────────────────── 화면 범위 → ViewModel ─────────────────────────

    private fun report() {
        val km = map ?: return
        val cam = km.cameraPosition ?: return
        val vp = km.viewport
        if (vp.width() <= 0) return
        val nw = km.fromScreenPoint(vp.left, vp.top) ?: return
        val se = km.fromScreenPoint(vp.right, vp.bottom) ?: return
        val z = cam.zoomLevel.toDouble()
        // 2: 버스 정류소까지 보이는 배율, 1: 지하철역·열차, 0: 전국 
        val level = when {
            z >= 15.0 -> 2
            z >= 12.0 -> 1
            else -> 0
        }
        vm.onMoveEnd(cam.position.latitude, cam.position.longitude, z, level,
            minOf(nw.latitude, se.latitude), minOf(nw.longitude, se.longitude),
            maxOf(nw.latitude, se.latitude), maxOf(nw.longitude, se.longitude))
    }

    /** 지금 배율의 1px 이 몇 m 인지 (상·하행 띄우는 거리) */
    private fun metersPerPx(): Double {
        val km = map ?: return 0.0
        val vp = km.viewport
        val y = vp.centerY()
        val l = km.fromScreenPoint(vp.left, y) ?: return 0.0
        val r = km.fromScreenPoint(vp.right, y) ?: return 0.0
        return PathMath.metersPerPixel(LatLng(l.latitude, l.longitude), LatLng(r.latitude, r.longitude), vp.width())
    }

    // ───────────────────────── 정류장·역 ─────────────────────────

    override fun setStops(list: List<StopPin>) {
        if (stops == list) return
        stops = list
        drawStops()
    }

    private fun drawStops() {
        stopLabels.values.forEach { runCatching { it.remove() } }
        stopLabels.clear()
    }

    override fun setStations(list: List<StationPin>) {
        if (stations == list) return
        stations = list
        drawStations()
    }

    override fun setUticIncidents(list: List<UticIncidentPin>) {
        if (uticIncidents == list) return
        uticIncidents = list
        drawUticIncidents()
    }

    override fun setUticRoadRisks(list: List<UticRoadRiskPin>) {
        if (uticRoadRisks == list) return
        uticRoadRisks = list
        drawUticRoadRisks()
    }

    override fun setUticSafetyPoints(list: List<UticSafetyPin>) {
        if (uticSafetyPoints == list) return
        uticSafetyPoints = list
        drawUticSafetyPoints()
    }

    override fun setItsCctvs(list: List<ItsCctvPin>) {
        if (itsCctvs == list) return
        itsCctvs = list
        drawItsCctvs()
    }

    override fun setItsRoadFeatures(list: List<ItsRoadFeaturePin>) {
        if (itsRoadFeatures == list) return
        itsRoadFeatures = list
        drawItsRoadFeatures()
    }

    /** 눌렀을 때 말풍선·자세히 보기에 쓰는 정보 (키 = "층|id") */
    private class MarkerInfo(val layer: String, val id: String, val title: String, val lat: Double, val lng: Double)

    private val markerInfo = HashMap<String, MarkerInfo>()
    private var calloutLabel: Label? = null

    private fun registerMarkers(layerId: String, items: List<MarkerInfo>) {
        markerInfo.keys.removeAll { it.startsWith("$layerId|") }
        items.forEach { markerInfo["$layerId|${it.id}"] = it }
    }

    private fun clearCallout() {
        calloutLabel?.let { runCatching { it.remove() } }
        calloutLabel = null
    }

    /** 마커를 누르면 그 위에 제목 말풍선만 띄움. 말풍선을 누르면 자세히 */
    private fun showCallout(layerId: String, id: String) {
        val info = markerInfo["$layerId|$id"] ?: return
        val l = layer(CALLOUT_LAYER) ?: return
        clearCallout()
        val bitmap = icons.callout(info.title.ifBlank { "정보" })
        calloutLabel = l.addLabel(
            LabelOptions.from(KLatLng.from(info.lat, info.lng))
                .setStyles(LabelStyles.from(icon(bitmap, 0.5f, 1f)))
                .setClickable(true)
                .setTag("$layerId|$id"),
        )
    }

    private fun openMarker(key: String) {
        val layerId = key.substringBefore('|')
        val id = key.substringAfter('|')
        clearCallout()
        when (layerId) {
            UTIC_INCIDENT_LAYER -> vm.onUticIncidentClick(id)
            UTIC_ROAD_RISK_LAYER -> vm.onUticRoadRiskClick(id)
            UTIC_SAFETY_LAYER -> vm.onUticSafetyPointClick(id)
            ITS_CCTV_LAYER -> vm.onItsCctvClick(id)
            ITS_ROAD_LAYER -> vm.onItsRoadFeatureClick(id)
        }
    }

    private val AMBER = 0xFFFFC107.toInt()

    private fun drawItsRoadFeatures() {
        itsRoadLabels.forEach { runCatching { it.remove() } }
        itsRoadLabels.clear()
        registerMarkers(ITS_ROAD_LAYER, itsRoadFeatures.map { MarkerInfo(ITS_ROAD_LAYER, it.id, it.title, it.lat, it.lng) })
        if (itsRoadFeatures.isEmpty()) return
        val l = layer(ITS_ROAD_LAYER) ?: return
        itsRoadFeatures.forEach { feature ->
            val st = styles("itsRoad:${feature.layer}") {
                val marker = when (feature.layer) {
                    "its_events", "its_safe_driving" -> icons.triangleBadge(AMBER)
                    "its_dangerous_cars" -> icons.triangleBadge(UTIC_ORANGE)
                    "its_disasters" -> icons.letterBadge(0xFF7B1FA2.toInt(), "재")
                    "its_vsl" -> icons.letterBadge(0xFF00897B.toInt(), "V")
                    "its_vms" -> icons.letterBadge(0xFF1565C0.toInt(), "전")
                    "its_vds" -> icons.letterBadge(0xFF546E7A.toInt(), "D")
                    "its_ice_sections" -> icons.letterBadge(0xFF00ACC1.toInt(), "빙")
                    "ex_rest" -> icons.letterBadge(0xFF2E7D32.toInt(), "휴")
                    "ex_toll" -> icons.letterBadge(0xFF1565C0.toInt(), "톨")
                    "ex_ic" -> icons.letterBadge(0xFF6D4C41.toInt(), "IC")
                    "ex_vms" -> icons.letterBadge(0xFFEF6C00.toInt(), "전광")
                    else -> icons.letterBadge(ITS_CCTV_COLOR, "I")
                }
                LabelStyles.from(icon(marker, 0.5f, 0.5f, 10))
            }
            l.addLabel(
                LabelOptions.from("ir:${feature.id}", KLatLng.from(feature.lat, feature.lng))
                    .setStyles(st)
                    .setClickable(true)
                    .setTag(feature.id),
            )?.let(itsRoadLabels::add)
        }
    }

    private fun drawItsCctvs() {
        itsCctvLabels.forEach { runCatching { it.remove() } }
        itsCctvLabels.clear()
        registerMarkers(ITS_CCTV_LAYER, itsCctvs.map { MarkerInfo(ITS_CCTV_LAYER, it.id, it.title, it.lat, it.lng) })
        if ("its_cctv" !in overlays) return
        val l = layer(ITS_CCTV_LAYER) ?: return
        val st = styles("itsCctv") { LabelStyles.from(icon(icons.cameraBadge(), 0.5f, 0.5f, 11)) }
        itsCctvs.forEach { cctv ->
            l.addLabel(
                LabelOptions.from("ic:${cctv.id}", KLatLng.from(cctv.lat, cctv.lng))
                    .setStyles(st)
                    .setClickable(true)
                    .setTag(cctv.id),
            )?.let(itsCctvLabels::add)
        }
    }

    private fun drawUticIncidents() {
        uticIncidentLabels.forEach { runCatching { it.remove() } }
        uticIncidentLabels.clear()
        registerMarkers(UTIC_INCIDENT_LAYER, uticIncidents.map { MarkerInfo(UTIC_INCIDENT_LAYER, it.id, it.title, it.lat, it.lng) })
        if ("utic_incidents" !in overlays) return
        val l = layer(UTIC_INCIDENT_LAYER) ?: return
        val st = styles("uticIncident") { LabelStyles.from(icon(icons.triangleBadge(AMBER), 0.5f, 0.5f, 10)) }
        uticIncidents.forEach { incident ->
            l.addLabel(
                LabelOptions.from("ui:${incident.id}", KLatLng.from(incident.lat, incident.lng))
                    .setStyles(st)
                    .setClickable(true)
                    .setTag(incident.id),
            )?.let(uticIncidentLabels::add)
        }
    }

    private fun drawUticRoadRisks() {
        uticRoadRiskLabels.forEach { runCatching { it.remove() } }
        uticRoadRiskLabels.clear()
        registerMarkers(UTIC_ROAD_RISK_LAYER, uticRoadRisks.map { MarkerInfo(UTIC_ROAD_RISK_LAYER, it.id, it.title, it.lat, it.lng) })
        if ("utic_road_risk" !in overlays) return
        val l = layer(UTIC_ROAD_RISK_LAYER) ?: return
        val st = styles("uticRoadRisk") { LabelStyles.from(icon(icons.triangleBadge(UTIC_ORANGE), 0.5f, 0.5f, 10)) }
        uticRoadRisks.forEach { risk ->
            l.addLabel(
                LabelOptions.from("ur:${risk.id}", KLatLng.from(risk.lat, risk.lng))
                    .setStyles(st)
                    .setClickable(true)
                    .setTag(risk.id),
            )?.let(uticRoadRiskLabels::add)
        }
    }

    private fun drawUticSafetyPoints() {
        uticSafetyLabels.forEach { runCatching { it.remove() } }
        uticSafetyLabels.clear()
        registerMarkers(UTIC_SAFETY_LAYER, uticSafetyPoints.map { MarkerInfo(UTIC_SAFETY_LAYER, it.id, it.title, it.lat, it.lng) })
        if ("utic_safety" !in overlays) return
        val l = layer(UTIC_SAFETY_LAYER) ?: return
        val st = styles("uticSafety") { LabelStyles.from(icon(icons.letterBadge(UTIC_BLUE, "안"), 0.5f, 0.5f, 11)) }
        uticSafetyPoints.forEach { point ->
            l.addLabel(
                LabelOptions.from("us:${point.id}", KLatLng.from(point.lat, point.lng))
                    .setStyles(st)
                    .setClickable(true)
                    .setTag(point.id),
            )?.let(uticSafetyLabels::add)
        }
    }

    override fun selectStation(id: String?) {
        selectedStation = id
        drawStations(restyle = true)
    }

    private fun drawStations(restyle: Boolean = false) {
        val l = layer(STATION_LAYER) ?: return
        // 지하철역도 카카오 지도 POI 로. 앱은 고른 역만 강조
        val want = stations.filter { it.id == selectedStation }.associateBy { it.id }
        stationLabels.keys.filter { it !in want }.forEach { id -> stationLabels.remove(id)?.let { runCatching { it.remove() } } }
        for (p in want.values) {
            val ring = if (p.colors.size > 1) TRANSFER_RING else p.colors.firstOrNull() ?: TRANSFER_RING
            val sel = p.id == selectedStation
            val st = styles("st$ring$sel") {
                if (sel) LabelStyles.from(withText(icon(icons.station(ring, 1.4f, true), 0.5f, 0.5f), 12f))
                else LabelStyles.from(icon(icons.station(ring, 1f, false), 0.5f, 0.5f, 12), withText(icon(icons.station(ring, 1f, false), 0.5f, 0.5f, 15)))
            }
            val existing = stationLabels[p.id]
            if (existing == null) {
                l.addLabel(LabelOptions.from("t:" + p.id, KLatLng.from(p.lat, p.lng)).setStyles(st).setTexts(text(p.name)).setClickable(true).setTag(p.id))
                    ?.let { stationLabels[p.id] = it }
            } else if (restyle) {
                runCatching { existing.changeStyles(st) }
            }
        }
    }

    // ───────────────────────── 노선 ─────────────────────────

    override fun setRoute(color: Int?, stops: List<RoutePin>, shape: List<LatLng>?, parts: List<List<LatLng>>?, strong: Int?) {
        routeArgs = RouteArgs(color, stops, shape, parts, strong)
        drawRoute()
    }

    private fun drawRoute() {
        drawRouteLines()
        val l = layer(ROUTE_PIN_LAYER) ?: return
        routeLabels.forEach { runCatching { it.remove() } }
        routeLabels.clear()
        val a = routeArgs ?: return
        val color = a.color ?: return
        for (p in a.stops) {
            val st = styles("rp$color${p.focus}") {
                if (p.focus) LabelStyles.from(withText(icon(icons.routeDot(color, true), 0.5f, 0.5f), 12f))
                else LabelStyles.from(icon(icons.routeDot(color, false), 0.5f, 0.5f, 12), withText(icon(icons.routeDot(color, false), 0.5f, 0.5f, 16)))
            }
            l.addLabel(LabelOptions.from(KLatLng.from(p.lat, p.lng)).setStyles(st).setTexts(text(p.name)).setClickable(true).setTag(p.id))
                ?.let { routeLabels += it }
        }
    }

    /** 노선 선: 상·하행이면 각자 오른쪽으로 4dp(지금 배율의 실제 거리)씩 띄우고, 고른 방향은 진하게 */
    private fun drawRouteLines() {
        val km = map ?: return
        val layer = km.routeLineManager?.getLayer(ROUTE_LAYER) ?: return
        routeLines.forEach { runCatching { it.remove() } }
        routeLines.clear()
        val a = routeArgs ?: return
        val color = a.color ?: return
        val lines: List<Pair<List<LatLng>, Boolean>> = if ((a.parts?.size ?: 0) >= 2) {
            val d = ROUTE_LINE_WIDTH_DP * 0.9 * density * metersPerPx()  // 상·하행 두 줄이 굵어진 만큼 더 띄움
            a.parts!!.mapIndexed { i, p -> PathMath.offsetMeters(p, d) to (a.strong == null || a.strong == i) }
        } else {
            listOf((a.shape?.takeIf { it.size >= 2 } ?: a.stops.map { LatLng(it.lat, it.lng) }) to true)
        }
        for ((pts, strong) in lines) {
            if (pts.size < 2) continue
            val c = if (strong) color else (color and 0x00FFFFFF) or (0x5A shl 24)
            val style = RouteLineStyle.from(ROUTE_LINE_WIDTH_DP, c, 2f, if (strong) Color.WHITE else 0x78FFFFFF,
                RouteLinePattern.from(icons.arrowPattern(), 70f))
            add(layer, pts, RouteLineStyles.from(style))?.let { routeLines += it }
        }
    }

    private fun add(layer: com.kakao.vectormap.route.RouteLineLayer, pts: List<LatLng>, styles: RouteLineStyles): RouteLine? {
        val set = RouteLineStylesSet.from(styles)
        val seg = RouteLineSegment.from(com.eunho.tagohub.data.Shapes.smooth(pts).map { KLatLng.from(it.lat, it.lng) }.toTypedArray(), styles)   // 꺾이는 곳 부드럽게
        return runCatching { layer.addRouteLine(RouteLineOptions.from(seg).setStylesSet(set)) }.getOrNull()
    }

    // ───────────────────────── 길찾기 ─────────────────────────

    override fun setTrip(segments: List<TripSegment>, marks: List<TripMark>) {
        trip = segments to marks
        drawTrip()
    }

    private fun drawTrip() {
        val km = map ?: return
        val layer = km.routeLineManager?.getLayer(TRIP_LAYER) ?: return
        tripLines.forEach { runCatching { it.remove() } }
        tripLines.clear()
        tripLabels.forEach { runCatching { it.remove() } }
        tripLabels.clear()
        val (segs, marks) = trip
        for (s in segs) {
            if (s.points.size < 2) continue
            val style = if (s.dashed) RouteLineStyle.from(RouteLinePattern.from(icons.walkDot(s.color), 9f))
                else RouteLineStyle.from(6f, s.color, 1.5f, Color.WHITE)
            add(layer, s.points, RouteLineStyles.from(style))?.let { tripLines += it }
        }
        val l = layer(TRIP_LAYER) ?: return
        for (m in marks) {
            val st = styles("tm${m.color}") { LabelStyles.from(withText(icon(icons.tripMark(m.color), 0.5f, 0.5f), 12f)) }
            l.addLabel(LabelOptions.from(KLatLng.from(m.lat, m.lng)).setStyles(st).setTexts(text(m.label)))?.let { tripLabels += it }
        }
    }

    // ───────────────────────── 버스·열차 ─────────────────────────

    override fun setBuses(list: List<VehiclePin>) {
        busPins = list
        drawVehicles(list, buses, BUS_LAYER, null)
    }

    override fun setKlidBuses(list: List<VehiclePin>) {
        klidBusPins = list
        drawVehicles(list, klidBuses, KLID_BUS_LAYER, null)
    }

    override fun setTrains(list: List<VehiclePin>) {
        trainPins = list
        drawVehicles(list, trains, TRAIN_LAYER, selectedTrain)
    }

    override fun selectTrain(id: String?) {
        selectedTrain = id
        drawVehicles(trainPins, trains, TRAIN_LAYER, id)
    }

    private fun drawVehicles(list: List<VehiclePin>, movers: HashMap<String, Mover>, layerId: String, selected: String?) {
        val l = layer(layerId) ?: return
        val want = list.associateBy { it.id }
        movers.keys.filter { it !in want }.forEach { id -> movers.remove(id)?.let { runCatching { it.label.remove() } } }
        val now = System.currentTimeMillis()
        for (v in list) {
            if (v.path.isEmpty()) continue
            val sel = v.id == selected
            val key = "v${v.color}${v.glyph}$sel"
            val st = styles(key) {
                val b = icons.vehicle(v.color, v.glyph, sel)
                LabelStyles.from(icon(b, 0.5f, 0.5f), withText(icon(b, 0.5f, 0.5f, 14), 10f))
            }
            val start = Shapes.along(v.path, v.startFrac)
            val m = movers[v.id]
            if (m == null) {
                val label = l.addLabel(LabelOptions.from(KLatLng.from(start.lat, start.lng)).setStyles(st).setTexts(text(v.label))
                    .setClickable(layerId == TRAIN_LAYER).setTag(v.id)) ?: continue
                movers[v.id] = Mover(label, v.path, v.startFrac, v.endFrac, v.durationMs, null, 0, now, key)
                continue
            }
            if (m.styleKey != key) {
                runCatching { m.label.changeStyles(st) }
                m.styleKey = key
            }
            // 지금 보이는 자리에서 이어서: 새 경로 위에서 이미 앞서 있으면 거기서부터, 아니면 짧게 미끄러져 새 시작점으로 (뒤로 순간이동 없음)
            val shownRaw = runCatching { m.label.position }.getOrNull()
            val shown = shownRaw?.let { LatLng(it.latitude, it.longitude) }
            var startFrac = v.startFrac
            var dur = v.durationMs
            var glide: LatLng? = null
            if (shown != null && Geo.meters(shown.lat, shown.lng, start.lat, start.lng) > 3.0) {
                val (f, dist) = if (v.path.size >= 2) Shapes.project(v.path, shown) else (0.0 to Double.MAX_VALUE)
                val end = maxOf(v.endFrac, v.startFrac)
                if (dist < 15.0 && f > v.startFrac) {
                    val span = end - v.startFrac
                    dur = if (span <= 0 || f >= end) 0L else (v.durationMs * (end - f) / span).toLong()
                    startFrac = minOf(f, end)
                } else {
                    glide = shown
                }
            }
            m.path = v.path
            m.startFrac = startFrac
            m.endFrac = maxOf(v.endFrac, startFrac)
            m.durMs = dur
            m.glideFrom = glide
            m.glideMs = if (glide != null) GLIDE_MS else 0
            m.t0 = now
        }
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    // ───────────────────────── 내 위치·카메라 ─────────────────────────

    override fun setMe(lat: Double, lng: Double) {
        me = LatLng(lat, lng)
        drawMe()
    }

    private fun drawMe() {
        val p = me ?: return
        val existing = meLabel
        if (existing != null) {
            runCatching { existing.moveTo(KLatLng.from(p.lat, p.lng)) }
            return
        }
        val l = layer(ME_LAYER) ?: return
        val st = styles("me") { LabelStyles.from(icon(icons.me(), 0.5f, 0.5f)) }
        meLabel = l.addLabel(LabelOptions.from(KLatLng.from(p.lat, p.lng)).setStyles(st))
    }

    private fun camera(block: (KakaoMap) -> Unit) {
        val km = map
        if (km == null) pendingCamera = { map?.let(block) } else block(km)
    }

    override fun moveTo(lat: Double, lng: Double, zoom: Double?) = camera { km ->
        val pos = KLatLng.from(lat, lng)
        val update = if (zoom != null) CameraUpdateFactory.newCenterPosition(pos, zoom.roundToInt()) else CameraUpdateFactory.newCenterPosition(pos)
        km.moveCamera(update, CameraAnimation.from(400, true, true))
    }

    override fun centerOn(lat: Double, lng: Double, panelFrac: Float) = camera { km ->
        // 아래 패널이 화면 아래쪽을 가리므로 지도 중심을 그 절반만큼 아래로 → 정류장이 보이는 영역 한가운데
        val vp = km.viewport
        val top = km.fromScreenPoint(vp.centerX(), vp.top)
        val bottom = km.fromScreenPoint(vp.centerX(), vp.bottom)
        val span = if (top != null && bottom != null) top.latitude - bottom.latitude else 0.0
        km.moveCamera(CameraUpdateFactory.newCenterPosition(KLatLng.from(lat - span * panelFrac / 2, lng)), CameraAnimation.from(350, true, true))
    }

    override fun focus(lat: Double, lng: Double) = camera { km ->
        moveTo(lat, lng, maxOf(km.zoomLevel, 16).toDouble())
    }

    override fun fitTo(points: List<LatLng>) {
        if (points.size < 2) return
        camera { km ->
            val arr = points.map { KLatLng.from(it.lat, it.lng) }.toTypedArray()
            km.moveCamera(CameraUpdateFactory.fitMapPoints(arr, (56 * density).toInt()), CameraAnimation.from(400, true, true))
        }
    }

    override fun setOverlays(ids: Set<String>) {
        if (overlays == ids) return
        val changed = (overlays - ids) + (ids - overlays)
        overlays = ids
        if (changed.any { id -> OVERLAY_IDS.any { it.first == id } }) applyOverlays()
        if ("utic_incidents" in changed) drawUticIncidents()
        if ("utic_road_risk" in changed) drawUticRoadRisks()
        if ("utic_safety" in changed) drawUticSafetyPoints()
        if ("its_cctv" in changed) drawItsCctvs()
        if (changed.any { it.startsWith("its_") && it != "its_cctv" }) drawItsRoadFeatures()
    }

    private fun applyOverlays() {
        val km = map ?: return
        OVERLAY_IDS.forEach { (id, ov) -> runCatching { if (id in overlays) km.showOverlay(ov) else km.hideOverlay(ov) } }
    }

    override fun setPin(lat: Double?, lng: Double?) {
        pin = if (lat != null && lng != null) LatLng(lat, lng) else null
        drawPin()
    }

    private fun drawPin() {
        pinLabel?.let { runCatching { it.remove() } }
        pinLabel = null
        val p = pin ?: return
        val l = layer(PIN_LAYER) ?: return
        val st = styles("pin") { LabelStyles.from(icon(icons.pin(), 0.5f, 1f)) }
        pinLabel = l.addLabel(LabelOptions.from(KLatLng.from(p.lat, p.lng)).setStyles(st))
    }

    override fun setBase(type: BaseMap) {
        base = type
        applyBase()
    }

    /** 카카오맵 종류: 기본(NORMAL) / 위성(SKYVIEW + 도로명·지명 SKYVIEW_HYBRID). 백지도·야간지도는 카카오에 없어 기본지도 */
    private fun applyBase() {
        val km = map ?: return
        if (base == BaseMap.Satellite) {
            km.changeMapType(MapType.SKYVIEW)
            km.showOverlay(MapOverlay.SKYVIEW_HYBRID)
        } else {
            km.hideOverlay(MapOverlay.SKYVIEW_HYBRID)
            km.changeMapType(MapType.NORMAL)
        }
    }

    // ───────────────────────── 생명주기 ─────────────────────────

    override fun resume() = runCatching { mapView.resume() }.let { }
    override fun pause() = runCatching { mapView.pause() }.let { }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { mapView.finish() }
        map = null
    }

    companion object {
        private const val TAG = "TagoKakao"
        private const val FRAME_MS = 66L
        private const val GLIDE_MS = 1_200L
        private const val STOP_LAYER = "stops"
        private const val STATION_LAYER = "stations"
        private const val ROUTE_PIN_LAYER = "routePins"
        private const val TRIP_LAYER = "trip"
        private const val BUS_LAYER = "buses"
        private const val KLID_BUS_LAYER = "klidBuses"
        private const val TRAIN_LAYER = "trains"
        private const val ME_LAYER = "me"
        private const val PIN_LAYER = "pin"
        private const val UTIC_INCIDENT_LAYER = "uticIncidents"
        private const val UTIC_ROAD_RISK_LAYER = "uticRoadRisks"
        private const val UTIC_SAFETY_LAYER = "uticSafety"
        private const val ITS_CCTV_LAYER = "itsCctvs"
        private const val ITS_ROAD_LAYER = "itsRoadFeatures"
        private const val CALLOUT_LAYER = "callout"
        /** 카카오 지도 오버레이 id (KakaoMap.showOverlay(String) 문서) */
        // 안드로이드 공식 MapOverlay: 로드뷰 라인·지형·자전거도로·스카이뷰 하이브리드 (교통정보는 카카오와 협의해야 쓸 수 있음)
        val OVERLAY_IDS = listOf("bicycle_road" to com.kakao.vectormap.MapOverlay.BICYCLE_ROAD, "hill_shading" to com.kakao.vectormap.MapOverlay.HILLSHADING)
        private const val ROUTE_LAYER = "routeLines"
        /** 노선도 선 굵기 (dp). 예전 5 → 8 */
        private const val ROUTE_LINE_WIDTH_DP = 8f
        /** 라벨 층 순서 (숫자가 클수록 위) */
        private val LAYERS = listOf(STATION_LAYER to 100, STOP_LAYER to 200, ROUTE_PIN_LAYER to 300, TRIP_LAYER to 350,
            BUS_LAYER to 400, KLID_BUS_LAYER to 420, TRAIN_LAYER to 450, UTIC_INCIDENT_LAYER to 470, UTIC_ROAD_RISK_LAYER to 475,
            UTIC_SAFETY_LAYER to 478, ITS_CCTV_LAYER to 479, ITS_ROAD_LAYER to 481, PIN_LAYER to 490, CALLOUT_LAYER to 495, ME_LAYER to 500)
        private val TRANSFER_RING = 0xFF333F4F.toInt()
        private val UTIC_ORANGE = 0xFFF57C00.toInt()
        private val UTIC_BLUE = 0xFF1976D2.toInt()
        private val ITS_CCTV_COLOR = 0xFF673AB7.toInt()

        /**
         * 이 앱의 키 해시 (카카오 개발자 사이트에 등록하는 값): 서명 인증서 SHA-1 의 Base64.
         * 디버그·릴리즈 서명이 다르면 값도 달라서 둘 다 등록해야 한다.
         */
        fun keyHash(ctx: Context): String? = runCatching {
            val pm = ctx.packageManager
            val sigs: Array<android.content.pm.Signature> = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
            val sig = sigs.firstOrNull() ?: return null
            val sha = java.security.MessageDigest.getInstance("SHA").digest(sig.toByteArray())
            android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
        }.getOrNull()

        /** 카카오 앱 키가 있는지 */
        val available: Boolean get() = BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()
    }
}
