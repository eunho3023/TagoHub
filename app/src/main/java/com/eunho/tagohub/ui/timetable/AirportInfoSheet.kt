package com.eunho.tagohub.ui.timetable

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import com.eunho.tagohub.data.AirportGuideClient
import com.eunho.tagohub.data.AirportGuideInfo
import com.eunho.tagohub.data.AirportInfo
import com.eunho.tagohub.data.AirportLive
import com.eunho.tagohub.data.AirportLiveClient
import com.eunho.tagohub.data.AirportLiveInfo

/**
 * 공항 정보: 공항을 고르면 연락처·주소, 시설, 지연·결항 통계, 대중교통, 라운지, 층별 입점업체, 문화 행사를 보여 준다.
 * 한국공항공사 공공데이터(파일) 기준이라 실시간이 아니다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AirportInfoSheet(initial: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all = remember { AirportInfo.all(context) }
    var current by remember { mutableStateOf(initial?.let { AirportInfo.match(all, it) } ?: all.firstOrNull()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (all.isEmpty()) {
            Text("공항 정보를 불러오지 못했어요.", Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEach { a -> FilterChip(selected = current?.short == a.short, onClick = { current = a }, label = { Text(a.short) }) }
            }
            val a = current ?: return@Column
            Spacer(Modifier.height(10.dp))
            Text(a.name + "  " + a.code, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            a.address?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            a.phone?.let { tel ->
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.filter { it.isDigit() || it == '-' }))) }) {
                    Text("대표전화 $tel")
                }
            }

            var refresh by remember { mutableStateOf(0) }
            val live by produceState<AirportLiveInfo?>(null, a.code, refresh) {
                value = null
                value = AirportLiveClient.load(a.code)
            }
            LiveSection(live) { refresh++ }
            val guide by produceState<AirportGuideInfo?>(null, a.code) {
                value = null
                value = AirportGuideClient.load(a.code)
            }
            GuideSections(guide)

            if (a.counters.isNotEmpty()) Section("체크인") {
                a.counters.forEach { (kind, c) ->
                    val parts = listOfNotNull(c["카운터"]?.let { "카운터 $it" }, c["발권데스크"]?.let { "발권데스크 $it" }, c["셀프체크인 키오스크"]?.let { "셀프체크인 $it" })
                    if (parts.isNotEmpty()) Line("$kind 청사", parts.joinToString(" · "))
                }
            }
            if (a.delay.isNotEmpty() || a.cancel.isNotEmpty()) {
                Section("지연·결항 (통계)") {
                    listOf("국내", "국제").forEach { kind ->
                        val d = a.delay[kind]
                        val c = a.cancel[kind]
                        if (d != null || c != null) {
                            Line(
                                kind,
                                listOfNotNull(
                                    d?.get("지연율(퍼센트)")?.let { "지연 $it%" },
                                    c?.get("결항률(퍼센트)")?.let { "결항 $it%" },
                                    d?.get("운항편수")?.let { "운항 ${it}편" },
                                ).joinToString(" · "),
                            )
                        }
                    }
                }
            }
            if (a.transport.isNotEmpty()) {
                Section("공항 오가는 대중교통") {
                    a.transport.forEach { t -> Line(t.type + if (t.count.isNotEmpty()) " ${t.count}개" else "", t.routes) }
                }
            }
            if (a.lounges.isNotEmpty()) {
                Section("라운지") {
                    a.lounges.forEach { l ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(l.name, fontWeight = FontWeight.Bold)
                            Text(listOf(l.place, l.hours).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (l.services.isNotBlank()) Text(l.services, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (a.shops.isNotEmpty()) {
                Section("층별 입점업체") {
                    a.shops.groupBy { it.terminal }.forEach { (terminal, inTerminal) ->
                        Text(terminal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        inTerminal.groupBy { it.floor }.toSortedMap().forEach { (floor, onFloor) ->
                            Text(floor, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                            onFloor.groupBy { it.type }.forEach { (type, list) ->
                                Row(Modifier.padding(vertical = 1.dp)) {
                                    Text(type, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                                    Text(list.joinToString(", ") { it.name }, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (a.events.isNotEmpty()) {
                Section("문화 행사") { a.events.forEach { (month, what) -> Line(month, what) } }
            }
            Text(
                "'실시간' 항목은 한국공항공사 운영 정보 API, 그 아래는 공공데이터 파일 기준이라 운영 시간·매장은 바뀔 수 있어요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

private fun levelColor(level: Int?): Color = when (level) {
    1 -> Color(0xFF027A48)
    2 -> Color(0xFFB54708)
    3 -> Color(0xFFD92D20)
    4 -> Color(0xFF912018)
    else -> Color.Unspecified
}

private fun lotColor(label: String?): Color = when {
    label == null -> Color.Unspecified
    "만차" in label -> levelColor(4)
    "혼잡" in label -> levelColor(3)
    "보통" in label -> levelColor(2)
    else -> levelColor(1)
}

/** 실시간 혼잡도·소요시간·주차·택시 (한국공항공사 API). 항목이 없는 공항은 줄이 안 나온다 */
@Composable
private fun LiveSection(info: AirportLiveInfo?, onRefresh: () -> Unit) {
    Section("실시간") {
        when {
            info == null -> Text("불러오는 중…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.isEmpty -> {
                if (info.errors.isEmpty()) Text("이 공항은 실시간 정보(혼잡도·주차장 등)를 제공하지 않아요.", fontSize = 14.sp)
                else Text("실시간 정보를 받지 못했어요.", fontSize = 14.sp)
                info.errors.firstOrNull()?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> {
                info.congestion?.let { c ->
                    LiveLine("혼잡도", "전체 ${AirportLive.Congestion.label(c.all)}", levelColor(c.all))
                }
                info.process?.let { p ->
                    val total = AirportLive.minutes(p.totalSec)
                    if (p.open == false) LiveLine("소요시간", "지금은 운영하지 않음", Color.Unspecified)
                    else if (total != null) {
                        LiveLine("평균 소요", total, Color.Unspecified)
                    }
                }
                info.lots.forEach { l ->
                    val amount = if (l.used != null && l.total != null) "${l.used}/${l.total}대" else null
                    LiveLine(l.name, listOfNotNull(l.label, l.percent, amount).joinToString(" · "), lotColor(l.label))
                }
                if (info.lots.isEmpty() && info.errors.isEmpty()) LiveLine("주차장", "실시간 정보 없음", Color.Unspecified)
                info.valet?.let { v ->
                    LiveLine("발렛파킹", listOfNotNull(v.label, v.total?.let { "대기 ${it}대" }).joinToString(" · "), lotColor(v.label))
                }
                if (info.indoor.isNotEmpty()) {
                    LiveLine("실내주차장 빈 자리", "${info.indoor.sumOf { it.empty }}대", Color.Unspecified)
                }
                info.taxi?.let { t ->
                    LiveLine("택시승강장", listOfNotNull(t.taxis?.let { "대기 택시 ${it}대" }, t.passengers?.let { "대기 승객 ${it}명" }, t.waitMin?.let { "예상 ${it}분" }).joinToString(" · "), Color.Unspecified)
                }
                val at = info.congestion?.time ?: info.lots.firstOrNull()?.time ?: info.taxi?.time ?: info.valet?.time
                at?.let { Text("기준 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                if (info.errors.isNotEmpty()) Text("일부 항목 조회 실패: ${info.errors.first()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onRefresh) { Text("새로고침") }
    }
}

@Composable
private fun LiveLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(label, Modifier.padding(end = 12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = 14.sp, color = color, fontWeight = if (color != Color.Unspecified) FontWeight.Bold else null)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(label, Modifier.padding(end = 12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = 14.sp)
    }
}

/** 주차요금표·공항 버스노선 (한국공항공사 API, 실시간 아님). 자료가 없는 공항은 그 줄을 안 보임 */
@Composable
private fun GuideSections(info: AirportGuideInfo?) {
    if (info == null) return
    if (info.fees.isNotEmpty()) Section("주차요금 (소형)") {
        info.fees.forEach { f -> Line(f.lot, f.text()) }
    }
    if (info.buses.isNotEmpty()) {
        var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Section("공항 버스 ${info.buses.size}개 노선") {
            TextButton(onClick = { open = !open }) { Text(if (open) "접기" else "노선 보기") }
            if (open) info.buses.forEach { b ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(b.title(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    b.detail().takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp) }
                    b.boarding?.let { Text("승차: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    b.route?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    b.company?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
