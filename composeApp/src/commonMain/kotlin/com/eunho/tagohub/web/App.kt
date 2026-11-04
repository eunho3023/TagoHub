package com.eunho.tagohub.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.PassedBus
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 도착 정보를 다시 받는 간격 (폰 앱과 같게 15초) */
private const val REFRESH_MS = 15_000L

@OptIn(ExperimentalTime::class)
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** 모든 글자 모양에 [font] 를 씀 */
private fun Typography.withFont(font: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = font)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

/**
 * 웹·PC 버전 첫 화면: 정류장 이름으로 찾고, 고르면 실시간 도착 (15초마다 새로, 남은 시간은 매초 줄어듦).
 * [font] 는 한글 글꼴 (웹은 브라우저 글꼴을 못 써서 따로 받음, PC 는 null → 시스템 글꼴).
 */
@Composable
fun App(repo: WebRepo, font: FontFamily? = null) {
    val base = MaterialTheme.typography
    MaterialTheme(typography = if (font == null) base else base.withFont(font)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(16.dp)) {
                var picked by remember { mutableStateOf<WebStop?>(null) }
                val stop = picked
                if (stop == null) SearchPane(repo) { picked = it } else StopPane(repo, stop) { picked = null }
            }
        }
    }
}

@Composable
private fun SearchPane(repo: WebRepo, onPick: (WebStop) -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<WebStop>>(emptyList()) }
    var message by remember { mutableStateOf<String?>("정류장 이름을 입력해 주세요") }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            result = emptyList()
            message = "정류장 이름을 입력해 주세요"
            return@LaunchedEffect
        }
        delay(250)
        message = "찾는 중..."
        runCatching { repo.search(query) }
            .onSuccess { result = it; message = if (it.isEmpty()) "찾는 정류장이 없어요" else null }
            .onFailure { result = emptyList(); message = "정류장 자료를 받지 못했어요" }
    }
    Text("TagoHub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.padding(4.dp))
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("정류장 찾기") }, singleLine = true)
    message?.let { Text(it, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(result, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 10.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                var city by remember(s.city) { mutableStateOf<String?>(null) }
                LaunchedEffect(s.city) { city = repo.cityName(s.city) }
                Text(
                    listOfNotNull(city, s.no?.let { "정류장 번호 $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StopPane(repo: WebRepo, stop: WebStop, onBack: () -> Unit) {
    var state by remember(stop.id) { mutableStateOf<Arrivals?>(null) }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(stop.id) {
        while (true) {
            state = repo.arrivals(stop, nowMs())
            delay(REFRESH_MS)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMs()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onBack) { Text("← 다시 찾기") }
    }
    Text(stop.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    stop.no?.let { Text("정류장 번호 $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.padding(6.dp))
    when (val s = state) {
        null -> Text("도착 정보를 받는 중...")
        Arrivals.Unsupported -> Text("이 지역(서울·경기 등)의 실시간 도착은 웹·PC 버전에서 아직 볼 수 없어요. 폰 앱에서 볼 수 있어요.")
        is Arrivals.Failed -> Text("도착 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        is Arrivals.Ok -> {
            val elapsed = ((now - s.atMs) / 1000).toInt().coerceAtLeast(0)
            val shown = s.list.filterNot { PassedBus.passed(it, elapsed) }
            if (shown.isEmpty()) Text("지금 도착 예정인 버스가 없어요")
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { it.routeId + "|" + it.arrSeconds }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.routeNo, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(ArrivalText.line(a, elapsed), style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
