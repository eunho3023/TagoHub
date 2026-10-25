package com.eunho.tagohub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.ui.coach.CoachScreen
import com.eunho.tagohub.ui.home.AppTab
import com.eunho.tagohub.ui.home.HomeScreen
import com.eunho.tagohub.ui.home.BusTab
import com.eunho.tagohub.ui.home.TransitMode
import com.eunho.tagohub.ui.home.TransitScreen
import com.eunho.tagohub.ui.home.MorePage
import com.eunho.tagohub.ui.home.SearchActions
import com.eunho.tagohub.ui.home.SubwayTab
import com.eunho.tagohub.ui.home.TabScaffold
import com.eunho.tagohub.ui.navi.NavigationScreen
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.home.PageScaffold
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapScreen
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.timetable.AirScreen
import com.eunho.tagohub.ui.timetable.TrainScreen

/** 앱의 큰 화면들: 홈, 대중교통(버스·지하철·기차·고속시외·항공), 지도, 내비게이션, 도로교통, 설정 */
enum class Dest { Home, Map, Transit, Navi, Road, Settings }

/** 지도 등에서 기차·고속시외·항공·도로교통을 열라는 요청이 오면 어느 화면(과 대중교통 갈래)인지 */
private fun ModeJump.Kind.dest(): Dest = if (this == ModeJump.Kind.Road) Dest.Road else Dest.Transit

private fun ModeJump.Kind.transitMode(): TransitMode? = when (this) {
    ModeJump.Kind.Train -> TransitMode.Train
    ModeJump.Kind.Coach -> TransitMode.Coach
    ModeJump.Kind.Air -> TransitMode.Air
    ModeJump.Kind.Road -> null
}

/**
 * 첫 화면은 "어디로 가시나요?" 홈. 지도는 늘 뒤에 만들어 두고(상태·위치 유지) 홈·기차 같은 화면이 그 위를 덮는다.
 * 지도 화면에서 뒤로 가면 홈, 지도에서 기차역·터미널·공항을 눌러 열린 화면에서 뒤로 가면 지도로 돌아온다.
 */
@Composable
fun MainScreen() {
    var dest by rememberSaveable { mutableStateOf(Dest.Home) }
    var returnTo by rememberSaveable { mutableStateOf(Dest.Home) }
    var mapMode by rememberSaveable { mutableStateOf(MapMode.Transit) }
    var transitMode by rememberSaveable { mutableStateOf(TransitMode.Bus) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var askExit by rememberSaveable { mutableStateOf(false) }
    val mapVm = viewModel<MapViewModel>(key = "transitMap")
    // 지도 화면(카카오맵 시작·타일·GPS)은 처음 지도를 열 때 만들고, 그 뒤로는 계속 유지(상태·위치 보존).
    // 홈이 첫 화면인데 뒤에서 미리 띄우면 앱 여는 시간이 길어짐. 안내 중이면 바로 만듦.
    val guiding by com.eunho.tagohub.guidance.DriveGuidance.active.collectAsStateWithLifecycle()
    val freeDriving by com.eunho.tagohub.guidance.FreeDriveSession.active.collectAsStateWithLifecycle()
    var mapStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(dest, guiding) { if (dest == Dest.Map || dest == Dest.Road || guiding) mapStarted = true }
    // 지도가 맨 앞에 보일 때만 도착정보·버스 위치를 주기적으로 받음 (홈이 덮고 있거나 앱을 내려놓으면 멈춤)
    LaunchedEffect(dest) { com.eunho.tagohub.core.AppState.mapVisible.value = dest == Dest.Map }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    /** 다른 화면을 열 때 어디로 돌아올지 기억 (지도에서 열었으면 지도) */
    fun open(next: Dest) {
        if (dest == Dest.Map || dest == Dest.Home) returnTo = dest
        dest = next
    }
    fun openKind(k: ModeJump.Kind) {
        k.transitMode()?.let { transitMode = it }
        open(k.dest())
    }
    fun openTransit(mode: TransitMode) {
        transitMode = mode
        open(Dest.Transit)
    }
    fun openMap(mode: MapMode) { mapMode = mode; dest = Dest.Map }
    fun onTab(tab: AppTab) {
        if (tab != AppTab.Home) returnTo = Dest.Home
        dest = when (tab) {
            AppTab.Home -> Dest.Home
            AppTab.Transit -> Dest.Transit
            AppTab.Map -> { mapMode = MapMode.Transit; Dest.Map }
            AppTab.Navi -> Dest.Navi
            AppTab.Settings -> Dest.Settings
        }
    }
    // 검색 결과를 누르면 그때 지도를 열어 그곳을 보여 줌 (검색 자체는 홈·버스·지하철 탭 안에서)
    val searchActions = SearchActions(
        onPlace = { mapVm.pickPlace(it); openMap(MapMode.Transit) },
        onStation = { mapVm.focusStation(it); openMap(MapMode.Subway) },
        onStop = { mapVm.pickStop(it); openMap(MapMode.Bus) },
        onRoute = { mapVm.pickRoute(it); openMap(MapMode.Bus) },
    )

    LaunchedEffect(Unit) {
        // 지도의 ☰ 는 더보기를 엶 (신호를 먼저 비워야 다음에 다시 전달됨)
        ModeJump.drawer.collect {
            if (it) {
                ModeJump.drawer.value = false
                open(Dest.Settings)
            }
        }
    }
    LaunchedEffect(Unit) {
        // 출발/도착 요청은 그 화면이 받아서 채운 뒤 지움 — 여기서는 화면만 엶
        ModeJump.request.collect { j -> j?.let { openKind(it.kind) } }
    }
    LaunchedEffect(Unit) {
        ModeJump.open.collect { k -> if (k != null) { openKind(k); ModeJump.open.value = null } }
    }

    // 뒤로 가기: 지도 위 창은 지도 화면이 먼저 닫고(안쪽 핸들러가 우선), 그 뒤 지도→홈, 다른 화면→온 곳, 홈→종료 확인
    BackHandler(enabled = dest == Dest.Home) { askExit = true }
    BackHandler(enabled = dest == Dest.Map) { dest = Dest.Home }
    BackHandler(enabled = dest != Dest.Home && dest != Dest.Map) { dest = returnTo }

    if (askExit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text("앱을 종료할까요?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askExit = false; activity?.finish() }) { Text("종료") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askExit = false }) { Text("취소") } },
        )
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        // 지도 탭: 지도 아래에 하단 탭 (달리는 중에는 숨김)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (mapStarted) MapScreen(mapVm, mapMode, onMapModeChange = { mapMode = it })
            }
            if (dest == Dest.Map && !guiding && !freeDriving) com.eunho.tagohub.ui.home.AppBottomNav(AppTab.Map, ::onTab)
        }
        if (dest != Dest.Map) {
            // 지도 위를 덮는 화면: 뒤의 지도가 터치를 받지 않게 막음
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                when (dest) {
                    Dest.Home -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars)) {
                            HomeScreen(
                                vm = mapVm,
                                search = searchActions,
                                onOpenMap = ::openMap,
                                onOpenTab = ::onTab,
                                onOpenTransit = ::openTransit,
                                onOpenPage = ::openKind,
                                onGoTo = { place ->
                                    openMap(MapMode.Transit)
                                    mapVm.openPlanner()
                                    mapVm.trip.goTo(place)
                                },
                                onFavorite = { f -> openMap(MapMode.Transit); mapVm.openFavorite(f) },
                            )
                        }
                        com.eunho.tagohub.ui.home.AppBottomNav(AppTab.Home, ::onTab)
                    }
                    Dest.Transit -> TabScaffold("대중교통", com.eunho.tagohub.ui.theme.headerColor(), AppTab.Transit, ::onTab) {
                        TransitScreen(
                            mode = transitMode,
                            onMode = { transitMode = it },
                            bus = { BusTab(mapVm, searchActions, onFavorite = { f -> openMap(MapMode.Bus); mapVm.openFavorite(f) }, onShowMap = { openMap(MapMode.Bus) }) },
                            subway = { SubwayTab(mapVm, searchActions, onShowMap = { openMap(MapMode.Subway) }) },
                            train = { TrainScreen() },
                            coach = { CoachScreen() },
                            air = { AirScreen() },
                        )
                    }
                    Dest.Navi -> TabScaffold("내비게이션", androidx.compose.ui.graphics.Color(0xFF294653), AppTab.Navi, ::onTab) {
                        NavigationScreen(
                            onPlanRoute = { openMap(MapMode.Transit); mapVm.openPlanner() },
                            onShowMap = { openMap(MapMode.Transit) },
                            onRoadInfo = { openKind(ModeJump.Kind.Road) },
                            onGoRecent = { place ->
                                openMap(MapMode.Transit)
                                mapVm.openPlanner()
                                mapVm.trip.goTo(place)
                            },
                        )
                    }
                    Dest.Road -> PageScaffold(ModeJump.Kind.Road, AppTab.Navi, ::onTab) { RoadInfoScreen(mapVm) }
                    Dest.Settings -> MorePage(
                        onOpen = ::openKind,
                        onNotices = { showNotices = true },
                        onReport = { showReport = true },
                        onTab = ::onTab,
                    )
                    Dest.Map -> Unit
                }
            }
        }
    }
    if (showNotices) NoticesDialog { showNotices = false }
    if (showReport) ReportDialog { showReport = false }
}

/** 공지사항 (깃허브 notices.json) */
@Composable
private fun NoticesDialog(onClose: () -> Unit) {
    val list by androidx.compose.runtime.produceState<List<com.eunho.tagohub.data.Notices.Notice>?>(null) { value = com.eunho.tagohub.data.NoticeClient.load() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("닫기") } },
        title = { Text("공지사항") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                val l = list
                when {
                    l == null -> item { }
                    l.isEmpty() -> item { Text("공지사항이 없습니다. (인터넷 연결을 확인하세요)") }
                    else -> items(l.size) { i ->
                        val n = l[i]
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(n.title, fontWeight = FontWeight.Bold)
                            Text(n.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}

/** 앱 제보: 오류·추가·삭제 → 깃허브 이슈 쓰기 화면 (제목·내용 채움) */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReportDialog(onClose: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf("오류") }
    var text by rememberSaveable { mutableStateOf("") }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("앱 제보") },
        text = {
            Column {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("오류", "추가 요청", "삭제 요청").forEach { k ->
                        androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) })
                    }
                }
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("내용 (어디서, 무엇이)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 4)
                Text("보내기를 누르면 깃허브 제보 화면이 열려요 (깃허브 로그인 필요).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = text.isNotBlank(), onClick = {
                runCatching { uri.openUri(com.eunho.tagohub.data.Notices.reportUrl(kind, text)) }
                onClose()
            }) { Text("보내기") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("취소") } },
    )
}
