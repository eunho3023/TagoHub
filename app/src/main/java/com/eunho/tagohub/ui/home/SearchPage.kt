package com.eunho.tagohub.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalConvenienceStore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.Place2
import com.eunho.tagohub.data.RecentItem
import com.eunho.tagohub.data.RecentItems
import com.eunho.tagohub.data.RouteHistory
import com.eunho.tagohub.data.RouteRecord
import com.eunho.tagohub.data.SavedPlace
import com.eunho.tagohub.data.SearchHistory
import com.eunho.tagohub.ui.common.SearchBox
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.ModeColor
import kotlinx.coroutines.flow.MutableStateFlow

/** 전체 화면 검색을 여닫는 신호 (홈·지도 검색창을 누르면 true, 화면이 [MainScreen] 위에 뜸) */
object SearchPageState {
    val open = MutableStateFlow(false)
}

/** 검색 화면 위쪽 분류 버튼: 카카오 장소 분류 코드 */
private data class Category(val label: String, val code: String, val icon: ImageVector)

private val CATEGORIES = listOf(
    Category("음식점", "FD6", Icons.Filled.Restaurant),
    Category("카페", "CE7", Icons.Filled.LocalCafe),
    Category("편의점", "CS2", Icons.Filled.LocalConvenienceStore),
    Category("주유소", "OL7", Icons.Filled.LocalGasStation),
    Category("주차장", "PK6", Icons.Filled.LocalParking),
    Category("약국", "PM9", Icons.Filled.LocalPharmacy),
    Category("병원", "HP8", Icons.Filled.LocalHospital),
    Category("은행", "BK9", Icons.Filled.AccountBalance),
)

/** 최근 기록 탭 */
private enum class RecentTab(val label: String) { All("최근"), Place("장소"), Transit("대중교통"), Trip("길찾기") }

/** 최근 목록 한 줄: 검색 기록 또는 경로 기록 */
private sealed class RecentRow(val atMs: Long) {
    class Item(val item: RecentItem) : RecentRow(item.atMs)
    class Trip(val record: RouteRecord) : RecentRow(record.atMs)
}

/** "11.03." (날짜를 모르면 빈칸) */
private fun recentDate(ms: Long): String = if (ms <= 0) "" else
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).let { "%02d.%02d.".format(it.monthValue, it.dayOfMonth) }

/**
 * 전체 화면 검색 (카카오맵·네이버지도처럼): 뒤로 + 검색칸, 분류(음식점·카페 …) 버튼, 집·회사, 최근 기록(최근·장소·대중교통·길찾기 탭).
 * 글자를 치면 아래가 검색 결과로 바뀜. 결과·최근 기록을 고르면 그것을 최근 기록 맨 앞에 남기고 지도에서 연다.
 */
@Composable
fun SearchPage(
    vm: MapViewModel,
    actions: SearchActions,
    onFavorite: (Favorite) -> Unit,
    onGoTo: (SavedPlace) -> Unit,
    onTrip: (RouteRecord) -> Unit,
    onEditHomeWork: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(RecentTab.All) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // 고르면 최근 기록에 남기고 검색 화면을 닫은 뒤 연다
    val now = { System.currentTimeMillis() }
    val picked = remember(actions) {
        SearchActions(
            onPlace = { p -> RecentItems.add(RecentItem.place(p, now())); SearchHistory.add(query); onClose(); actions.onPlace(p) },
            onStation = { g -> RecentItems.add(RecentItem.of(Favorite.of(g), now())); SearchHistory.add(query); onClose(); actions.onStation(g) },
            onStop = { s -> RecentItems.add(RecentItem.of(Favorite.of(s), now())); SearchHistory.add(query); onClose(); actions.onStop(s) },
            onRoute = { r ->
                RecentItems.add(RecentItem.of(Favorite.of(r.routeId, r.routeNo, r.routeType, r.cityCode, r.startName, r.endName), now()))
                SearchHistory.add(query); onClose(); actions.onRoute(r)
            },
        )
    }
    fun openRecent(r: RecentItem) {
        when (r.kind) {
            RecentItem.Kind.Query -> { query = r.title; RecentItems.add(r.copy(atMs = now())) }
            RecentItem.Kind.Place -> r.toPlace()?.let { picked.onPlace(it) }
            else -> r.toFavorite()?.let { f -> RecentItems.add(r.copy(atMs = now())); onClose(); onFavorite(f) }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                SearchBox(
                    query, { query = it; if (it.isNotEmpty()) category = null }, "장소·주소·버스·지하철 검색",
                    Modifier.weight(1f),
                    onSearch = {
                        if (query.isNotBlank()) {
                            RecentItems.add(RecentItem.query(query, now()))
                            vm.searchBus(query)
                        }
                    },
                    focusRequester = focus,
                )
            }
            if (query.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize()) { item { SearchResults(vm, SearchScope.All, query, picked) } }
                return@Column
            }
            CategoryRow(category) { category = if (category == it) null else it }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val cat = CATEGORIES.firstOrNull { it.code == category }
            if (cat != null) {
                CategoryResults(vm, cat, picked.onPlace)
                return@Column
            }
            HomeWorkRow(vm, onGoTo = { onClose(); onGoTo(it) }, onEdit = { onClose(); onEditHomeWork() })
            RecentList(tab, { tab = it }, ::openRecent, onTrip = { r -> onClose(); onTrip(r) })
        }
    }
}

@Composable
private fun CategoryRow(selected: String?, onPick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CATEGORIES, key = { it.code }) { c ->
            val on = c.code == selected
            Surface(
                onClick = { onPick(c.code) },
                shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(c.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 분류를 고르면: 내 주변(1.5km) 그 분류 장소, 가까운 순 */
@Composable
private fun CategoryResults(vm: MapViewModel, cat: Category, onPlace: (Place2) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 지도를 아직 안 열었으면 지도가 내 위치를 몰라서, 휴대폰이 아는 위치(마지막 위치 → 새 위치)를 직접 받아 넘김
    var noLocation by remember(cat.code) { mutableStateOf(false) }
    val list by produceState<List<Place2>?>(null, cat.code) {
        val here = if (com.eunho.tagohub.ui.bus.hasLocationPermission(context)) {
            (com.eunho.tagohub.ui.bus.lastKnownLocation(context) ?: com.eunho.tagohub.ui.bus.currentLocation(context))?.let { it.latitude to it.longitude }
        } else null
        val found = vm.nearbyCategory(cat.code, here)
        noLocation = found == null
        value = found.orEmpty()
    }
    val l = list
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("내 주변 ${cat.label}", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
        when {
            l == null -> item { Text("찾는 중…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            l.isEmpty() -> item {
                Text(
                    if (noLocation) "내 위치를 알 수 없어요. 위치 권한과 위치(GPS)를 켜 주세요." else "주변 1.5km 안에 ${cat.label}이(가) 없어요.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(l, key = { it.name + it.lat + it.lng }) { p ->
                RecentLine(
                    Icons.Filled.Place, Color(0xFFE53935), p.name,
                    listOfNotNull(p.distance?.let { if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it}m" }, p.category, p.address).joinToString(" · "),
                    null, onClick = { onPlace(p) }, onDelete = null,
                )
            }
        }
    }
}

@Composable
private fun HomeWorkRow(vm: MapViewModel, onGoTo: (SavedPlace) -> Unit, onEdit: () -> Unit) {
    val saved by vm.trip.homeWork.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("집", Icons.Filled.Home, saved.home), Triple("회사", Icons.Filled.Work, saved.work)).forEach { (label, icon, place) ->
            Row(
                Modifier.clickable { if (place != null) onGoTo(place) else onEdit() }.heightIn(min = 44.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(4.dp))
                Text(if (place != null) label else "$label 등록", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (place != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(onClick = onEdit, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
            Text("편집", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp)
        }
    }
}

@Composable
private fun RecentList(tab: RecentTab, onTab: (RecentTab) -> Unit, onOpen: (RecentItem) -> Unit, onTrip: (RouteRecord) -> Unit) {
    val items by RecentItems.items.collectAsStateWithLifecycle()
    val trips by RouteHistory.items.collectAsStateWithLifecycle()
    val rows: List<RecentRow> = remember(items, trips, tab) {
        val places = items.filter { it.kind == RecentItem.Kind.Place }
        val transit = items.filter { it.kind == RecentItem.Kind.Stop || it.kind == RecentItem.Kind.Route || it.kind == RecentItem.Kind.Station }
        when (tab) {
            RecentTab.All -> (items.map { RecentRow.Item(it) } + trips.map { RecentRow.Trip(it) }).sortedByDescending { it.atMs }
            RecentTab.Place -> places.map { RecentRow.Item(it) }
            RecentTab.Transit -> transit.map { RecentRow.Item(it) }
            RecentTab.Trip -> trips.map { RecentRow.Trip(it) }
        }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(RecentTab.entries.toList(), key = { it.name }) { t ->
            val on = t == tab
            Surface(
                onClick = { onTab(t) },
                shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                border = if (on) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface) else null,
            ) {
                Text(t.label, Modifier.heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            item { Text("최근 기록이 없어요.", Modifier.padding(horizontal = 16.dp, vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(rows.size) { i ->
            when (val r = rows[i]) {
                is RecentRow.Item -> {
                    val it = r.item
                    val (icon, tint) = when (it.kind) {
                        RecentItem.Kind.Query -> Icons.Filled.Search to MaterialTheme.colorScheme.onSurfaceVariant
                        RecentItem.Kind.Place -> Icons.Filled.Place to Color(0xFFE53935)
                        RecentItem.Kind.Stop -> Icons.Filled.DirectionsBus to ModeColor.Bus
                        RecentItem.Kind.Route -> Icons.Filled.DirectionsBus to com.eunho.tagohub.ui.theme.busRouteColor(it.extra)
                        RecentItem.Kind.Station -> Icons.Filled.DirectionsSubway to ModeColor.Subway
                    }
                    val sub = when (it.kind) {
                        RecentItem.Kind.Stop -> it.extra ?: it.sub
                        RecentItem.Kind.Route -> listOfNotNull(it.extra, it.sub).joinToString(" · ").ifBlank { null }
                        else -> it.sub
                    }
                    val title = if (it.kind == RecentItem.Kind.Route) "${it.title}번" else it.title
                    RecentLine(icon, tint, title, sub, recentDate(it.atMs), onClick = { onOpen(it) }, onDelete = { RecentItems.remove(it) })
                }
                is RecentRow.Trip -> {
                    val t = r.record
                    RecentLine(
                        Icons.Filled.Directions, MaterialTheme.colorScheme.primary, "${t.fromName} → ${t.toName}", t.mode.ifBlank { null },
                        recentDate(t.atMs), onClick = { onTrip(t) }, onDelete = { RouteHistory.remove(t) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (rows.isNotEmpty() && tab != RecentTab.Trip) {
            item {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { RecentItems.clear() }) { Text("검색 기록 전체 삭제") }
                }
            }
        }
    }
}

@Composable
private fun RecentLine(icon: ImageVector, tint: Color, title: String, sub: String?, date: String?, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!date.isNullOrBlank()) Text(date, Modifier.padding(horizontal = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "$title 지우기", modifier = Modifier.size(18.dp)) }
        else Spacer(Modifier.width(12.dp))
    }
}

/** 검색창처럼 생긴 버튼: 누르면 전체 화면 검색([SearchPage])을 엶 (홈·지도 위쪽) */
@Composable
fun SearchBarButton(modifier: Modifier = Modifier, placeholder: String = "장소·주소·버스·지하철 검색") {
    Surface(
        onClick = { SearchPageState.open.value = true },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(placeholder, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
