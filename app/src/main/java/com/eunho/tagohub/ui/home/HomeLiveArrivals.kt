package com.eunho.tagohub.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.eunho.tagohub.ui.theme.LocalDarkTheme
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
    val state by produceState<LiveState>(LiveState.Checking, retry) {
        if (!hasLocationPermission(context)) {
            value = LiveState.NoPermission
            return@produceState
        }
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
            val got = LinkedHashMap<String, List<RouteEta>>()
            coroutineScope {
                near.forEach { (s, _) ->
                    launch {
                        got[s.nodeId] = when (val r = runCatching { arrivalsFor(Graph.repo, s) }.getOrNull()) {
                            is TagoResult.Success -> HomeText.routeEtas(r.data)
                            else -> emptyList()
                        }
                    }
                }
            }
            value = LiveState.Ready(
                near.map { (s, meters) -> LiveStopRow(s, meters.toInt(), got[s.nodeId].orEmpty()) },
                clockNow(),
            )
            delay(30_000L)
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
    val dark = LocalDarkTheme.current
    // 보드 위에서는 어두운 바탕과 대비가 큰 라임을 쓴다 (라이트·다크 공통)
    val live = Color(0xFFC9F751)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (dark) Color(0xFF121C2B) else Color(0xFF0B1420),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(live, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("실시간 도착", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEAF1FA))
                Spacer(Modifier.width(8.dp))
                // 받은 시각을 작게: 값이 늘 최신인지 눈으로 확인할 수 있게
                if (state is LiveState.Ready) {
                    Text("${state.at} 기준", fontSize = 12.sp, color = Color(0xFF93A3B8))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "새로고침",
                    Modifier
                        .heightIn(min = 32.dp)
                        .clickable(role = Role.Button, onClick = onRetry)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 4.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9DB4FF),
                )
            }
            Spacer(Modifier.height(6.dp))
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
                    // 정류장마다 두 노선까지, 모두 합쳐 네 줄까지만 (카드가 길어지지 않게)
                    val rows = state.stops.flatMap { st -> st.routes.take(2).map { r -> st to r } }.take(4)
                    if (rows.isEmpty()) {
                        LiveMessage(
                            text = "가까운 정류장의 도착 정보가 아직 없어요.",
                            primary = "지도에서 보기" to onShowMap,
                        )
                    } else {
                        rows.forEachIndexed { i, (st, route) ->
                            if (i > 0) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 14.dp),
                                    color = Color(0x1AFFFFFF),
                                )
                            }
                            LiveRow(st, route, live, onStop)
                        }
                        Text(
                            "내 주변 정류장 모두 보기",
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = onShowMap)
                                .heightIn(min = 38.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = 14.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9DB4FF),
                        )
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
                    color = Color(0xFFEAF1FA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val where = if (st.meters >= 1000) "%.1fkm".format(st.meters / 1000.0) else "${st.meters}m"
                Text(
                    listOfNotNull(where, st.stop.nodeNo).joinToString(" · "),
                    fontSize = 11.sp,
                    color = Color(0xFF93A3B8),
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
                        color = if (route.seconds != null && route.seconds <= 180) live else Color(0xFFEAF1FA),
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
                        .background(Color(0x14FFFFFF), RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.weight(if (i == 0) 1f else 0.8f).height(13.dp)
                        .background(Color(0x14FFFFFF), RoundedCornerShape(7.dp)),
                )
                Spacer(Modifier.weight(0.5f))
                Box(
                    Modifier.size(width = 34.dp, height = 15.dp)
                        .background(Color(0x14FFFFFF), RoundedCornerShape(7.dp)),
                )
            }
        }
    }
}

/** 안내 문구와 (선택) 버튼 줄 */
@Composable
private fun LiveMessage(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFB6C3D4))
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
        color = if (filled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (filled) null else BorderStroke(1.dp, Color(0x33FFFFFF)),
    ) {
        Text(
            label,
            Modifier.heightIn(min = 36.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) MaterialTheme.colorScheme.onSecondaryContainer else Color(0xFFEAF1FA),
        )
    }
}
