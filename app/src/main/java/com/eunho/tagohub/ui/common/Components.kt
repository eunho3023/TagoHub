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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.Grade
import com.eunho.tagohub.data.Place
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** 시각·요금 숫자는 고정폭 숫자로 정렬 */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** 화면 제목. 왼쪽 세로 띠가 교통수단 색 — 역 안내판의 노선 띠를 닮게. */
@Composable
fun ScreenTitle(
    title: String,
    color: Color,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack == null) 16.dp else 4.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
        }
        Box(
            Modifier
                .width(5.dp)
                .height(if (subtitle == null) 24.dp else 40.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
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
        shape = RoundedCornerShape(12.dp),
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

@Composable
fun DateStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit, days: Int = 7) {
    val today = remember { LocalDate.now() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items((0 until days).toList()) { i ->
            val d = today.plusDays(i.toLong())
            val dow = d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.KOREAN)
            val label = when (i) {
                0 -> "오늘"
                1 -> "내일"
                else -> "${d.monthValue}/${d.dayOfMonth}"
            }
            FilterChip(
                selected = d == selected,
                onClick = { onSelect(d) },
                label = {
                    Text(
                        "$label $dow",
                        color = when {
                            d == selected -> Color.Unspecified
                            d.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFC0392B)
                            d.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF2E6BC4)
                            else -> Color.Unspecified
                        },
                    )
                },
            )
        }
    }
}

@Composable
fun GradeStrip(grades: List<Grade>, selected: Grade?, accent: Color, allLabel: String, onSelect: (Grade?) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = accent.copy(alpha = 0.16f),
        selectedLabelColor = accent,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) }, colors = colors)
        }
        items(grades) { g ->
            FilterChip(selected = selected?.id == g.id, onClick = { onSelect(g) }, label = { Text(g.name) }, colors = colors)
        }
    }
}

@Composable
fun PlaceField(label: String, value: Place?, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
            Text(
                value?.name ?: "선택하세요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 출발/도착 선택 + 교환 버튼 */
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
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PlaceField(depLabel, dep, accent, onClick = onDep)
            Spacer(Modifier.height(6.dp))
            PlaceField(arrLabel, arr, accent, onClick = onArr)
        }
        if (onSwap != null) {
            IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapVert, contentDescription = "출발·도착 바꾸기") }
        }
    }
}

/** 검색 가능한 선택 시트. loader 는 캐시된 목록을 로컬 필터링하므로 타이핑마다 호출해도 가볍다. */
@Composable
fun PlacePickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Place) -> Unit,
    loader: suspend (String) -> TagoResult<List<Place>>,
) {
    var query by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<List<Place>>>(Load.Loading) }
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
            LoadView(
                state,
                onRetry = { retry++ },
                isEmpty = { it.isEmpty() },
                emptyText = "일치하는 항목이 없습니다.",
            ) { list ->
                LazyColumn {
                    items(list) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = if (p.sub != null) { { Text(p.sub) } } else null,
                            trailingContent = {
                                Text(p.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.clickable { onPick(p) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
