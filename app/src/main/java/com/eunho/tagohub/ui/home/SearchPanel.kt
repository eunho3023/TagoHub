package com.eunho.tagohub.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.Place2
import com.eunho.tagohub.data.StationGroup
import com.eunho.tagohub.ui.bus.RouteListRow
import com.eunho.tagohub.ui.bus.StopListRow
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.SearchBox
import com.eunho.tagohub.ui.common.SectionHeader
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.subwayLineColor

/** 어느 화면의 검색인지: 홈은 전부, 버스 탭은 정류소·노선, 지하철 탭은 역만 */
enum class SearchScope { All, Bus, Subway }

/** 검색 결과를 눌렀을 때 할 일 (화면이 지도를 열고 그곳으로 보냄) */
class SearchActions(
    val onPlace: (Place2) -> Unit,
    val onStation: (StationGroup) -> Unit,
    val onStop: (BusStop) -> Unit,
    val onRoute: (BusRoute) -> Unit,
)

/**
 * 검색창 + 그 아래 결과. 지도를 띄우지 않고 이 화면 안에서 결과를 보여 준다.
 * 입력을 멈추면(0.3초) 엔터 없이 바로 검색하고, 초성("ㅅㅇㅇ")도 된다.
 * 장소는 카카오 검색이라 초성은 안 되고, 지하철역·버스 정류소는 초성으로 찾는다.
 * 숫자가 든 말(노선번호)은 전국 검색이라 호출이 많아 엔터를 눌렀을 때만 찾는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPanel(
    vm: MapViewModel,
    scope: SearchScope,
    query: String,
    onQuery: (String) -> Unit,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    val busResult by vm.busResult.collectAsStateWithLifecycle()
    val progress by vm.busProgress.collectAsStateWithLifecycle()
    val names by vm.cityNames.collectAsStateWithLifecycle()
    var settled by remember { mutableStateOf("") }
    val places by produceState(emptyList<Place2>(), query, scope) {
        value = emptyList()
        if (scope == SearchScope.All && query.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            value = vm.searchPlaces(query)
        }
    }
    val stations = remember(query, scope) { if (scope != SearchScope.Bus && query.isNotBlank()) vm.searchStations(query, 10) else emptyList() }

    LaunchedEffect(query, scope) {
        if (query.isBlank()) {
            settled = ""
            vm.clearBusSearch()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        settled = query
        if (scope != SearchScope.Subway && !query.any(Char::isDigit)) vm.searchBus(query)
    }
    DisposableEffect(Unit) { onDispose { vm.clearBusSearch() } }

    Column(modifier.fillMaxWidth()) {
        SearchBox(
            query, onQuery, "검색하기",
            onSearch = { if (scope != SearchScope.Subway && query.isNotBlank()) vm.searchBus(query) },
        )
        if (query.isBlank()) return@Column

        progress?.let { (done, total) ->
            LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = ModeColor.Bus)
            Text(
                "전국 검색 중  $done / $total 지역",
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (places.isNotEmpty()) {
            SectionHeader("장소 ${places.size}")
            places.take(8).forEach { pl ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.onPlace(pl) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(pl.category, pl.address).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (stations.isNotEmpty()) {
            SectionHeader("지하철역 ${stations.size}")
            stations.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.onStation(g) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DirectionsSubway, contentDescription = null, tint = ModeColor.Subway, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (g.displayLines.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                                g.displayLines.take(4).forEach { Pill(it, subwayLineColor(it)) }
                            }
                        }
                    }
                }
            }
        }
        if (scope != SearchScope.Subway) {
            when (val r = busResult) {
                is Load.Ok -> {
                    if (r.value.routes.isNotEmpty()) {
                        SectionHeader("노선 ${r.value.routes.size}")
                        r.value.routes.take(30).forEach { route -> RouteListRow(route, names[route.cityCode]) { actions.onRoute(route) } }
                    }
                    if (r.value.stops.isNotEmpty()) {
                        SectionHeader("정류장 ${r.value.stops.size}")
                        r.value.stops.take(30).forEach { st ->
                            val dir by produceState<String?>(null, st.nodeId) { value = vm.stopDirection(st.nodeId)?.let { "$it 방면" } }
                            StopListRow(st, dir, names[st.cityCode], vm.stopNumbers(st)) { actions.onStop(st) }
                        }
                    }
                    r.value.warning?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                is Load.Fail -> MessageBox(r.message, isError = true, onRetry = { vm.searchBus(query) })
                else -> Unit
            }
        }
        val nothing = settled == query && progress == null && places.isEmpty() && stations.isEmpty() &&
            (scope == SearchScope.Subway || (busResult as? Load.Ok)?.value?.let { it.stops.isEmpty() && it.routes.isEmpty() } == true)
        if (nothing) MessageBox("검색 결과가 없어요.")
    }
}
