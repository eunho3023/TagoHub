@file:OptIn(ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** 알약 모양 선택 버튼. 고른 것은 파란 바탕에 파란 글씨, 아래 화살표를 달면 누르면 시트가 열리는 필터 */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    dropdown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = CircleShape,
        color = if (selected) cs.primaryContainer else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) cs.primary else if (textColor != Color.Unspecified) textColor else cs.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (dropdown) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp),
                    tint = if (selected) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** 역·정류장 이름 앞의 노선도 점 */
@Composable
fun StationDot() {
    Box(Modifier.size(12.dp).clip(CircleShape).border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
}

/** 시트 안 라디오 줄: 알약 모양 테두리, 고른 줄은 파랑 */
@Composable
fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .border(1.5.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (selected) cs.primary else cs.onSurface,
        )
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(if (selected) 7.dp else 2.dp, if (selected) cs.primary else cs.onSurfaceVariant, CircleShape),
        )
    }
}

/** 둥근 동그라미 버튼 (이전·다음 날 등) */
@Composable
fun CircleButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(40.dp),
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

/**
 * 달력 시트: 달을 넘겨 가며 날짜를 고르고, 고른 날은 동그라미로 표시.
 * [min]~[max] 밖의 날은 흐리게 두고 누를 수 없다.
 */
@Composable
fun CalendarSheet(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    min: LocalDate = LocalDate.now(),
    max: LocalDate = LocalDate.now().plusYears(1),
    title: String = "날짜 선택",
) {
    val cs = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    var selected by remember { mutableStateOf(initial.coerceIn(min, max)) }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircleButton(onClick = { month = month.minusMonths(1) }, enabled = month > YearMonth.from(min)) {
                    Text("‹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    "${month.year}년 ${month.monthValue}월",
                    Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                )
                CircleButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(max)) {
                    Text("›", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                    Text(
                        d, Modifier.weight(1f).padding(vertical = 6.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (i == 0) cs.error else cs.onSurfaceVariant,
                    )
                }
            }
            val lead = month.atDay(1).dayOfWeek.value % 7 // 일요일 = 0
            val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val d = week.getOrNull(i)
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                            if (d != null) {
                                val ok = d in min..max
                                val on = d == selected
                                Column(
                                    Modifier.size(44.dp).clip(CircleShape)
                                        .background(if (on) cs.primary else Color.Transparent)
                                        .then(if (ok) Modifier.clickable { selected = d } else Modifier),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        d.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            on -> cs.onPrimary
                                            !ok -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                                            i == 0 -> cs.error
                                            else -> cs.onSurface
                                        },
                                    )
                                    if (d == today) Text("오늘", style = MaterialTheme.typography.labelSmall, color = if (on) cs.onPrimary else cs.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${selected.year}년 ${selected.monthValue}월 ${selected.dayOfMonth}일 (${selected.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.KOREAN)})",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onPick(selected); onDismiss() },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("선택 완료", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/** 설정용 스위치: 48×28 알약, 켜면 파랑 바탕에 흰 손잡이 */
@Composable
fun ClearSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) cs.primary else cs.outlineVariant)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (checked) cs.onPrimary else cs.surface))
    }
}
