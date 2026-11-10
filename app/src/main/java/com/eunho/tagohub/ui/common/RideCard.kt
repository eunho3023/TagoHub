package com.eunho.tagohub.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 운행편 카드. 기차·고속버스·항공이 같은 카드를 쓴다 (시안 08).
 *
 * 위 줄: 차종·등급 배지 + 편명, 오른쪽에 짧은 상태("다음 출발", "12분 후")
 * 가운데: 출발 시각 ──(소요 시간)── 도착 시각
 * 그 아래: 출발지·도착지 이름
 * 맨 아래: [footer] — 화면마다 다른 것(요금·현재 위치·도착 예정)만 여기에 넣는다
 *
 * @param accent 노선·모드 색 (선과 점, 강조 테두리에 씀)
 * @param dimmed 이미 지나간 편이면 흐리게
 * @param emphasized 다음 편이면 테두리를 굵게
 */
@Composable
fun RideCard(
    accent: Color,
    depText: String,
    arrText: String,
    depLabel: String,
    arrLabel: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = accent,
    code: String? = null,
    duration: String? = null,
    dimmed: Boolean = false,
    emphasized: Boolean = false,
    headNote: String? = null,
    numberStyle: TextStyle = Tnum,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier.fillMaxWidth().alpha(if (dimmed) 0.4f else 1f),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (emphasized) 2.dp else 1.dp,
            if (emphasized) accent else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!badge.isNullOrBlank()) Pill(badge, badgeColor)
                if (!code.isNullOrBlank()) {
                    Text(code, style = MaterialTheme.typography.bodyMedium.merge(numberStyle), fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.weight(1f))
                if (headNote != null) {
                    Text(headNote, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    depText,
                    style = MaterialTheme.typography.headlineSmall.merge(numberStyle),
                    fontWeight = FontWeight.ExtraBold,
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (duration != null) {
                        Text(duration, style = MaterialTheme.typography.labelMedium, color = variant, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).border(2.dp, accent, CircleShape))
                        Box(Modifier.weight(1f).height(3.dp).background(accent.copy(alpha = 0.5f)))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    }
                }
                Text(
                    arrText,
                    style = MaterialTheme.typography.headlineSmall.merge(numberStyle),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    depLabel,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = variant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    arrLabel,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = variant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (footer != null) {
                Spacer(Modifier.height(10.dp))
                footer()
            }
        }
    }
}
