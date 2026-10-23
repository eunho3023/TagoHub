package com.eunho.tagohub.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.subwayLineColor

/** 버스 탭: 정류소·노선 검색(초성·자동), 즐겨찾기. 지도는 "지도에서 보기"로 */
@Composable
fun BusTab(
    vm: MapViewModel,
    search: SearchActions,
    onFavorite: (Favorite) -> Unit,
    onShowMap: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchPanel(vm, SearchScope.Bus, query, { query = it }, search)
            if (query.isBlank()) {
                Spacer(Modifier.height(16.dp))
                Favorites(vm, onFavorite)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도에서 보기") }
            }
        }
    }
}

/** 지하철 탭: 역 검색(초성·자동), 호선별 역 목록(가나다순). 지도는 "지도에서 보기"로 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubwayTab(
    vm: MapViewModel,
    search: SearchActions,
    onShowMap: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf<String?>(null) }
    val lines = remember { vm.subwayLines() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchPanel(vm, SearchScope.Subway, query, { query = it }, search)
            if (query.isBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("호선", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { name ->
                        FilterChip(
                            selected = line == name,
                            onClick = { line = if (line == name) null else name },
                            label = { Text(name, maxLines = 1) },
                        )
                    }
                }
                line?.let { selected ->
                    val stations = remember(selected) { vm.stationsOfLine(selected) }
                    Spacer(Modifier.height(10.dp))
                    Text("$selected · 역 ${stations.size}개 (가나다순)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    stations.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { search.onStation(g) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                                g.displayLines.take(4).forEach { Pill(it, subwayLineColor(it)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도에서 보기") }
            }
        }
    }
}
