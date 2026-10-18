@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import com.eunho.tagohub.data.RouteTimes
import com.eunho.tagohub.data.JeonjuTimetable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.data.TripPlan
import com.eunho.tagohub.data.TripLeg
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Train
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import com.eunho.tagohub.data.Favorite
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.ui.bus.BusDest
import com.eunho.tagohub.ui.bus.StopRouteItem
import com.eunho.tagohub.ui.bus.currentLocation
import com.eunho.tagohub.ui.bus.lastKnownLocation
import com.eunho.tagohub.ui.bus.hasLocationPermission
import com.eunho.tagohub.ui.bus.startLocationUpdates
import com.eunho.tagohub.ui.common.LoadView
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.ui.bus.RouteInfoCard
import com.eunho.tagohub.ui.bus.RouteListRow
import com.eunho.tagohub.ui.bus.StopListRow
import com.eunho.tagohub.ui.bus.TimelineRow
import com.eunho.tagohub.ui.bus.TurnMarker
import com.eunho.tagohub.ui.common.PlacePickerSheet
import com.eunho.tagohub.ui.common.SectionHeader
import com.eunho.tagohub.ui.common.SearchBox
import com.eunho.tagohub.ui.subway.StationDetailPanel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.Tnum
import com.eunho.tagohub.data.StationGroup
import com.eunho.tagohub.ui.theme.subwayLineColor
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private fun LocalTime.hhmm() = TagoTime.clockText(hour * 60 + minute)

@Composable
fun MapScreen(
    vm: MapViewModel = viewModel(),
    mapMode: MapMode = MapMode.Bus,
    onMapModeChange: ((MapMode) -> Unit)? = null,
) {
    LaunchedEffect(mapMode) { vm.configure(mapMode) }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    val mode by vm.mode.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val needZoom by vm.needZoom.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val trainView by vm.trainView.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val wantsLocate by vm.wantsLocate.collectAsStateWithLifecycle()
    val baseMap by vm.baseMap.collectAsStateWithLifecycle()
    var baseMenu by remember { mutableStateOf(false) }
    val station by vm.station.collectAsStateWithLifecycle()
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    val overlays by vm.overlays.collectAsStateWithLifecycle()
    val uticLoading by vm.uticLoading.collectAsStateWithLifecycle()
    val uticRiskLoading by vm.uticRiskLoading.collectAsStateWithLifecycle()
    val uticSafetyLoading by vm.uticSafetyLoading.collectAsStateWithLifecycle()
    val itsCctvLoading by vm.itsCctvLoading.collectAsStateWithLifecycle()
    val selectedItsCctv by vm.selectedItsCctv.collectAsStateWithLifecycle()
    val itsRoadLoading by vm.itsRoadLoading.collectAsStateWithLifecycle()
    val itsRoadCachedLayers by vm.itsRoadCachedLayers.collectAsStateWithLifecycle()
    val selectedItsRoadFeature by vm.selectedItsRoadFeature.collectAsStateWithLifecycle()

    // 지도: 카카오맵. 인증이 실패하면 이유(등록할 패키지명·키 해시)를 알리고, 설정을 고친 뒤 새로고침 버튼으로 다시 시도.
    // 화면 생명주기에 맞춰 resume/pause (카카오맵은 꼭 불러야 함)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapAttempt by remember { mutableStateOf(0) }
    var mapFailed by remember { mutableStateOf(false) }
    val osm: MapEngine = remember(mapAttempt) {
        mapFailed = false
        if (!KakaoCanvas.available) vm.onMapProblem("카카오맵 앱 키가 없습니다. local.properties 에 KAKAO_NATIVE_KEY 를 넣고 다시 빌드하세요.")
        KakaoCanvas(context, vm) { reason ->
            mapFailed = true
            vm.onKakaoFailed(reason, context.packageName, KakaoCanvas.keyHash(context))
        }
    }
    DisposableEffect(osm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> osm.resume()
                Lifecycle.Event.ON_PAUSE -> osm.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        osm.resume()
        vm.attach(osm, dark)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.detach(osm)
            osm.release()
        }
    }

    fun locate() {
        scope.launch {
            val loc = currentLocation(context)
            if (loc == null) vm.banner.value = "현재 위치를 가져오지 못했습니다. 위치 서비스가 켜져 있는지 확인하세요."
            else vm.setMe(loc.latitude, loc.longitude, center = true)
        }
    }

    var locationKey by remember { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) { locate(); locationKey++ } else vm.banner.value = "위치 권한이 없어 현재 위치를 표시할 수 없습니다."
    }
    // 내 위치: 화면이 보이는 동안 GPS 를 따라 계속 움직임 (앱이 뒤로 가면 멈춰 배터리 아낌)
    DisposableEffect(lifecycle, locationKey) {
        var stop: () -> Unit = {}
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { stop(); stop = startLocationUpdates(context) { vm.setMe(it.latitude, it.longitude, center = false) } }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { stop(); stop = {} }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); stop() }
    }

    // 시작: 저장된 자리가 있으면 그대로 두고 내 위치 점만, 없으면 내 위치로 이동.
    // 휴대폰이 알고 있는 마지막 위치를 먼저 바로 쓰고, 새 위치는 뒤에서 받아 보정 (GPS 를 기다리지 않음)
    LaunchedEffect(wantsLocate) {
        if (wantsLocate) {
            vm.wantsLocate.value = false
            if (!hasLocationPermission(context) && !vm.startsAtSaved && vm.askLocationOnce()) {
                // 앱을 처음 켰을 때 현재 위치로 띄우려면 권한이 필요 → 한 번만 묻는다 (허용하면 locate() 가 내 위치로 이동)
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else if (hasLocationPermission(context)) {
                val center = !vm.startsAtSaved
                val quick = lastKnownLocation(context)
                quick?.let { vm.setMe(it.latitude, it.longitude, center) }
                currentLocation(context)?.let { vm.setMe(it.latitude, it.longitude, center && quick == null) }
            }
        }
    }

    // 패널이 떠 있는 동안만 도착정보·버스위치 갱신
    LaunchedEffect(selected, refreshTick) { selected?.let { vm.runStop(it) } }
    LaunchedEffect(route, refreshTick) { route?.let { vm.runRoute(it) } }

    BackHandler(enabled = panel != MapPanel.None) { vm.closePanel() }
    // 장소 카드가 열려 있으면 뒤로 가기로 먼저 닫음 (앱 종료 묻기보다 먼저)
    val pickedOpen by vm.picked.collectAsStateWithLifecycle()
    BackHandler(enabled = pickedOpen != null) { vm.closePick() }

    // 패널 높이(화면 대비 비율). 패널 종류가 바뀌면 기본 높이로
    val sheetFrac = remember { mutableFloatStateOf(0.45f) }
    LaunchedEffect(panel) {
        sheetFrac.floatValue = when (panel) {
            MapPanel.Stop -> 0.4f
            MapPanel.Route, MapPanel.Train -> 0.5f
            MapPanel.Station, MapPanel.Plan -> 0.55f
            MapPanel.None -> sheetFrac.floatValue
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
      val sheet = remember(constraints.maxHeight) { Sheet(sheetFrac, constraints.maxHeight.toFloat()) }
      CompositionLocalProvider(LocalSheet provides sheet) {
        key(osm) { AndroidView(factory = { osm.view }, modifier = Modifier.fillMaxSize()) }

        // ── 상단 안내 ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 64.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (mapMode) {
                MapMode.Subway -> StationSearch(vm)
                MapMode.Bus -> BusSearchBar(vm)
                MapMode.Transit -> BusSearchBar(vm, withStations = true)
            }
            banner?.let { Banner(it) { vm.banner.value = null } }
            when {
                mode == null -> Hint("지도를 불러오는 중")
                needZoom && panel == MapPanel.None -> Hint(
                    if (mapMode == MapMode.Transit) "지도를 확대하면 정류장·지하철역과 실시간 열차가 표시됩니다"
                    else if (mapMode == MapMode.Subway) "지도를 확대하면 지하철역과 실시간 열차가 표시됩니다"
                    else "지도를 확대하면 주변 버스 정류장이 표시됩니다"
                )
            }
            if ("utic_incidents" in overlays && uticLoading) Hint("UTIC 돌발정보 불러오는 중")
            if ("utic_road_risk" in overlays && uticRiskLoading) Hint("UTIC 도로위험예보 불러오는 중")
            if ("utic_safety" in overlays && uticSafetyLoading) Hint("UTIC 교통안전정보 불러오는 중")
            if ("its_cctv" in overlays && itsCctvLoading) Hint("ITS CCTV 불러오는 중")
            val itsRoadLabels = mapOf(
                "its_events" to "ITS 돌발정보",
                "its_disasters" to "ITS 재난정보",
                "its_vsl" to "가변속도표지",
                "its_vms" to "도로전광표지",
                "its_safe_driving" to "주의운전",
                "its_dangerous_cars" to "위험물 사고",
                "its_vds" to "VDS 시설물",
                "its_ice_sections" to "상습 결빙구간",
            )
            itsRoadLabels.filterKeys { it in overlays }.forEach { (id, label) ->
                if (id in itsRoadLoading) Hint("$label 불러오는 중")
            }
            if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = if (mapMode == MapMode.Bus) ModeColor.Bus else ModeColor.Subway)
        }

        // ── 오른쪽 버튼 ──
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mapFailed) {
                SmallFloatingActionButton(onClick = { mapAttempt++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "지도 다시 시도")
                }
            }
            SmallFloatingActionButton(onClick = vm::refreshNow) {
                Icon(Icons.Filled.Refresh, contentDescription = "지도 정보 새로고침")
            }
            SmallFloatingActionButton(onClick = {
                if (hasLocationPermission(context)) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Icon(Icons.Filled.MyLocation, contentDescription = "내 위치") }
            // 기차·고속시외·항공·제보·공지사항은 왼쪽 메뉴(☰)로
            // 지하철 실시간 열차는 늘 표시 (버튼 없앰)
            // 지도 종류: 카카오맵 기본·위성
            if (mode == "kakao") {
                SmallFloatingActionButton(onClick = { baseMenu = true }) {
                    Icon(Icons.Filled.Layers, contentDescription = "지도 종류와 레이어")
                }
            }
        }

        // ── 하단 패널 ── (imePadding: 키보드가 올라오면 패널도 키보드 위로, 내려가면 다시 아래로)
        val pickedPlace by vm.picked.collectAsStateWithLifecycle()
        Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
            pickedPlace?.let { PickCard(vm, it) }
            when (panel) {
                MapPanel.Stop -> selected?.let { StopPanel(vm, it, hasRoute = route != null) }
                MapPanel.Route -> route?.let { RoutePanel(vm, it, hasStop = selected != null) }
                MapPanel.Station -> station?.let { d ->
                    PanelSurface {
                        StationDetailPanel(
                            d, vm.stations,
                            onClose = vm::closePanel,
                            refreshKey = refreshTick,
                            onArrivalClick = { a -> vm.showArrivalPath(d.group, a) },
                            onUseStation = { asOrigin -> vm.useStationAs(d.group, asOrigin) },
                        )
                    }
                }
                MapPanel.Train -> trainView?.let { TrainPanel(vm, it, hasStation = station != null) }
                MapPanel.Plan -> PlannerPanel(vm)
                MapPanel.None -> Unit
            }
        }
      }
    }
    selectedItsCctv?.let { cctv ->
        ItsCctvDialog(cctv = cctv, onDismiss = vm::dismissItsCctv)
    }
    selectedItsRoadFeature?.let { feature ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissItsRoadFeature,
            title = { Text(feature.title) },
            text = { Text(feature.details.ifBlank { "ITS에서 제공한 도로 정보입니다." }) },
            confirmButton = {
                TextButton(onClick = vm::dismissItsRoadFeature) { Text("닫기") }
            },
        )
    }
    if (baseMenu && mode == "kakao") {
        MapLayerSheet(
            base = baseMap ?: vm.effectiveBase(),
            overlays = overlays,
            uticLoading = uticLoading,
            uticRiskLoading = uticRiskLoading,
            uticSafetyLoading = uticSafetyLoading,
            itsCctvLoading = itsCctvLoading,
            loadingLayers = itsRoadLoading,
            cachedLayers = itsRoadCachedLayers,
            onBaseSelect = vm::setBaseMap,
            onToggle = vm::toggleOverlay,
            onDismiss = { baseMenu = false },
        )
    }
}

private data class MapLayerOption(
    val id: String,
    val title: String,
    val description: String,
)

@Composable
private fun MapLayerSheet(
    base: BaseMap,
    overlays: Set<String>,
    uticLoading: Boolean,
    uticRiskLoading: Boolean,
    uticSafetyLoading: Boolean,
    itsCctvLoading: Boolean,
    loadingLayers: Set<String>,
    cachedLayers: Set<String>,
    onBaseSelect: (BaseMap) -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = listOf(
        "지도" to listOf(
            MapLayerOption("bicycle_road", "자전거도로", "지도에 자전거도로를 겹쳐 표시합니다."),
            MapLayerOption("hill_shading", "지형도", "지형의 높낮이를 음영으로 표시합니다."),
        ),
        "UTIC 교통정보" to listOf(
            MapLayerOption("utic_incidents", "돌발정보", "공사·사고 등 교통 돌발 상황"),
            MapLayerOption("utic_road_risk", "도로위험예보", "도로 위 위험 상황과 예보"),
            MapLayerOption("utic_safety", "교통안전정보", "교통안전 시설·위험 지점"),
        ),
        "ITS 도로정보" to listOf(
            MapLayerOption("its_cctv", "고속도로·국도 CCTV", "마커를 눌러 실시간 영상을 봅니다."),
            MapLayerOption("its_events", "돌발정보", "ITS에서 제공하는 도로 돌발 상황"),
            MapLayerOption("its_disasters", "재난정보", "도로 통제 등 재난 정보를 표시합니다."),
            MapLayerOption("its_vsl", "가변속도표지", "가변 제한속도 표지 위치"),
            MapLayerOption("its_vms", "도로전광표지", "전광표지 위치와 표시 내용"),
            MapLayerOption("its_safe_driving", "주의운전정보", "주의가 필요한 도로 구간"),
            MapLayerOption("its_dangerous_cars", "위험물 사고", "위험물 운송차량 사고 정보"),
            MapLayerOption("its_vds", "VDS 시설물", "교통량 검지 시설물 위치"),
            MapLayerOption("its_ice_sections", "상습 결빙구간", "겨울철 결빙 주의 구간"),
        ),
    )
    val enabledCount = overlays.count { id -> groups.any { group -> group.second.any { it.id == id } } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("지도와 교통 레이어", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (enabledCount == 0) "원하는 정보를 켜서 지도에 표시하세요."
                    else "${enabledCount}개 레이어 사용 중 · 다시 눌러 끌 수 있어요.",
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BaseMap.entries.forEach { type ->
                        androidx.compose.material3.FilterChip(
                            selected = base == type,
                            onClick = { onBaseSelect(type) },
                            label = { Text(type.label) },
                            trailingIcon = if (base == type) {
                                { Icon(Icons.Filled.Check, contentDescription = "선택됨") }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            groups.forEach { (groupTitle, options) ->
                item(key = "heading:$groupTitle") {
                    Text(
                        groupTitle,
                        Modifier.padding(top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(options, key = { it.id }) { option ->
                    val enabled = option.id in overlays
                    val status = when (option.id) {
                        "utic_incidents" -> if ("utic_incidents" in overlays && uticLoading) "불러오는 중" else if (enabled) "사용 중" else "지도 범위"
                        "utic_road_risk" -> if ("utic_road_risk" in overlays && uticRiskLoading) "불러오는 중" else if (enabled) "사용 중" else "지도 범위"
                        "utic_safety" -> if ("utic_safety" in overlays && uticSafetyLoading) "불러오는 중" else if (enabled) "사용 중" else "지도 범위"
                        "its_cctv" -> if ("its_cctv" in overlays && itsCctvLoading) "불러오는 중" else if (enabled) "사용 중" else "지도 범위"
                        else -> {
                            when {
                                option.id in loadingLayers -> "불러오는 중"
                                option.id in cachedLayers -> "저장된 정보"
                                enabled -> "사용 중"
                                else -> "지도 범위"
                            }
                        }
                    }
                    Surface(
                        onClick = { onToggle(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    if (enabled && option.id in loadingLayers && option.id in cachedLayers) {
                                        "저장된 $status 표시 · 최신 정보 갱신 중"
                                    } else if (enabled) "$status 표시 중" else option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (enabled) Icon(Icons.Filled.Check, contentDescription = "켜짐")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItsCctvDialog(cctv: com.eunho.tagohub.data.ItsCctv, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var playbackError by remember(cctv.id) { mutableStateOf<String?>(null) }
    var videoView by remember(cctv.id) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(cctv.id, videoView) {
        onDispose { videoView?.stopPlayback() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(cctv.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${if (cctv.roadType == "ex") "고속도로" else "국도"} · ${cctv.format ?: "실시간 영상"}" +
                        (cctv.resolution?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                key(cctv.id) {
                    AndroidView(
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setOnErrorListener { _, what, extra ->
                                    playbackError = "앱에서 영상을 재생하지 못했습니다 (오류 $what/$extra). 외부 플레이어로 시도해 보세요."
                                    true
                                }
                                setOnPreparedListener { player ->
                                    player.isLooping = true
                                    player.start()
                                }
                                setVideoURI(Uri.parse(cctv.streamUrl))
                                videoView = this
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
                playbackError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "ITS가 제공하는 고속도로·국도 CCTV입니다. 기기 또는 영상 제공 상태에 따라 재생되지 않을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val uri = Uri.parse(cctv.streamUrl)
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.apple.mpegurl"),
                                )
                            } catch (_: ActivityNotFoundException) {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                } catch (_: ActivityNotFoundException) {
                                    playbackError = "이 영상을 열 수 있는 외부 플레이어가 설치되어 있지 않습니다."
                                } catch (_: SecurityException) {
                                    playbackError = "외부 플레이어를 실행할 권한이 없습니다."
                                }
                            } catch (_: SecurityException) {
                                playbackError = "외부 플레이어를 실행할 권한이 없습니다."
                            }
                        },
                    ) { Text("외부 플레이어") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

/** 지도에서 고른 곳: 여기서 출발 / 여기로 도착 */
@Composable
private fun PickCard(vm: MapViewModel, p: MapViewModel.PickedPlace) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFFE53935))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val extra = listOfNotNull(p.category, p.phone).joinToString(" · ")
                    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    p.url?.let { u ->
                        Text(p.linkLabel ?: "카카오맵에서 자세히 보기", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { runCatching { uri.openUri(u) } })
                    }
                }
                IconButton(onClick = vm::closePick) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
            }
            Row(Modifier.padding(top = 6.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.usePicked(asOrigin = true) }, modifier = Modifier.weight(1f)) { Text("여기서 출발") }
                OutlinedButton(onClick = vm::usePickedAsVia, modifier = Modifier.weight(1f)) { Text("경유지로") }
                Button(onClick = { vm.usePicked(asOrigin = false) }, modifier = Modifier.weight(1f)) { Text("여기로 도착") }
            }
            // 주변 찾기 (카카오 카테고리로 장소 검색)
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(com.eunho.tagohub.data.KakaoLocal.CATEGORIES) { (code, label) ->
                    AssistChip(onClick = { vm.findNearby(code, label) }, label = { Text("주변 $label") })
                }
            }
            val near by vm.nearby.collectAsStateWithLifecycle()
            near?.let { (label, list) ->
                Text(if (list.isEmpty()) "1.5km 안에 $label 없음" else "가까운 $label", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                list.take(6).forEach { pl ->
                    Row(Modifier.fillMaxWidth().clickable { vm.pickPlace(pl) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        pl.distance?.let { Text(if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            // 기차역·터미널·공항: 그 시간표의 출발지·도착지로
            p.kind?.let { k ->
                Row(Modifier.padding(top = 4.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.jumpPicked(asOrigin = true) }, modifier = Modifier.weight(1f)) { Text("${k.label} 출발") }
                    OutlinedButton(onClick = { vm.jumpPicked(asOrigin = false) }, modifier = Modifier.weight(1f)) { Text("${k.label} 도착") }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Banner(text: String, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
        }
    }
}

/** 바텀시트 높이 상태 (화면 높이 px 기준 비율) */
private class Sheet(val frac: MutableFloatState, val maxPx: Float)

private val LocalSheet = compositionLocalOf<Sheet?> { null }

private val SNAPS = listOf(0.22f, 0.45f, 0.88f, 1.0f)   // 1.0 = 맨 위까지 끌면 전체화면

/** 위쪽 손잡이를 위아래로 끌면 높이가 바뀌고, 놓으면 가까운 높이(작게·중간·크게)에 맞춰진다. 손잡이를 누르면 중간↔크게 */
@Composable
private fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    val sheet = LocalSheet.current
    val density = LocalDensity.current
    val heightDp = sheet?.let { with(density) { (it.maxPx * it.frac.floatValue).toDp() } } ?: 340.dp
    val drag = rememberDraggableState { delta ->
        sheet?.let { it.frac.floatValue = (it.frac.floatValue - delta / it.maxPx).coerceIn(0.15f, 1.0f) }
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .height(heightDp),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            sheet?.let { s ->
                                val f = s.frac.floatValue
                                s.frac.floatValue = when {
                                    velocity < -1500f -> SNAPS.firstOrNull { it > f + 0.02f } ?: SNAPS.last()
                                    velocity > 1500f -> SNAPS.lastOrNull { it < f - 0.02f } ?: SNAPS.first()
                                    else -> SNAPS.minBy { kotlin.math.abs(it - f) }
                                }
                            }
                        },
                    )
                    ,
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            content()
        }
    }
}

/** 즐겨찾기 별 색 */
private val FAVORITE_GOLD = Color(0xFFF5B301)

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String?,
    color: Color,
    onClose: () -> Unit,
    leading: @Composable () -> Unit = {},
    switchLabel: String? = null,
    onSwitch: () -> Unit = {},
    /** null 이면 별 버튼 없음 */
    favorite: Boolean? = null,
    onFavorite: () -> Unit = {},
    /** null 이면 새로고침 버튼 없음 */
    onRefresh: (() -> Unit)? = null,
) {
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(5.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (switchLabel != null) TextButton(onClick = onSwitch) { Text(switchLabel) }
        onRefresh?.let { r -> IconButton(onClick = r) { Icon(Icons.Filled.Refresh, contentDescription = "새로고침") } }
        if (favorite != null) {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "즐겨찾기에서 빼기" else "즐겨찾기에 넣기",
                    tint = if (favorite) FAVORITE_GOLD else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
    }
}

@Composable
private fun StopPanel(vm: MapViewModel, d: BusDest.Stop, hasRoute: Boolean) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val rows by d.rows.collectAsStateWithLifecycle()
    val updated by d.updated.collectAsStateWithLifecycle()
    val others by d.others.collectAsStateWithLifecycle()
    val direction by d.direction.collectAsStateWithLifecycle()
    // 같은 자리 다른 시군 정류장까지 번호를 모두: "30690 · 3110313"
    val numbers = (listOf(d.stop) + others).mapNotNull { it.nodeNo }.distinct().joinToString(", ").ifEmpty { null }
    // 받은 뒤 지난 초 (매초 갱신) → 도착 시간이 실제로 줄어든다
    val elapsedSec by produceState(0, updated) {
        while (true) {
            value = updated?.let { java.time.Duration.between(it, LocalTime.now()).seconds.toInt().coerceIn(0, 600) } ?: 0
            delay(1000)
        }
    }
    PanelSurface {
        PanelHeader(
            title = d.stop.name,
            subtitle = listOfNotNull(numbers, direction?.let { "$it 방면" }).joinToString(" · ").ifEmpty { null },
            color = ModeColor.Bus,
            onClose = vm::closePanel,
            switchLabel = if (hasRoute) "노선 보기" else null,
            onSwitch = { vm.openPanel(MapPanel.Route) },
            favorite = favs.any { it.kind == Favorite.Kind.Stop && it.id == d.stop.nodeId },
            onFavorite = { vm.toggleFavorite(Favorite.of(d.stop)) },
            onRefresh = vm::refreshNow,
        )
        // 이 정류장을 출발지·도착지로 길찾기
        if (d.stop.lat != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.useStopAs(d.stop, asOrigin = true) }, modifier = Modifier.weight(1f)) { Text("출발지로") }
                Button(onClick = { vm.useStopAs(d.stop, asOrigin = false) }, modifier = Modifier.weight(1f)) { Text("도착지로") }
            }
        }
        val timetable by vm.stopTimetable.collectAsStateWithLifecycle()
        // 실시간 도착 ↔ 예상 시간표
        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = timetable == null, onClick = vm::closeStopTimetable, label = { Text("실시간 도착") },
                leadingIcon = { Icon(Icons.Filled.DirectionsBus, contentDescription = null, modifier = Modifier.size(16.dp)) })
            FilterChip(selected = timetable != null, onClick = { vm.openStopTimetable() }, label = { Text("시간표") },
                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) })
            Spacer(Modifier.weight(1f))
            updated?.let {
                Text("${it.hhmm()} 갱신", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val tt = timetable
        if (tt != null) {
            StopTimetable(vm, tt)
        } else {
            LoadView(rows, isEmpty = { it.isEmpty() }, emptyText = "이 정류소를 지나는 노선 정보가 없습니다.") { list ->
                LazyColumn {
                    items(list) { row ->
                        // 노선을 누르면 지도에 노선 + 실시간 버스 위치
                        StopRouteItem(row, elapsedSec, direction) { vm.showRoute(row) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** 정류장 예상 시간표: 요일 → 노선 → 이 정류장에 서는 예상 시각 (다음 차 강조) */
@Composable
private fun StopTimetable(vm: MapViewModel, state: Load<List<RouteTimes>>) {
    val day by vm.timetableDay.collectAsStateWithLifecycle()
    var pick by remember(state) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(com.eunho.tagohub.data.DayKind.entries) { k ->
                FilterChip(selected = k == day, onClick = { vm.openStopTimetable(k) }, label = { Text(k.label) })
            }
        }
        LoadView(state, onRetry = { vm.openStopTimetable() }, isEmpty = { it.isEmpty() }, emptyText = "시간표가 없습니다.") { list ->
            val cur = list.firstOrNull { it.routeId == pick } ?: list.first()
            Column(Modifier.fillMaxSize()) {
                // 지금 고른 버스 (누르면 "버스 선택" 목록)
                var picking by remember { mutableStateOf(false) }
                Surface(
                    onClick = { picking = true },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteTitle(cur, Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "버스 선택")
                    }
                }
                if (picking) {
                    ModalBottomSheet(onDismissRequest = { picking = false }) {
                        Text("버스 선택", Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        LazyColumn(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                            items(list) { r ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pick = r.routeId
                                            picking = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RouteTitle(r, Modifier.weight(1f))
                                    if (r.routeId == cur.routeId) Icon(Icons.Filled.Check, contentDescription = "선택됨")
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                // 전국 노선은 고를 때 계산 (첫차·막차·배차간격 + 이 정류장까지 소요시간)
                LaunchedEffect(cur.routeId, cur.loaded) { if (!cur.loaded) vm.ensureTimes(cur.routeId) }
                Text(
                    if (cur.estimated) "첫차·막차와 배차간격으로 계산한 예상 시각이라 실제와 다를 수 있습니다."
                    else "운행 시각표와 정류장 거리로 계산한 예상 시각이라 실제와 다를 수 있습니다.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!cur.loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = busRouteColor(cur.routeType))
                    Text("시간표 계산 중", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                if (cur.minutes.isEmpty()) {
                    Text(
                        "이 노선은 시간 정보가 없습니다.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val now = remember(cur) { LocalTime.now().let { it.hour * 60 + it.minute } }
                val next = if (day == com.eunho.tagohub.data.DayKind.today()) cur.minutes.firstOrNull { it >= now } else null
                Text(
                    "첫차 ${JeonjuTimetable.clock(cur.minutes.first())}   막차 ${JeonjuTimetable.clock(cur.minutes.last())}   하루 ${cur.minutes.size}회" +
                        (next?.let { "   다음 ${JeonjuTimetable.clock(it)} (${it - now}분 후)" } ?: ""),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                    fontWeight = FontWeight.Medium,
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    // 시(時)별로 한 줄
                    val byHour = cur.minutes.groupBy { (it / 60) % 24 }.toSortedMap().toList()
                    items(byHour) { (h, mins) ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(String.format(java.util.Locale.ROOT, "%02d", h), Modifier.width(36.dp),
                                style = MaterialTheme.typography.titleMedium.merge(Tnum), fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                mins.forEach { m ->
                                    val isNext = m == next
                                    Text(
                                        String.format(java.util.Locale.ROOT, "%02d", m % 60),
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isNext) busRouteColor(cur.routeType) else Color.Transparent)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.titleMedium.merge(Tnum),
                                        color = when {
                                            isNext -> Color.White
                                            next != null && m < next -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePanel(vm: MapViewModel, d: BusDest.Route, hasStop: Boolean) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val stopTimes by d.stopTimes.collectAsStateWithLifecycle()
    val info by d.info.collectAsStateWithLifecycle()
    val stops by d.stops.collectAsStateWithLifecycle()
    val buses by d.buses.collectAsStateWithLifecycle()
    val updated by d.updated.collectAsStateWithLifecycle()
    val r = info.valueOrNull()
    val color = busRouteColor(d.routeType ?: r?.routeType)

    PanelSurface {
        PanelHeader(
            title = listOfNotNull(r?.startName, r?.endName).joinToString(" ↔ ").ifEmpty { "${d.routeNo}번" },
            subtitle = "운행 중 ${buses.size}대" + (updated?.let { ", ${it.hhmm()} 위치, 10초마다 갱신" } ?: ""),
            color = color,
            onClose = vm::closePanel,
            leading = {
                Pill(d.routeNo, color)
                Spacer(Modifier.width(8.dp))
            },
            switchLabel = if (hasStop) "정류소" else null,
            onSwitch = { vm.openPanel(MapPanel.Stop) },
            favorite = favs.any { it.kind == Favorite.Kind.Route && it.id == d.routeId },
            onFavorite = { vm.toggleFavorite(vm.routeFavorite(d)) },
            onRefresh = vm::refreshNow,
        )
        r?.let { RouteInfoCard(it) }
        val dir by d.dir.collectAsStateWithLifecycle()
        LoadView(stops, isEmpty = { it.isEmpty() }, emptyText = "노선의 정류소 정보가 없습니다.") { all ->
            // 상·하행 "○○ 방면" 버튼: 목록은 그 방향만, 지도는 그 방향 선이 진하게
            val labels = remember(all) { vm.routeDirLabels(d) }
            labels?.let { names ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEachIndexed { i, name ->
                        FilterChip(
                            selected = dir == i,
                            onClick = { vm.setRouteDir(d, i) },
                            label = { Text("$name 방면", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            val list = remember(all, dir) { vm.routeStopsInDir(d, all) }
            // 회차 정류장 뒤에 표시 (방향을 나눌 수 없는 노선에서만 의미가 있음)
            val turnOrd = remember(all) { if (labels == null) vm.routeTurnOrd(d) else null }
            val busesAt = remember(buses) {
                { s: RouteStop -> buses.filter { b -> if (b.ord != null) b.ord == s.ord else b.nodeId == s.nodeId } }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(list) {
                // 정류소에서 들어왔으면 그 정류소 근처로, 아니면 첫 버스 근처로
                val target = list.indexOfFirst { it.nodeId == d.focusNodeId }.takeIf { it >= 0 }
                    ?: list.indexOfFirst { busesAt(it).isNotEmpty() }.takeIf { it >= 0 }
                if (target != null && target > 2) listState.scrollToItem(target - 2)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(list) { i, s ->
                    if (i > 0 && list[i - 1].ord == turnOrd) TurnMarker(color)
                    TimelineRow(
                        s, color,
                        isFirst = i == 0,
                        isLast = i == list.lastIndex,
                        buses = busesAt(s),
                        focused = s.nodeId == d.focusNodeId,
                        times = stopTimes[s.ord]?.let { (a, b) -> "${JeonjuTimetable.clock(a)} - ${JeonjuTimetable.clock(b)}" },
                    ) { vm.focusRouteStop(s.nodeId) }
                }
            }
        }
    }
}

/** 지하철 탭 상단 역 검색 (내장 데이터라 입력하는 즉시 결과) */
@Composable
private fun StationSearch(vm: MapViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val results = remember(query) { vm.searchStations(query) }
    fun pick(g: StationGroup) {
        vm.focusStation(g)
        query = ""
        focus.clearFocus()
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column {
            SearchBox(query, { query = it }, "역 이름으로 찾기", onSearch = { results.firstOrNull()?.let { pick(it) } })
            results.forEach { g ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { pick(g) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        g.displayLines.take(4).forEach { Pill(it, subwayLineColor(it)) }
                    }
                }
            }
        }
    }
}

/** 버스 탭 상단: 정류소명·정류소번호·노선번호를 전국에서 한 번에 검색. 결과에 지역 표시 */
@Composable
private fun BusSearchBar(vm: MapViewModel, withStations: Boolean = false) {
    val result by vm.busResult.collectAsStateWithLifecycle()
    // 버스·지하철 통합: 검색한 말로 지하철역도 (앱 안 데이터)
    var searched by rememberSaveable { mutableStateOf("") }
    val stations = remember(searched, result) { if (withStations && searched.isNotBlank()) vm.searchStations(searched).take(8) else emptyList() }
    // 장소도 같이 (카카오 키워드 검색)
    val places by produceState(emptyList<com.eunho.tagohub.data.Place2>(), searched) { value = vm.searchPlaces(searched) }
    // 지도를 만지거나 움직이면 키보드 내림
    val focusMgr = LocalFocusManager.current
    LaunchedEffect(Unit) { vm.hideKeyboard.collect { focusMgr.clearFocus() } }
    val progress by vm.busProgress.collectAsStateWithLifecycle()
    val names by vm.cityNames.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { com.eunho.tagohub.ui.ModeJump.drawer.value = true }) { Icon(Icons.Filled.Menu, contentDescription = "메뉴") }
                SearchBox(
                    query,
                    {
                        query = it
                        if (it.isEmpty()) {
                            vm.clearBusSearch()
                            searched = ""
                        }
                    },
                    if (withStations) "정류소·노선번호·지하철역" else "정류소명·정류소번호·노선번호",
                    Modifier.weight(1f),
                    onSearch = {
                        searched = query
                        vm.searchBus(query)
                    },
                )
                IconButton(onClick = {
                    query = ""
                    focus.clearFocus()
                    vm.openPlanner()
                }) { Icon(Icons.Filled.Directions, contentDescription = "길찾기", tint = ModeColor.Bus) }
            }
            // 즐겨찾기: 검색어가 없을 때 검색창 바로 아래 (누르면 바로 열기)
            val favs by vm.favorites.collectAsStateWithLifecycle()
            if (query.isEmpty() && result is Load.Idle && favs.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(favs, key = { it.kind.name + it.id }) { f ->
                        AssistChip(
                            onClick = {
                                focus.clearFocus()
                                vm.openFavorite(f)
                            },
                            label = { Text(if (f.kind == Favorite.Kind.Route) "${f.name}번" else f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    if (f.kind == Favorite.Kind.Route) Icons.Filled.DirectionsBus else Icons.Filled.Place,
                                    contentDescription = null,
                                    tint = if (f.kind == Favorite.Kind.Route) busRouteColor(f.extra) else ModeColor.Bus,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                    }
                }
            }
            progress?.let { (done, total) ->
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = ModeColor.Bus,
                )
                Text(
                    "전국 검색 중  $done / $total 지역",
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result !is Load.Idle) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.heightIn(max = 360.dp)) {
                    LoadView(
                        result,
                        onRetry = { vm.searchBus(query) },
                        isEmpty = { it.stops.isEmpty() && it.routes.isEmpty() && stations.isEmpty() && progress == null },
                        emptyText = "전국에서 검색 결과가 없습니다.",
                    ) { r ->
                        LazyColumn {
                            r.warning?.let { w ->
                                item { Text(w, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (places.isNotEmpty()) {
                                item { SectionHeader("장소 ${places.size}") }
                                items(places.take(8)) { pl ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.pickPlace(pl)
                                                query = ""
                                                searched = ""
                                                vm.clearBusSearch()
                                                focus.clearFocus()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(listOfNotNull(pl.category, pl.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if (stations.isNotEmpty()) {
                                item { SectionHeader("지하철역 ${stations.size}") }
                                items(stations) { g ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.focusStation(g)
                                                query = ""
                                                searched = ""
                                                vm.clearBusSearch()
                                                focus.clearFocus()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.DirectionsSubway, contentDescription = null, tint = ModeColor.Subway, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            g.displayLines.take(4).forEach { Pill(it, subwayLineColor(it)) }
                                        }
                                    }
                                }
                            }
                            if (r.routes.isNotEmpty()) {
                                item { SectionHeader("노선 ${r.routes.size}") }
                                items(r.routes) { route ->
                                    RouteListRow(route, names[route.cityCode]) {
                                        vm.pickRoute(route)
                                        query = ""
                                        focus.clearFocus()
                                    }
                                }
                            }
                            if (r.stops.isNotEmpty()) {
                                item { SectionHeader("정류소 ${r.stops.size}") }
                                items(r.stops) { st ->
                                    val dir by produceState<String?>(null, st.nodeId) { value = vm.stopDirection(st.nodeId)?.let { "$it 방면" } }
                                    StopListRow(st, dir, names[st.cityCode], vm.stopNumbers(st)) {
                                        vm.pickStop(st)
                                        query = ""
                                        focus.clearFocus()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 열차 경로 패널 ─────────────────────────

@Composable
private fun TrainPanel(vm: MapViewModel, v: TrainView, hasStation: Boolean) {
    val color = subwayLineColor(v.lineName)
    val listState = rememberLazyListState()
    LaunchedEffect(v) {
        val i = v.path.indexOfFirst { it.norm == v.trainAtNorm }.takeIf { it >= 0 }
            ?: v.path.indexOfFirst { it.norm == v.focusNorm }.takeIf { it >= 0 }
        if (i != null && i > 2) listState.scrollToItem(i - 2)
    }
    PanelSurface {
        PanelHeader(
            title = v.dest?.let { "${it}행" } ?: "${v.lineName} 열차",
            subtitle = listOfNotNull(
                v.trainNo?.trimStart('0')?.takeIf { it.isNotEmpty() }?.let { "열차 $it" },
                v.status,
                "${v.path.first().norm} → ${v.path.last().norm}  ${v.path.size}개 역",
            ).joinToString("   "),
            color = color,
            onClose = vm::closePanel,
            leading = {
                Pill(v.lineName, color)
                Spacer(Modifier.width(8.dp))
            },
            switchLabel = if (hasStation) "역 정보" else null,
            onSwitch = { vm.openPanel(MapPanel.Station) },
        )
        if (v.trainId == null) {
            Text(
                "이 열차의 실시간 위치를 찾지 못해 경로만 표시합니다.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(v.path) { i, st ->
                MetroTimelineRow(
                    name = st.norm,
                    color = color,
                    isFirst = i == 0,
                    isLast = i == v.path.lastIndex,
                    trainHere = st.norm == v.trainAtNorm,
                    focused = st.norm == v.focusNorm,
                ) { vm.focusMetro(st) }
            }
        }
    }
}

@Composable
private fun MetroTimelineRow(
    name: String,
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
    trainHere: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .background(if (focused) color.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            Modifier
                .width(28.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxHeight()) {
                Box(
                    Modifier
                        .width(4.dp)
                        .weight(1f)
                        .background(if (isFirst) Color.Transparent else color)
                )
                Box(
                    Modifier
                        .width(4.dp)
                        .weight(1f)
                        .background(if (isLast) Color.Transparent else color)
                )
            }
            if (trainHere) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Train, contentDescription = "열차", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            } else {
                Box(
                    Modifier
                        .size(if (isLast) 14.dp else 12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, color, RoundedCornerShape(3.dp))
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 11.dp)
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (focused || isLast) FontWeight.Bold else FontWeight.Normal)
            when {
                trainHere -> Text("열차 현재 위치", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                isLast -> Text("종착", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────── 길찾기 ─────────────────────────

private val LegColors = TripController.LEG_COLORS.map { Color(it) }

@Composable
private fun PlannerPanel(vm: MapViewModel) {
    val t = vm.trip
    val origin by t.origin.collectAsStateWithLifecycle()
    val dest by t.dest.collectAsStateWithLifecycle()
    val editing by t.editing.collectAsStateWithLifecycle()
    val selected by t.selected.collectAsStateWithLifecycle()
    val note by t.note.collectAsStateWithLifecycle()

    PanelSurface {
        PanelHeader(
            title = if (selected != null) "경로 상세" else "길찾기",
            subtitle = note ?: "카카오 대중교통 길찾기",
            color = ModeColor.Bus,
            onClose = vm::closePanel,
            switchLabel = if (selected != null) "목록" else null,
            onSwitch = { t.select(null) },
        )
        if (selected == null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanPointField("출발", origin?.name ?: "내 위치", active = editing == PlanField.Origin) { t.edit(PlanField.Origin) }
                    PlanPointField("도착", dest?.name ?: "목적지를 입력하세요", active = editing == PlanField.Dest, placeholder = dest == null) {
                        t.edit(PlanField.Dest)
                    }
                }
                IconButton(onClick = t::swap) { Icon(Icons.Filled.SwapVert, contentDescription = "출발·도착 바꾸기") }
            }
            Spacer(Modifier.height(6.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            editing != null -> PlanSearch(t, editing!!)
            selected != null -> PlanDetail(t, selected!!)
            else -> PlanList(t)
        }
    }
}

@Composable
private fun PlanPointField(label: String, value: String, active: Boolean, placeholder: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) ModeColor.Bus.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = ModeColor.Bus, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlanSearch(t: TripController, field: PlanField) {
    val results by t.results.collectAsStateWithLifecycle()
    var q by rememberSaveable(field) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SearchBox(
            q,
            {
                q = it
                t.search(it)
            },
            if (field == PlanField.Dest) "목적지 (카카오 장소 검색)" else "출발지 (카카오 장소 검색)",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            onSearch = { t.search(q) },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (field == PlanField.Origin) {
                item {
                    PlanResultRow(Icons.Filled.MyLocation, "내 위치", "현재 위치에서 출발") { t.pick(null) }
                }
            }
            when (val r = results) {
                is Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = ModeColor.Bus) }
                is Load.Ok -> {
                    if (r.value.isEmpty()) item { MessageText("검색 결과가 없습니다.") }
                    items(r.value) { p ->
                        PlanResultRow(if (p.stopId != null) Icons.Filled.DirectionsBus else Icons.Filled.Place, p.name, p.sub) { t.pick(p) }
                    }
                }
                is Load.Fail -> item { MessageText(r.message) }
                is Load.Idle -> item { MessageText("장소를 검색하거나 지도에서 위치를 길게 눌러 선택하세요.") }
            }
        }
    }
}

@Composable
private fun MessageText(text: String) {
    Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlanResultRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PlanList(t: TripController) {
    val plans by t.plans.collectAsStateWithLifecycle()
    val destination by t.dest.collectAsStateWithLifecycle()
    val ridePlan by t.ridePlan.collectAsStateWithLifecycle()
    val sort by t.sort.collectAsStateWithLifecycle()
    val mode by t.mode.collectAsStateWithLifecycle()
    val via by t.via.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        if (destination != null) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(PlanMode.entries) { option ->
                    val icon = when (option) {
                        PlanMode.Transit -> Icons.Filled.DirectionsBus
                        PlanMode.Car -> Icons.Filled.DirectionsCar
                        PlanMode.Walk -> Icons.AutoMirrored.Filled.DirectionsWalk
                        PlanMode.Bicycle -> Icons.AutoMirrored.Filled.DirectionsBike
                    }
                    FilterChip(
                        selected = mode == option,
                        onClick = { if (mode != option) t.selectMode(option) },
                        label = { Text(option.label) },
                        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (mode != PlanMode.Transit) {
                when (val route = ridePlan) {
                    is Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    is Load.Fail -> MessageText(route.message)
                    is Load.Ok -> {
                        val result = route.value
                        val distance = if (result.distanceMeters >= 1000) {
                            "%.1fkm".format(result.distanceMeters / 1000.0)
                        } else "${result.distanceMeters}m"
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "약 ${(result.durationSeconds + 59) / 60}분",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(distance, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            when (result.mode) {
                                PlanMode.Car -> "카카오 자동차 길찾기 · 교통상황에 따라 달라질 수 있습니다."
                                PlanMode.Walk -> "카카오 도보 경로"
                                PlanMode.Bicycle -> "카카오 자전거 경로"
                                PlanMode.Transit -> ""
                            },
                            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is Load.Idle -> MessageText("${mode.label} 경로를 불러올 준비가 되었습니다.")
                }
            }
        }
        via?.let { v ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("경유  ${v.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { t.via.value = null; t.plan() }) { Text("경유 빼기") }
            }
        }
        if (mode == PlanMode.Transit) {
            Box(Modifier.weight(1f)) {
                LoadView(
                    plans,
                    onRetry = t::plan,
                    isEmpty = { it.isEmpty() },
                    emptyText = "대중교통 경로가 없습니다. 다른 이동수단을 선택해 경로를 확인하세요.",
                    idle = { MessageText("출발지와 도착지를 선택하면 대중교통 경로를 보여줍니다.") },
                ) { list ->
                    val shown = remember(list, sort) {
                        when (sort) {
                            PlanSort.Fast -> list.sortedBy { it.minutes }
                            PlanSort.LessWalk -> list.sortedWith(compareBy({ it.walkStartM + it.walkEndM + it.transferWalkM }, { it.minutes }))
                            PlanSort.LessTransfer -> list.sortedWith(compareBy({ it.legs.size }, { it.minutes }))
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlanSort.entries.forEach { option ->
                                FilterChip(selected = sort == option, onClick = { t.sort.value = option }, label = { Text(option.label) })
                            }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(shown) { p ->
                                PlanCard(p) { t.select(p) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(p: TripPlan, onClick: () -> Unit) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("약 ${p.minutes}분", style = MaterialTheme.typography.titleMedium.merge(Tnum), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (p.legs.size == 1) "직통" else "환승 ${p.legs.size - 1}회", style = MaterialTheme.typography.labelMedium, color = if (p.legs.size == 1) ModeColor.Bus else variant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "걷기", tint = variant, modifier = Modifier.size(16.dp))
            p.legs.forEachIndexed { i, leg ->
                if (i > 0) Text("›", color = variant)
                Pill(leg.routeNo, legColor(leg, i))
            }
            Text("›", color = variant)
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "걷기", tint = variant, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        val parts = listOfNotNull(
            "${p.origin.name} 승차",
            p.legs.drop(1).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.boardName }?.let { "$it 환승" },
            (if (p.stops > 0) "${p.stops}정류장" else "") + if (p.rideMeters > 0) " (${"%.1f".format(p.rideMeters / 1000)}km)" else "",
            "걷기 ${p.walkMinutes}분",
            p.firstWaitSec?.let { "첫 버스 ${Fmt.arrival(it)}" },
            p.fare?.let { "요금 ${"%,d".format(it)}원" },
            p.source,
        ).filter { it.isNotBlank() }
        Text(parts.joinToString("   "), style = MaterialTheme.typography.bodySmall, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlanDetail(t: TripController, p: TripPlan) {
    val live by t.live.collectAsStateWithLifecycle()
    val origin by t.origin.collectAsStateWithLifecycle()
    val dest by t.dest.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                "약 ${p.minutes}분   " + (if (p.stops > 0) "${p.stops}정류장   " else "") + "걷기 ${p.walkMinutes}분" +
                    (p.fare?.let { "   요금 ${"%,d".format(it)}원" } ?: ""),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            StepRow(Icons.AutoMirrored.Filled.DirectionsWalk, MaterialTheme.colorScheme.onSurfaceVariant,
                "${origin?.name ?: "출발지"}에서 걷기", "${p.walkStartM.toInt()}m  ${p.origin.name} 정류장까지")
        }
        p.legs.forEachIndexed { i, leg ->
            val color = legColor(leg, i)
            val arrivals = if (i == 0) live?.first else live?.transfer
            val prev = p.legs.getOrNull(i - 1)
            if (prev != null && prev.alightName != leg.boardName && (p.transferWalkM > 0 || leg.subway != prev.subway)) {
                item {
                    StepRow(Icons.AutoMirrored.Filled.DirectionsWalk, MaterialTheme.colorScheme.onSurfaceVariant,
                        "환승: 걸어서 갈아타기", "${prev.alightName} → ${leg.boardName}")
                }
            }
            item {
                StepRow(if (leg.subway) Icons.Filled.DirectionsSubway else Icons.Filled.DirectionsBus, color,
                    (if (i == 0) "" else "환승  ") + (if (leg.subway) "${leg.routeNo} 탑승  ${leg.boardName}역" else "${(listOf(leg.routeNo) + leg.alternatives).joinToString(" · ")}번 탑승  ${leg.boardName}") +
                        (if (i == 1) p.transferRemark?.let { " ($it)" }.orEmpty() else ""),
                    when {
                        leg.subway -> "실시간 도착은 지도에서 역을 누르면 볼 수 있어요"
                        i > 1 -> "갈아탈 때 정류장에서 확인"
                        live == null -> "도착 정보 확인 중"
                        arrivals.isNullOrEmpty() -> "지금 오는 버스 정보가 없습니다"
                        else -> arrivals.take(2).joinToString("   ") { a ->
                            Fmt.arrival(a.arrSeconds) + (a.prevStops?.let { " (${it}정류장 전)" } ?: "")
                        }
                    },
                    highlight = !arrivals.isNullOrEmpty(),
                )
            }
            item {
                StepRow(Icons.Filled.Place, color,
                    if (leg.stops > 0) "${leg.stops}정류장 이동" else if (leg.meters > 0) "약 ${"%.1f".format(leg.meters / 1000)}km 이동" else "이동",
                    "${leg.alightName}${if (leg.subway) "역" else ""} 하차")
            }
        }
        item {
            StepRow(Icons.AutoMirrored.Filled.DirectionsWalk, MaterialTheme.colorScheme.onSurfaceVariant,
                "${p.dest.name}에서 걷기", "${p.walkEndM.toInt()}m  ${dest?.name ?: "도착지"}까지")
        }
    }
}

/** 구간 색: 지하철은 노선 색, 버스는 구간 순서 색 */
private fun legColor(leg: TripLeg, i: Int): Color = if (leg.subway) subwayLineColor(leg.routeNo) else LegColors[i % LegColors.size]

@Composable
private fun StepRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, sub: String, highlight: Boolean = false) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall.merge(Tnum),
                color = if (highlight) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** 시간표 버스 이름: 종류 배지 + "4000(전주대캠퍼스-전주대캠퍼스)" */
@Composable
private fun RouteTitle(r: RouteTimes, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        r.routeType?.let {
            Pill(it.removeSuffix("버스"), busRouteColor(it))
            Spacer(Modifier.width(8.dp))
        }
        val ends = listOfNotNull(r.startName, r.endName).joinToString("-")
        Text(
            r.routeNo + if (ends.isNotEmpty()) "($ends)" else "",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
