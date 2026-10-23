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
import com.eunho.tagohub.ui.home.MorePage
import com.eunho.tagohub.ui.home.PageScaffold
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapScreen
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.timetable.AirScreen
import com.eunho.tagohub.ui.timetable.TrainScreen

/** 앱의 큰 화면들: 첫 화면(홈), 지도(버스·지하철 포함), 기차·고속시외·항공·도로 정보, 더보기 */
enum class Dest { Home, Map, Train, Coach, Air, Road, More }

private fun ModeJump.Kind.dest(): Dest = when (this) {
    ModeJump.Kind.Train -> Dest.Train
    ModeJump.Kind.Coach -> Dest.Coach
    ModeJump.Kind.Air -> Dest.Air
    ModeJump.Kind.Road -> Dest.Road
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
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var askExit by rememberSaveable { mutableStateOf(false) }
    val mapVm = viewModel<MapViewModel>(key = "transitMap")
    // 지도 화면(카카오맵 시작·타일·GPS)은 처음 지도를 열 때 만들고, 그 뒤로는 계속 유지(상태·위치 보존).
    // 홈이 첫 화면인데 뒤에서 미리 띄우면 앱 여는 시간이 길어짐. 안내 중이면 바로 만듦.
    val guiding by com.eunho.tagohub.guidance.DriveGuidance.active.collectAsStateWithLifecycle()
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
    fun openMap(mode: MapMode) { mapMode = mode; dest = Dest.Map }
    fun onTab(tab: AppTab) = when (tab) {
        AppTab.Home -> dest = Dest.Home
        AppTab.Bus -> openMap(MapMode.Bus)
        AppTab.Subway -> openMap(MapMode.Subway)
        AppTab.Train -> { returnTo = Dest.Home; dest = Dest.Train }
        AppTab.More -> { returnTo = Dest.Home; dest = Dest.More }
    }

    LaunchedEffect(Unit) {
        // 지도의 ☰ 는 더보기를 엶 (신호를 먼저 비워야 다음에 다시 전달됨)
        ModeJump.drawer.collect {
            if (it) {
                ModeJump.drawer.value = false
                open(Dest.More)
            }
        }
    }
    LaunchedEffect(Unit) {
        // 출발/도착 요청은 그 화면이 받아서 채운 뒤 지움 — 여기서는 화면만 엶
        ModeJump.request.collect { j -> j?.let { open(it.kind.dest()) } }
    }
    LaunchedEffect(Unit) {
        ModeJump.open.collect { k -> if (k != null) { open(k.dest()); ModeJump.open.value = null } }
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
        if (mapStarted) MapScreen(mapVm, mapMode, onMapModeChange = { mapMode = it })
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
                                onSearch = { openMap(MapMode.Transit); mapVm.requestSearchFocus() },
                                onOpenMap = ::openMap,
                                onOpenPage = { k -> open(k.dest()) },
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
                    Dest.Train -> PageScaffold(ModeJump.Kind.Train, AppTab.Train, ::onTab) { TrainScreen() }
                    Dest.Coach -> PageScaffold(ModeJump.Kind.Coach, AppTab.More, ::onTab) { CoachScreen() }
                    Dest.Air -> PageScaffold(ModeJump.Kind.Air, AppTab.More, ::onTab) { AirScreen() }
                    Dest.Road -> PageScaffold(ModeJump.Kind.Road, AppTab.More, ::onTab) { RoadInfoScreen(mapVm) }
                    Dest.More -> MorePage(
                        onOpen = { k -> open(k.dest()) },
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
