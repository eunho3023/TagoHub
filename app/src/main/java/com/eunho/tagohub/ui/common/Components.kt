@file:OptIn(ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eunho.tagohub.data.PlaceGroups
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.Grade
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.PlaceGeo
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** 시각·요금 숫자는 고정폭 숫자로 정렬 */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** 화면 제목. 흰 바탕에 굵은 제목, 제목 앞의 작은 색 점이 교통수단. 아래에 얇은 선. */
@Composable
fun ScreenTitle(
    title: String,
    color: Color,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack == null) 16.dp else 4.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            }
            Column(Modifier.weight(1f)) {
                // 어느 교통수단인지는 제목 앞의 수단 색 점으로만 표시
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 호선·노선 원형 배지 (지하철 노선도의 숫자 원). 환승역은 여러 개를 나란히 */
@Composable
fun LineBadge(label: String, color: Color, size: Dp = 32.dp, ring: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (ring) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (label.length > 1) (size.value * 0.34f).sp else (size.value * 0.5f).sp,
            maxLines = 1,
        )
    }
}

/** "2호선" → "2", "신분당선" → "신분" 처럼 배지 안에 들어갈 짧은 글자 */
fun lineBadgeLabel(name: String): String =
    Regex("""^(\d{1,2})호선""").find(name)?.groupValues?.get(1)
        ?: name.removePrefix("수도권").removePrefix("서울").replace("선", "").replace("도시철도", "").trim().take(2).ifEmpty { name.take(1) }

@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "지우기") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
fun MessageBox(text: String, isError: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("다시 시도") }
        }
    }
}

@Composable
fun <T> LoadView(
    state: Load<T>,
    onRetry: (() -> Unit)? = null,
    isEmpty: (T) -> Boolean = { false },
    emptyText: String = "결과가 없습니다.",
    idle: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is Load.Idle -> idle()
        is Load.Loading -> Box(
            Modifier
                .fillMaxWidth()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is Load.Fail -> MessageBox(state.message, isError = true, onRetry = onRetry)
        is Load.Ok -> if (isEmpty(state.value)) MessageBox(emptyText) else content(state.value)
    }
}

/** 노선번호·등급 배지 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = true) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        modifier = modifier
            .clip(shape)
            .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color, shape))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = if (filled) Color.White else color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 달력에서 날짜 고르기 (아래에서 올라오는 달력 시트). [min]~[max] 밖의 날은 누를 수 없다 */
@Composable
fun CalendarDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    min: LocalDate = LocalDate.now(),
    max: LocalDate = LocalDate.now().plusYears(1),
) = CalendarSheet(initial, onPick, onDismiss, min, max)

/** "2026.10.23 (금)" */
fun dateLabel(d: LocalDate): String =
    "%04d.%02d.%02d (%s)".format(d.year, d.monthValue, d.dayOfMonth, d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.KOREAN))

/** 달력을 여는 날짜 버튼 (글자 입력 대신) */
@Composable
fun DateField(
    value: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    min: LocalDate = LocalDate.now(),
    max: LocalDate = LocalDate.now().plusYears(1),
) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(listOfNotNull(label, dateLabel(value)).joinToString("  "), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (open) CalendarDialog(value, onPick, { open = false }, min, max)
}

/**
 * 날짜 고르기 한 줄: 고른 날짜(처음엔 오늘)를 보여 주고, 누르면 달력이 열려 1년 뒤까지 고를 수 있다.
 * 오늘이면 날짜 뒤에 "오늘"을 붙인다.
 */
@Composable
fun DateStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    var calendar by remember { mutableStateOf(false) }
    val isToday = selected == LocalDate.now()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            PillChip(
                label = dateLabel(selected) + if (isToday) " · 오늘" else "",
                selected = true,
                onClick = { calendar = true },
                dropdown = true,
            )
        }
    }
    if (calendar) CalendarDialog(selected, onSelect, { calendar = false })
}

@Composable
fun GradeStrip(grades: List<Grade>, selected: Grade?, accent: Color, allLabel: String, onSelect: (Grade?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { PillChip(allLabel, selected == null, { onSelect(null) }) }
        items(grades) { g -> PillChip(g.name, selected?.id == g.id, { onSelect(g) }) }
    }
}

@Composable
fun PlaceField(label: String, value: Place?, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
            Text(
                value?.name ?: "선택하세요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 출발/도착 선택 카드: 왼쪽에 노선도처럼 점과 선으로 두 곳을 잇고, 이름은 크게(두 줄까지 끝까지), 오른쪽에 바꾸기 버튼.
 */
@Composable
fun OdSelector(
    dep: Place?,
    arr: Place?,
    accent: Color,
    depLabel: String,
    arrLabel: String,
    onDep: () -> Unit,
    onArr: () -> Unit,
    onSwap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(20.dp).fillMaxHeight().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(CircleShape).border(3.dp, accent, CircleShape))
                Box(Modifier.weight(1f).width(4.dp).padding(vertical = 3.dp).background(accent.copy(alpha = 0.35f)))
                Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                OdRow(depLabel, dep, accent, onDep)
                HorizontalDivider(color = cs.outlineVariant)
                OdRow(arrLabel, arr, accent, onArr)
            }
            if (onSwap != null) {
                Spacer(Modifier.width(8.dp))
                Surface(onClick = onSwap, shape = CircleShape, color = cs.surfaceVariant, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.SwapVert, contentDescription = "출발·도착 바꾸기") }
                }
            }
        }
    }
}

@Composable
private fun OdRow(label: String, value: Place?, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(
            value?.name ?: "선택하세요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (value != null) FontWeight.ExtraBold else FontWeight.Normal,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 선택 목록을 묶어 보는 방식 */
private enum class PickMode(val label: String) { Alphabet("가나다순"), Distance("거리순"), Region("지역별") }

/**
 * 검색 가능한 선택 시트. loader 는 캐시된 목록을 로컬 필터링하므로 타이핑마다 호출해도 가볍다.
 * [browse] 이면 검색어가 없을 때 가나다순 / 지역별(그리고 [geo] 가 있으면 내 위치에서 가까운 거리순)으로 묶어 보여줌.
 */
@Composable
fun PlacePickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Place) -> Unit,
    loader: suspend (String) -> TagoResult<List<Place>>,
    /** 기차역·터미널·공항처럼 목록이 길 때: 검색어가 없으면 가나다순 / 거리순 / 지역별로 묶어 보여줌 */
    browse: Boolean = false,
    /** 위치·시·도를 알려 주는 표 (거리순·지역별에 씀) */
    geo: PlaceGeo? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PickMode.Alphabet) }
    var retry by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<List<Place>>>(Load.Loading) }
    // 내 위치 (이미 아는 마지막 위치만. 여기서 권한을 새로 묻지 않음)
    val here = remember { if (com.eunho.tagohub.ui.bus.hasLocationPermission(context)) com.eunho.tagohub.ui.bus.lastKnownLocation(context) else null }
    LaunchedEffect(query, retry) {
        if (query.isNotEmpty()) delay(200)
        if (state !is Load.Ok) state = Load.Loading
        state = loader(query).toLoad()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            SearchBox(query, { query = it }, "이름으로 찾기", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (browse && query.isEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickMode.entries.filter { it != PickMode.Distance || geo != null }.forEach { m ->
                        PillChip(m.label, mode == m, { mode = m })
                    }
                }
            }
            LoadView(
                state,
                onRetry = { retry++ },
                isEmpty = { it.isEmpty() },
                emptyText = "일치하는 항목이 없습니다.",
            ) { loaded ->
                val list = remember(loaded, geo) { geo?.enrich(loaded) ?: loaded }
                if (browse && query.isEmpty()) {
                    if (mode == PickMode.Distance && geo != null) {
                        DistancePlaceList(list, geo, here, onPick)
                    } else {
                        GroupedPlaceList(list, mode == PickMode.Region, onPick)
                    }
                    return@LoadView
                }
                LazyColumn {
                    items(list) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = if (p.sub != null) { { Text(PlaceGroups.shortRegion(p.sub)) } } else null,
                            trailingContent = {
                                Text(p.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            leadingContent = { StationDot() },
                            modifier = Modifier.clickable { onPick(p) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** 내 위치에서 가까운 순 (거리를 함께 보여줌). 위치를 모르면 안내만 */
@Composable
private fun DistancePlaceList(list: List<Place>, geo: PlaceGeo, here: android.location.Location?, onPick: (Place) -> Unit) {
    if (here == null) {
        MessageBox("내 위치를 알 수 없어 거리순으로 볼 수 없어요.\n위치 권한과 위치 서비스가 켜져 있는지 확인해 주세요.")
        return
    }
    val sorted = remember(list, here) { geo.byDistance(list, here.latitude, here.longitude) }
    LazyColumn {
        items(sorted, key = { it.first.id }) { (p, meters) ->
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = if (!p.sub.isNullOrBlank()) { { Text(PlaceGroups.shortRegion(p.sub)) } } else null,
                trailingContent = {
                    Text(
                        meters?.let { if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it.toInt()}m" } ?: "위치 모름",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = { StationDot() },
                            modifier = Modifier.clickable { onPick(p) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 가나다순(초성 머리글) 또는 지역별(시·도) 머리글로 묶은 목록. 위의 머리글 버튼을 누르면 그 자리로 이동 */
@Composable
private fun GroupedPlaceList(list: List<Place>, byRegion: Boolean, onPick: (Place) -> Unit) {
    val groups = remember(list, byRegion) { if (byRegion) PlaceGroups.byRegion(list) else PlaceGroups.byInitial(list) }
    // 각 묶음 머리글이 목록에서 몇 번째 칸인지
    val starts = remember(groups) {
        var at = 0
        groups.map { (_, places) -> at.also { at += 1 + places.size } }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(groups) { i, g ->
                TextButton(onClick = { scope.launch { state.animateScrollToItem(starts[i]) } }) {
                    Text(if (byRegion) g.first.take(2) else g.first, fontWeight = FontWeight.Bold)
                }
            }
        }
        LazyColumn(state = state) {
            groups.forEach { (name, places) ->
                item(key = "group-$name") {
                    Text(
                        if (byRegion) "$name  ${places.size}" else name,
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                    )
                }
                items(places, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = if (!byRegion && p.sub != null) { { Text(PlaceGroups.shortRegion(p.sub)) } } else null,
                        leadingContent = { StationDot() },
                            modifier = Modifier.clickable { onPick(p) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
