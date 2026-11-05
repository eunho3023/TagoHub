package com.eunho.tagohub.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 역 편의 정보 한 줄 (서버 data/files/subway_station_facilities.tsv) */
data class StationFacilityW(val operator: String, val line: String, val station: String, val category: String, val detail: String)

/** 혼잡도 한 방향 하루치: 5:30~0:30 30분 칸 39개 (%, 없으면 -1) */
data class CongestionSeries(val direction: String, val values: List<Int>)

/**
 * 웹·PC 지하철 부가 정보: 서울 지하철 노선도 그림(누르면 그 역), 혼잡도(서울교통공사 1~8호선 평균), 역 편의시설.
 * 모두 서버에 올린 앱 자료 파일이라 한 번 받으면 바로.
 */
class SubwayExtrasRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private val lock = Mutex()
    private var congestion: Map<String, List<CongestionSeries>>? = null
    private var facilities: Map<String, List<StationFacilityW>>? = null
    private var hotspots: List<Triple<String, Float, Float>>? = null
    private var image: ImageBitmap? = null

    suspend fun congestion(day: String, station: SubwayStationW): List<CongestionSeries> {
        if (!seoulArea(station)) return emptyList()
        val t = lock.withLock { congestion ?: parseCongestion(http.getText(base + WebData.filePath("subway_congestion.tsv"))).also { congestion = it } }
        return t["$day|${station.line.trim()}|${norm(station.name)}"].orEmpty()
    }

    suspend fun facilities(station: SubwayStationW): List<StationFacilityW> {
        val t = lock.withLock { facilities ?: parseFacilities(http.getText(base + WebData.filePath("subway_station_facilities.tsv"))).also { facilities = it } }
        return pick(t[norm(station.name)].orEmpty(), station)
    }

    suspend fun hotspots(): List<Triple<String, Float, Float>> = lock.withLock {
        hotspots ?: http.getText(base + WebData.filePath("seoul_metro_map_hotspots.tsv")).lineSequence().mapNotNull { l ->
            val f = l.split('\t')
            val x = f.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            val y = f.getOrNull(2)?.toFloatOrNull() ?: return@mapNotNull null
            Triple(f[0].trim(), x, y)
        }.toList().also { hotspots = it }
    }

    suspend fun mapImage(): ImageBitmap? {
        image?.let { return it }
        val r = http.get(base + WebData.filePath("seoul_metro_map.jpg"))
        return (if (r.status.isSuccess()) decodeImage(r.readRawBytes()) else null).also { image = it }
    }

    companion object {
        private val PAREN = Regex("\\(.*?\\)")
        private val OTHER_CITY = mapOf("MTRBS" to "부산", "MTRDG" to "대구", "MTRGJ" to "광주", "MTRDJ" to "대전", "MTRIC" to "인천")

        /** "청량리(서울시립대입구)" → "청량리", "서울역" → "서울" (앱 StationName.norm 과 같음) */
        fun norm(s: String): String {
            val t = s.replace(PAREN, "").replace(" ", "").trim()
            return if (t.length >= 3 && t.endsWith("역")) t.dropLast(1) else t
        }

        private fun lineNorm(s: String) = s.replace(" ", "").removeSuffix("선")

        private fun seoulArea(s: SubwayStationW) = OTHER_CITY.keys.none { s.id.startsWith(it) }

        /** 같은 이름 역 중 이 노선·지역 것만 (부산 1호선과 서울 1호선을 운영기관으로 구분) */
        fun pick(rows: List<StationFacilityW>, s: SubwayStationW): List<StationFacilityW> {
            val city = OTHER_CITY.entries.firstOrNull { s.id.startsWith(it.key) }?.value
            val line = lineNorm(s.line)
            return rows.filter { f ->
                val sameCity = if (city != null) f.operator.contains(city) else OTHER_CITY.values.none { f.operator.contains(it) }
                val fl = lineNorm(f.line)
                sameCity && (fl == line || fl.contains(line) || line.contains(fl))
            }.distinct()
        }

        fun parseCongestion(text: String): Map<String, List<CongestionSeries>> {
            val out = HashMap<String, MutableList<CongestionSeries>>()
            text.lineSequence().forEach { line ->
                val f = line.split('\t')
                if (f.size < 5 || f[0] !in setOf("W", "S", "U")) return@forEach
                val v = f[4].split(',').map { it.trim().toIntOrNull() ?: -1 }
                out.getOrPut("${f[0]}|${f[1].trim()}|${norm(f[2])}") { ArrayList() } += CongestionSeries(f[3].trim(), v)
            }
            return out
        }

        fun parseFacilities(text: String): Map<String, List<StationFacilityW>> =
            text.lineSequence().drop(1).mapNotNull { row ->
                val c = row.split('\t')
                if (c.size < 5 || c[2].isBlank() || c[4].isBlank()) null
                else StationFacilityW(c[0].trim().removePrefix("﻿"), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim())
            }.groupBy { norm(it.station) }

        /** 5:30 부터 30분 칸 → 칸 번호 (영업 전이면 첫 칸, 0시대는 뒤쪽 칸) */
        fun slotOf(hour: Int, minute: Int): Int {
            var m = hour * 60 + minute
            if (m < 60) m += 1440 else if (m < 330) return 0
            return ((m - 330) / 30).coerceIn(0, 38)
        }

        fun slotLabel(slot: Int): String {
            val m = (330 + slot * 30) % 1440
            return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
        }

        fun describe(p: Int): String = when {
            p < 0 -> "정보 없음"; p < 60 -> "여유"; p < 100 -> "보통"; p < 130 -> "다소 혼잡"; p < 150 -> "혼잡"; else -> "매우 혼잡"
        }

        /** 요일 코드: 평일 W, 토 S, 일 U */
        fun dayOf(isoDay: Int) = when (isoDay) { 6 -> "S"; 7 -> "U"; else -> "W" }
    }
}

private fun congestionColor(p: Int): Color = when {
    p < 0 -> Color(0xFFBDBDBD); p < 60 -> Color(0xFF2E9E5B); p < 100 -> Color(0xFF3B7DDD); p < 130 -> Color(0xFFF08C00); else -> SoonColor
}

/** 역 화면 "혼잡도" 칸: 요일 고르기, 방향마다 지금 칸 + 하루 막대 */
@Composable
internal fun CongestionView(repo: SubwayExtrasRepo, station: SubwayStationW) {
    val iso = remember { todayIso() }
    var day by remember { mutableStateOf(SubwayExtrasRepo.dayOf(iso)) }
    var data by remember(station.id, day) { mutableStateOf<List<CongestionSeries>?>(null) }
    LaunchedEffect(station.id, day) { data = runCatching { repo.congestion(day, station) }.getOrDefault(emptyList()) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((code, label) in listOf("W" to "평일", "S" to "토요일", "U" to "일요일")) {
            val on = day == code
            Text(
                label, Modifier.background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                    .pointerInput(code) { detectTapGestures { day = code } }.padding(horizontal = 12.dp, vertical = 5.dp),
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    val list = data
    when {
        list == null -> return Note("혼잡도를 불러오는 중...")
        list.isEmpty() -> return Note("이 역은 혼잡도 자료가 없어요 (서울교통공사 1~8호선만 있어요)")
    }
    val now = seoulNow()
    val slot = SubwayExtrasRepo.slotOf(now.hour, now.minute)
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        items(list!!, key = { it.direction }) { s ->
            val v = s.values.getOrElse(slot) { -1 }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.direction, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${SubwayExtrasRepo.slotLabel(slot)} " + (if (v >= 0) "$v% · " else "") + SubwayExtrasRepo.describe(v),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = congestionColor(v),
                    )
                }
                // 하루 막대 (지금 칸 굵게)
                val top = (s.values.maxOrNull() ?: 0).coerceAtLeast(100)
                Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    s.values.forEachIndexed { i, p ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight((p.coerceAtLeast(0).toFloat() / top).coerceIn(0.02f, 1f))
                                .background(congestionColor(p).copy(alpha = if (i == slot) 1f else 0.45f)),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    for (lbl in listOf("05:30", "12:00", "18:00", "00:30")) {
                        Text(lbl, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "note") { Note("과거 평균 자료예요 (정원 대비 %). 실제와 다를 수 있어요.") }
    }
}

/** 역 화면 "시설" 칸: 분류별로 묶어서 */
@Composable
internal fun FacilityView(repo: SubwayExtrasRepo, station: SubwayStationW) {
    var data by remember(station.id) { mutableStateOf<List<StationFacilityW>?>(null) }
    LaunchedEffect(station.id) { data = runCatching { repo.facilities(station) }.getOrDefault(emptyList()) }
    val list = data
    when {
        list == null -> return Note("역 시설 정보를 불러오는 중...")
        list.isEmpty() -> return Note("이 역은 시설 정보가 없어요")
    }
    val groups = list!!.groupBy { it.category }.toList()
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        for ((cat, rows) in groups) {
            item(key = "h$cat") {
                Text(
                    cat, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows.map { it.detail }.distinct(), key = { "$cat|$it" }) { d ->
                Text(d, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 서울 지하철 노선도 그림: 끌어서 옮기고 두 손가락·휠로 확대, 역 이름을 누르면 그 역 찾기 */
@Composable
internal fun SubwayMapPane(repo: SubwayExtrasRepo, onBack: () -> Unit, onStation: (String) -> Unit) {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var spots by remember { mutableStateOf<List<Triple<String, Float, Float>>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { spots = repo.hotspots(); img = repo.mapImage() }.onFailure { failed = true }
        if (img == null) failed = true
    }
    Header("서울 지하철 노선도", "역 이름을 누르면 그 역 시간표", onBack)
    val image = img
    if (image == null) return Note(if (failed) "노선도를 받지 못했어요" else "노선도를 불러오는 중... (약 2.6MB)")
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }        // 0 = 아직 안 맞춤
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun fitScale() = if (size.width == 0) 1f else minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
    fun zoomAt(c: Offset, k: Float) {
        val s = (scale * k).coerceIn(fitScale(), 2.5f)
        val real = s / scale
        offset = c - (c - offset) * real
        scale = s
    }
    Box(
        Modifier.fillMaxSize().background(Color.White).onSizeChanged {
            size = it
            if (scale == 0f && it.width > 0) {
                scale = fitScale()
                offset = Offset((it.width - image.width * scale) / 2, (it.height - image.height * scale) / 2)
            }
        }
            .pointerInput(image) { detectTransformGestures { c, pan, zoom, _ -> if (zoom != 1f) zoomAt(c, zoom); offset += pan } }
            .pointerInput(image) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val ch = e.changes.first()
                            zoomAt(ch.position, if (ch.scrollDelta.y < 0) 1.2f else 1 / 1.2f)
                            ch.consume()
                        }
                    }
                }
            }
            .pointerInput(image, spots) {
                detectTapGestures { p ->
                    if (scale <= 0f) return@detectTapGestures
                    // 누른 곳에서 가장 가까운 역 (그림 위 거리로 40픽셀 안)
                    val ix = (p.x - offset.x) / scale / image.width
                    val iy = (p.y - offset.y) / scale / image.height
                    val near = spots.minByOrNull { (_, x, y) -> (x - ix) * (x - ix) + (y - iy) * (y - iy) } ?: return@detectTapGestures
                    val dx = (near.second - ix) * image.width * scale
                    val dy = (near.third - iy) * image.height * scale
                    if (dx * dx + dy * dy < 40f * 40f) onStation(near.first)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (scale > 0f) {
                drawImage(
                    image, dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                    dstSize = IntSize((image.width * scale).toInt(), (image.height * scale).toInt()),
                )
            }
        }
        Text(
            "끌어서 옮기기 · 휠·두 손가락으로 확대", Modifier.align(Alignment.BottomCenter).padding(12.dp)
                .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
        )
    }
}
