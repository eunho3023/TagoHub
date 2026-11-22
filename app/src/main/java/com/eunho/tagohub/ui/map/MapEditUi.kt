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

/** 지도 고치기 막대: 정류장·역을 누르고 새 자리를 누르면 옮김, "저장하기"로 한 번에 저장, "끝내기"로 마침 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StopFixBar(vm: MapViewModel, s: MapViewModel.StopFixState) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("✏️ 지도 고치기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (s.joining) "「${s.sel?.name}」과(와) 한 정류장으로 묶을 정류장을 지도에서 누르세요 (다시 누르면 빠져요)."
                else s.sel?.let { "「${it.name}」${s.toward?.let { t -> " ($t 방면)" } ?: ""} 고름 → 지도에서 새 위치를 누르면 옮겨져요." }
                    ?: s.selStation?.let { "「${it.name}역」 고름 → 지도에서 새 위치를 누르면 옮겨져요." }
                    ?: "정류장·역을 누르고 → 지도에서 새 위치를 누르면 옮겨져요. 여러 곳을 옮긴 뒤 저장하기로 한 번에 저장해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.msg?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
            if (s.sel != null && s.together.isNotEmpty() && !s.joining) {
                Text("같이 묶인 정류장: " + s.together.joinToString(" · ") { listOfNotNull(it.name, it.nodeNo).joinToString(" ") }, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (s.joining) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = vm::stopFixJoinCancel, enabled = !s.busy) { Text("취소") }
                    Button(onClick = vm::stopFixJoinSave, enabled = !s.busy && s.joinPick.isNotEmpty()) {
                        Text(if (s.busy) "저장 중…" else "묶기 저장 (${s.joinPick.size}곳)")
                    }
                }
                return@Column
            }
            if (s.sel != null) {
                // 이 정류장에 서는 버스 (같은 이름의 길 건너 정류장과 가려 고치게)
                Text(
                    when {
                        s.buses == null -> "서는 버스 찾는 중…"
                        s.buses.isEmpty() -> "서는 버스 정보가 없어요."
                        else -> "서는 버스 ${s.buses.size}개: " + s.buses.joinToString(" · ")
                    },
                    Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
                Text("노선 모양은 정류장 화면 → 노선 → \"노선 모양 고치기\"에서 고쳐요.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.sel?.let { sel ->
                    TextButton(onClick = vm::stopFixJoinStart, enabled = !s.busy) { Text("다른 정류장과 묶기") }
                    val manual = com.eunho.tagohub.data.MapEdits.group(sel.nodeId)
                    if (s.together.isNotEmpty() && manual?.isEmpty() != true) TextButton(onClick = vm::stopFixSplit, enabled = !s.busy) { Text("따로 떼기") }
                    // 다른 정류장 쪽에서 이 정류장을 묶어 둔 것도 여기서 풀 수 있게
                    if (manual != null || com.eunho.tagohub.data.StopMerge.manual.listedBy(sel.nodeId).isNotEmpty()) TextButton(onClick = vm::stopFixGroupReset, enabled = !s.busy) { Text("손으로 묶은 것 지우기") }
                    if (s.moves.containsKey(sel.nodeId)) TextButton(onClick = vm::stopFixUndo, enabled = !s.busy) { Text("이 정류장 되돌리기") }
                    if (com.eunho.tagohub.data.MapEdits.stop(sel.nodeId) != null) TextButton(onClick = vm::stopFixReset, enabled = !s.busy) { Text("고친 위치 지우기") }
                    TextButton(onClick = vm::stopFixUnpick, enabled = !s.busy) { Text("고르기 취소") }
                }
                s.selStation?.let { g ->
                    if (s.stationMoves.containsKey(g.id)) TextButton(onClick = vm::stopFixStationUndo, enabled = !s.busy) { Text("이 역 되돌리기") }
                    TextButton(onClick = vm::stopFixUnpick, enabled = !s.busy) { Text("고르기 취소") }
                }
            }
            // 늘 보이는 두 단추: 저장하기 · 끝내기
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::stopFixSaveAll, enabled = !s.busy && s.count > 0, modifier = Modifier.weight(1f)) {
                    Text(if (s.busy) "저장 중…" else if (s.count == 0) "저장하기" else "저장하기 (${s.count}곳)")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::stopFixEnd, enabled = !s.busy) { Text("끝내기") }
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
fun AdminKeyRow(onOpenFix: (() -> Unit)? = null) {
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
                    Text("지도 고치기를 시작하면 정류장·노선·역 화면에서 고칠 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 예전에 넣은 관리자 키가 있을 때만 (관리자 아이디 로그인은 로그아웃으로 끔)
                if (com.eunho.tagohub.data.MapEdits.adminKeySet()) TextButton(onClick = { scope.launch { com.eunho.tagohub.data.MapEdits.setAdminKey(context, ""); msg = "관리자 키를 지웠어요" } }) { Text("키 지우기") }
            }
            // 지도 고치기는 여기서만 시작 (켜져 있는 동안 정류장·노선·역 패널에 고치기 단추)
            val editOn by com.eunho.tagohub.data.MapEdits.editing.collectAsStateWithLifecycle()
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!editOn) Button(onClick = { com.eunho.tagohub.data.MapEdits.editing.value = true; onOpenFix?.invoke() }) { Text("지도 고치기 시작") }
                else {
                    Text("지도 고치기 중", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (onOpenFix != null) TextButton(onClick = onOpenFix) { Text("지도 열기") }
                    TextButton(onClick = { com.eunho.tagohub.data.MapEdits.editing.value = false }) { Text("끝내기") }
                }
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
