package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

internal class RoadState {
    var tab by mutableStateOf(0)
    var picked by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var incidents by mutableStateOf<List<RoadIncident>?>(null)
    var jams by mutableStateOf<List<RoadJam>?>(null)
    var gas by mutableStateOf<List<GasStation>?>(null)
    var failed by mutableStateOf(setOf<Int>())
    var reload by mutableIntStateOf(0)
    val map = MapState(36.4, 127.8, 7)
    val signal = SignalState()
}

private val JamColor = Color(0xFFF08C00)

/**
 * 도로 (한국도로공사): 고속도로 돌발·정체 구간을 지도와 목록으로, 휴게소 주유 가격.
 * 목록을 누르면 지도가 그 자리로. 국가교통정보센터·경찰청 자료(일반 도로 소통·CCTV)는 폰 앱에서.
 */
@Composable
internal fun RoadPane(repo: RoadRepo, st: RoadState, tiles: TileLoader, modeChips: @Composable () -> Unit, signals: SignalsRepo? = null) {
    Header("TagoHub", "고속도로 돌발·정체·주유 가격·신호등", action = "새로", onAction = {
        st.incidents = null; st.jams = null; st.gas = null; st.failed = emptySet(); st.reload++
    })
    modeChips()
    LaunchedEffect(st.tab, st.reload) {
        // 새로 고침·탭 바꿈으로 취소된 것은 실패로 치지 않음 (CancellationException 은 다시 던짐)
        suspend fun <T> load(tab: Int, block: suspend () -> List<T>): List<T> {
            st.failed -= tab
            return try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { st.failed += tab; emptyList() }
        }
        when (st.tab) {
            0 -> if (st.incidents == null) st.incidents = load(0) { repo.incidents() }
            1 -> if (st.jams == null) st.jams = load(1) { repo.jams() }
            2 -> if (st.gas == null) st.gas = load(2) { repo.gas() }
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((i, label) in (listOf("돌발", "정체", "주유 가격") + listOfNotNull("신호등".takeIf { signals != null })).withIndex()) {
            val on = st.tab == i
            Text(
                label, Modifier.background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { st.tab = i; st.picked = null }.padding(horizontal = 12.dp, vertical = 5.dp),
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (st.tab == 3 && signals != null) return SignalView(signals, st.signal, tiles)
    if (st.tab == 2) return GasList(st)

    // 돌발·정체: 위에 지도, 아래 목록
    val points: List<Triple<String, Double, Double>> = if (st.tab == 0) {
        st.incidents.orEmpty().mapNotNull { i -> if (i.lat != null && i.lng != null) Triple(i.id, i.lat, i.lng) else null }
    } else {
        st.jams.orEmpty().mapNotNull { j -> if (j.lat != null && j.lng != null) Triple(j.id, j.lat, j.lng) else null }
    }
    val color = if (st.tab == 0) SoonColor else JamColor
    AppMap(
        st.map, tiles, Modifier.fillMaxWidth().height(240.dp),
        dots = points.sortedBy { it.first == st.picked }.map { (id, la, lo) ->
            MapDot(id, la, lo, color, if (id == st.picked) 9f else 6f)
        },
        onDot = { st.picked = it.id },
        onTap = { st.picked = null },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    fun focus(id: String, lat: Double?, lng: Double?) {
        st.picked = id
        if (lat != null && lng != null) st.map.moveTo(lat, lng, 13)
    }
    if (st.tab == 0) {
        val list = st.incidents
        when {
            0 in st.failed -> Note("돌발 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
            list == null -> Note("돌발 정보를 불러오는 중...")
            list.isEmpty() -> Note("지금 고속도로 돌발 정보가 없어요")
            else -> LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                // 지도에서 고른 것을 맨 위로
                items(list.sortedBy { it.id != st.picked }, key = { it.id }) { i ->
                    Column(
                        Modifier.fillMaxWidth().background(if (i.id == st.picked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable { focus(i.id, i.lat, i.lng) }.padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(i.kind, style = MaterialTheme.typography.labelMedium, color = SoonColor, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(i.road, i.dir).joinToString(" · "), Modifier.padding(start = 8.dp).weight(1f),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                            i.time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (i.text.isNotEmpty()) Text(i.text, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium)
                        val sub = listOfNotNull(i.place, i.jamMeters?.let { "정체 ${distance(it)}" }, i.status).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    } else {
        val list = st.jams
        when {
            1 in st.failed -> Note("정체 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
            list == null -> Note("정체 구간을 불러오는 중...")
            list.isEmpty() -> Note("지금 고속도로 정체 구간이 없어요")
            else -> LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                items(list.sortedBy { it.id != st.picked }, key = { it.id }) { j ->
                    Row(
                        Modifier.fillMaxWidth().background(if (j.id == st.picked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable { focus(j.id, j.lat, j.lng) }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(listOf(j.route, j.zone).filter { it.isNotEmpty() }.joinToString(" "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(j.dir, j.time?.let { "$it 기준" }).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        j.speed?.let { Text("${it}km/h", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = JamColor) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun GasList(st: RoadState) {
    val list = st.gas
    when {
        2 in st.failed -> return Note("주유 가격을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        list == null -> return Note("주유 가격을 불러오는 중...")
        list.isEmpty() -> return Note("주유 가격 정보가 없어요")
    }
    OutlinedTextField(
        st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("휴게소·노선 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
    )
    val q = st.query.replace(" ", "")
    val shown = list!!.filter { q.isEmpty() || it.name.replace(" ", "").contains(q) || it.route.orEmpty().contains(q) }
    val cheapest = shown.mapNotNull { it.gasoline }.minOrNull()
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        items(shown, key = { it.name + "|" + it.dir }) { g ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(g.route, g.dir, g.company).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    g.gasoline?.let {
                        Text(
                            "휘발유 ${won(it)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = if (it == cheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val rest = listOfNotNull(g.diesel?.let { "경유 ${won(it)}" }, g.lpg?.let { "LPG ${won(it)}" }).joinToString("  ")
                    if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 1500 → "1.5km", 800 → "800m" */
internal fun distance(m: Int): String = if (m >= 1000) "${m / 1000}.${(m % 1000) / 100}km" else "${m}m"
