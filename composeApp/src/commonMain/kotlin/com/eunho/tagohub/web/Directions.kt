package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 장소 (카카오 장소 찾기) */
data class WebPlace(val name: String, val address: String?, val lat: Double, val lng: Double, val category: String?)

/** 대중교통 경로 한 단계: BUS·SUBWAY·WALKING */
data class TransitStep(
    val type: String, val guidance: String?, val distance: Int, val time: Int,
    val stops: List<String>, val vehicles: List<String>, val points: List<Pair<Double, Double>>,
)

/** 대중교통 경로 하나 (카카오 길찾기) */
data class TransitRoute(val totalDistance: Int, val totalTime: Int, val transfers: Int, val fare: Int?, val steps: List<TransitStep>)

/** 웹·PC 길찾기: 서버 웹 통로 /w/kakao/place (장소, 하루 저장), /w/kakao/transit (대중교통, 5분 저장) */
class DirectionsRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"

    suspend fun places(q: String, near: Pair<Double, Double>? = null): List<WebPlace> = parsePlaces(
        http.getText(base + "w/kakao/place", query = listOf("q" to q.trim(), "x" to near?.second?.toString(), "y" to near?.first?.toString())),
    )

    suspend fun transit(from: WebPlace, to: WebPlace): Pair<String, List<TransitRoute>> = parseTransit(
        http.getText(
            base + "w/kakao/transit",
            query = listOf("sx" to from.lng.toString(), "sy" to from.lat.toString(), "ex" to to.lng.toString(), "ey" to to.lat.toString()),
        ),
    )

    companion object {
        private fun Any?.map(): Map<*, *>? = this as? Map<*, *>
        private fun Any?.list(): List<*> = this as? List<*> ?: emptyList<Any?>()
        private fun Map<*, *>.str(k: String): String? = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        private fun Map<*, *>.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

        fun parsePlaces(body: String): List<WebPlace> {
            val root = runCatching { MiniJson.parse(body) }.getOrNull().map() ?: return emptyList()
            return root["documents"].list().mapNotNull { d ->
                val m = d.map() ?: return@mapNotNull null
                WebPlace(
                    name = m.str("place_name") ?: return@mapNotNull null,
                    address = m.str("road_address_name") ?: m.str("address_name"),
                    lat = m.str("y")?.toDoubleOrNull() ?: return@mapNotNull null,
                    lng = m.str("x")?.toDoubleOrNull() ?: return@mapNotNull null,
                    category = m.str("category_name")?.substringAfterLast(" > "),
                )
            }
        }

        /** (상태, 경로들). 상태 OK 가 아니면 경로 없음 ([message] 로 문구) */
        fun parseTransit(body: String): Pair<String, List<TransitRoute>> {
            val root = runCatching { MiniJson.parse(body) }.getOrNull().map() ?: return "INVALID_RESPONSE" to emptyList()
            val status = root.str("status") ?: "INVALID_RESPONSE"
            val routes = root["routes"].list().mapNotNull { r ->
                val rm = r.map() ?: return@mapNotNull null
                val p = rm["properties"].map() ?: return@mapNotNull null
                val steps = rm["steps"].list().mapNotNull { st ->
                    val sm = st.map() ?: return@mapNotNull null
                    val sp = sm["properties"].map() ?: return@mapNotNull null
                    TransitStep(
                        type = sp.str("type") ?: return@mapNotNull null,
                        guidance = sp.str("guidance"),
                        distance = sp.int("distance") ?: 0,
                        time = sp.int("time") ?: 0,
                        stops = sp["stops"].list().mapNotNull { it.map()?.str("name") },
                        vehicles = sp["vehicles"].list().mapNotNull { it.map()?.str("name") },
                        points = sm["path"].map()?.get("points").list().mapNotNull { pt ->
                            val xy = pt.list()
                            val x = xy.getOrNull(0)?.toString()?.toDoubleOrNull()
                            val y = xy.getOrNull(1)?.toString()?.toDoubleOrNull()
                            if (x != null && y != null) y to x else null
                        },
                    )
                }
                TransitRoute(p.int("totalDistance") ?: 0, p.int("totalTime") ?: 0, p.int("transfers") ?: 0, p["fare"].map()?.int("value"), steps)
            }
            return status to routes
        }

        fun message(status: String): String = when (status) {
            "EQUAL_POINTS" -> "출발지와 도착지가 같아요"
            "STARTNODES_NULL" -> "출발지 근처에 대중교통이 없어요"
            "ENDNODES_NULL" -> "도착지 근처에 대중교통이 없어요"
            "NO_RESULTS" -> "대중교통 경로를 찾지 못했어요"
            else -> "경로를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."
        }

        /** 단계 한 줄 요약: "버스 102", "지하철 1호선", "걷기 5분" */
        fun label(s: TransitStep): String = when (s.type) {
            "BUS" -> "버스 " + (s.vehicles.firstOrNull() ?: s.guidance.orEmpty())
            "SUBWAY" -> s.vehicles.firstOrNull() ?: s.guidance ?: "지하철"
            else -> "걷기 ${(s.time + 59) / 60}분"
        }
    }
}

internal class DirectionsState {
    var from by mutableStateOf<WebPlace?>(null)
    var to by mutableStateOf<WebPlace?>(null)
    var editing by mutableStateOf<String?>("from")
    var query by mutableStateOf("")
    var places by mutableStateOf<List<WebPlace>>(emptyList())
    var status by mutableStateOf<String?>(null)
    var routes by mutableStateOf<List<TransitRoute>?>(null)
    var picked by mutableStateOf<Int?>(null)
    val map = MapState(36.35, 127.38, 12)
}

private val BusLine = Color(0xFF2E9E5B)
private val SubwayLine = Color(0xFF3B7DDD)
private val WalkLine = Color(0xFF9E9E9E)

private fun stepColor(t: String) = when (t) { "BUS" -> BusLine; "SUBWAY" -> SubwayLine; else -> WalkLine }

/**
 * 길찾기 (카카오): 출발·도착 장소를 찾아 고르면 대중교통 경로 목록, 누르면 지도에 경로와 단계별 안내.
 */
@Composable
internal fun DirectionsPane(repo: DirectionsRepo, st: DirectionsState, tiles: TileLoader, modeChips: @Composable () -> Unit) {
    Header("TagoHub", "대중교통 길찾기")
    modeChips()
    val from = st.from
    val to = st.to
    LaunchedEffect(from, to) {
        st.routes = null; st.status = null; st.picked = null
        if (from == null || to == null) return@LaunchedEffect
        try {
            val (status, routes) = repo.transit(from, to)
            st.status = status
            st.routes = routes
            if (routes.isNotEmpty() && to.name != MY_PLACE) Recent.addPlace(to)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { st.status = "ERROR"; st.routes = emptyList() }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaceBox("출발", from?.name ?: "장소 찾기", st.editing == "from", Modifier.weight(1f)) { st.editing = "from"; st.query = ""; st.places = emptyList() }
            Text(
                "⇄", Modifier.padding(horizontal = 8.dp).clickable { val t = st.from; st.from = st.to; st.to = t },
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            )
            PlaceBox("도착", to?.name ?: "장소 찾기", st.editing == "to", Modifier.weight(1f)) { st.editing = "to"; st.query = ""; st.places = emptyList() }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val editing = st.editing
    if (editing != null) {
        var searching by remember { mutableStateOf(false) }
        LaunchedEffect(st.query) {
            val q = st.query.trim()
            if (q.length < 2) { st.places = emptyList(); return@LaunchedEffect }
            delay(400)
            searching = true
            val near = (if (editing == "to") st.from else st.to)?.let { it.lat to it.lng }
            st.places = try { repo.places(q, near) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            searching = false
        }
        OutlinedTextField(
            st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(if (editing == "from") "출발 장소·주소·역 이름" else "도착 장소·주소·역 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
        if (st.places.isEmpty()) Note(if (searching) "찾는 중..." else if (st.query.trim().length < 2) "두 글자 이상 입력해 주세요" else "찾는 장소가 없어요")
        fun use(p: WebPlace) {
            if (editing == "from") st.from = p else st.to = p
            st.editing = if (editing == "from" && st.to == null) "to" else null
            st.query = ""; st.places = emptyList()
        }
        // 검색어가 없으면 집·회사 (장소 찾기 결과 줄의 "집"·"회사"로 정함)
        if (st.query.isBlank()) {
            RecentHead("자주 가는 곳", null)
            for ((label, p) in listOf("집" to HomeWork.home, "회사" to HomeWork.work)) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(enabled = p != null) { p?.let(::use) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        p?.name ?: "장소를 찾아 결과 줄의 $label 단추로 정해요", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (p != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            items(st.places, key = { "${it.name}|${it.lat}|${it.lng}" }) { p ->
                Column(
                    Modifier.fillMaxWidth().clickable { use(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        p.category?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        // 이 장소를 집·회사로 저장
                        for ((label, save) in listOf<Pair<String, (WebPlace) -> Unit>>("집" to HomeWork::saveHome, "회사" to HomeWork::saveWork)) {
                            Text(
                                label, Modifier.padding(start = 8.dp).clickable { save(p) }.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    p.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }
    val routes = st.routes
    when {
        from == null || to == null -> return Note("출발·도착 장소를 골라 주세요")
        routes == null -> return Note("경로를 찾는 중...")
        routes.isEmpty() -> return Note(DirectionsRepo.message(st.status ?: ""))
    }
    val pick = st.picked
    if (pick != null && pick in routes!!.indices) return RouteDetail(routes[pick], from!!, to!!, st, tiles)
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        itemsIndexed(routes!!) { i, r ->
            Column(Modifier.fillMaxWidth().clickable { st.picked = i }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(duration((r.totalTime + 59) / 60), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull("환승 ${r.transfers}번", r.fare?.let(::won), distance(r.totalDistance)).joinToString("  ·  "),
                        Modifier.padding(start = 10.dp, bottom = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 단계 막대: 시간 비율로
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val total = r.steps.sumOf { it.time }.coerceAtLeast(1)
                    for (s in r.steps) Row(Modifier.weight(s.time.coerceAtLeast(1).toFloat() / total).height(8.dp).background(stepColor(s.type), RoundedCornerShape(4.dp))) {}
                }
                Text(
                    r.steps.filter { it.type != "WALKING" }.joinToString("  →  ") { DirectionsRepo.label(it) + (it.stops.firstOrNull()?.let { s -> " ($s)" } ?: "") },
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RouteDetail(r: TransitRoute, from: WebPlace, to: WebPlace, st: DirectionsState, tiles: TileLoader) {
    val all = r.steps.flatMap { it.points } + listOf(from.lat to from.lng, to.lat to to.lng)
    LaunchedEffect(r) { st.map.fit(all) }
    Text(
        "← 다른 경로", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { st.picked = null }.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
    )
    MapHost(
        st.map, tiles, Modifier.fillMaxWidth().height(260.dp),
        lines = r.steps.filter { it.points.size >= 2 }.map { MapLine(it.points, stepColor(it.type), if (it.type == "WALKING") 3f else 6f) },
        dots = listOf(MapDot("from", from.lat, from.lng, BusLine, 7f, label = "출발"), MapDot("to", to.lat, to.lng, SoonColor, 7f, label = "도착")),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        item(key = "sum") {
            Text(
                duration((r.totalTime + 59) / 60) + "  ·  " + listOfNotNull("환승 ${r.transfers}번", r.fare?.let(::won)).joinToString("  ·  "),
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
        }
        itemsIndexed(r.steps) { _, s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    when (s.type) { "BUS" -> "버스"; "SUBWAY" -> "지하철"; else -> "걷기" },
                    Modifier.widthIn(min = 52.dp).background(stepColor(s.type), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (s.type == "WALKING") {
                        Text("${distance(s.distance)} 걷기 (약 ${(s.time + 59) / 60}분)", style = MaterialTheme.typography.bodyMedium)
                        s.guidance?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        Text(
                            (s.vehicles.firstOrNull() ?: s.guidance ?: "") + (if (s.vehicles.size > 1) "  (또는 ${s.vehicles.drop(1).take(3).joinToString(", ")})" else ""),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${s.stops.firstOrNull().orEmpty()} → ${s.stops.lastOrNull().orEmpty()}  ·  ${(s.stops.size - 1).coerceAtLeast(0)}${if (s.type == "SUBWAY") "개 역" else "정거장"}  ·  약 ${(s.time + 59) / 60}분",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "src") { Note("카카오 대중교통 길찾기 결과예요") }
    }
}

/** 길찾기 출발 "내 위치" (브라우저 위치) */
internal const val MY_PLACE = "내 위치"
