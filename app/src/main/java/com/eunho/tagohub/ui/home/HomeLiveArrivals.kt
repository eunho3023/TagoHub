package com.eunho.tagohub.ui.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.core.AppState
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.ui.bus.arrivalsFor
import com.eunho.tagohub.ui.bus.currentLocation
import com.eunho.tagohub.ui.bus.hasLocationPermission
import com.eunho.tagohub.ui.bus.lastKnownLocation
import com.eunho.tagohub.ui.common.AnimatedValueText
import com.eunho.tagohub.ui.common.BoardDivider
import com.eunho.tagohub.ui.common.BoardLink
import com.eunho.tagohub.ui.common.BoardUpdated
import com.eunho.tagohub.ui.common.LiveBoard
import com.eunho.tagohub.ui.theme.BoardColor
import com.eunho.tagohub.ui.theme.NumberFont
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 실시간 카드에 그릴 정류장 하나: 정류장·남은 거리·지금 오는 노선 */
private data class LiveStopRow(
    val stop: BusStop,
    val meters: Int,
    val routes: List<RouteEta>,
)

/**
 * 지도 아래 「실시간 도착」 카드의 상태.
 * 위치 권한이 없거나 아직 위치를 모르는 때에도 카드 자리를 비우지 않고 안내를 보여 준다.
 */
private sealed interface LiveState {
    /** 위치와 도착 정보를 받는 중 */
    object Checking : LiveState

    /** 위치 권한이 없어 안내만 보여 주는 상태 */
    object NoPermission : LiveState

    /** 정류장과 도착 정보를 받음 (정류장이 없으면 빈 목록) */
    data class Ready(val stops: List<LiveStopRow>, val at: String) : LiveState

    /** 위치·자료 문제로 받지 못함 */
    data class Problem(val text: String) : LiveState
}

/**
 * 마지막으로 그린 카드. 휴대폰에도 저장해 앱을 닫았다 열어도 회색 막대 대신 이것을 바로 보여 주고, 뒤에서 새로 받아 바꿈.
 * 10분 안이면 지난 시간만큼 도착 시간을 줄이고(이미 지나갔을 버스는 뺌), 30분 안이면 노선만(시간 없이), 그보다 오래되면 쓰지 않음.
 */
private object LiveMemory {
    private const val AGE_MS = 10 * 60_000L
    private const val KEEP_MS = 30 * 60_000L
    private const val PREFS = "home_live"
    @Volatile private var last: Pair<Long, LiveState.Ready>? = null

    fun get(ctx: Context, now: Long = System.currentTimeMillis()): LiveState.Ready? {
        val (t, s) = last ?: load(ctx)?.also { last = it } ?: return null
        val age = now - t
        if (age < 0 || age >= KEEP_MS) return null
        val sec = if (age < AGE_MS) (age / 1000).toInt() else null
        val stops = s.stops.map { it.copy(routes = HomeText.aged(it.routes, sec)) }
        return if (stops.all { it.routes.isEmpty() }) null else s.copy(stops = stops)
    }

    fun put(ctx: Context, s: LiveState.Ready) {
        val now = System.currentTimeMillis()
        last = now to s
        runCatching {
            val js = org.json.JSONObject().put("t", now).put("at", s.at).put("s", org.json.JSONArray().apply {
                s.stops.forEach { r ->
                    put(org.json.JSONObject().put("c", r.stop.cityCode).put("id", r.stop.nodeId).put("n", r.stop.name)
                        .put("no", r.stop.nodeNo).put("la", r.stop.lat).put("lo", r.stop.lng).put("m", r.meters)
                        .put("r", org.json.JSONArray().apply {
                            r.routes.forEach { e -> put(org.json.JSONObject().put("no", e.routeNo).put("ty", e.routeType).put("sec", e.seconds)) }
                        }))
                }
            })
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", js.toString()).apply()
        }
    }

    private fun load(ctx: Context): Pair<Long, LiveState.Ready>? = runCatching {
        val js = org.json.JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null) ?: return null)
        fun org.json.JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
        fun org.json.JSONObject.num(k: String) = if (isNull(k)) null else optDouble(k)
        val arr = js.getJSONArray("s")
        val stops = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rs = o.getJSONArray("r")
            LiveStopRow(
                BusStop(o.getString("c"), o.getString("id"), o.getString("n"), o.str("no"), o.num("la"), o.num("lo")),
                o.getInt("m"),
                (0 until rs.length()).map { j ->
                    val e = rs.getJSONObject(j)
                    val sec = if (e.isNull("sec")) null else e.getInt("sec")
                    RouteEta(e.getString("no"), e.str("ty"), sec?.let(HomeText::shortEta), sec)
                },
            )
        }
        js.getLong("t") to LiveState.Ready(stops, js.getString("at"))
    }.getOrNull()
}

private fun clockNow(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())

/**
 * 지도 중심 홈의 실시간 도착 카드.
 * 가까운 정류장(700m 안) 두 곳에서 지금 오는 버스를 30초마다 새로 받아 보여 준다.
 * 조회는 기존 NearbyStops·Favorites 와 같은 함수(arrivalsFor · HomeText.routeEtas)를 그대로 쓴다.
 */
@Composable
internal fun LiveArrivalsCard(
    onStop: (BusStop) -> Unit,
    onShowMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 「새로고침」을 누르면 이 값이 바뀌어 produceState 가 처음부터 다시 받는다.
    var retry by remember { mutableStateOf(0) }
    val state by produceState<LiveState>(LiveMemory.get(context) ?: LiveState.Checking, retry) {
        if (!hasLocationPermission(context)) {
            value = LiveState.NoPermission
            return@produceState
        }
        var fails = 0
        // 처음 한 번은 지자체 도착이 3초 넘게 걸리는 정류장은 TAGO 도착을 먼저 (정류장 화면을 처음 열 때와 같음). 다음 갱신부터 지자체 도착
        var first = true
        while (true) {
            AppState.awaitForeground()
            val place = lastKnownLocation(context) ?: currentLocation(context)
            val db = StopDb.get(context)
            if (place == null || db == null) {
                value = LiveState.Problem(if (place == null) "위치를 확인하지 못했어요" else "정류장 자료를 아직 받지 못했어요")
                delay(30_000L)
                continue
            }
            val near = withContext(Dispatchers.IO) {
                db.near(place.latitude, place.longitude, 700.0, 12)
                    .filter { it.first.name.isNotBlank() }
                    .distinctBy { it.first.nodeId }
                    .take(2)
            }
            if (near.isEmpty()) {
                value = LiveState.Ready(emptyList(), clockNow())
                delay(45_000L)
                continue
            }
            // 정류장마다 동시에 받고(앱은 공공 API 동시 요청을 4개로 제한), 한 곳이 늦어도 나머지는 바로 보여 준다.
            // 못 받은 정류장은 지난번 것을 그대로 (예전엔 빈 목록으로 바뀌어 "오는 버스 없음"처럼 보였음)
            val before = (value as? LiveState.Ready)?.stops.orEmpty().associate { it.stop.nodeId to it.routes }
            val got = LinkedHashMap<String, List<RouteEta>>()
            var failed = false
            // 이미 보여 준 도착이 있으면(새로고침·저장본) 지역 도착을 끝까지 기다림 — 늦을 때마다 TAGO 로 바뀌어 정보가 달라 보이지 않게
            val quick = if (first && before.values.all { it.isEmpty() }) com.eunho.tagohub.ui.bus.QUICK_LOCAL_MS else null
            first = false
            // 받은 정류장부터 바로 그림 (예전엔 두 곳을 다 받을 때까지 회색 막대만 보였음). 아직 못 받은 곳은 지난번 것
            fun show() {
                val s = LiveState.Ready(
                    near.map { (s, meters) -> LiveStopRow(s, meters.toInt(), got[s.nodeId] ?: before[s.nodeId].orEmpty()) },
                    clockNow(),
                )
                value = s
                LiveMemory.put(context, s)
            }
            coroutineScope {
                near.forEach { (s, _) ->
                    launch {
                        got[s.nodeId] = when (val r = runCatching { arrivalsFor(Graph.repo, s, quickMs = quick) }.getOrNull()) {
                            is TagoResult.Success -> HomeText.routeEtas(r.data)
                            else -> { failed = true; before[s.nodeId].orEmpty() }
                        }
                        // 도착이 하나라도 생기면 그림. 둘 다 비면 다 받은 뒤 한 번 ("도착 정보 없음"이 잠깐 깜박이지 않게)
                        if (got[s.nodeId].orEmpty().isNotEmpty() || got.size == near.size) show()
                    }
                }
            }
            show()
            // 못 받은 정류장이 있으면 2·4·6초 뒤 다시, 다른 앱에 갔다 오면 바로
            fails = if (failed) fails + 1 else 0
            AppState.pause(AppState.retryMs(fails, 15_000L))   // 정류장 화면과 같은 15초 (예전엔 30초)
        }
    }
    LiveCard(
        state = state,
        onRetry = { retry += 1 },
        onStop = onStop,
        onShowMap = onShowMap,
        modifier = modifier,
    )
}

@Composable
private fun LiveCard(
    state: LiveState,
    onRetry: () -> Unit,
    onStop: (BusStop) -> Unit,
    onShowMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = BoardColor.Live
    // 정류장마다 두 노선까지, 모두 합쳐 네 줄까지만 (카드가 길어지지 않게)
    val rows = (state as? LiveState.Ready)?.stops
        ?.flatMap { st -> st.routes.take(2).map { r -> st to r } }
        ?.take(4)
        .orEmpty()
    // 판 아래 한 줄: 갱신 상태와 바로 가는 길. 줄이 없으면(안내 문구만 있으면) 그리지 않는다
    val footer: (@Composable RowScope.() -> Unit)? = if (rows.isEmpty()) null else {
        {
            BoardUpdated("15초마다 갱신")
            Spacer(Modifier.weight(1f))
            BoardLink("새로고침", onRetry)
            BoardLink("모두 보기", onShowMap)
        }
    }
    LiveBoard(
        title = "실시간 도착",
        modifier = modifier.fillMaxWidth(),
        sub = (state as? LiveState.Ready)?.let { "${it.at} 기준" },
        footer = footer,
    ) {
        when (state) {
            LiveState.Checking -> CheckingRows()
            LiveState.NoPermission -> LiveMessage(
                text = "위치를 허용하면 지금 오는 버스를 바로 보여드려요.",
                primary = "다시 확인" to onRetry,
                secondary = "지도에서 보기" to onShowMap,
            )

            is LiveState.Problem -> LiveMessage(
                text = state.text,
                primary = "다시 시도" to onRetry,
                secondary = "지도에서 보기" to onShowMap,
            )

            is LiveState.Ready -> {
                if (rows.isEmpty()) {
                    LiveMessage(
                        text = "가까운 정류장의 도착 정보가 아직 없어요.",
                        primary = "지도에서 보기" to onShowMap,
                    )
                } else {
                    rows.forEachIndexed { i, (st, route) ->
                        if (i > 0) BoardDivider()
                        LiveRow(st, route, live, onStop)
                    }
                }
            }
        }
    }
}

/** 카드에서 누르면 바로 그 정류장으로 가는 한 줄: 노선 알약 · 정류장 이름 · 남은 시간 */
@Composable
private fun LiveRow(st: LiveStopRow, route: RouteEta, live: Color, onStop: (BusStop) -> Unit) {
    Surface(
        onClick = { onStop(st.stop) },
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.heightIn(min = 28.dp).widthIn(min = 44.dp)
                    .background(busRouteColor(route.routeType), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    route.routeNo,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    st.stop.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BoardColor.On,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val where = if (st.meters >= 1000) "%.1fkm".format(st.meters / 1000.0) else "${st.meters}m"
                Text(
                    listOfNotNull(where, st.stop.nodeNo).joinToString(" · "),
                    fontSize = 11.sp,
                    color = BoardColor.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val eta = route.text
            if (eta != null) {
                AnimatedValueText(eta, label = "home-live-arrival") { value ->
                    Text(
                        value,
                        fontFamily = NumberFont,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = if (route.seconds != null && route.seconds <= 180) live else BoardColor.On,
                    )
                }
            }
        }
    }
}

/** 아직 받는 중: 글자 대신 회색 막대 두 줄 */
@Composable
private fun CheckingRows() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(2) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 44.dp, height = 26.dp)
                        .background(BoardColor.SurfaceAlt, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.weight(if (i == 0) 1f else 0.8f).height(13.dp)
                        .background(BoardColor.SurfaceAlt, RoundedCornerShape(7.dp)),
                )
                Spacer(Modifier.weight(0.5f))
                Box(
                    Modifier.size(width = 34.dp, height = 15.dp)
                        .background(BoardColor.SurfaceAlt, RoundedCornerShape(7.dp)),
                )
            }
        }
    }
}

/** 안내 문구와 (선택) 버튼 줄 */
@Composable
private fun LiveMessage(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = BoardColor.Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LivePill(primary.first, filled = true, onClick = primary.second)
            if (secondary != null) LivePill(secondary.first, filled = false, onClick = secondary.second)
        }
    }
}

@Composable
private fun LivePill(label: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) BoardColor.Live else Color.Transparent,
        border = if (filled) null else BorderStroke(1.dp, Color(0x33FFFFFF)),
    ) {
        Text(
            label,
            Modifier.heightIn(min = 36.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) BoardColor.OnLive else BoardColor.On,
        )
    }
}
