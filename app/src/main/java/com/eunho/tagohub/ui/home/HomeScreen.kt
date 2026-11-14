package com.eunho.tagohub.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import com.eunho.tagohub.ui.common.pressScale
import com.eunho.tagohub.ui.common.riseIn
import com.eunho.tagohub.ui.map.MapMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirportShuttle
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.SavedKind
import com.eunho.tagohub.data.SavedPlace
import com.eunho.tagohub.ui.ModeJump
import com.eunho.tagohub.ui.common.AnimatedValueText
import com.eunho.tagohub.ui.bus.arrivalsFor
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.NumberFont
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 아래 탭: 홈(지도)·대중교통(버스·지하철·기차·고속시외·항공)·내비게이션·설정 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Home("홈", Icons.Outlined.Home),
    Transit("대중교통", Icons.Outlined.DirectionsTransit),
    Navi("길찾기", Icons.Outlined.Directions),
    Settings("설정", Icons.Outlined.Settings),
}

@Composable
fun AppBottomNav(active: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Column {
            Row(Modifier.fillMaxWidth().height(70.dp)) {
                AppTab.entries.forEach { tab ->
                    val on = tab == active
                    val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 고른 탭은 아이콘 뒤에 옅은 파란 알약
                        Box(
                            Modifier
                                .size(width = 54.dp, height = 30.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        // 글자 크기 설정이 커져도 탭 이름이 잘리지 않게 최대 두 줄로 표시
                        Text(
                            tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Black else FontWeight.SemiBold,
                            color = tint,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private class Tile(val label: String, val icon: ImageVector, val light: Color, val dark: Color, val onClick: () -> Unit)

/** 홈 위쪽 카드: 앱 이름 + 검색 (누르면 전체 화면 검색). 지도 위에 떠 있음 */
@Composable
fun HomeTopCard(modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = com.eunho.tagohub.ui.theme.glassColor(),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
            BrandHeader(Modifier.riseIn(0))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.riseIn(2)) { SearchBarButton() }
        }
    }
}

/**
 * 홈 아래 시트: 지도 위에 떠 있고 손으로 끌어 올리고 내린다.
 * 끝까지 내리면 손잡이와 제목만 남아 지도가 거의 다 보이고, 올리면 실시간 도착·바로가기·즐겨찾기·최근이 펼쳐진다.
 * 목록을 맨 위에서 더 내리면 시트가 내려가고, 위로 밀면 시트가 먼저 올라간 뒤 목록이 움직인다.
 */
@Composable
fun HomeSheet(
    vm: MapViewModel,
    search: SearchActions,
    onOpenMap: (MapMode) -> Unit,
    onOpenTab: (AppTab) -> Unit,
    onGoTo: (SavedPlace) -> Unit,
    onFavorite: (Favorite) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // 시트 높이(dp): 접힘 · 중간 · 펼침
    val peek = 96f
    val full = (maxHeight.value * 0.86f).coerceAtLeast(peek + 160f)
    val mid = (maxHeight.value * 0.5f).coerceIn(peek + 80f, full)
    val snaps = listOf(peek, mid, full)
    var h by rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    if (h < 0f) h = mid
    h = h.coerceIn(peek, full)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetScroll = rememberScrollState()
    var recentY by remember { mutableStateOf(0) }
    var favY by remember { mutableStateOf(0) }

    fun settle(velocity: Float) {
        val target = when {
            velocity > 900f -> snaps.lastOrNull { it < h - 1f } ?: peek      // 아래로 휙 → 한 단계 내림
            velocity < -900f -> snaps.firstOrNull { it > h + 1f } ?: full    // 위로 휙 → 한 단계 올림
            else -> snaps.minBy { kotlin.math.abs(it - h) }
        }
        scope.launch { androidx.compose.animation.core.animate(h, target) { v, _ -> h = v } }
    }
    fun expandThen(scrollTo: () -> Int) {
        scope.launch {
            androidx.compose.animation.core.animate(h, full) { v, _ -> h = v }
            sheetScroll.animateScrollTo(scrollTo())
        }
    }
    // 목록 스크롤과 시트 끌기를 이어 줌
    val nested = remember(peek, full) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y >= 0f || h >= full) return androidx.compose.ui.geometry.Offset.Zero
                val next = (h - available.y / density).coerceAtMost(full)
                val used = next - h
                h = next
                return androidx.compose.ui.geometry.Offset(0f, -used * density)
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y <= 0f || h <= peek) return androidx.compose.ui.geometry.Offset.Zero
                val next = (h - available.y / density).coerceAtLeast(peek)
                val used = h - next
                h = next
                return androidx.compose.ui.geometry.Offset(0f, used * density)
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (snaps.any { kotlin.math.abs(it - h) < 1f }) return androidx.compose.ui.unit.Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { delta -> h = (h - delta / density).coerceIn(peek, full) }

    Surface(
        Modifier.fillMaxWidth().height(h.dp),
        color = com.eunho.tagohub.ui.theme.glassColor(),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // 손잡이 + 제목: 끌어서 올리고 내림, 누르면 접기 ↔ 중간
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { v -> settle(v) })
                    .clickable(role = Role.Button) {
                        val target = if (h > peek + 1f) peek else mid
                        scope.launch { androidx.compose.animation.core.animate(h, target) { v, _ -> h = v } }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("지금 오는 버스", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                        Text("내 주변 정류장 · 즐겨찾기 · 최근 목적지", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)
                    .nestedScroll(nested)
                    .verticalScroll(sheetScroll)
                    .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            ) {
                // 시트를 올리면 가장 먼저 보이는 정보: 가까운 정류장에 지금 오는 버스
                LiveArrivalsCard(
                    onStop = search.onStop,
                    onShowMap = { onOpenMap(MapMode.Bus) },
                    modifier = Modifier.padding(top = 2.dp),
                )
                ShortcutTiles(
                    vm, onGoTo, onOpenTab,
                    onFavorites = { expandThen { favY } },
                    onRecent = { expandThen { recentY } },
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(14.dp))
                Column(Modifier.onGloballyPositioned { recentY = it.positionInParent().y.toInt() }) { RecentChips(onGoTo, Modifier) }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.onGloballyPositioned { favY = it.positionInParent().y.toInt() }) { Favorites(vm, onFavorite) }
                Spacer(Modifier.height(16.dp))
                NearbyStops(search.onStop, onShowMap = { onOpenMap(MapMode.Bus) })
            }
        }
    }
}

/** 위쪽 로고: 앱 아이콘(접힌 지도 + 위치 핀) + 앱 이름 */
@Composable
internal fun BrandHeader(modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 아이콘 그림은 둘레에 여백이 있어 조금 크게 그리고 잘라 냄
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.eunho.tagohub.R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.requiredSize(42.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("모두의 지도", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface)
            Text("원하는 곳 어디든지!", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(action, Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp).wrapContentHeight(Alignment.CenterVertically), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 최근에 길찾기한 목적지 (눌러서 바로 길찾기) */
@Composable
private fun RecentChips(onGoTo: (SavedPlace) -> Unit, modifier: Modifier = Modifier) {
    val items by com.eunho.tagohub.data.RecentDestinations.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    Column(modifier.padding(top = 18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("최근 검색", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Text("지우기", Modifier.clickable { com.eunho.tagohub.data.RecentDestinations.clear() }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(3).forEach { r ->
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Surface(
                onClick = { onGoTo(SavedPlace(r.name, r.lat, r.lng)) },
                interactionSource = src,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.pressScale(src),
            ) {
                Text(r.name, Modifier.heightIn(min = 32.dp).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
}

@Composable
private fun TileSection(title: String, tiles: List<Tile>, columns: Int, dark: Boolean) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp))
    tiles.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { t -> ModeTile(t, dark, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ModeTile(t: Tile, dark: Boolean, modifier: Modifier = Modifier) {
    // 흰 카드에 수단 색 원형 아이콘 + 굵은 이름 (색은 아이콘에만)
    // 한 줄에 세 칸: 흰 카드, 위에 수단 색 원형 아이콘, 아래에 이름 (긴 이름도 줄바꿈 없이 한 줄에 들어가는 크기)
    Surface(
        onClick = t.onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 78.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(38.dp).background(if (dark) t.dark else t.light, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
com.eunho.tagohub.ui.common.FitText(
                t.label,
                maxSizeSp = 13.5f, minSizeSp = 9f,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 바로가기 네 칸: 집 · 회사(저장해 둔 곳으로 바로 길찾기), 즐겨찾기 · 최근(아래 목록으로 내려감) */
@Composable
private fun ShortcutTiles(vm: MapViewModel, onGoTo: (SavedPlace) -> Unit, onOpenTab: (AppTab) -> Unit, onFavorites: () -> Unit, onRecent: () -> Unit, modifier: Modifier = Modifier) {
    val saved by vm.trip.homeWork.state.collectAsStateWithLifecycle()
    val items: List<Triple<String, ImageVector, () -> Unit>> = listOf(
        Triple(SavedKind.Home.label, Icons.Outlined.Home, saved.home?.let { p -> { onGoTo(p) } } ?: { onOpenTab(AppTab.Navi) }),
        Triple(SavedKind.Work.label, Icons.Outlined.Work, saved.work?.let { p -> { onGoTo(p) } } ?: { onOpenTab(AppTab.Navi) }),
        Triple("즐겨찾기", Icons.Outlined.StarBorder, onFavorites),
        Triple("최근", Icons.Outlined.History, onRecent),
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (label, icon, click) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // 칸마다 다른 파스텔 바탕 + 같은 계열 아이콘
                Surface(
                    onClick = click,
                    shape = RoundedCornerShape(18.dp),
                    color = com.eunho.tagohub.ui.theme.Pastel.bg(i),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label, tint = com.eunho.tagohub.ui.theme.Pastel.icon(i), modifier = Modifier.size(26.dp))
                    }
                }
                com.eunho.tagohub.ui.common.FitText(label, Modifier.padding(top = 6.dp), maxSizeSp = 12f, minSizeSp = 9f, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 집·회사 한 번에 가기 (저장해 둔 것만 보임) */
@Composable
private fun SavedPlaces(vm: MapViewModel, onGoTo: (SavedPlace) -> Unit) {
    val saved by vm.trip.homeWork.state.collectAsStateWithLifecycle()
    if (saved.home == null && saved.work == null) return
    val first = remember { com.eunho.tagohub.data.HomeWork.suggested(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    val order = listOf(first, SavedKind.entries.first { it != first })
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { kind ->
            val place = saved[kind] ?: return@forEach
            Surface(
                onClick = { onGoTo(place) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == SavedKind.Home) Icons.Outlined.Home else Icons.Outlined.Work, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${kind.label}으로", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 자주 가는 곳: 즐겨찾기(앞에서 4개). 정류장은 가장 먼저 오는 버스와 남은 시간을 30초마다 새로 받아 보여줌 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun Favorites(vm: MapViewModel, onFavorite: (Favorite) -> Unit) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val shown = remember(favs) { favs.take(4) }
    Text("즐겨찾기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    if (shown.isEmpty()) {
        Text(
            "정류장이나 노선의 ★ 를 누르면 여기에 모여요.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val etas by produceState(emptyMap<String, List<RouteEta>>(), shown) {
        val stops = shown.filter { it.kind == Favorite.Kind.Stop }
        while (true) {
            // 앱이 내려가 있으면 기다림. 즐겨찾기마다 동시에 받고(공공 API 동시 요청은 앱이 4개로 제한), 오는 대로 화면에 채움.
            // 전에는 하나씩 차례로 받아서 즐겨찾기가 늘수록 오래 걸리고, 다 받은 뒤에야 한꺼번에 보였음
            com.eunho.tagohub.core.AppState.awaitForeground()
            val got = LinkedHashMap<String, List<RouteEta>>(value)
            kotlinx.coroutines.coroutineScope {
                stops.forEach { f ->
                    launch {
                        got[f.id] = when (val r = runCatching { arrivalsFor(Graph.repo, f.toStop()) }.getOrNull()) {
                            is TagoResult.Success -> HomeText.routeEtas(r.data)
                            else -> got[f.id] ?: emptyList()   // 이번에 못 받으면 지난 값을 그대로 둠
                        }
                        value = LinkedHashMap(got)
                    }
                }
            }
            delay(45_000L)
        }
    }
    // 하나의 흰 카드 안에 줄로 나눠서, 도착 시간은 빨간 글씨
    val red = if (LocalDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFD92D20)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            shown.forEachIndexed { i, f ->
                val routes = etas[f.id].orEmpty()
                val first = routes.firstOrNull()
                val badgeText = when (f.kind) {
                    Favorite.Kind.Route -> f.name
                    Favorite.Kind.Stop -> first?.routeNo
                    Favorite.Kind.Station -> null
                }
                val badgeColor = when (f.kind) {
                    Favorite.Kind.Route -> busRouteColor(f.extra)
                    Favorite.Kind.Stop -> busRouteColor(first?.routeType)
                    Favorite.Kind.Station -> ModeColor.Subway
                }
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onFavorite(f) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.heightIn(min = 28.dp).widthIn(min = 34.dp).background(badgeColor, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (badgeText != null) Text(badgeText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                        else Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (f.kind == Favorite.Kind.Route) (f.sub ?: "노선") else f.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        // 정류장: 여기 서는 노선을 모두, 노선마다 가장 빨리 오는 버스. 역: 지나는 노선
                        if (f.kind == Favorite.Kind.Stop && routes.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                routes.forEach { r ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            r.routeNo,
                                            Modifier.background(busRouteColor(r.routeType), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1,
                                        )
                                        if (r.text != null) AnimatedValueText(" ${r.text}", label = "home-favorite-arrival") { value ->
                                            Text(value, fontFamily = NumberFont, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = if (r.seconds != null && r.seconds <= 180) red else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                        if (f.kind == Favorite.Kind.Station && !f.sub.isNullOrBlank()) {
                            Text(f.sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
