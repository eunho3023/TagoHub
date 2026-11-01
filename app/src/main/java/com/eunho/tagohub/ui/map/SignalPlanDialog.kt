package com.eunho.tagohub.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.PlanSlot
import com.eunho.tagohub.data.SignalIntersection
import com.eunho.tagohub.data.SignalPlans
import com.eunho.tagohub.data.TodayPlan

/**
 * 인천·대구 교차로 하나의 오늘 신호 운영계획: 지금 시간대의 주기·옵셋과 현시별 시간, 오늘 시간대별 주기.
 * 실시간 신호가 아니라 계획이라서 실제 신호와 다를 수 있다.
 */
@Composable
fun SignalPlanDialog(intersection: SignalIntersection, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val plan by produceState<TodayPlan?>(null, intersection.id) {
        value = SignalPlans.load(intersection)
        loading = false
    }
    val region = SignalPlans.parts(intersection.id)?.first?.let { SignalPlans.REGIONS[it] }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(intersection.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = plan
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    p == null -> Text("이 교차로의 신호 계획을 받지 못했어요. 잠시 뒤에 다시 열어 주세요.", style = MaterialTheme.typography.bodyMedium)
                    else -> PlanBody(p)
                }
                Text(
                    "${region ?: ""} 경찰청 교차로 신호 운영계획(하루 한 번 갱신)이에요. 실시간 신호가 아니라 계획이라 감응·점멸 제어 등으로 실제와 다를 수 있고, 현시가 어느 방향인지는 이 자료에 없어요.".trim(),
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
private fun PlanBody(p: TodayPlan) {
    Text("오늘 ${if (p.holiday) "특수일 " else ""}계획 ${p.planNo}번", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    p.current?.let { s ->
        val until = p.next?.let { " ~ ${it.startLabel}" } ?: " ~ 다음 날"
        Text("지금 시간대  ${s.startLabel}$until", style = MaterialTheme.typography.bodyMedium)
        Text("주기 ${s.cycleSec}초 · 옵셋 ${s.offsetSec}초", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        PhaseLines(s)
    }
    if (p.slots.size > 1) {
        Text("오늘 시간대별 주기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        p.slots.forEach { s ->
            Row(Modifier.fillMaxWidth()) {
                Text(s.startLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (s == p.current) FontWeight.Bold else FontWeight.Normal)
                Text("${s.cycleSec}초", style = MaterialTheme.typography.bodyMedium, fontWeight = if (s == p.current) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** 현시별 시간. A링과 B링이 같으면 한 줄로 */
@Composable
private fun PhaseLines(s: PlanSlot) {
    fun line(r: List<Int>) = r.withIndex().filter { it.value > 0 }.joinToString(" · ") { "${it.index + 1}현시 ${it.value}초" }
    if (s.ringA.isEmpty() && s.ringB.isEmpty()) return
    if (s.ringB.isEmpty() || s.ringA == s.ringB) {
        Text(line(s.ringA), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("A링  " + line(s.ringA), style = MaterialTheme.typography.bodyMedium)
        Text("B링  " + line(s.ringB), style = MaterialTheme.typography.bodyMedium)
    }
}
