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
import com.eunho.tagohub.data.AirportInfo

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
            Text("공항 정보를 불러오지 못했습니다.", Modifier.padding(24.dp))
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

            Section("시설") {
                a.facility["주 활주로 규격(미터)"]?.let { Line("주 활주로", "$it m") }
                a.facility["보조 활주로 규격(미터)"]?.let { Line("보조 활주로", "$it m") }
                a.facility["연간처리능력(회)"]?.let { Line("연간 처리능력", "$it 회") }
                a.facility["25년 활용률(퍼센트)"]?.let { Line("25년 활용률", "$it%") }
                a.facility["슬롯"]?.let { Line("슬롯", it) }
                a.facility["비고"]?.let { Line("관리", it) }
                a.parking["동시주차능력(대)"]?.let { Line("주차장 동시 주차", "$it 대") }
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
                "한국공항공사 공공데이터 기준이며 실시간 정보가 아닙니다. 운영 시간·매장은 바뀔 수 있어요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
            )
        }
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
