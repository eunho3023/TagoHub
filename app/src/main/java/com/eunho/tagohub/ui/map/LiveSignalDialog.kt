package com.eunho.tagohub.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.SignalColor
import com.eunho.tagohub.data.SignalIntersection
import com.eunho.tagohub.data.SignalPhase
import com.eunho.tagohub.data.UlsanSignals
import kotlinx.coroutines.delay

/** 방금 받은 신호 상태와 받은 시각 */
private class Snapshot(val phases: Map<String, List<SignalPhase>>?, val atMs: Long)

/**
 * 울산 교차로 하나의 실시간 신호: 방향·종류별 지금 색과 남은 초. 10초마다 새로 받고, 사이에는 1초마다 남은 시간을 줄여서 보임.
 * 신호 상태는 참고용이라 실제 신호와 차이가 있을 수 있다.
 */
@Composable
fun LiveSignalDialog(intersection: SignalIntersection, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val snap by produceState<Snapshot?>(null, intersection.id) {
        while (true) {
            val got = UlsanSignals.phases()
            value = Snapshot(got, System.currentTimeMillis())
            loading = false
            delay(REFRESH_MS)
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val phases = snap?.phases?.get(intersection.id).orEmpty()
    val elapsed = (now - (snap?.atMs ?: now)).coerceAtLeast(0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(intersection.name) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    snap?.phases == null -> Text("신호를 받지 못했어요. 잠시 뒤에 다시 열어 주세요.", style = MaterialTheme.typography.bodyMedium)
                    phases.isEmpty() -> Text("이 교차로의 실시간 신호가 지금은 없어요.", style = MaterialTheme.typography.bodyMedium)
                    else -> phases.forEach { PhaseRow(it, elapsed) }
                }
                Text(
                    "한국지역정보개발원 교차로 신호 자료예요. 참고용이라 실제 신호와 다를 수 있고, 방향 표기는 자료에 적힌 그대로예요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PhaseRow(p: SignalPhase, elapsedMs: Long) {
    val left = UlsanSignals.seconds(p.remainingMs?.minus(elapsedMs.toInt()))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(0.dp))
        androidx.compose.foundation.layout.Box(Modifier.size(14.dp).background(colorOf(p.color), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("${p.directionLabel} ${p.kindLabel}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            p.color.label + if (left != null) "  ${left}초" else if (p.remainingMs != null) "  곧 바뀜" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun colorOf(c: SignalColor): Color = when (c) {
    SignalColor.Green -> Color(0xFF2E9B57)
    SignalColor.Yellow, SignalColor.YellowBlink -> Color(0xFFFBC02D)
    SignalColor.Red, SignalColor.RedBlink -> Color(0xFFE53935)
    SignalColor.Off -> Color(0xFF9E9E9E)
}

private const val REFRESH_MS = 10_000L
