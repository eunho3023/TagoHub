package com.eunho.tagohub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.ui.coach.CoachScreen
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapScreen
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.timetable.AirScreen
import com.eunho.tagohub.ui.timetable.TrainScreen
import com.eunho.tagohub.ui.map.ItsRoadScreen

/**
 * 한 화면: 지도(버스·지하철) 위에 기차·고속시외·항공을 패널로 띄움 (아래 탭 없음).
 * 여는 방법: 지도에서 기차역·터미널·공항을 눌러 '출발/도착' 또는 지도 오른쪽 버튼(ModeJump.open).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var panel by rememberSaveable { mutableStateOf<ModeJump.Kind?>(null) }
    var mapMode by rememberSaveable { mutableStateOf(MapMode.Transit) }
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ModeJump.drawer.collect {
            if (it) {
                // 신호를 먼저 비워야 다음에 ☰ 를 눌렀을 때 다시 전달됨(값이 계속 true 면 변화가 없어 안 열림).
                // 여는 동작은 따로 실행해서 회전 등으로 끊겨도 이 수신기가 죽지 않게 함
                ModeJump.drawer.value = false
                scope.launch { runCatching { drawerState.open() } }
            }
        }
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // 홈 화면(열린 창 없음)에서 뒤로 가기 → 앱을 끌지 물어봄 (지도 위 창이 열려 있으면 지도 화면이 먼저 닫음)
    var askExit by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = panel == null && !drawerState.isOpen) { askExit = true }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val mapVm = viewModel<MapViewModel>(key = "transitMap")
    if (askExit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text("앱을 종료할까요?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askExit = false; activity?.finish() }) { Text("종료") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askExit = false }) { Text("취소") } },
        )
    }
    LaunchedEffect(Unit) {
        // 출발/도착 요청은 패널 화면이 받아서 채운 뒤 지움 — 여기서는 패널만 엶
        ModeJump.request.collect { j -> j?.let { panel = it.kind } }
    }
    LaunchedEffect(Unit) {
        ModeJump.open.collect { k -> if (k != null) { panel = k; ModeJump.open.value = null } }
    }
    BackHandler(enabled = panel != null) { panel = null }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,   // 닫혀 있을 땐 지도 끌기와 겹치지 않게 ☰ 로만 엶
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
              // 가로 화면은 세로가 짧아서(약 340dp) 9개 항목이 안 들어감 → 두 줄로 나눠 한눈에 보이게
              val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
              fun pick(k: ModeJump.Kind) { panel = k; scope.launch { drawerState.close() } }
              val transit = listOf(
                  Triple("기차", Icons.Filled.Train, ModeJump.Kind.Train),
                  Triple("고속·시외버스", Icons.Filled.DirectionsBus, ModeJump.Kind.Coach),
                  Triple("항공", Icons.Filled.Flight, ModeJump.Kind.Air),
              )
              val traffic = listOf(
                  Triple("도로 정보", Icons.Filled.Traffic, ModeJump.Kind.Road),
              )
              Column(Modifier.verticalScroll(rememberScrollState())) {
                if (compact) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            CompactDrawerLabel("대중교통")
                            transit.forEach { (label, icon, kind) -> CompactDrawerItem(label, icon, panel == kind) { pick(kind) } }
                            CompactDrawerLabel("기타")
                            CompactDrawerItem("공지사항", Icons.Filled.Info, false) { showNotices = true; scope.launch { drawerState.close() } }
                            CompactDrawerItem("앱 제보", Icons.Filled.Email, false) { showReport = true; scope.launch { drawerState.close() } }
                        }
                        Column(Modifier.weight(1f)) {
                            CompactDrawerLabel("교통 정보")
                            traffic.forEach { (label, icon, kind) -> CompactDrawerItem(label, icon, panel == kind) { pick(kind) } }
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 18.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 18.dp, vertical = 20.dp),
                    ) {
                        Text("TAGOHUB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("이동 정보를 한곳에서", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                        Text("대중교통 · 철도 · 도로", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                    }
                    DrawerSectionLabel("대중교통")
                    transit.forEach { (label, icon, kind) -> DrawerItem(label, icon, panel == kind) { pick(kind) } }
                    DrawerSectionLabel("교통 정보")
                    traffic.forEach { (label, icon, kind) -> DrawerItem(label, icon, panel == kind) { pick(kind) } }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    DrawerItem("공지사항", Icons.Filled.Info, false) { showNotices = true; scope.launch { drawerState.close() } }
                    DrawerItem("앱 제보", Icons.Filled.Email, false) { showReport = true; scope.launch { drawerState.close() } }
                }
              }
            }
        },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)   // 아래 탭이 없어져서 시스템 버튼 영역만 비움
    ) {
        MapScreen(mapVm, mapMode, onMapModeChange = { mapMode = it })
        panel?.let { k ->
            Surface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(34.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { panel = null }) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
                    }
                    Box(Modifier.weight(1f)) {
                        when (k) {
                            ModeJump.Kind.Train -> TrainScreen()
                            ModeJump.Kind.Coach -> CoachScreen()
                            ModeJump.Kind.Air -> AirScreen()
                            ModeJump.Kind.Road -> RoadInfoScreen(mapVm)
                        }
                    }
                }
            }
        }
    }
    }
    if (showNotices) NoticesDialog { showNotices = false }
    if (showReport) ReportDialog { showReport = false }
}

/** 가로 화면용 작은 항목 (높이 40dp) */
@Composable
private fun CompactDrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactDrawerLabel(label: String) {
    Text(
        label,
        Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DrawerSectionLabel(label: String) {
    Text(
        label,
        Modifier.padding(start = 28.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
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
