package com.eunho.tagohub.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ItsRoadScreen(vm: MapViewModel) {
    val traffic by vm.itsTrafficRows.collectAsState()
    val forecasts by vm.itsForecastRows.collectAsState()
    val mainlineSections by vm.itsMainlineSections.collectAsState()
    val fogSections by vm.itsFogSections.collectAsState()
    val loading by vm.itsQueryLoading.collectAsState()
    val error by vm.itsQueryError.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("ITS 도로정보", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "지도 레이어는 지도 종류 메뉴에서 켜세요. 소통정보는 현재 지도 범위(최대 500건), 예측정보는 본선 구간별로 조회합니다.",
            Modifier.padding(top = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(3) { index ->
                val label = listOf("교통소통", "교통예측", "안개 취약구간")[index]
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            }
        }
        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("ITS 데이터를 불러오고 있습니다.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let {
            Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when (tab) {
            0 -> {
                Button(onClick = vm::loadItsTraffic, Modifier.fillMaxWidth()) {
                    Text(if (traffic.isEmpty()) "현재 지도에서 교통정보 조회" else "교통정보 새로고침 · ${traffic.size}건")
                }
                if (traffic.isEmpty() && !loading && error == null) {
                    ItsEmptyMessage("교통소통 정보", "현재 지도 위치로 이동한 뒤 조회 버튼을 눌러 주세요.")
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(traffic, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.roadName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        row.direction,
                                        row.speedKmh?.let { "속도 ${it} km/h" },
                                        row.travelTimeSeconds?.let { "통행시간 ${it}초" },
                                        row.updatedAt?.let { "갱신 $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            1 -> ForecastPanel(mainlineSections, forecasts, vm)
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(fogSections) { section ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(section.roadName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(section.description, style = MaterialTheme.typography.bodySmall)
                            if (section.startKm != null || section.endKm != null) {
                                Text("${section.startKm ?: "?"}~${section.endKm ?: "?"} km", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForecastPanel(
    sections: List<com.eunho.tagohub.data.ItsMainlineSection>,
    forecasts: List<com.eunho.tagohub.data.ItsForecastRow>,
    vm: MapViewModel,
) {
    var selected by remember { mutableStateOf<com.eunho.tagohub.data.ItsMainlineSection?>(null) }
    var showSections by rememberSaveable { mutableStateOf(false) }
    var sectionQuery by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)) }
    var hour by rememberSaveable { mutableStateOf(LocalTime.now().hour.toString().padStart(2, '0')) }
    val filteredSections = remember(sections, sectionQuery) {
        val query = sectionQuery.trim()
        if (query.isEmpty()) sections
        else sections.filter {
            it.roadName.contains(query, ignoreCase = true) ||
                it.startName.contains(query, ignoreCase = true) ||
                it.endName.contains(query, ignoreCase = true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showSections = true }, Modifier.fillMaxWidth()) {
            Text(selected?.let { "${it.roadName} · ${it.startName}~${it.endName}" } ?: "본선 구간 선택")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = date,
                onValueChange = { date = it.filter(Char::isDigit).take(8) },
                label = { Text("예측 날짜(YYYYMMDD)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = hour,
                onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                label = { Text("시각(0~23)") },
                modifier = Modifier.weight(0.55f),
                singleLine = true,
            )
        }
        Button(
            onClick = { selected?.let { vm.loadItsForecast(it.id, date, hour.toIntOrNull() ?: -1) } },
            enabled = selected != null && date.length == 8 && (hour.toIntOrNull() ?: -1) in 0..23,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("예측정보 조회") }
        if (forecasts.isEmpty() && selected != null) {
            ItsEmptyMessage("예측정보", "조회 버튼을 눌러 선택한 구간의 예측 정보를 확인하세요.")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(forecasts, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("구간 ${row.sectionId} · 연결 ${row.id.substringAfter(':')}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(
                                row.sectionType,
                                row.speedKmh?.let { "예측속도 $it km/h" },
                                row.lengthKm?.let { "길이 $it km" },
                                row.forecastDate?.let { "날짜 $it" },
                                row.forecastHour?.let { "시각 $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (showSections) {
        AlertDialog(
            onDismissRequest = { showSections = false },
            title = { Text("본선 구간 선택") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    OutlinedTextField(
                        value = sectionQuery,
                        onValueChange = { sectionQuery = it },
                        label = { Text("고속도로·나들목 검색") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                        items(filteredSections, key = { it.id }) { section ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = section; showSections = false }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(section.roadName, fontWeight = FontWeight.SemiBold)
                                Text("${section.startName}~${section.endName}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSections = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun ItsEmptyMessage(title: String, message: String) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$title 조회 전", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
