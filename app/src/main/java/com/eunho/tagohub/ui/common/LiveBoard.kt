package com.eunho.tagohub.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.ui.theme.BoardColor

/**
 * 실시간 도착판. 화면(홈·정류소)이 달라도 같은 판을 쓴다.
 * 라이트·다크 모드와 무관하게 늘 짙은 남색이고, 라임은 LIVE 표시와 임박한 값에만 쓴다.
 *
 * @param title 판 제목 ("실시간 도착" · "도착 정보")
 * @param sub 제목 옆 작은 설명 ("15초 갱신")
 * @param footer 판 아래 한 줄 (없으면 그리지 않음)
 */
@Composable
fun LiveBoard(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = BoardColor.Surface,
        border = BorderStroke(1.dp, BoardColor.Line),
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = BoardColor.On)
                Spacer(Modifier.width(8.dp))
                LiveBadge()
                if (sub != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(sub, fontSize = 12.sp, color = BoardColor.Muted)
                }
            }
            content()
            if (footer != null) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = BoardColor.Line)
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    content = footer,
                )
            }
        }
    }
}

/** 라임 LIVE 표시. 글자는 잉크색이어야 대비가 확보된다. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(BoardColor.Live, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LIVE",
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = BoardColor.OnLive,
            letterSpacing = 0.4.sp,
        )
    }
}

/** 보드 위 갱신 상태 점 (라임 점 + 보조 설명) */
@Composable
fun BoardUpdated(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(6.dp).background(BoardColor.Live, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = BoardColor.Muted)
    }
}

/** 보드 안 열 구분선 (얇은 흰색) */
@Composable
fun BoardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(horizontal = 14.dp), color = BoardColor.Line)
}

/** 보드 안에서 누를 수 있는 글자 (예: "시간표 ›", "모두 보기") */
@Composable
fun BoardLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = modifier) {
        Row(
            Modifier.height(36.dp).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = BoardColor.Link)
        }
    }
}
