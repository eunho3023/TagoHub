package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 공항 기본 정보 (서버 data/files/airport_info.json, 앱과 같은 파일) */
data class AirportStatic(
    val code: String, val short: String, val name: String, val address: String?, val phone: String?,
    val transport: List<Pair<String, String>>, val lounges: List<String>, val shopCount: Int,
    val delay: Map<String, String>, val cancel: Map<String, String>,
)

/** 공항 실시간: 혼잡도(구역 A·B·C, 전체 1 원활~4 매우 혼잡), 소요시간(초), 주차장 */
data class AirportLiveW(
    val time: String?, val levels: List<Int?>, val all: Int?,
    val processTime: String?, val processOpen: Boolean?, val processSec: Int?,
    val lots: List<Triple<String, String?, String?>>,
)

/** 한국공항공사 실시간 운항 한 줄 */
data class FlightStatusW(
    val flight: String, val airline: String, val scheduled: String?, val estimated: String?, val remark: String?,
    val departure: Boolean, val other: String, val gate: String?, val codeshare: Boolean,
)

/** 웹·PC 공항 정보: 기본 정보는 서버 파일, 실시간은 서버 웹 통로 /w/kac/ (혼잡도·소요시간·주차장 2분, 운항 1분 저장) */
class AirportInfoRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private var statics: Map<String, AirportStatic>? = null

    suspend fun static(code: String): AirportStatic? =
        (statics ?: parseStatic(http.getText(base + WebData.filePath("airport_info.json"))).also { statics = it })[code]

    suspend fun live(code: String): AirportLiveW = coroutineScope {
        val cong = async {
            val v = when (code) { "GMP", "CJU" -> "v1"; "PUS", "CJJ", "TAE" -> "v2"; else -> null }
            v?.let { safe { rows(http.getText(base + "w/kac/airport-congestion/$it")) } }?.firstOrNull { it.s("IATA_APCD") == code }
        }
        val proc = async { safe { rows(http.getText(base + "w/kac/airport-process-time/v1")) }?.firstOrNull { it.s("IATA_APCD") == code } }
        val lots = async { safe { rows(http.getText(base + "w/kac/parking-congestion/info")) }.orEmpty() }
        val c = cong.await()
        val p = proc.await()
        val lotName = LOT_AIRPORT[code]
        AirportLiveW(
            time = c?.s("PRC_HR"),
            levels = listOf("A", "B", "C").map { c?.n("CGDR_${it}_LVL") }, all = c?.n("CGDR_ALL_LVL"),
            processTime = p?.s("PRC_HR"), processOpen = p?.n("OPR_STS_CD")?.let { it == 1 }, processSec = p?.n("STY_TCT_AVG_ALL"),
            lots = if (lotName == null) emptyList() else lots.await().filter { it.s("airportKor").orEmpty().startsWith(lotName) }.mapNotNull { m ->
                Triple(m.s("parkingAirportCodeName") ?: return@mapNotNull null, m.s("parkingCongestion"), m.s("parkingCongestionDegree"))
            },
        )
    }

    /** [code] 공항의 [ymd] 하루 운항 (출발·도착, 최대 10쪽) */
    suspend fun status(code: String, ymd: String): List<FlightStatusW> {
        if (code == ICN) return icn(ymd)
        if (code !in LOT_AIRPORT && code != "KPO") return emptyList()
        val out = ArrayList<FlightStatusW>()
        for (page in 1..10) {
            val body = http.getText(base + "w/kac/flight-aircraft-status/info", query = listOf("air" to code, "date" to ymd, "pageNo" to page.toString()))
            val got = parseStatus(body)
            out += got
            if (got.size < 100 || out.size >= totalCount(body)) break
        }
        return out
    }

    /** 인천공항 [ymd] 하루 여객 출발·도착 (인천국제공항공사, 서버 /w/icn/, 쪽마다 1000건) */
    private suspend fun icn(ymd: String): List<FlightStatusW> = coroutineScope {
        listOf(true, false).map { dep ->
            async {
                val out = ArrayList<FlightStatusW>()
                for (page in 1..5) {
                    val body = http.getText(base + "w/icn/" + (if (dep) "dep" else "arr"), query = listOf("date" to ymd, "pageNo" to page.toString()))
                    val got = parseIcn(body, dep)
                    out += got
                    if (rows(body).size < 1000 || out.size >= totalCount(body)) break
                }
                out
            }
        }.awaitAll().flatten()
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    companion object {
        val LOT_AIRPORT = mapOf(
            "GMP" to "김포", "PUS" to "김해", "CJU" to "제주", "TAE" to "대구", "KWJ" to "광주", "RSU" to "여수", "USN" to "울산",
            "KUV" to "군산", "WJU" to "원주", "CJJ" to "청주", "MWX" to "무안", "HIN" to "사천", "YNY" to "양양",
        )

        private fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        private fun Map<*, *>.n(k: String) = s(k)?.toDoubleOrNull()?.toInt()

        /** 공공데이터포털 JSON 의 response.body.items(.item) → 행 */
        fun rows(text: String): List<Map<*, *>> {
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
            val body = (root["response"] as? Map<*, *>)?.get("body") as? Map<*, *> ?: return emptyList()
            val items = body["items"]
            val raw = if (items is Map<*, *>) items["item"] else items
            return when (raw) { is List<*> -> raw.filterIsInstance<Map<*, *>>(); is Map<*, *> -> listOf(raw); else -> emptyList() }
        }

        fun totalCount(text: String): Int {
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return 0
            return ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>)?.get("totalCount")?.toString()?.toDoubleOrNull()?.toInt() ?: 0
        }

        private fun hhmm(s: String?) = s?.takeIf { it.length >= 12 }?.let { "${it.substring(8, 10)}:${it.substring(10, 12)}" }

        fun parseStatus(body: String): List<FlightStatusW> = rows(body).mapNotNull { m ->
            FlightStatusW(
                flight = m.s("airFln") ?: return@mapNotNull null, airline = m.s("airlineKorean").orEmpty(),
                scheduled = hhmm(m.s("std")), estimated = hhmm(m.s("etd")), remark = m.s("rmkKor"),
                departure = m.s("io") == "O", other = m.s("city").orEmpty(), gate = m.s("gate"), codeshare = m.s("cdsrYn") == "Y",
            )
        }

        const val ICN = "ICN"

        /** 보여 줄 운항: 출발/도착 하나, 공동운항 빼고, 예정 시각 순 */
        fun shown(flights: List<FlightStatusW>, departure: Boolean) =
            flights.filter { it.departure == departure && !it.codeshare }.distinctBy { it.flight + it.scheduled }.sortedBy { it.scheduled ?: "99" }

        /** [fromMin](하루 분) 이후 첫 운항의 자리 (없으면 끝, 음수면 처음) */
        fun firstFromNow(list: List<FlightStatusW>, fromMin: Int): Int {
            if (fromMin <= 0) return 0
            val i = list.indexOfFirst { f -> f.scheduled?.split(":")?.let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }?.let { it >= fromMin } ?: false }
            return if (i < 0) (list.size - 1).coerceAtLeast(0) else i
        }

        private fun icnTerminal(t: String?) = when (t) { "P01" -> "T1"; "P02" -> "탑승동"; "P03" -> "T2"; else -> null }

        /** 인천공항 운항 (getFltDeparturesDeOdp·getFltArrivalsDeOdp). 공동운항(codeshare Slave)은 [FlightStatusW.codeshare] */
        fun parseIcn(body: String, departure: Boolean): List<FlightStatusW> = rows(body).mapNotNull { m ->
            FlightStatusW(
                flight = m.s("flightId") ?: return@mapNotNull null, airline = m.s("airline").orEmpty(),
                scheduled = hhmm(m.s("scheduleDatetime")), estimated = hhmm(m.s("estimatedDatetime")), remark = m.s("remark"),
                departure = departure, other = m.s("airport").orEmpty(),
                gate = listOfNotNull(icnTerminal(m.s("terminalId")), m.s("gateNumber")?.takeIf { it != "-" }).joinToString(" ").ifEmpty { null },
                codeshare = m.s("codeshare").equals("Slave", ignoreCase = true),
            )
        }

        /** 편명 비교용 (공백·하이픈 빼고 대문자) */
        fun norm(no: String) = no.filter { it.isLetterOrDigit() }.uppercase()

        fun parseStatic(json: String): Map<String, AirportStatic> {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyMap()
            val airports = root["airports"] as? Map<*, *> ?: return emptyMap()
            fun strMap(v: Any?): Map<String, String> = (v as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to x.toString() }.orEmpty()
            return airports.mapNotNull { (short, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                val code = m.s("code") ?: return@mapNotNull null
                code to AirportStatic(
                    code = code, short = short.toString(), name = m.s("name") ?: short.toString(), address = m.s("address"), phone = m.s("phone"),
                    transport = (m["transport"] as? List<*>).orEmpty().mapNotNull { t ->
                        val tm = t as? Map<*, *> ?: return@mapNotNull null
                        (tm.s("type") ?: return@mapNotNull null) to tm.s("routes").orEmpty()
                    },
                    lounges = (m["lounges"] as? List<*>).orEmpty().mapNotNull { l ->
                        val lm = l as? Map<*, *> ?: return@mapNotNull null
                        listOfNotNull(lm.s("name"), lm.s("place"), lm.s("hours")).joinToString(" · ").ifEmpty { null }
                    },
                    shopCount = (m["shops"] as? List<*>)?.size ?: 0,
                    delay = ((m["delay"] as? Map<*, *>)?.get("국내") as? Map<*, *>)?.let(::strMap).orEmpty(),
                    cancel = ((m["cancel"] as? Map<*, *>)?.get("국내") as? Map<*, *>)?.let(::strMap).orEmpty(),
                )
            }.toMap()
        }

        fun levelLabel(l: Int?) = when (l) { 1 -> "원활"; 2 -> "보통"; 3 -> "혼잡"; 4 -> "매우 혼잡"; else -> "정보 없음" }
    }
}

private fun levelColor(l: Int?) = when (l) { 1 -> Color(0xFF2E9E5B); 2 -> Color(0xFF3B7DDD); 3 -> Color(0xFFF08C00); 4 -> SoonColor; else -> Color(0xFF9E9E9E) }

/** 공항 정보 화면: 실시간(혼잡도·소요시간·주차장) · 실시간 운항(출발·도착) · 기본 정보 */
@Composable
internal fun AirportPane(repo: AirportInfoRepo, code: String, name: String, onBack: () -> Unit) {
    var info by remember(code) { mutableStateOf<AirportStatic?>(null) }
    var live by remember(code) { mutableStateOf<AirportLiveW?>(null) }
    var flights by remember(code) { mutableStateOf<List<FlightStatusW>?>(null) }
    // 인천공항은 실시간 혼잡도·기본 정보가 없어 출발부터
    var tab by remember(code) { mutableStateOf(if (code == AirportInfoRepo.ICN) 1 else 0) }
    val today = seoulDate()
    val ymd = "${today.year}${today.monthNumber.toString().padStart(2, '0')}${today.dayOfMonth.toString().padStart(2, '0')}"
    LaunchedEffect(code) { info = runCatching { repo.static(code) }.getOrNull() }
    LaunchedEffect(code) { live = runCatching { repo.live(code) }.getOrNull() ?: AirportLiveW(null, listOf(null, null, null), null, null, null, null, emptyList()) }
    LaunchedEffect(code) { flights = runCatching { repo.status(code, ymd) }.getOrDefault(emptyList()) }
    Header(info?.name ?: name, info?.address, onBack)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, label) in listOf("실시간", "출발", "도착", "공항 정보").withIndex()) {
            if (code == AirportInfoRepo.ICN && (i == 0 || i == 3)) continue
            val on = tab == i
            Text(
                label, Modifier.background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // 출발·도착은 지금 시각(30분 전부터) 쪽을 먼저 보이게
    val listState = rememberLazyListState()
    LaunchedEffect(tab, flights) {
        val fs = flights ?: return@LaunchedEffect
        if (tab != 1 && tab != 2) return@LaunchedEffect
        val now = seoulNow()
        val idx = AirportInfoRepo.firstFromNow(AirportInfoRepo.shown(fs, tab == 1), now.hour * 60 + now.minute - 30)
        listState.scrollToItem(idx)
    }
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), state = listState) {
        when (tab) {
            0 -> liveSection(live)
            1, 2 -> flightSection(flights, departure = tab == 1)
            else -> staticSection(info)
        }
    }
}

private fun LazyListScope.head(key: String, text: String) = item(key = key) {
    Text(
        text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun LazyListScope.line(key: String, left: String, right: String?, color: Color? = null) = item(key = key) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        right?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun LazyListScope.liveSection(live: AirportLiveW?) {
    if (live == null) { item(key = "w") { Note("실시간 정보를 불러오는 중...") }; return }
    if (live.all != null || live.levels.any { it != null }) {
        head("hc", "출국장 혼잡도" + (live.time?.let { " · ${it}시 기준" } ?: ""))
        line("call", "전체", AirportInfoRepo.levelLabel(live.all), levelColor(live.all))
        live.levels.forEachIndexed { i, l -> if (l != null) line("c$i", "${"ABC"[i]} 구역", AirportInfoRepo.levelLabel(l), levelColor(l)) }
    }
    if (live.processSec != null || live.processOpen != null) {
        head("hp", "출국 소요시간" + (live.processTime?.let { " · ${it}시 기준" } ?: ""))
        line("p", "보안검색까지 평균", if (live.processOpen == false) "운영 안 함" else live.processSec?.let { "약 ${((it + 30) / 60).coerceAtLeast(1)}분" })
    }
    if (live.lots.isNotEmpty()) {
        head("hl", "주차장")
        live.lots.forEachIndexed { i, (n, label, pct) -> line("l$i", n, listOfNotNull(label, pct).joinToString(" · ").ifEmpty { null }) }
    }
    if (live.all == null && live.levels.all { it == null } && live.processSec == null && live.lots.isEmpty()) item(key = "none") { Note("이 공항은 실시간 정보가 없어요") }
}

private fun LazyListScope.flightSection(flights: List<FlightStatusW>?, departure: Boolean) {
    if (flights == null) { item(key = "w") { Note("실시간 운항을 불러오는 중...") }; return }
    val list = AirportInfoRepo.shown(flights, departure)
    if (list.isEmpty()) { item(key = "none") { Note("오늘 실시간 운항 정보가 없어요") }; return }
    items(list, key = { it.flight + "|" + it.scheduled }) { f ->
        val changed = f.estimated != null && f.estimated != f.scheduled
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (changed) f.estimated!! else f.scheduled.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (changed) SoonColor else MaterialTheme.colorScheme.onSurface)
                    if (changed) Text("  예정 ${f.scheduled}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull((if (departure) "→ " else "← ") + f.other, f.airline, f.flight).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                f.remark?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (it.contains("지연") || it.contains("결항")) SoonColor else MaterialTheme.colorScheme.primary) }
                f.gate?.takeIf { departure }?.let { Text("탑승구 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun LazyListScope.staticSection(info: AirportStatic?) {
    if (info == null) { item(key = "w") { Note("공항 정보를 불러오는 중...") }; return }
    head("hb", "기본 정보")
    info.address?.let { line("addr", it, null) }
    info.phone?.let { line("tel", "대표 전화", it) }
    if (info.shopCount > 0) line("shop", "입점 매장", "${info.shopCount}곳")
    if (info.transport.isNotEmpty()) {
        head("ht", "교통편")
        info.transport.forEachIndexed { i, (t, r) ->
            item(key = "t$i") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (r.isNotEmpty()) Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (info.lounges.isNotEmpty()) {
        head("hlo", "라운지")
        info.lounges.forEachIndexed { i, l -> line("lo$i", l, null) }
    }
    if (info.delay.isNotEmpty() || info.cancel.isNotEmpty()) {
        head("hd", "국내선 지연·결항 (지난해)")
        info.delay["지연율(퍼센트)"]?.let { line("d", "지연율", "$it%") }
        info.cancel["결항률(퍼센트)"]?.let { line("x", "결항률", "$it%") }
    }
}
