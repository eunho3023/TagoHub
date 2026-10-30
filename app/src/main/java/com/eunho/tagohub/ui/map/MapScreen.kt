@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.data.TripPlanner
import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.ui.bus.BusDest
import com.eunho.tagohub.ui.bus.StopRouteItem
import com.eunho.tagohub.ui.bus.hideDuplicateNumbers
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
    val dark = com.eunho.tagohub.ui.theme.LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    val mode by vm.mode.collectAsStateWithLifecycle()
    val mapFailure by vm.mapFailure.collectAsStateWithLifecycle()
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
        if (!KakaoCanvas.available) vm.onMapProblem(com.eunho.tagohub.core.DevMode.msg("카카오맵 앱 키가 없어요. local.properties 에 KAKAO_NATIVE_KEY 를 넣고 다시 빌드하세요.", "지도를 불러오지 못했어요. 잠시 뒤 새로고침 버튼을 눌러 주세요."))
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
            if (loc == null) android.widget.Toast.makeText(context, "현재 위치를 가져오지 못했어요. 위치 서비스가 켜져 있는지 확인하세요.", android.widget.Toast.LENGTH_SHORT).show()
            else vm.setMe(loc.latitude, loc.longitude, center = true)
        }
    }

    var locationKey by remember { mutableStateOf(0) }
    var showLocationWhy by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) { locate(); locationKey++ } else android.widget.Toast.makeText(context, "위치 권한이 없어 현재 위치를 표시할 수 없어요.", android.widget.Toast.LENGTH_SHORT).show()
    }
    // 내 위치: 화면이 보이는 동안 GPS 를 따라 계속 움직임 (앱이 뒤로 가면 멈춰 배터리 아낌)
    val compass = remember { com.eunho.tagohub.core.HeadingSensor(context) { vm.onCompass(it) } }
    DisposableEffect(lifecycle, locationKey) {
        var stop: () -> Unit = {}
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { stop(); stop = startLocationUpdates(context) {
                    if (!com.eunho.tagohub.guidance.DriveGuidance.simulating.value) {
                        vm.setMe(it.latitude, it.longitude, center = false)
                        vm.onGpsFix(it.hasBearing(), it.bearing, if (it.hasSpeed()) it.speed else 0f)
                        compass.setLocation(it.latitude, it.longitude)
                    }
                } }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { stop(); stop = {}; compass.stop() }
                else -> {}
            }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) compass.start()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); stop(); compass.stop() }
    }

    if (showLocationWhy) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLocationWhy = false },
            title = { Text("내 위치를 사용할게요") },
            text = { Text("내 주변 정류장과 역, 가까운 버스·지하철을 보여 드리려면 위치 권한이 필요해요. 위치는 주변 정보를 찾는 데만 써요.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLocationWhy = false
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("계속") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showLocationWhy = false }) { Text("나중에") } },
        )
    }

    // 시작: 저장된 자리가 있으면 그대로 두고 내 위치 점만, 없으면 내 위치로 이동.
    // 휴대폰이 알고 있는 마지막 위치를 먼저 바로 쓰고, 새 위치는 뒤에서 받아 보정 (GPS 를 기다리지 않음)
    LaunchedEffect(wantsLocate) {
        if (wantsLocate) {
            vm.wantsLocate.value = false
            if (!hasLocationPermission(context) && !vm.startsAtSaved && vm.askLocationOnce()) {
                // 앱을 처음 켰을 때 현재 위치로 띄우려면 권한이 필요 → 이유를 먼저 설명하고 한 번만 묻는다 (허용하면 locate() 가 내 위치로 이동)
                showLocationWhy = true
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
    val freeDriving by com.eunho.tagohub.guidance.FreeDriveSession.active.collectAsStateWithLifecycle()
    // 경로 안내든 자유주행이든 달리는 중이면 검색창·하단 패널은 숨김
    val driving = guiding || freeDriving

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
        if (!driving) Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, end = 64.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (mapMode) {
                MapMode.Subway -> StationSearch(vm)
                MapMode.Bus -> BusSearchBar(vm)
                MapMode.Transit -> BusSearchBar(vm, withStations = true)
            }
            // 지도를 아예 못 띄울 때의 안내만 보임 (부가 기능의 경고·'확대하면 …' 안내는 지움)
            mapFailure?.let { Banner(it) { vm.mapFailure.value = null } }
            // 숨은 조작(지도 길게 누르기)은 처음 한 번만 짧게 알려 줌
            var longPressHint by remember { mutableStateOf(!hintSeen(context, "map_long_press")) }
            if (longPressHint && mapFailure == null) Banner("지도를 길게 누르면 그 곳으로 길찾기를 시작할 수 있어요.", info = true) {
                markHintSeen(context, "map_long_press")
                longPressHint = false
            }
            val itsRoadLabels = mapOf(
                "its_events" to "ITS 돌발정보",
                "its_disasters" to "ITS 재난정보",
                "its_vsl" to "가변속도표지",
                "its_vms" to "도로전광표지",
                "its_safe_driving" to "주의운전",
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
                .align(if (driving) Alignment.BottomEnd else Alignment.TopEnd)
                .then(if (driving) Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 96.dp, end = 30.dp) else Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp, end = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mapFailed) {
                SmallFloatingActionButton(onClick = { mapAttempt++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "지도 다시 시도")
                }
            }
            SmallFloatingActionButton(onClick = {
                if (driving && vm.recenterGuidance()) Unit
                else if (hasLocationPermission(context)) locate()
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
        FreeDriveOverlay(vm)

        // ── 하단 패널 ── (imePadding: 키보드가 올라오면 패널도 키보드 위로, 내려가면 다시 아래로)
        val pickedPlace by vm.picked.collectAsStateWithLifecycle()
        // 태블릿·폴더블·가로 화면처럼 폭이 넓으면 패널이 화면 전체 폭으로 퍼지지 않게 왼쪽에 폭을 제한해 붙임
        val wideScreen = maxWidth >= 600.dp
        if (!driving) Column(
            Modifier
                .align(if (wideScreen) Alignment.BottomStart else Alignment.BottomCenter)
                .then(if (wideScreen) Modifier.widthIn(max = 440.dp) else Modifier)
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding(),
        ) {
            pickedPlace?.let { PickCard(vm, it) }
            when (panel) {
                MapPanel.Stop -> selected?.let { StopPanel(vm, it, hasRoute = route != null) }
                MapPanel.Route -> route?.let { RoutePanel(vm, it, hasStop = selected != null) }
                MapPanel.Station -> station?.let { d ->
                    val favs by vm.favorites.collectAsStateWithLifecycle()
                    val railName = remember(d) { vm.railStationFor(d.group)?.name }   // 지하철역과 같은 자리의 기차역
                    PanelSurface {
                        StationDetailPanel(
                            d, vm.stations,
                            onClose = vm::closePanel,
                            refreshKey = refreshTick,
                            onArrivalClick = { a -> vm.showArrivalPath(d.group, a) },
                            onUseStation = { asOrigin -> vm.useStationAs(d.group, asOrigin) },
                            onStationName = { n -> vm.stationGroupNear(n, d.group)?.let { vm.focusStation(it) } },
                            neighborResolver = { line, all, code -> vm.neighborsOf(d.group, line, all, code) },
                            favorite = favs.any { it.kind == Favorite.Kind.Station && it.id == d.group.id },
                            onFavorite = { vm.toggleFavorite(Favorite.of(d.group)) },
                            railStationName = railName,
                            onRailJump = railName?.let { rn -> { asOrigin -> vm.jumpToTrain(rn, asOrigin) } },
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
        MapLayerOption(listOf("utic_incidents", "its_events"), "돌발·사고", Icons.Filled.Warning),
        MapLayerOption(listOf("ex_flow"), "고속도로 소통", Icons.Filled.Traffic),
        MapLayerOption(listOf("ex_congest"), "정체구간", Icons.Filled.Traffic),
        MapLayerOption(listOf("ex_burst"), "고속도로 돌발", Icons.Filled.Warning),
        MapLayerOption(listOf("ex_safe"), "안전주행 안내", Icons.Filled.Warning),
        MapLayerOption(listOf("ex_rest"), "휴게소", Icons.Filled.Place),
        MapLayerOption(listOf("ex_toll", "ex_ic"), "영업소·IC", Icons.Filled.Directions),
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
                    d.isEmpty -> Text("등록된 상세 정보가 없어요.", style = MaterialTheme.typography.bodyMedium)
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
    // 목록에 든 영상 주소는 시각이 들어 있어 오래되면 안 열림 → 재생 직전에 이 CCTV 의 주소를 새로 받아 씀 (못 받으면 목록의 주소)
    var streamUrl by remember(cctv.id) { mutableStateOf(cctv.streamUrl) }
    // ExoPlayer: 창을 열자마자 재생 (버퍼 0.3초만 모이면 시작)
    val player = remember(cctv.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(1500, 6000, 300, 600).build())
            .build().apply {
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                playWhenReady = true
            }
    }
    val repo = remember { com.eunho.tagohub.data.ItsCctvRepository() }
    var retried by remember(cctv.id) { mutableStateOf(false) }
    fun play(url: String) {
        // 주소에 .m3u8 이 없고(서버가 302 로 넘겨 줌) 확장자로는 HLS 인지 알 수 없어서 형식을 직접 지정
        player.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setUri(com.eunho.tagohub.data.CctvProxy.wrap(url))
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                .build(),
        )
        player.prepare()
    }
    LaunchedEffect(cctv.id) {
        streamUrl = repo.freshStreamUrl(cctv) ?: cctv.streamUrl
        play(streamUrl)
    }
    val scope = rememberCoroutineScope()
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!retried) {
                    // 한 번은 주소를 다시 받아 재시도 (처음 받은 주소가 그 사이 만료됐을 수 있음)
                    retried = true
                    scope.launch {
                        repo.freshStreamUrl(cctv)?.let { streamUrl = it }
                        play(streamUrl)
                    }
                } else {
                    playbackError = "앱에서 영상을 재생하지 못했어요 (${error.errorCodeName}). 외부 플레이어로 시도해 보세요."
                }
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
                        maxLines = 2,
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
                    "ITS가 제공하는 고속도로·국도 CCTV입니다. 기기 또는 영상 제공 상태에 따라 재생되지 않을 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val uri = Uri.parse(com.eunho.tagohub.data.CctvProxy.wrap(streamUrl))
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.apple.mpegurl"),
                                )
                            } catch (_: ActivityNotFoundException) {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                } catch (_: ActivityNotFoundException) {
                                    playbackError = "이 영상을 열 수 있는 외부 플레이어가 설치되어 있지 않아요."
                                } catch (_: SecurityException) {
                                    playbackError = "외부 플레이어를 실행할 권한이 없어요."
                                }
                            } catch (_: SecurityException) {
                                playbackError = "외부 플레이어를 실행할 권한이 없어요."
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
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                // 버튼 이름이 두 줄로 꺾이지 않게 한 줄로 (자리가 모자라면 글자를 줄임)
                val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                OutlinedButton(onClick = { vm.usePicked(asOrigin = true) }, modifier = Modifier.weight(1f), contentPadding = tight) { com.eunho.tagohub.ui.common.FitText("여기서 출발", maxSizeSp = 15f) }
                OutlinedButton(onClick = vm::usePickedAsVia, modifier = Modifier.weight(1f), contentPadding = tight) { com.eunho.tagohub.ui.common.FitText("경유지로", maxSizeSp = 15f) }
                Button(onClick = { vm.usePicked(asOrigin = false) }, modifier = Modifier.weight(1f), contentPadding = tight) { com.eunho.tagohub.ui.common.FitText("여기로 도착", maxSizeSp = 15f) }
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
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        pl.distance?.let { Text(if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            // 기차역·터미널·공항: 그 시간표의 출발지·도착지로
            p.kind?.let { k ->
                Row(Modifier.padding(top = 4.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.jumpPicked(asOrigin = true) }, modifier = Modifier.weight(1f)) { com.eunho.tagohub.ui.common.FitText("${k.label} 출발", maxSizeSp = 15f) }
                    OutlinedButton(onClick = { vm.jumpPicked(asOrigin = false) }, modifier = Modifier.weight(1f)) { com.eunho.tagohub.ui.common.FitText("${k.label} 도착", maxSizeSp = 15f) }
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
private fun Banner(text: String, info: Boolean = false, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (info) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (info) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
        }
    }
}

/** 바텀시트 높이 상태 (화면 높이 px 기준 비율) */
private class Sheet(val frac: MutableFloatState, val maxPx: Float)

private val LocalSheet = compositionLocalOf<Sheet?> { null }

/** 정류장·노선 정보를 지도 위 시트가 아니라 따로 열린 전체 화면으로 보여 줄 때 true */
private val LocalFullScreen = compositionLocalOf { false }

/** 전체 화면에서 "지도에서 보기"를 누르면 부를 동작 (지도 위 시트에서는 null) */
private val LocalShowMap = compositionLocalOf<(() -> Unit)?> { null }

private val SNAPS = listOf(0.22f, 0.45f, 0.88f, 1.0f)   // 1.0 = 맨 위까지 끌면 전체화면

/** 위쪽 손잡이를 위아래로 끌면 높이가 바뀌고, 놓으면 가까운 높이(작게·중간·크게)에 맞춰진다. 손잡이를 누르면 중간↔크게 */
@Composable
private fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    if (LocalFullScreen.current) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { content() }
        }
        return
    }
    val sheet = LocalSheet.current
    val density = LocalDensity.current
    // 가로 화면처럼 세로가 짧으면 비율만으로는 패널이 너무 낮아져서(내용이 안 보임) 최소 높이를 보장 (화면보다 크진 않게)
    val heightDp = sheet?.let {
        val full = with(density) { it.maxPx.toDp() }
        with(density) { (it.maxPx * it.frac.floatValue).toDp() }.coerceAtLeast(minOf(190.dp, full))
    } ?: 340.dp
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
    // 정류소·노선 패널 머리: 큰 굵은 이름, 오른쪽에 회색 원 안의 닫기(X) — 색 띠 없이 깔끔하게
    val full = LocalFullScreen.current
    Row(Modifier.padding(start = if (full) 6.dp else 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (full) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (switchLabel != null) TextButton(onClick = onSwitch) { Text(switchLabel, fontWeight = FontWeight.Bold) }
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
        if (!LocalFullScreen.current) Surface(onClick = onClose, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
        }
    }
}

/**
 * 버스 노선 머리띠(노선도 방식): 노선 색 바탕에 굵은 노선번호와 종류 알약, 아래에 "기점 → 종점 · 배차 · 운행 중".
 * 닫기·즐겨찾기·새로고침은 흰 아이콘으로 오른쪽에.
 */
@Composable
private fun RouteBandHeader(
    routeNo: String,
    type: String?,
    line: String,
    detail: String,
    color: Color,
    onClose: () -> Unit,
    switchLabel: String?,
    onSwitch: () -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onRefresh: () -> Unit,
) {
    // 노선 종류 배지 + 큰 노선번호, 오른쪽에 새로고침·즐겨찾기·회색 원 닫기. 아래에 "기점 → 종점", 배차·운행 대수
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (LocalFullScreen.current) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            type?.takeIf { it.isNotBlank() }?.let {
                Pill(it.removeSuffix("버스"), color)
                Spacer(Modifier.width(10.dp))
            }
            Text(routeNo, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (switchLabel != null) TextButton(onClick = onSwitch) { Text(switchLabel, fontWeight = FontWeight.Bold) }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "새로고침") }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "즐겨찾기에서 빼기" else "즐겨찾기에 넣기",
                    tint = if (favorite) FAVORITE_GOLD else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!LocalFullScreen.current) Surface(onClick = onClose, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
            }
        }
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 정류장·노선을 지도 없이 따로 여는 전체 화면 (홈·버스 탭에서 정류장이나 노선을 눌렀을 때).
 * 정류장 화면에서 노선을 누르면 노선 화면으로, 뒤로 가면 정류장으로 돌아온다.
 * [runLoops] 가 true 면 도착정보·버스 위치를 여기서 주기적으로 받음 (지도가 이미 떠 있으면 지도 쪽이 받음).
 */
@Composable
fun BusDetailScreen(vm: MapViewModel, runLoops: Boolean, onShowMap: () -> Unit, onLeave: () -> Unit) {
    val panel by vm.panel.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    if (runLoops) {
        LaunchedEffect(selected, refreshTick) { selected?.let { vm.runStop(it) } }
        LaunchedEffect(route, refreshTick) { route?.let { vm.runRoute(it) } }
    }
    BackHandler {
        vm.closePanel()
        if (vm.panel.value == MapPanel.None) onLeave()
    }
    LaunchedEffect(panel) { if (panel == MapPanel.None) onLeave() }
    CompositionLocalProvider(LocalFullScreen provides true, LocalShowMap provides onShowMap) {
        when (panel) {
            MapPanel.Stop -> selected?.let { StopPanel(vm, it, hasRoute = route != null) }
            MapPanel.Route -> route?.let { d ->
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { RoutePanel(vm, d, hasStop = selected != null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Surface(
                            onClick = onShowMap,
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text("지도에서 보기", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun StopPanel(vm: MapViewModel, d: BusDest.Stop, hasRoute: Boolean) {
    val showMap = LocalShowMap.current
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val rows by d.rows.collectAsStateWithLifecycle()
    val updated by d.updated.collectAsStateWithLifecycle()
    val liveNote by d.note.collectAsStateWithLifecycle()
    val others by d.others.collectAsStateWithLifecycle()
    val direction by d.direction.collectAsStateWithLifecycle()
    // 같은 자리 다른 시군 정류장까지 번호를 모두: "30690 · 3110313"
    val numbers = (listOf(d.stop) + others).mapNotNull { it.nodeNo }.distinct()
        .mapIndexed { i, no -> if (i == 0 && direction != null) "$no (${direction} 방면)" else no }
        .joinToString(", ").ifEmpty { null }
    // 받은 뒤 지난 초 (매초 갱신) → 도착 시간이 실제로 줄어든다
    val elapsedSec by produceState(0, updated) {
        while (true) {
            value = updated?.let { java.time.Duration.between(it, LocalTime.now()).seconds.toInt().coerceIn(0, 600) } ?: 0
            delay(1000)
        }
    }
    PanelSurface {
        PanelHeader(
            title = d.stop.name.replace(Regex("\\.지하철(\\d+호선)$"), " (\$1)"),   // "당산역.지하철2호선" → "당산역 (2호선)"
            subtitle = listOfNotNull(numbers, updated?.let { "${it.hhmm()} 갱신" }).joinToString(" · ").ifEmpty { null },
            color = ModeColor.Bus,
            onClose = vm::closePanel,
            switchLabel = if (hasRoute) "노선 보기" else null,
            onSwitch = { vm.openPanel(MapPanel.Route) },
            favorite = favs.any { it.kind == Favorite.Kind.Stop && it.id == d.stop.nodeId },
            onFavorite = { vm.toggleFavorite(Favorite.of(d.stop)) },
            onRefresh = vm::refreshNow,
        )
        val timetable by vm.stopTimetable.collectAsStateWithLifecycle()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        liveNote?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        // 노선 목록(가운데, 남는 높이를 모두 씀) + 아래 고정 바
        Box(Modifier.weight(1f)) {
            val tt = timetable
            if (tt != null) {
                StopTimetable(vm, tt)
            } else {
                LoadView(rows, isEmpty = { it.isEmpty() }, emptyText = "이 정류장을 지나는 노선 정보가 없어요.") { list ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(hideDuplicateNumbers(list)) { row ->
                            // 노선을 누르면 지도에 노선 + 실시간 버스 위치
                            StopRouteItem(row, elapsedSec, direction) { vm.showRoute(row) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        // 아래 바: 왼쪽 시간표(↔ 실시간) 알약, 오른쪽 파란 [출발][도착] 알약 (이 정류장을 길찾기 출발지·도착지로)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = { if (timetable == null) vm.openStopTimetable() else vm.closeStopTimetable() },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (timetable == null) Icons.Filled.Schedule else Icons.Filled.DirectionsBus, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (timetable == null) "시간표" else "실시간", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (showMap != null) {
                Surface(onClick = showMap, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("지도", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (d.stop.lat != null) {
                androidx.compose.material3.OutlinedButton(onClick = { vm.useStopAs(d.stop, asOrigin = true); showMap?.invoke() }, modifier = Modifier.heightIn(min = 48.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                    Text("여기서 출발", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                Button(onClick = { vm.useStopAs(d.stop, asOrigin = false); showMap?.invoke() }, modifier = Modifier.heightIn(min = 48.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                    Text("여기로 도착", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
    }
}

/** 정류장 예상 시간표: 요일 → 노선 → 이 정류장에 서는 예상 시각 (다음 차 강조) */
@Composable
private fun StopTimetable(vm: MapViewModel, state: Load<List<RouteTimes>>) {
    val day by vm.timetableDay.collectAsStateWithLifecycle()
    val preferredRouteId by vm.timetablePreferredRouteId.collectAsStateWithLifecycle()
    var pick by remember(preferredRouteId) { mutableStateOf(preferredRouteId) }
    Column(Modifier.fillMaxSize()) {
        Text(
            "운행 요일",
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(com.eunho.tagohub.data.DayKind.entries) { k ->
                com.eunho.tagohub.ui.common.PillChip(k.label, k == day, { vm.openStopTimetable(k) })
            }
        }
        LoadView(state, onRetry = { vm.openStopTimetable() }, isEmpty = { it.isEmpty() }, emptyText = "시간표가 없어요.") { list ->
            val cur = list.firstOrNull { it.routeId == pick } ?: list.first()
            LaunchedEffect(list, preferredRouteId) {
                if (list.none { it.routeId == pick }) {
                    pick = preferredRouteId?.takeIf { id -> list.any { it.routeId == id } } ?: list.firstOrNull()?.routeId
                }
            }
            Column(Modifier.fillMaxSize()) {
                var picking by remember { mutableStateOf(false) }
                Text(
                    "노선",
                    Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    onClick = { picking = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteTitle(cur, Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "버스 선택")
                    }
                }
                if (picking) {
                    ModalBottomSheet(onDismissRequest = { picking = false }) {
                        Text("노선 선택", Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cur.estimated) "첫차·막차와 배차간격으로 계산한 예상 시각입니다."
                            else "운행 회차와 정류장 이동시간으로 계산한 예상 시각입니다.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!cur.loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = busRouteColor(cur.routeType))
                    Text("시간표 계산 중", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                if (cur.minutes.isEmpty()) {
                    Text(
                        "이 노선은 시간 정보가 없어요.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val now by produceState(LocalTime.now().let { it.hour * 60 + it.minute }, day, cur.routeId) {
                    while (true) {
                        val time = LocalTime.now()
                        value = time.hour * 60 + time.minute
                        delay(30_000L)
                    }
                }
                val next = if (day == com.eunho.tagohub.data.DayKind.today()) cur.minutes.firstOrNull { it >= now } else null
                val mainTime = when {
                    next != null -> JeonjuTimetable.clock(next)
                    day == com.eunho.tagohub.data.DayKind.today() -> "운행 종료"
                    else -> JeonjuTimetable.clock(cur.minutes.first())
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        next != null -> "다음 출발"
                                        day == com.eunho.tagohub.data.DayKind.today() -> "오늘 운행 종료"
                                        else -> "선택한 요일 첫 운행"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(mainTime, style = MaterialTheme.typography.headlineSmall.merge(Tnum), fontWeight = FontWeight.ExtraBold)
                            }
                            if (next != null) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
                                    Text("${next - now}분 후", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("첫차", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                                Text(JeonjuTimetable.clock(cur.minutes.first()), style = MaterialTheme.typography.bodyLarge.merge(Tnum), fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("막차", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                                Text(JeonjuTimetable.clock(cur.minutes.last()), style = MaterialTheme.typography.bodyLarge.merge(Tnum), fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("하루 운행", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                                Text("${cur.minutes.size}회", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text(
                    "시간별 출발 시각",
                    Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    val byHour = cur.minutes.groupBy { (it / 60) % 24 }.toSortedMap().toList()
                    items(byHour, key = { it.first }) { (h, mins) ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(String.format(java.util.Locale.ROOT, "%02d시", h), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${mins.size}회", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                mins.forEach { m ->
                                    val isNext = m == next
                                    val isPast = day == com.eunho.tagohub.data.DayKind.today() && m < now
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isNext) busRouteColor(cur.routeType) else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isNext) Color.White else MaterialTheme.colorScheme.onSurface,
                                        border = if (isNext) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                                        modifier = Modifier.heightIn(min = 44.dp),
                                    ) {
                                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (isNext) {
                                                Text("다음", Modifier.padding(end = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                                            }
                                            Text(
                                                JeonjuTimetable.clock(m),
                                                style = MaterialTheme.typography.titleSmall.merge(Tnum),
                                                fontWeight = if (isNext) FontWeight.ExtraBold else FontWeight.SemiBold,
                                                color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f) else if (isNext) Color.White else MaterialTheme.colorScheme.onSurface,
                                            )
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
    }
}

private fun arrivalAgeSeconds(updated: java.time.LocalTime?): Int {
    if (updated == null) return 0
    val raw = java.time.Duration.between(updated, java.time.LocalTime.now()).seconds
    val normalized = when {
        raw >= 0 -> raw
        raw >= -5 -> 0
        else -> raw + 86_400
    }
    return normalized.toInt().coerceIn(0, 1_200)
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
    // 정류장에서 들어왔으면 그 정류장의 도착정보(정류장 화면과 같은 값)를 목록에도 그대로 보여 줌
    val selectedStop by vm.selected.collectAsStateWithLifecycle()
    val noRows = remember { kotlinx.coroutines.flow.MutableStateFlow<com.eunho.tagohub.core.Load<List<com.eunho.tagohub.ui.bus.StopRouteRow>>>(com.eunho.tagohub.core.Load.Idle) }
    val stopRows by (selectedStop?.rows ?: noRows).collectAsStateWithLifecycle()
    val noUpdated = remember { kotlinx.coroutines.flow.MutableStateFlow<java.time.LocalTime?>(null) }
    val stopUpdated by (selectedStop?.updated ?: noUpdated).collectAsStateWithLifecycle()
    val focusElapsedSec by produceState(arrivalAgeSeconds(stopUpdated), stopUpdated) {
        val initialElapsed = arrivalAgeSeconds(stopUpdated)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            value = (initialElapsed + ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()).coerceIn(0, 1_200)
            delay(1_000L)
        }
    }
    val focusArr = remember(stopRows, d.routeId, d.focusNodeId, selectedStop) {
        if (selectedStop?.stop?.nodeId != d.focusNodeId) null
        else stopRows.valueOrNull()?.firstOrNull { it.routeId == d.routeId }?.arrivals?.firstOrNull()
    }
    val focusArrival = focusArr?.let { com.eunho.tagohub.data.ArrivalText.line(it, focusElapsedSec) }
    var showTimetableStopPicker by rememberSaveable(d.routeId) { mutableStateOf(false) }

    PanelSurface {
        RouteBandHeader(
            routeNo = d.routeNo,
            type = d.routeType ?: r?.routeType,
            line = listOfNotNull(r?.startName, r?.endName).joinToString(" → "),
            detail = listOfNotNull(
                r?.interval?.let { "배차 ${it}분" },
                "운행 중 ${buses.size}대",
                updated?.let { "${it.hhmm()} 갱신" },
            ).joinToString(" · "),
            color = color,
            onClose = vm::closePanel,
            switchLabel = if (hasStop) "정류장" else null,
            onSwitch = { vm.openPanel(MapPanel.Stop) },
            favorite = favs.any { it.kind == Favorite.Kind.Route && it.id == d.routeId },
            onFavorite = { vm.toggleFavorite(vm.routeFavorite(d)) },
            onRefresh = vm::refreshNow,
        )
        r?.let { RouteInfoCard(it) }
        r?.let { LastBusChips(d.routeId, d.routeNo, d.cityCode, r.startName, r.lastTime) }
        val dir by d.dir.collectAsStateWithLifecycle()
        LoadView(stops, isEmpty = { it.isEmpty() }, emptyText = "노선의 정류장 정보가 없어요.") { all ->
            // 상·하행 "○○ 방면" 버튼: 목록은 그 방향만, 지도는 그 방향 선이 진하게
            val labels = remember(all) { vm.routeDirLabels(d) }
            labels?.let { names ->
                // 방면 탭: 고른 쪽은 굵게 + 아래 파란 줄
                Row(Modifier.fillMaxWidth()) {
                    names.forEachIndexed { i, name ->
                        val on = dir == i
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .clickable { vm.setRouteDir(d, i) },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Text(
                                "$name 방면",
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val list = remember(all, dir) { vm.routeStopsInDir(d, all) }
            // 회차 정류장 뒤에 표시 (방향을 나눌 수 없는 노선에서만 의미가 있음)
            val turnOrd = remember(all) { if (labels == null) vm.routeTurnOrd(d) else null }
            val busesAt = remember(buses) {
                { s: RouteStop -> buses.filter { b -> if (b.ord != null) b.ord == s.ord else b.nodeId == s.nodeId } }
            }
            // 목록 안 번호로 본 각 버스의 위치 → 정류장마다 가장 가까이 오는 버스까지 몇 정류장인지
            val busPos = remember(buses, list) { com.eunho.tagohub.data.RouteLive.positions(buses, list) }
            val routeLine = remember(all) { TripPlanner.Line(d.routeId, d.routeNo, all) }
            // 서버에서 같은 위치가 반복되어도 카운트다운은 이어가고, 버스 위치가 바뀌었을 때만 새 기준으로 시작한다.
            val routeReceivedAt = remember(buses) { updated }
            val routeElapsedSec by produceState(arrivalAgeSeconds(routeReceivedAt), buses) {
                val initialElapsed = arrivalAgeSeconds(routeReceivedAt)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    value = (initialElapsed + ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()).coerceIn(0, 1_200)
                    delay(1_000L)
                }
            }
            val liveOf = { i: Int, s: RouteStop ->
                // 도착정보(API)와 버스 위치가 어긋나면 위치를 믿음: 이미 와 있는데 "1정류장 전", 가까이 버스가 보이는데 "7정류장 전"(API 가 그 버스를 빠뜨림)
                val away = com.eunho.tagohub.data.RouteLive.stopsAway(i, busPos.at, busPos.left)
                if (s.nodeId == d.focusNodeId && focusArrival != null && !com.eunho.tagohub.data.RouteLive.preferPosition(focusArr?.prevStops, com.eunho.tagohub.data.RouteLive.allAway(i, busPos.at, busPos.left))) focusArrival
                else away?.let { n ->
                    val from = list[i - n]
                    val seconds = if (n > 0) {
                        val estimate = TripPlanner.minutesBetween(routeLine, from.ord, s.ord) * com.eunho.tagohub.data.RouteSpeed.factor(d.routeId) * 60.0
                        if (estimate.isFinite()) Math.round(estimate).toInt().coerceAtLeast(1) else null
                    } else null
                    val remaining = seconds?.let { (it - routeElapsedSec).coerceAtLeast(0) }
                    com.eunho.tagohub.data.RouteLive.countdownText(n, remaining)
                }
            }
            // 목록에서 누른(지도에 표시된) 정류소에서 내리겠다고 알림을 켤 수 있음
            list.firstOrNull { it.nodeId == d.focusNodeId && it.lat != null && it.lng != null }?.let { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AlightButton(com.eunho.tagohub.guidance.AlightAlert.Target(s.name, s.lat!!, s.lng!!, false, d.routeNo))
                }
            }
            var diagram by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "목록", true to "배치도").forEach { (isDiagram, label) ->
                    FilterChip(selected = diagram == isDiagram, onClick = { diagram = isDiagram }, label = { Text(label) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val focused = list.firstOrNull { it.nodeId == d.focusNodeId }
                    if (focused != null) vm.openRouteStopTimetable(focused.nodeId, d.routeId)
                    else showTimetableStopPicker = true
                }) {
                    Icon(Icons.Filled.Schedule, contentDescription = "정류장별 시간표", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("정류장 시간표")
                }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(list) {
                // 정류소에서 들어왔으면 그 정류소 근처로, 아니면 첫 버스 근처로
                val target = list.indexOfFirst { it.nodeId == d.focusNodeId }.takeIf { it >= 0 }
                    ?: list.indexOfFirst { busesAt(it).isNotEmpty() }.takeIf { it >= 0 }
                if (target != null && target > 2) listState.scrollToItem(target - 2)
            }
            if (diagram) {
                RouteDiagramView(
                    routeId = d.routeId,
                    stops = list,
                    color = color,
                    busOrds = buses.mapNotNull { it.ord }.toSet(),
                    focusedNodeId = d.focusNodeId,
                    onStop = { vm.focusRouteStop(it.nodeId) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else
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
                        live = liveOf(i, s),
                    ) { vm.focusRouteStop(s.nodeId) }
                }
            }
            if (showTimetableStopPicker) {
                ModalBottomSheet(onDismissRequest = { showTimetableStopPicker = false }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            "시간표를 볼 정류장",
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "정류장을 고르면 이 노선을 포함한 전체 시간표를 보여줘요.",
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                            itemsIndexed(list, key = { _, stop -> stop.nodeId }) { i, stop ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showTimetableStopPicker = false
                                            vm.openRouteStopTimetable(stop.nodeId, d.routeId)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stop.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(
                                            stop.nodeNo ?: "노선 내 ${i + 1}번째 정류장",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(Icons.Filled.Schedule, contentDescription = "이 정류장 시간표")
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
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
            SearchBox(query, { query = it }, "검색하기", onSearch = { results.firstOrNull()?.let { pick(it) } })
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
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 엔터를 안 쳐도 입력을 멈추면 바로 검색 (장소·역·정류소). 숫자가 든 말은 전국 노선 검색(느리고 호출이 많음)이라 엔터 때만
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        searched = query
        if (!query.any(Char::isDigit)) vm.searchBus(query)
    }
    LaunchedEffect(Unit) {
        // 홈 화면의 검색창에서 왔으면 바로 입력할 수 있게 (화면이 뜬 뒤에)
        vm.searchFocus.collect { n ->
            if (n > 0) {
                kotlinx.coroutines.delay(350)
                runCatching { searchFocus.requestFocus() }
            }
        }
    }

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
                    "검색하기",
                    Modifier.weight(1f),
                    onSearch = {
                        searched = query
                        vm.searchBus(query)
                    },
                    focusRequester = searchFocus,
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
                            label = { Text(if (f.kind == Favorite.Kind.Route) "${f.name}번" else f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
                        emptyText = "전국에서 검색 결과가 없어요.",
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
                                            Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(listOfNotNull(pl.category, pl.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                item { SectionHeader("정류장 ${r.stops.size}") }
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
                "이 열차의 실시간 위치를 찾지 못해 경로만 표시해요.",
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
                    isOrigin = i == 0 && st.norm == v.originNorm,
                    isPrevious = st.norm == v.previousNorm && st.norm != v.trainAtNorm,
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
    isOrigin: Boolean = false,
    isPrevious: Boolean = false,
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
            val tags = buildList {
                if (isOrigin) add("기점")
                if (isPrevious) add("전역")
                if (trainHere) add("열차 현재 위치")
                if (isLast) add("종점")
            }
            if (tags.isNotEmpty()) {
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (trainHere) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (trainHere) FontWeight.Bold else FontWeight.Normal,
                )
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
 * 길찾기 탭 화면: 지도 밖에서 출발·도착을 고르고 경로 목록과 이동 순서를 봄 (지도 패널과 같은 [TripController] 를 씀).
 * 경로를 골랐거나 자동차·도보 경로가 나오면 "지도에서 보기"로 지도에 그림.
 */
@Composable
fun PlannerPage(vm: MapViewModel, onShowMap: () -> Unit, modifier: Modifier = Modifier) {
    val t = vm.trip
    val origin by t.origin.collectAsStateWithLifecycle()
    val dest by t.dest.collectAsStateWithLifecycle()
    val editing by t.editing.collectAsStateWithLifecycle()
    val selected by t.selected.collectAsStateWithLifecycle()
    val note by t.note.collectAsStateWithLifecycle()
    val mode by t.mode.collectAsStateWithLifecycle()
    val ride by t.ridePlan.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        Text(
            note ?: "이동 경로를 미리 살펴보세요.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (selected != null || (mode != PlanMode.Transit && ride is Load.Ok)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) TextButton(onClick = { t.select(null) }) { Text("‹ 목록") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShowMap) { Text("지도에서 보기") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            when {
                editing != null -> PlanSearch(t, editing!!)
                selected != null -> PlanDetail(t, selected!!)
                else -> PlanList(t)
            }
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
                maxLines = 2,
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
                ?: when (field) {
                    PlanField.Dest -> "목적지 (카카오 장소 검색)"
                    PlanField.Via -> "경유지 (카카오 장소 검색)"
                    PlanField.Origin -> "출발지 (카카오 장소 검색)"
                },
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
                    if (r.value.isEmpty()) item { MessageText("검색 결과가 없어요.") }
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
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            if (mode == PlanMode.Car) CarViaBar(t)
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
                    is Load.Idle -> MessageText("${mode.label} 경로를 불러올 준비가 되었어요.")
                }
            }
        }
        via?.takeIf { mode == PlanMode.Transit }?.let { v ->
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
                    emptyText = "대중교통 경로가 없어요. 다른 이동수단을 선택해 경로를 확인하세요.",
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

/** 자동차 길찾기 경유지: 순서대로 나열하고 하나씩 뺄 수 있음. 최대 5개 (카카오 길찾기 waypoints) */
@Composable
private fun CarViaBar(t: TripController) {
    val vias by t.carVias.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        vias.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("경유 ${i + 1}  ${v.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { t.removeCarVia(i) }) { Text("빼기") }
            }
        }
        if (vias.size < com.eunho.tagohub.data.KakaoDrive.MAX_WAYPOINTS) {
            TextButton(onClick = { t.edit(PlanField.Via) }) { Text(if (vias.isEmpty()) "경유지 추가" else "경유지 더 추가 (${vias.size}/${com.eunho.tagohub.data.KakaoDrive.MAX_WAYPOINTS})") }
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
                if (!leg.subway) {
                    Icon(
                        if (leg.intercity) androidx.compose.material.icons.Icons.Filled.AirportShuttle else Icons.Filled.DirectionsBus,
                        contentDescription = if (leg.intercity) "시외·고속버스" else "시내버스",
                        tint = variant, modifier = Modifier.size(16.dp),
                    )
                }
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
                StepRow(if (leg.subway) Icons.Filled.DirectionsSubway else if (leg.intercity) androidx.compose.material.icons.Icons.Filled.AirportShuttle else Icons.Filled.DirectionsBus, color,
                    (if (i == 0) "" else "환승  ") + (if (leg.subway) "${leg.routeNo} 탑승  ${leg.boardName}역" else "${(listOf(leg.routeNo) + leg.alternatives).joinToString(" · ")}번 ${if (leg.intercity) "시외·고속버스" else "시내버스"} 탑승  ${leg.boardName}") +
                        (if (i == 1) p.transferRemark?.let { " ($it)" }.orEmpty() else ""),
                    when {
                        leg.subway -> "실시간 도착은 지도에서 역을 누르면 볼 수 있어요"
                        i > 1 -> "갈아탈 때 정류장에서 확인"
                        live == null -> "도착 정보 확인 중"
                        arrivals.isNullOrEmpty() -> "지금 오는 버스 정보가 없어요"
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 처음 한 번만 보이는 안내를 봤는지 */
private fun hintSeen(context: android.content.Context, key: String): Boolean =
    runCatching { context.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE).getBoolean(key, false) }.getOrDefault(true)

private fun markHintSeen(context: android.content.Context, key: String) {
    runCatching { context.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE).edit().putBoolean(key, true).apply() }
}
