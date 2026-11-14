package com.eunho.tagohub.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 지도 고치기 중 아래 막대: 할 일 안내 + 고른 점 지우기 · 처음 모양 · 고친 것 지우기 · 닫기 · 저장.
 * 고친 것은 서버에 저장돼 웹 버전과 같이 씀 ([com.eunho.tagohub.data.MapEdits]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapEditBar(vm: MapViewModel, s: MapViewModel.MapEditState) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (s.isLine) "정차하는 정류장(역)은 처음부터 선 위의 \"정\" 점으로 들어가 있어요. 점을 누르고 옮길 곳을 누르면 옮겨지고, 점을 고르지 않고 선 가까이를 누르면 점이 새로 생겨요. 회색 \"정\" 점을 누르면 선이 그 자리에 붙어요. 크게 확대할수록 섬세하게 고칠 수 있어요."
                else "지도에서 실제 위치를 누르세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.msg?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.isLine && s.sel >= 0) TextButton(onClick = vm::editDeleteSelected, enabled = !s.busy) { Text("고른 점 지우기") }
                TextButton(onClick = vm::editRestart, enabled = !s.busy) { Text("처음 모양으로") }
                if (s.hasEdit) TextButton(onClick = vm::editReset, enabled = !s.busy) { Text("고친 것 지우기") }
                TextButton(onClick = vm::editClose, enabled = !s.busy) { Text("닫기") }
                Button(onClick = vm::editSave, enabled = !s.busy) { Text(if (s.busy) "저장 중…" else "저장") }
            }
        }
    }
}

/** 패널 안 "고치기" 단추 줄 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapEditLinks(links: List<Pair<String, () -> Unit>>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        links.forEach { (label, run) -> TextButton(onClick = run) { Text(label, style = MaterialTheme.typography.labelMedium) } }
    }
}

/** 설정 → 관리자: 관리자 키를 넣고 확인 (서버와 맞아야 켜짐). 켜져 있으면 끄기 */
@Composable
fun AdminKeyRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val admin by com.eunho.tagohub.data.MapEdits.admin.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (admin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("관리자 모드 켜짐", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text("노선·정류장·역 화면에서 지도 고치기를 할 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { scope.launch { com.eunho.tagohub.data.MapEdits.setAdminKey(context, ""); msg = "관리자 모드를 껐어요" } }) { Text("끄기") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("관리자 키") }, visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true; msg = null
                    scope.launch {
                        val ok = com.eunho.tagohub.data.MapEdits.setAdminKey(context, key)
                        busy = false
                        msg = if (ok) "관리자 모드를 켰어요" else "키가 맞지 않거나 서버에 연결하지 못했어요"
                        if (ok) key = ""
                    }
                }) { Text(if (busy) "확인 중…" else "확인") }
            }
            Text("지도 고치기(노선 모양·정류장·역 위치)는 관리자만 할 수 있어요", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        msg?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
    }
}
