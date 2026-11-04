package com.eunho.tagohub.web

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/** kakaomap.js 의 window.TagoKakao */
external object TagoKakao {
    fun hasKey(): Int
    fun load(cb: (Int) -> Unit)
    fun show(x: Double, y: Double, w: Double, h: Double, lat: Double, lng: Double, level: Int, dotCb: (String) -> Unit, idleCb: (String) -> Unit, tapCb: () -> Unit)
    fun place(x: Double, y: Double, w: Double, h: Double)
    fun hide()
    fun view(lat: Double, lng: Double, level: Int)
    fun fit(json: String)
    fun lines(json: String)
    fun dots(json: String)
}

/** 카카오 지도 SDK 받기 (한 번): null 받는 중, true 됨, false 못 씀 → OpenStreetMap */
private object KakaoSdk {
    var ready by mutableStateOf<Boolean?>(null)
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (runCatching { TagoKakao.hasKey() }.getOrDefault(0) == 0) { ready = false; return }
        TagoKakao.load { ok -> ready = ok == 1 }
    }
}

@Composable
actual fun MapHost(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier,
    lines: List<MapLine>,
    dots: List<MapDot>,
    onDot: (MapDot) -> Unit,
    onTap: () -> Unit,
) {
    LaunchedEffect(Unit) { KakaoSdk.start() }
    when (KakaoSdk.ready) {
        true -> KakaoMap(state, modifier, lines, dots, onDot, onTap)
        false -> MapView(state, tiles, modifier, lines, dots, onDot, onTap)
        null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("지도를 불러오는 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun num(v: Double): String = v.toString()

private fun linesJson(lines: List<MapLine>) = lines.joinToString(",", "[", "]") { l ->
    "{\"color\":\"${l.color.hex()}\",\"width\":${l.width},\"pts\":" + l.points.joinToString(",", "[", "]") { "[${num(it.first)},${num(it.second)}]" } + "}"
}

private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

private fun dotsJson(dots: List<MapDot>) = dots.joinToString(",", "[", "]") { d ->
    "{\"id\":\"${esc(d.id)}\",\"lat\":${num(d.lat)},\"lng\":${num(d.lng)},\"color\":\"${d.color.hex()}\",\"ring\":\"${d.ring.hex()}\",\"r\":${d.radius}" +
        (d.label?.let { ",\"label\":\"${esc(it)}\"" } ?: "") + "}"
}

/** 카카오 지도 칸: Compose 화면 위 같은 자리에 지도 div 를 띄우고, 위치·선·점을 맞춰 줌 */
@Composable
private fun KakaoMap(state: MapState, modifier: Modifier, lines: List<MapLine>, dots: List<MapDot>, onDot: (MapDot) -> Unit, onTap: () -> Unit) {
    val density = LocalDensity.current.density
    var shown by remember { mutableStateOf(false) }
    // 지도가 마지막으로 알려 준 위치 (같은 값을 다시 보내 되돌이 없게)
    var last by remember { mutableStateOf(Triple(0.0, 0.0, 0)) }
    val curDots by rememberUpdatedState(dots)
    val curOnDot by rememberUpdatedState(onDot)
    val curOnTap by rememberUpdatedState(onTap)
    DisposableEffect(Unit) { onDispose { TagoKakao.hide() } }
    Box(
        modifier.onSizeChanged { state.size = it }.onGloballyPositioned { c ->
            val r = c.boundsInWindow()
            val x = r.left / density.toDouble(); val y = r.top / density.toDouble()
            val w = r.width / density.toDouble(); val h = r.height / density.toDouble()
            if (!shown) {
                TagoKakao.show(
                    x, y, w, h, state.lat, state.lng, kakaoLevel(state.zoom),
                    { id -> curDots.firstOrNull { it.id == id }?.let(curOnDot) },
                    { s ->
                        val v = s.split(",").mapNotNull { it.toDoubleOrNull() }
                        if (v.size == 7) {
                            val z = zoomOfLevel(v[2].toInt())
                            last = Triple(v[0], v[1], z)
                            state.lat = v[0]; state.lng = v[1]; state.zoom = z
                            state.shownBounds = doubleArrayOf(v[3], v[4], v[5], v[6])
                        }
                    },
                    { curOnTap() },
                )
                shown = true
            } else TagoKakao.place(x, y, w, h)
        },
    )
    LaunchedEffect(shown, lines) { if (shown) TagoKakao.lines(linesJson(lines)) }
    LaunchedEffect(shown, dots) { if (shown) TagoKakao.dots(dotsJson(dots)) }
    LaunchedEffect(shown, state.fitRequest) {
        val pts = state.fitRequest ?: return@LaunchedEffect
        if (!shown) return@LaunchedEffect
        TagoKakao.fit(pts.joinToString(",", "[", "]") { "[${num(it.first)},${num(it.second)}]" })
        state.fitRequest = null
    }
    // 화면 코드가 위치를 바꿨으면 (지도가 알려 준 것과 다르면) 지도도 옮김
    LaunchedEffect(shown, state.lat, state.lng, state.zoom) {
        if (!shown || state.fitRequest != null) return@LaunchedEffect
        val (la, lo, z) = last
        if (abs(la - state.lat) > 1e-6 || abs(lo - state.lng) > 1e-6 || z != state.zoom) TagoKakao.view(state.lat, state.lng, kakaoLevel(state.zoom))
    }
}
