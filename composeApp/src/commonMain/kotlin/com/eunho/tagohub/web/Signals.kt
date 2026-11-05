package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 신호 교차로 (서울은 id 앞에 "seoul:", 울산은 "ulsan:") */
data class SignalSpot(val id: String, val name: String, val lat: Double, val lng: Double)

/** 한 방향·한 신호의 지금 색과 남은 시간(밀리초) */
data class SignalNow(val direction: String, val kind: String, val color: String, val remainMs: Int?)

/**
 * 웹·PC 실시간 신호등: 서울(교통정보 v2x, 교차로마다, 3초 저장)과 울산(한국지역정보개발원, 도시 전체 3초 저장).
 * 서울 교차로 목록은 서버에 올린 앱 파일(seoul_signals.tsv), 울산 목록은 서버 통로(하루 저장).
 */
class SignalsRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private var spots: List<SignalSpot>? = null

    suspend fun spots(): List<SignalSpot> {
        spots?.let { return it }
        val seoul = runCatching { parseSeoulSpots(http.getText(base + WebData.filePath("seoul_signals.tsv"))) }.getOrDefault(emptyList())
        val ulsan = runCatching { parseUlsanSpots(http.getText(base + "w/signal/ulsan/map")) }.getOrDefault(emptyList())
        return (seoul + ulsan).also { if (it.isNotEmpty()) spots = it }
    }

    suspend fun now(spot: SignalSpot): List<SignalNow> = when {
        spot.id.startsWith("seoul:") -> parseSeoul(http.getText(base + "w/signal/seoul/" + spot.id.removePrefix("seoul:")))
        else -> parseUlsan(http.getText(base + "w/signal/ulsan/now"))[spot.id.removePrefix("ulsan:")].orEmpty()
    }

    companion object {
        val DIRECTIONS = linkedMapOf("nt" to "북쪽", "ne" to "북동쪽", "et" to "동쪽", "se" to "남동쪽", "st" to "남쪽", "sw" to "남서쪽", "wt" to "서쪽", "nw" to "북서쪽")
        val KINDS = linkedMapOf("St" to "직진", "Lt" to "좌회전", "Pd" to "보행자", "Ut" to "유턴", "Bs" to "버스", "Bc" to "자전거")
        private val SEOUL_FIELD = Regex("(nt|et|st|wt|ne|se|sw|nw)(Bs|Bc|Lt|Pd|St|Ut)sg(StatNm|RmdrCs)")
        private val ULSAN_FIELD = Regex("(nt|et|st|wt|ne|se|sw|nw)(St|Lt|Pd|Ut)sg(SttsNm|RmndCs)")
        /** 서울은 1/10초, 이 값 넘으면 카운트다운 없는 신호 */
        private const val NO_COUNTDOWN = 36000.0

        /** J2735 신호 단계 → "red" "yellow" "green" "yellowBlink" "redBlink" "off" */
        fun color(state: String): String = when (state.trim().lowercase()) {
            "stop-and-remain", "pre-movement" -> "red"
            "stop-then-proceed", "stop-then-processed" -> "redBlink"
            "protected-movement-allowed", "permissive-movement-allowed" -> "green"
            "protected-clearance", "permissive-clearance" -> "yellow"
            "caution-conflicting-traffic" -> "yellowBlink"
            else -> "off"
        }

        fun parseSeoulSpots(text: String): List<SignalSpot> = text.lineSequence().mapNotNull { l ->
            val f = l.split('\t')
            if (f.size < 4 || f[0].isBlank()) return@mapNotNull null
            SignalSpot("seoul:" + f[0].trim(), f[1].trim().ifBlank { "교차로" }, f[2].toDoubleOrNull() ?: return@mapNotNull null, f[3].toDoubleOrNull() ?: return@mapNotNull null)
        }.toList()

        private fun ulsanItems(json: String): List<Map<*, *>> {
            val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return emptyList()
            val body = (root["body"] as? Map<*, *>) ?: ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>) ?: return emptyList()
            val item = (body["items"] as? Map<*, *>)?.get("item") ?: body["items"]
            return when (item) { is List<*> -> item.filterIsInstance<Map<*, *>>(); is Map<*, *> -> listOf(item); else -> emptyList() }
        }

        fun parseUlsanSpots(json: String): List<SignalSpot> = ulsanItems(json).mapNotNull { m ->
            SignalSpot(
                "ulsan:" + (m["crsrdId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null),
                m["crsrdNm"]?.toString().orEmpty().ifBlank { "교차로" },
                m["mapCtptIntLat"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
                m["mapCtptIntLot"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
            )
        }

        private fun sorted(states: Map<Pair<String, String>, String>, remains: Map<Pair<String, String>, Int>): List<SignalNow> {
            val dirs = DIRECTIONS.keys.toList()
            val kinds = KINDS.keys.toList()
            return states.keys.sortedWith(compareBy({ dirs.indexOf(it.first) }, { kinds.indexOf(it.second) }))
                .map { SignalNow(it.first, it.second, color(states.getValue(it)), remains[it]) }
        }

        private fun seoulRows(root: Any?): List<Map<*, *>> = when (root) {
            is List<*> -> root.filterIsInstance<Map<*, *>>()
            is Map<*, *> -> if (root.containsKey("itstId")) listOf(root) else root.values.firstNotNullOfOrNull { v -> seoulRows(v).takeIf { it.isNotEmpty() } }.orEmpty()
            else -> emptyList()
        }

        /** 서울: 줄이 여럿이면 가장 늦게 보낸 줄 */
        fun parseSeoul(json: String): List<SignalNow> {
            val newest = seoulRows(runCatching { MiniJson.parse(json) }.getOrNull())
                .maxByOrNull { it["trsmUtcTime"]?.toString()?.toDoubleOrNull() ?: 0.0 } ?: return emptyList()
            val states = HashMap<Pair<String, String>, String>()
            val remains = HashMap<Pair<String, String>, Int>()
            for ((k, v) in newest) {
                val f = SEOUL_FIELD.matchEntire(k?.toString() ?: continue) ?: continue
                val key = f.groupValues[1] to f.groupValues[2]
                val value = v?.toString()?.trim().orEmpty()
                if (value.isEmpty() || value == "null") continue
                if (f.groupValues[3] == "StatNm") states[key] = value
                else value.toDoubleOrNull()?.takeIf { it < NO_COUNTDOWN }?.let { remains[key] = (it * 100).toInt() }
            }
            return sorted(states, remains)
        }

        /** 울산: 교차로 id → 신호들 (남은 시간은 밀리초) */
        fun parseUlsan(json: String): Map<String, List<SignalNow>> = ulsanItems(json).mapNotNull { m ->
            val id = m["crsrdId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val states = HashMap<Pair<String, String>, String>()
            val remains = HashMap<Pair<String, String>, Int>()
            for ((k, v) in m) {
                val f = ULSAN_FIELD.matchEntire(k?.toString() ?: continue) ?: continue
                val key = f.groupValues[1] to f.groupValues[2]
                val value = v?.toString()?.trim().orEmpty()
                if (value.isEmpty() || value == "null") continue
                if (f.groupValues[3] == "SttsNm") states[key] = value else value.toDoubleOrNull()?.let { remains[key] = it.toInt() }
            }
            id to sorted(states, remains)
        }.toMap()
    }
}

private fun lampColor(c: String) = when (c) {
    "red", "redBlink" -> SoonColor
    "yellow", "yellowBlink" -> Color(0xFFF2B600)
    "green" -> Color(0xFF2E9E5B)
    else -> Color(0xFF9E9E9E)
}

private fun lampLabel(c: String) = when (c) {
    "red" -> "적색"; "redBlink" -> "적색 점멸"; "yellow" -> "황색"; "yellowBlink" -> "황색 점멸"; "green" -> "녹색"; else -> "정보 없음"
}

internal class SignalState {
    var picked by mutableStateOf<SignalSpot?>(null)
    val map = MapState(37.5665, 126.978, 15)
}

private const val SIGNAL_ZOOM = 14

/** 도로 화면 "신호등" 칸: 지도에서 교차로를 누르면 방향별 지금 신호와 남은 시간 (2초마다 새로) */
@Composable
internal fun SignalView(repo: SignalsRepo, st: SignalState, tiles: TileLoader) {
    var spots by remember { mutableStateOf<List<SignalSpot>?>(null) }
    LaunchedEffect(Unit) { spots = repo.spots() }
    val all = spots
    val b = st.map.bounds()
    val shown = if (all == null || st.map.zoom < SIGNAL_ZOOM) emptyList()
        else all.filter { it.lat in b[0]..b[2] && it.lng in b[1]..b[3] }.take(400)
    AppMap(
        st.map, tiles, Modifier.fillMaxWidth().height(300.dp),
        dots = (shown.filter { it.id != st.picked?.id } + listOfNotNull(st.picked)).map { s ->
            MapDot(s.id, s.lat, s.lng, if (s.id == st.picked?.id) SoonColor else MaterialTheme.colorScheme.primary, if (s.id == st.picked?.id) 8f else 5f)
        },
        onDot = { d -> st.picked = shown.firstOrNull { it.id == d.id } ?: st.picked },
        onTap = { st.picked = null },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val p = st.picked
    if (p == null) {
        return Note(
            when {
                all == null -> "교차로 목록을 불러오는 중..."
                st.map.zoom < SIGNAL_ZOOM -> "지도를 확대하면 교차로가 보여요 (서울·울산)"
                shown.isEmpty() -> "이 근처에는 실시간 신호 교차로가 없어요 (서울·울산만 있어요)"
                else -> "교차로 ${shown.size}곳 · 점을 누르면 지금 신호가 보여요"
            },
        )
    }
    var now by remember(p.id) { mutableStateOf<List<SignalNow>?>(null) }
    var got by remember(p.id) { mutableLongStateOf(0L) }
    var tick by remember(p.id) { mutableLongStateOf(0L) }
    LaunchedEffect(p.id) {
        while (true) {
            now = try { repo.now(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { now ?: emptyList() }
            got = nowMs()
            delay(2000)
        }
    }
    LaunchedEffect(p.id) { while (true) { delay(250); tick = nowMs() } }
    Text(
        p.name, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
    )
    val list = now
    when {
        list == null -> return Note("지금 신호를 불러오는 중...")
        list.isEmpty() -> return Note("이 교차로는 지금 신호 정보가 없어요")
    }
    val passed = (tick - got).coerceAtLeast(0)
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        items(list!!, key = { it.direction + it.kind }) { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(lampColor(s.color), CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${SignalsRepo.DIRECTIONS[s.direction] ?: s.direction} ${SignalsRepo.KINDS[s.kind] ?: s.kind}", style = MaterialTheme.typography.bodyMedium)
                    Text(lampLabel(s.color), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.remainMs?.let { ms ->
                    val left = ((ms - passed) / 1000).coerceAtLeast(0)
                    Text("${left}초", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = lampColor(s.color))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "note") { Note("신호 정보는 몇 초 늦을 수 있어요. 실제 신호를 꼭 확인하세요.") }
    }
}
