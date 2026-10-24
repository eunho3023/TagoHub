package com.eunho.tagohub.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.SubwayRegion
import com.eunho.tagohub.data.SubwayMap
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

/**
 * 지하철 탭: 역 검색(초성·자동)과 권역별 노선도(수도권·부산·대구·광주·대전).
 * 노선도는 두 손가락으로 확대·이동, 확대하면 역 이름이 보이고 역을 누르면 지도에서 그 역 정보가 열린다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubwayTab(
    vm: MapViewModel,
    search: SearchActions,
    onShowMap: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var regionName by rememberSaveable { mutableStateOf(SubwayRegion.Capital.name) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    // 수도권은 노선도 그림(역 이름을 누르면 열림), 나머지 권역은 역 위치로 그린 노선도
    val region = SubwayRegion.valueOf(regionName)
    val officialMap = region == SubwayRegion.Capital
    val stations = remember { vm.metroStations() }
    val lines = remember(stations, region) { SubwayMap.lines(stations, region) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        if (query.isNotBlank()) {
            // 검색 중에는 결과 목록이 화면을 차지함
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SearchPanel(vm, SearchScope.Subway, query, { query = it }, search)
            }
            return@Column
        }
        SearchPanel(vm, SearchScope.Subway, query, { query = it }, search)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubwayRegion.entries.forEach { r ->
                FilterChip(selected = r == region, onClick = { regionName = r.name; highlight = null }, label = { Text(r.label, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(6.dp))
        // 노선 이름 칩: 누르면 그 노선만 진하게 (다시 누르면 전체). 공식 노선도는 그림이라 강조 없음
        if (!officialMap) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { l ->
                    val on = highlight == l.name
                    Pill(l.name, subwayLineColor(l.name), Modifier.clickable { highlight = if (on) null else l.name }, filled = on || highlight == null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (officialMap) {
            SubwayImageMap(
                onStation = { name -> vm.stationGroupNamed(name)?.let(search.onStation) },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        } else if (lines.isEmpty()) {
            Text("이 권역의 노선 자료가 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        } else {
            SubwayLineMap(
                lines, highlight,
                onStation = { s -> vm.groupOfMetro(s)?.let(search.onStation) },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        }
        Text(
            if (officialMap) "노선도 · 두 손가락으로 확대·이동 · 역 이름을 누르면 지도에서 열려요" else "두 손가락으로 확대·이동 · 역을 누르면 지도에서 열려요",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}
