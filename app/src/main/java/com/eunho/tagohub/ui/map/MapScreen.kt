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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tsunami
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    val selectedUticIncident by vm.selectedUticIncident.collectAsStateWithLifecycle()

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
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { stop(); stop = startLocationUpdates(context) { if (!com.eunho.tagohub.guidance.DriveGuidance.simulating.value) vm.setMe(it.latitude, it.longitude, center = false) } }
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
    // 경로 안내 중에는 검색창·하단 패널을 숨기고 안내 화면만 보여줌
    val guiding by com.eunho.tagohub.guidance.DriveGuidance.active.collectAsStateWithLifecycle()

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
        if (!guiding) Column(
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
                needZoom && panel == MapPanel.None -> Hint(
                    if (mapMode == MapMode.Transit) "지도를 확대하면 정류장·지하철역과 실시간 열차가 표시됩니다"
                    else if (mapMode == MapMode.Subway) "지도를 확대하면 지하철역과 실시간 열차가 표시됩니다"
                    else "지도를 확대하면 주변 버스 정류장이 표시됩니다"
                )
            }
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
            }
            AlightStatusCard()
            if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = if (mapMode == MapMode.Bus) ModeColor.Bus else ModeColor.Subway)
        }

        // ── 오른쪽 버튼 ── (안내 중에는 아래쪽으로 내려 파란 배너·소통 막대를 가리지 않게)
        Column(
            Modifier
                .align(if (guiding) Alignment.BottomEnd else Alignment.TopEnd)
                .then(if (guiding) Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 96.dp, end = 30.dp) else Modifier.padding(top = 12.dp, end = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mapFailed) {
                SmallFloatingActionButton(onClick = { mapAttempt++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "지도 다시 시도")
                }
            }
            if (!guiding) SmallFloatingActionButton(onClick = vm::refreshNow) {
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

        // ── 경로 안내 중 화면 (배너·소통 막대·도착 시각 바) ──
        DriveGuidanceOverlay(vm)

        // ── 하단 패널 ── (imePadding: 키보드가 올라오면 패널도 키보드 위로, 내려가면 다시 아래로)
        val pickedPlace by vm.picked.collectAsStateWithLifecycle()
        if (!guiding) Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
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
    selectedItsRoadFeature?.takeIf { it.layer == com.eunho.tagohub.data.ExMapLayers.REST }?.let { feature ->
        RestAreaDialog(feature, vm::dismissItsRoadFeature)
    }
    selectedItsRoadFeature?.takeIf { it.layer != com.eunho.tagohub.data.ExMapLayers.REST }?.let { feature ->
        val lines = feature.details.lines().filter { it.isNotBlank() }
        val period = lines.filter { Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(it) }
        MapInfoDialog(
            title = feature.title,
            body = (lines - period.toSet()).joinToString("\n").ifBlank { "ITS에서 제공한 도로 정보입니다." },
            period = period,
            source = if (feature.layer.startsWith("ex_")) "한국도로공사 제공" else "국토교통부 제공",
            onDismiss = vm::dismissItsRoadFeature,
        )
    }
    selectedUticIncident?.let { i ->
        MapInfoDialog(
            title = i.title,
            body = i.address.orEmpty(),
            period = listOfNotNull(i.startDate, i.endDate),
            source = "UTIC 제공",
            onDismiss = vm::dismissUticIncident,
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
    /** 한 항목이 여러 출처의 레이어를 함께 켜고 끔 (예: 돌발 = UTIC + ITS) */
    val ids: List<String>,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** 카카오맵 "지도설정" 시트 형식: 지도 종류 카드 + 둥근 아이콘 격자 (켜면 파란 원, 끄면 회색 원) */
@OptIn(ExperimentalLayoutApi::class)
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
    val layers = listOf(
        MapLayerOption(listOf("its_cctv"), "CCTV", Icons.Filled.Videocam),
        MapLayerOption(listOf("utic_incidents", "its_events", "its_dangerous_cars"), "돌발·사고", Icons.Filled.Warning),
        MapLayerOption(listOf("utic_road_risk", "its_safe_driving", "its_disasters"), "위험·재난", Icons.Filled.Report),
        MapLayerOption(listOf("utic_safety"), "교통안전", Icons.Filled.Shield),
        MapLayerOption(listOf("ex_congest"), "정체구간", Icons.Filled.Traffic),
        MapLayerOption(listOf("its_vms", "its_vsl", "ex_vms"), "전광판", Icons.Filled.Campaign),
        MapLayerOption(listOf("ex_rest"), "휴게소", Icons.Filled.Place),
        MapLayerOption(listOf("ex_toll", "ex_ic"), "영업소·IC", Icons.Filled.Directions),
        MapLayerOption(listOf("its_ice_sections"), "결빙구간", Icons.Filled.AcUnit),
        MapLayerOption(listOf("bicycle_road"), "자전거도로", Icons.AutoMirrored.Filled.DirectionsBike),
        MapLayerOption(listOf("hill_shading"), "지형도", Icons.Filled.Terrain),
    )
    val anyLoading = uticLoading || uticRiskLoading || uticSafetyLoading || itsCctvLoading || loadingLayers.isNotEmpty()
    val blue = Color(0xFF3B8CFF)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text(
                "지도설정",
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = blue,
            )
            if (anyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else HorizontalDivider()

            // 지도 종류
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BaseMap.entries.forEach { type ->
                    val picked = base == type
                    Column(
                        Modifier.weight(1f).clickable { onBaseSelect(type) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 88.dp, height = 64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (type == BaseMap.Satellite) Color(0xFF3E5A3A) else Color(0xFFE9E3C9))
                                .then(if (picked) Modifier.border(2.dp, blue, RoundedCornerShape(8.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) {
                                Box(Modifier.fillMaxSize().background(blue.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, contentDescription = "선택됨", tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                        Text(
                            type.label,
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                            color = if (picked) blue else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            // 레이어 격자: 한 줄 4개
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                maxItemsInEachRow = 4,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                layers.forEach { option ->
                    val on = option.ids.any { it in overlays }
                    Column(
                        Modifier.weight(1f).clickable {
                            // 켜져 있으면 전부 끄고, 아니면 전부 켬 (하나씩 뒤집되 이미 원하는 상태인 것은 건드리지 않음)
                            option.ids.forEach { id -> if ((id in overlays) == on) onToggle(id) }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape)
                                .background(if (on) blue else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                option.icon,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            option.title,
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) blue else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // 마지막 줄 빈칸을 채워 정렬 유지
                repeat((4 - layers.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 휴게소 마커 상세: 열면서 주유 가격·편의시설·입점 브랜드·대표 메뉴를 받아 보여줌 */
@Composable
private fun RestAreaDialog(feature: com.eunho.tagohub.data.ItsRoadFeature, onDismiss: () -> Unit) {
    val code = feature.id.substringAfter(':')
    val repo = remember { com.eunho.tagohub.data.ExRestRepository() }
    val detail by produceState<com.eunho.tagohub.data.ExRestDetail?>(null, feature.id) {
        value = runCatching { repo.detail(code, feature.title) }.getOrNull()
            ?: com.eunho.tagohub.data.ExRestDetail(null, emptyList(), emptyList(), emptyList())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(feature.title, fontWeight = FontWeight.Bold) },
        text = {
            val d = detail
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (feature.details.isNotBlank()) {
                    Text(feature.details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    d == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    d.isEmpty -> Text("등록된 상세 정보가 없습니다.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        d.gas?.let { g ->
                            RestSection("주유 가격" + (g.company?.let { " · $it" } ?: "")) {
                                listOfNotNull(g.gasoline?.let { "휘발유 $it" }, g.diesel?.let { "경유 $it" }, g.lpg?.let { "LPG $it" })
                                    .forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                g.tel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        if (d.facilities.isNotEmpty()) {
                            RestSection("편의시설") { Text(d.facilities.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
                        }
                        if (d.brands.isNotEmpty()) {
                            RestSection("입점 매장") { Text(d.brands.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
                        }
                        if (d.foods.isNotEmpty()) {
                            RestSection("메뉴") {
                                d.foods.forEach { f ->
                                    Row {
                                        Text((if (f.recommended) "★ " else "") + f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        f.price?.let { Text(it + "원", style = MaterialTheme.typography.bodyMedium) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("한국도로공사 제공", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인", color = Color(0xFF3B8CFF)) } },
    )
}

@Composable
private fun RestSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF3B8CFF))
        content()
    }
}

/** 지도 위 정보(마커)를 눌렀을 때: 제목 / 본문 / 기간 / 제공처 / 확인 */
@Composable
private fun MapInfoDialog(title: String, body: String, period: List<String>, source: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge)
                if (period.isNotEmpty()) Text(period.joinToString("\n"), style = MaterialTheme.typography.bodyLarge)
                Text(source, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인", color = Color(0xFF3B8CFF)) } },
    )
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ItsCctvDialog(cctv: com.eunho.tagohub.data.ItsCctv, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var playbackError by remember(cctv.id) { mutableStateOf<String?>(null) }
    // ExoPlayer: 창을 열자마자 재생 (버퍼 0.3초만 모이면 시작)
    val player = remember(cctv.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(1500, 6000, 300, 600).build())
            .build().apply {
                // 주소에 .m3u8 이 없고(서버가 302 로 넘겨 줌) 확장자로는 HLS 인지 알 수 없어서 형식을 직접 지정
                setMediaItem(
                    androidx.media3.common.MediaItem.Builder()
                        .setUri(com.eunho.tagohub.data.CctvProxy.wrap(cctv.streamUrl))
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = "앱에서 영상을 재생하지 못했습니다 (${error.errorCodeName}). 외부 플레이어로 시도해 보세요."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    var fullscreen by rememberSaveable(cctv.id) { mutableStateOf(false) }
    // 화면 방향은 고정하지 않음(예전에 가로로 고정했다가 세로로 안 돌아오는 문제). 폰을 가로로 돌리면 영상이 크게 보임

    if (fullscreen) {
        // 전체화면은 별도 창(Dialog)이 아니라 화면 위에 직접 덮는다: 창 크기가 회전 전 값으로 남아 영상이 밀리던 문제
        BackHandler { fullscreen = false }
        val activity = context.findActivity()
        DisposableEffect(Unit) {
            val window = activity?.window
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // 노치(카메라 구멍)가 있는 기기에서 가로로 돌렸을 때 옆이 검게 비지 않게
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window?.attributes = window?.attributes?.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                key(cctv.id) {
                    AndroidView(
                        factory = { viewContext ->
                            androidx.media3.ui.PlayerView(viewContext).apply {
                                this.player = player
                                useController = false
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        cctv.name,
                        Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { fullscreen = false }) { Text("전체화면 종료", color = Color.White) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Color.White) }
                }
                playbackError?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
            }
    } else Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            // 가로 화면은 세로가 짧아서 아래 버튼이 잘렸음 → 전체화면·닫기를 맨 위로 올리고, 내용은 스크롤, 영상 높이는 화면에 맞춤
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cctv.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { fullscreen = true }) { Icon(Icons.Filled.Fullscreen, contentDescription = "전체화면") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
                }
                Text(
                    "${if (cctv.roadType == "ex") "고속도로" else "국도"} · ${cctv.format ?: "실시간 영상"}" +
                        (cctv.resolution?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                key(cctv.id) {
                    AndroidView(
                        factory = { viewContext ->
                            androidx.media3.ui.PlayerView(viewContext).apply {
                                this.player = player
                                useController = false
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(minOf(220, (screenH * 0.5).toInt()).dp),
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
                            val uri = Uri.parse(com.eunho.tagohub.data.CctvProxy.wrap(cctv.streamUrl))
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
                    TextButton(onClick = { fullscreen = true }) { Text("전체화면") }
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
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.saveHomeWork(com.eunho.tagohub.data.SavedKind.Home) }) { Text("집으로 저장") }
                TextButton(onClick = { vm.saveHomeWork(com.eunho.tagohub.data.SavedKind.Work) }) { Text("회사로 저장") }
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
        r?.let { LastBusChips(d.routeId, d.routeNo, d.cityCode, r.startName, r.lastTime) }
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
            // 목록에서 누른(지도에 표시된) 정류소에서 내리겠다고 알림을 켤 수 있음
            list.firstOrNull { it.nodeId == d.focusNodeId && it.lat != null && it.lng != null }?.let { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AlightButton(com.eunho.tagohub.guidance.AlightAlert.Target(s.name, s.lat!!, s.lng!!, false, d.routeNo))
                }
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
            if (editing == null) HomeWorkChips(t)
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

/**
 * 집·회사 한 번 길찾기 칩: 누르면 그곳으로 가는 길찾기, 길게 누르면 다시 설정. 아직 없으면 "집 설정".
 * 지금 시각에 갈 가능성이 큰 쪽(아침 회사·저녁 집)을 앞에 둔다.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeWorkChips(t: TripController) {
    val saved by t.homeWork.state.collectAsStateWithLifecycle()
    val first = remember { com.eunho.tagohub.data.HomeWork.suggested(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    val order = listOf(first, com.eunho.tagohub.data.SavedKind.entries.first { it != first })
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        order.forEach { kind ->
            val place = saved[kind]
            Surface(
                shape = RoundedCornerShape(50),
                color = ModeColor.Bus.copy(alpha = if (place != null && kind == first) 0.18f else 0.08f),
                modifier = Modifier.combinedClickable(
                    onClick = { if (place != null) t.goTo(place) else t.startSaving(kind) },
                    onLongClick = { t.startSaving(kind) },
                ),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == com.eunho.tagohub.data.SavedKind.Home) Icons.Filled.Home else Icons.Filled.Work, contentDescription = null, tint = ModeColor.Bus, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (place != null) "${kind.label}으로" else "${kind.label} 설정",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ModeColor.Bus,
                    )
                }
            }
        }
        if (saved.home != null && saved.work != null) CommuteReminderChip()
        if (saved.home != null || saved.work != null) {
            Text("길게 누르면 변경", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val saving by t.saving.collectAsStateWithLifecycle()
    var q by rememberSaveable(field) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SearchBox(
            q,
            {
                q = it
                t.search(it)
            },
            saving?.let { "${it.label}으로 저장할 장소 (카카오 장소 검색)" }
                ?: if (field == PlanField.Dest) "목적지 (카카오 장소 검색)" else "출발지 (카카오 장소 검색)",
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
                        if (result.mode == PlanMode.Car) {
                            val driveOption by t.driveOption.collectAsStateWithLifecycle()
                            DriveRoutePanel(result, driveOption, t::selectDriveOption, Modifier.weight(1f))
                        } else {
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
                                if (result.mode == PlanMode.Walk) "카카오 도보 경로" else "카카오 자전거 경로",
                                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            com.eunho.tagohub.data.ArrivalText.line(a)
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
            item {
                // 내릴 곳 좌표: 구간이 직접 주거나, 없으면 정류장 DB 에서
                val alight = remember(leg) {
                    val la = leg.alightLat
                    val ln = leg.alightLng
                    if (la != null && ln != null) com.eunho.tagohub.guidance.AlightAlert.Target(leg.alightName, la, ln, leg.subway, leg.routeNo)
                    else com.eunho.tagohub.data.StopDb.peek()?.byId(leg.alightStopId)?.let { s ->
                        if (s.lat != null && s.lng != null) com.eunho.tagohub.guidance.AlightAlert.Target(leg.alightName, s.lat, s.lng, leg.subway, leg.routeNo) else null
                    }
                }
                alight?.let { AlightButton(it, Modifier.padding(start = 58.dp, bottom = 6.dp)) }
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
