package com.eunho.tagohub.ui.map

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.DriveOption
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.ZoneGroup
import com.eunho.tagohub.data.ZoneStatus
import kotlinx.coroutines.delay
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.data.TurnKind
import com.eunho.tagohub.data.driveSteps
import com.eunho.tagohub.guidance.DriveGuidance
import com.eunho.tagohub.guidance.GuidanceText
import com.eunho.tagohub.ui.bus.hasLocationPermission

/** 내비 앱 느낌의 색: 안내 배너(파랑), 모의주행 버튼(남색) */
private val NaviBlue = Color(0xFF2F6BFF)
private val NaviBlueDark = Color(0xFF1F4FC4)
private val NaviNavy = Color(0xFF1F2A44)

internal fun TurnKind.icon(): ImageVector = when (this) {
    TurnKind.Left -> Icons.Filled.TurnLeft
    TurnKind.Right -> Icons.Filled.TurnRight
    TurnKind.UTurn -> Icons.Filled.UTurnLeft
    TurnKind.SlightLeft -> Icons.Filled.TurnSlightLeft
    TurnKind.SlightRight -> Icons.Filled.TurnSlightRight
    TurnKind.Straight, TurnKind.Start -> Icons.Filled.Straight
    TurnKind.HighwayEnter, TurnKind.HighwayExit -> Icons.Filled.Route
    TurnKind.Toll -> Icons.Filled.Paid
    TurnKind.Destination -> Icons.Filled.Flag
}

/**
 * 안내 중 화면: 위쪽 파란 회전 배너(+이어지는 작은 배너), 오른쪽 남은 길 소통 막대, 아래 도착 시각 바와 ⋮ 설정.
 * 지도 전체 크기의 Box 안에 놓는다. 안내 중이 아니면 아무것도 그리지 않는다(지도를 내 위치에 맞추는 일만 함).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveGuidanceOverlay(vm: MapViewModel, modifier: Modifier = Modifier) {
    val active by DriveGuidance.active.collectAsStateWithLifecycle()
    val info by DriveGuidance.info.collectAsStateWithLifecycle()
    val route by DriveGuidance.route.collectAsStateWithLifecycle()
    val note by DriveGuidance.note.collectAsStateWithLifecycle()
    val simulating by DriveGuidance.simulating.collectAsStateWithLifecycle()

    val nav = remember(route) { route?.let { NavRoute.from(it) } }
    // 지도가 내 위치(GPS)를 따라감: 진행 방향 앞쪽을 더 보여주고, 빠를수록 넓게. 손으로 움직이면 멈춤
    val lastPos = remember { arrayOfNulls<Pair<Double, Double>>(1) }
    var bearing by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(info?.lat, info?.lng) {
        val i = info ?: return@LaunchedEffect
        val last = lastPos[0]
        // 8m 이상 움직였을 때만 방향을 새로 구함 (멈춰 있을 때 GPS 흔들림으로 방향이 돌지 않게)
        if (last == null || Geo.meters(last.first, last.second, i.lat, i.lng) >= 8.0) {
            if (last != null) bearing = Geo.bearing(last.first, last.second, i.lat, i.lng)
            lastPos[0] = i.lat to i.lng
        }
        vm.followMe(i.lat, i.lng, i.speedKmh, bearing)
    }
    // 지나온 경로는 지도에서 지우고 남은 경로만 그림 (재탐색으로 경로가 바뀌어도 새 경로로)
    val latest = rememberUpdatedState(info)
    LaunchedEffect(nav) {
        val n = nav ?: return@LaunchedEffect
        var drawn = -1
        while (true) {
            val progress = latest.value?.state?.progressMeters ?: 0.0
            val bucket = (progress / 25.0).toInt()
            if (bucket != drawn) {
                drawn = bucket
                vm.trip.showDriveRemaining(n, progress, DriveGuidance.destination ?: n.points.last())
            }
            delay(700)
        }
    }
    // 안내 중에는 화면이 저절로 꺼지지 않게 (운전 중 지도를 계속 봐야 함)
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
    if (!active) return

    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    // 뒤로 가기는 바로 끄지 않고 먼저 물어봄 (운전 중 잘못 눌러 안내가 끝나지 않게)
    BackHandler { confirmEnd = true }

    Box(modifier.fillMaxSize()) {
        val st = info?.state

        // ── 위: 파란 회전 배너 ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                st == null -> StatusBanner("위치를 찾는 중…")
                st.arrived -> StatusBanner("목적지에 도착했습니다")
                st.offRoute -> StatusBanner("경로를 다시 찾는 중", warn = true)
                st.nextGuide != null -> {
                    TurnBanner(st.nextGuide, st.distanceToNext)
                    val after = st.afterGuide
                    // 바로 이어지는 회전만 작게 미리 보여줌
                    if (after != null && st.distanceNextToAfter <= 1000.0 && !st.nextGuide.isDestination) {
                        AfterBanner(after, st.distanceNextToAfter)
                    }
                }
                else -> StatusBanner("경로 안내 중")
            }
            info?.zone?.takeIf { it.next != null && it.distance <= 1500.0 }?.let { ZoneCard(it) }
            info?.rest?.let { RestCard(it) }
            val suggestion by DriveGuidance.suggestion.collectAsStateWithLifecycle()
            suggestion?.let { SuggestionCard(it) }
            if (simulating) NoteChip("모의주행 중 · 실제 위치가 아닙니다", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            note?.let { NoteChip(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
        }

        // ── 오른쪽: 남은 길 소통 막대 ──
        if (route != null && st != null) {
            val spans = remember(nav, st.progressMeters.toInt() / 100) { nav?.trafficSpans(st.progressMeters).orEmpty() }
            TrafficBar(
                spans,
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).fillMaxHeight(0.36f),
            )
        }

        // ── 왼쪽 아래: 현재 속도와 (앞에 카메라가 있으면) 제한속도 ──
        val zone = info?.zone
        val limit = zone?.next?.zone?.limitKmh?.takeIf { zone.next.zone.kind.isCamera && zone.distance <= 1500.0 }
        SpeedGauge(
            speedKmh = info?.speedKmh ?: 0,
            limitKmh = limit,
            modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, bottom = 88.dp),
        )

        // ── 아래: 도착 시각·남은 거리 바 ──
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (st != null) {
                        Text(
                            GuidanceText.etaLabel(System.currentTimeMillis(), st.remainingSeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${NavText.short(st.remainingMeters)} · ${GuidanceText.duration(st.remainingSeconds)} · ${DriveGuidance.destinationName.ifBlank { "목적지" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text("위치를 찾는 중…", style = MaterialTheme.typography.titleMedium)
                    }
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "안내 설정") }
            }
        }
    }

    if (menu) GuidanceSettingsSheet(onDismiss = { menu = false }, onEnd = { menu = false; confirmEnd = true })
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("경로 안내를 종료할까요?") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; DriveGuidance.stop(context) }) { Text("안내 종료") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("계속 안내") } },
        )
    }
}

/** 현재 속도(동그라미)와, 앞 단속카메라의 제한속도(빨간 테두리). 제한을 넘으면 속도가 빨갛게 */
@Composable
private fun SpeedGauge(speedKmh: Int, limitKmh: Int?, modifier: Modifier = Modifier) {
    val over = limitKmh != null && speedKmh > limitKmh + 3
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.size(64.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("$speedKmh", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (over) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface)
                Text("km/h", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (limitKmh != null) {
            Box(
                Modifier.size(48.dp).background(Color.White, CircleShape).border(5.dp, Color(0xFFD32F2F), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$limitKmh", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** 더 빠른 경로 제안: "더 빠른 경로 · 12분 단축 (거리 +3.2km)" [변경] [무시] */
@Composable
private fun SuggestionCard(s: DriveGuidance.Suggestion) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("더 빠른 경로가 있어요", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = NaviBlue)
                Text("${GuidanceText.duration(s.savedSeconds)} 단축", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { DriveGuidance.dismissSuggestion() }) { Text("무시") }
            Button(onClick = { DriveGuidance.acceptSuggestion() }, colors = ButtonDefaults.buttonColors(containerColor = NaviBlue, contentColor = Color.White)) { Text("경로 변경") }
        }
    }
}

/** 다음 휴게소: "다음 휴게소 서울만남휴게소 · 12.3km" */
@Composable
private fun RestCard(r: com.eunho.tagohub.data.RestStatus) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp, modifier = Modifier.widthIn(max = 320.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalCafe, contentDescription = null, tint = Color(0xFF2E9B57), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("다음 ${r.name} · ${NavText.short(r.distance)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/** 앞의 단속카메라·어린이 보호구역: "과속단속카메라 · 500m" (제한속도를 넘으면 빨갛게) */
@Composable
private fun ZoneCard(z: ZoneStatus) {
    val rz = z.next ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (z.overspeed) Color(0xFFD32F2F) else MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (rz.zone.kind.group) {
                    ZoneGroup.Protect -> Icons.Filled.ChildCare
                    ZoneGroup.Bump -> Icons.Filled.Warning
                    ZoneGroup.Camera, ZoneGroup.Rest -> Icons.Filled.CameraAlt
                },
                contentDescription = null,
                tint = if (z.overspeed) Color.White else Color(0xFFE08A00),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${rz.zone.kind.label} · ${NavText.short(z.distance)}" + (rz.zone.limitKmh?.let { " · 제한 $it" } ?: ""),
                color = if (z.overspeed) Color.White else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TurnBanner(guide: DriveGuide, meters: Double) {
    val kind = TurnKind.of(guide)
    Surface(shape = RoundedCornerShape(16.dp), color = NaviBlue, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(NavText.short(meters), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (guide.isDestination) "목적지" else TurnKind.text(guide),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AfterBanner(guide: DriveGuide, meters: Double) {
    Surface(shape = RoundedCornerShape(12.dp), color = NaviBlueDark, shadowElevation = 6.dp, modifier = Modifier.widthIn(max = 260.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("이어서", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Icon(TurnKind.of(guide).icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(6.dp))
            Text(NavText.short(meters), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StatusBanner(text: String, warn: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (warn) Color(0xFFE08A00) else NaviBlue, shadowElevation = 8.dp) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NoteChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = content, style = MaterialTheme.typography.bodySmall)
    }
}

/** 남은 길의 소통 상태를 세로 막대로: 위가 목적지, 아래(내 위치)에서 위로 올라감 */
@Composable
private fun TrafficBar(spans: List<com.eunho.tagohub.data.TrafficSpan>, modifier: Modifier = Modifier) {
    Canvas(modifier.width(14.dp)) {
        val w = size.width * 0.55f
        val x = (size.width - w) / 2f
        val markerR = size.width / 2f
        val top = markerR
        val bottom = size.height - markerR
        val h = bottom - top
        // 바탕(소통 정보가 없는 곳)
        drawRoundRect(Color(0x55808080), Offset(x, top), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2f))
        spans.forEach { s ->
            val y1 = bottom - h * s.to
            val y2 = bottom - h * s.from
            if (y2 - y1 > 0.5f) drawRect(Color(trafficColor(s.state)), Offset(x, y1), Size(w, y2 - y1))
        }
        // 내 위치
        drawCircle(Color.White, markerR, Offset(size.width / 2f, bottom))
        drawCircle(NaviBlue, markerR * 0.62f, Offset(size.width / 2f, bottom))
        // 목적지 깃발 대신 위쪽 끝점
        drawLine(Color.White, Offset(size.width / 2f, top), Offset(size.width / 2f, top), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** ⋮ 설정: 안내음성 켜기·끄기와 크기, (모의주행이면 배속·이탈 시험), 안내 종료 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidanceSettingsSheet(onDismiss: () -> Unit, onEnd: () -> Unit) {
    val voiceOn by DriveGuidance.voiceOn.collectAsStateWithLifecycle()
    val volume by DriveGuidance.voiceVolume.collectAsStateWithLifecycle()
    val simulating by DriveGuidance.simulating.collectAsStateWithLifecycle()
    val simFactor by DriveGuidance.simFactor.collectAsStateWithLifecycle()
    val groups by DriveGuidance.zoneGroups.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("안내 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("안내음성", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = voiceOn, onCheckedChange = { DriveGuidance.voiceOn.value = it })
            }
            Column {
                Text("안내음성 크기", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = volume, onValueChange = { DriveGuidance.voiceVolume.value = it }, enabled = voiceOn)
            }
            HorizontalDivider()
            Text("안내받을 항목", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val context = LocalContext.current
            ZoneGroup.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = g in groups, onCheckedChange = { DriveGuidance.setZoneGroup(context, g, it) })
                }
            }
            if (simulating) {
                HorizontalDivider()
                Text("모의주행", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 15).forEach { f ->
                        FilterChip(selected = simFactor == f, onClick = { DriveGuidance.simFactor.value = f }, label = { Text("${f}배속") })
                    }
                    OutlinedButton(onClick = { DriveGuidance.deviate.value += 1 }) { Text("이탈 시험") }
                }
            }
            Button(
                onClick = onEnd,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaviNavy, contentColor = Color.White),
            ) { Text("안내 종료") }
        }
    }
}

/**
 * 자동차 경로 미리보기: 시간·거리·요금 머리글, 회전 단계 목록, 아래 [모의주행][안내시작] 버튼.
 * 이미 안내 중이면 버튼 자리에 [안내 종료].
 */
@Composable
internal fun DriveRoutePanel(plan: RidePlan, option: DriveOption, onOption: (DriveOption) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        // 길찾기 옵션: 추천 · 최단거리 · 무료도로 · 고속도로 제외
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DriveOption.entries) { o ->
                FilterChip(selected = option == o, onClick = { onOption(o) }, label = { Text(o.label) })
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(GuidanceText.duration(plan.durationSeconds), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(NavText.short(plan.distanceMeters.toDouble()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            val toll = plan.tollWon
            val fare = buildList {
                add(if (toll != null && toll > 0) "통행료 ${"%,d".format(toll)}원" else "통행료 무료")
                plan.taxiWon?.takeIf { it > 0 }?.let { add("택시 ${"%,d".format(it)}원") }
            }
            Text(fare.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            trafficSummary(plan.roads)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        val steps = remember(plan.guides) { driveSteps(plan.guides) }
        LazyColumn(Modifier.weight(1f, fill = true), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(steps) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(s.kind.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.kind == TurnKind.Destination) plan.destName.ifBlank { "목적지" } else s.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (s.kind == TurnKind.Destination) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (s.distanceAfterMeters > 0) {
                            Text(NavText.short(s.distanceAfterMeters.toDouble()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        DriveStartBar(plan)
    }
}

/**
 * 아래 두 버튼: [모의주행(남색)] [안내시작(파랑)]. 위치(앱 사용 중)와 알림(안드로이드 13+) 권한을 확인한 뒤 경로 안내 서비스를 켠다.
 * 위치 권한이 없으면 시작하지 않는다. 알림 권한만 없으면 안내는 되지만 알림이 보이지 않을 수 있다.
 */
@Composable
private fun DriveStartBar(plan: RidePlan) {
    val context = LocalContext.current
    val active by DriveGuidance.active.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) pending?.invoke()
        pending = null
    }
    /** [simulate] 이 true 면 모의주행(GPS 대신 가상 위치). 둘 다 같은 권한이 필요(위치 유형 서비스를 올리기 때문) */
    fun begin(simulate: Boolean) {
        val route = plan.drive ?: return
        val start = { DriveGuidance.start(context, route, LatLng(plan.destLat, plan.destLng), plan.destName, simulate, plan.option) }
        val need = buildList {
            if (!hasLocationPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) start() else { pending = start; launcher.launch(need.toTypedArray()) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (active) {
            OutlinedButton(onClick = { DriveGuidance.stop(context) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("안내 종료") }
        } else {
            Button(
                onClick = { begin(true) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaviNavy, contentColor = Color.White),
            ) { Text("모의주행") }
            Button(
                onClick = { begin(false) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaviBlue, contentColor = Color.White),
            ) { Text("안내시작") }
        }
    }
}
