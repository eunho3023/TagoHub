package com.eunho.tagohub.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** PNG 바이트 → 그림 (웹·PC 각각: 둘 다 Skia) */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * 화면의 지도 칸. 웹은 카카오 지도(폰 앱과 같은 지도, 키·도메인이 맞을 때), 못 쓰면·PC 는 [MapView](OpenStreetMap).
 * 카카오 지도는 화면 위에 따로 뜨는 칸이라 그 위에 Compose 를 겹쳐 그리지 말 것 (카드 등은 지도 칸 밖에).
 */
@Composable
expect fun MapHost(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    onDot: (MapDot) -> Unit = {},
    onTap: () -> Unit = {},
)

/** 카카오 지도 확대 단계(1 가까움 … 14 멂) ↔ 웹 지도 확대(z) */
fun kakaoLevel(zoom: Int): Int = (20 - zoom).coerceIn(1, 14)
fun zoomOfLevel(level: Int): Int = (20 - level).coerceIn(MIN_ZOOM, MAX_ZOOM)

/** 색 → "#rrggbb" */
fun Color.hex(): String {
    val v = (red * 255).roundToInt() shl 16 or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()
    return "#" + v.toString(16).padStart(6, '0')
}

/**
 * 지도 바탕 그림: OpenStreetMap 공식 타일 (키 없이, 브라우저에서 받을 수 있음; 화면에 출처를 표시해야 함).
 * 이용 규칙상 많이 쓰면 다른 타일 서버로 바꿔야 함 — 주소는 여기 한 곳.
 */
private const val TILE = 256
@Suppress("UNUSED_PARAMETER")
private fun tileUrl(z: Int, x: Int, y: Int, dark: Boolean) = "https://tile.openstreetmap.org/$z/$x/$y.png"
const val MAP_ATTRIBUTION = "© OpenStreetMap contributors"

const val MIN_ZOOM = 6
const val MAX_ZOOM = 19

/** 위경도 ↔ 지도 픽셀 (웹 메르카토르, 확대 [z] 에서 세계 전체 폭 = 256·2^z) */
object Mercator {
    fun x(lng: Double, z: Int) = (lng + 180) / 360 * TILE * 2.0.pow(z)
    fun y(lat: Double, z: Int): Double {
        val r = lat * PI / 180
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * TILE * 2.0.pow(z)
    }
    fun lng(x: Double, z: Int) = x / (TILE * 2.0.pow(z)) * 360 - 180
    fun lat(y: Double, z: Int): Double {
        val n = PI - 2 * PI * y / (TILE * 2.0.pow(z))
        return atan(sinh(n)) * 180 / PI
    }

    /** 이 점들이 [w]×[h] 화면에 다 들어오는 가장 큰 확대 */
    fun fitZoom(pts: List<Pair<Double, Double>>, w: Int, h: Int, pad: Int = 48): Int {
        if (pts.size < 2 || w <= 0 || h <= 0) return 15
        for (z in MAX_ZOOM - 2 downTo MIN_ZOOM) {
            val xs = pts.map { x(it.second, z) }
            val ys = pts.map { y(it.first, z) }
            if (xs.max() - xs.min() <= w - pad * 2 && ys.max() - ys.min() <= h - pad * 2) return z
        }
        return MIN_ZOOM
    }
}

/** 지도 위치·확대 (화면이 바뀌어도 남게 바깥에서 들고 있음) */
class MapState(lat: Double, lng: Double, zoom: Int) {
    var lat by mutableDoubleStateOf(lat)
    var lng by mutableDoubleStateOf(lng)
    var zoom by mutableIntStateOf(zoom)
    var size by mutableStateOf(IntSize.Zero)
    /** 카카오 지도가 알려 준 실제 보이는 범위 (남, 서, 북, 동). 없으면 메르카토르로 계산 */
    var shownBounds by mutableStateOf<DoubleArray?>(null)
    /** 카카오 지도에 "이 점들이 다 보이게" 부탁 (지도가 처리하면 null 로) */
    var fitRequest by mutableStateOf<List<Pair<Double, Double>>?>(null)

    fun moveTo(lat: Double, lng: Double, zoom: Int = this.zoom) {
        this.lat = lat; this.lng = lng; this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** 이 점들이 다 보이게 */
    fun fit(pts: List<Pair<Double, Double>>) {
        if (pts.isEmpty()) return
        fitRequest = pts
        val z = Mercator.fitZoom(pts, size.width, size.height)
        val cx = (pts.minOf { Mercator.x(it.second, z) } + pts.maxOf { Mercator.x(it.second, z) }) / 2
        val cy = (pts.minOf { Mercator.y(it.first, z) } + pts.maxOf { Mercator.y(it.first, z) }) / 2
        moveTo(Mercator.lat(cy, z), Mercator.lng(cx, z), z)
    }

    /** 화면에 보이는 범위 (남, 서, 북, 동) */
    fun bounds(): DoubleArray {
        shownBounds?.let { return it }
        val cx = Mercator.x(lng, zoom); val cy = Mercator.y(lat, zoom)
        val hw = size.width / 2.0; val hh = size.height / 2.0
        return doubleArrayOf(Mercator.lat(cy + hh, zoom), Mercator.lng(cx - hw, zoom), Mercator.lat(cy - hh, zoom), Mercator.lng(cx + hw, zoom))
    }

    internal fun toScreen(lat: Double, lng: Double): Offset {
        val cx = Mercator.x(this.lng, zoom); val cy = Mercator.y(this.lat, zoom)
        return Offset((Mercator.x(lng, zoom) - cx + size.width / 2).toFloat(), (Mercator.y(lat, zoom) - cy + size.height / 2).toFloat())
    }

    internal fun pan(dx: Float, dy: Float) {
        val cx = Mercator.x(lng, zoom) - dx; val cy = Mercator.y(lat, zoom) - dy
        lat = Mercator.lat(cy, zoom).coerceIn(-85.0, 85.0); lng = Mercator.lng(cx, zoom)
    }

    /** [at] 화면 점을 고정한 채 한 단계 확대·축소 */
    internal fun zoomAt(delta: Int, at: Offset) {
        val nz = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nz == zoom) return
        val px = Mercator.x(lng, zoom) + at.x - size.width / 2; val py = Mercator.y(lat, zoom) + at.y - size.height / 2
        val aLat = Mercator.lat(py, zoom); val aLng = Mercator.lng(px, zoom)
        val nx = Mercator.x(aLng, nz) - at.x + size.width / 2; val ny = Mercator.y(aLat, nz) - at.y + size.height / 2
        zoom = nz; lat = Mercator.lat(ny, nz); lng = Mercator.lng(nx, nz)
    }
}

/** 지도에 그릴 선 (노선 경로) */
data class MapLine(val points: List<Pair<Double, Double>>, val color: Color, val width: Float = 5f)

/** 지도에 그릴 점 (정류장·버스). [id] 로 눌린 것을 알려 줌 */
data class MapDot(
    val id: String, val lat: Double, val lng: Double, val color: Color, val radius: Float = 6f,
    val ring: Color = Color.White, val label: String? = null,
)

/** 지도 바탕 그림 받기 (기억, 한 번에 6장까지) */
class TileLoader(private val http: HttpClient) {
    val images = mutableStateMapOf<String, ImageBitmap>()
    private val failed = HashSet<String>()
    private val loading = HashSet<String>()
    private val gate = Semaphore(6)

    suspend fun load(key: String, url: String) {
        if (key in images || key in failed || key in loading) return
        loading += key
        try {
            val img = gate.withPermit {
                val r = http.get(url) { header("Accept", "image/png") }
                if (r.status.isSuccess()) decodeImage(r.readRawBytes()) else null
            }
            if (img != null) {
                if (images.size > 400) images.keys.take(100).forEach { images.remove(it) }
                images[key] = img
            } else failed += key
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed += key
        } finally {
            loading -= key
        }
    }
}

/**
 * 지도: 끌어서 옮기기, 휠·두 번 누르기·+/- 로 확대, 선·점 그리기, 점 누르기 ([onDot]), 빈 곳 누르기 ([onTap]).
 */
@Composable
fun MapView(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    onDot: (MapDot) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val dark = LocalDark.current
    Box(modifier.clipToBounds().background(if (dark) Color(0xFF1B1F24) else Color(0xFFE9ECEF)).onSizeChanged { state.size = it }) {
        val z = state.zoom
        val size = state.size
        // 보이는 바탕 그림 범위
        val cx = Mercator.x(state.lng, z); val cy = Mercator.y(state.lat, z)
        val x0 = floor((cx - size.width / 2) / TILE).toInt(); val x1 = floor((cx + size.width / 2) / TILE).toInt()
        val y0 = floor((cy - size.height / 2) / TILE).toInt(); val y1 = floor((cy + size.height / 2) / TILE).toInt()
        val n = 1 shl z
        val visible = buildList {
            for (ty in y0..y1) for (tx in x0..x1) if (ty in 0 until n) add(Triple(tx, ty, ((tx % n) + n) % n))
        }
        for ((tx, ty, wx) in visible) {
            val key = "$z/$wx/$ty"
            LaunchedEffect(key) { tiles.load(key, tileUrl(z, wx, ty, dark)) }
        }
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(state) { detectDragGestures { change, drag -> change.consume(); state.pan(drag.x, drag.y) } }
                .pointerInput(state, dots) {
                    detectTapGestures(
                        onDoubleTap = { state.zoomAt(1, it) },
                        onTap = { p ->
                            val hit = dots.asReversed().firstOrNull { d ->
                                val s = state.toScreen(d.lat, d.lng)
                                (s - p).getDistance() <= d.radius + 10
                            }
                            if (hit != null) onDot(hit) else onTap()
                        },
                    )
                }
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Scroll) {
                                val c = e.changes.first()
                                val dy = c.scrollDelta.y
                                if (dy != 0f) state.zoomAt(if (dy < 0) 1 else -1, c.position)
                                c.consume()
                            }
                        }
                    }
                },
        ) {
            val ox = size.width / 2 - cx; val oy = size.height / 2 - cy
            for ((tx, ty, wx) in visible) {
                val img = tiles.images["$z/$wx/$ty"] ?: continue
                drawImage(img, dstOffset = IntOffset((tx * TILE + ox).roundToInt(), (ty * TILE + oy).roundToInt()), dstSize = IntSize(TILE, TILE))
            }
            // 어두운 화면: 밝은 바탕 그림을 살짝 어둡게
            if (dark) drawRect(Color.Black.copy(alpha = 0.35f))
            for (l in lines) {
                if (l.points.size < 2) continue
                val path = Path()
                l.points.forEachIndexed { i, (la, lo) ->
                    val p = state.toScreen(la, lo)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(l.width + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, l.color, style = Stroke(l.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (d in dots) {
                val p = state.toScreen(d.lat, d.lng)
                if (p.x < -20 || p.y < -20 || p.x > size.width + 20 || p.y > size.height + 20) continue
                drawCircle(d.ring, d.radius + 2.5f, p)
                drawCircle(d.color, d.radius, p)
            }
        }
        // 점 이름표 (버스 번호판 끝자리 등)
        for (d in dots) {
            val label = d.label ?: continue
            val p = state.toScreen(d.lat, d.lng)
            if (p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height) continue
            Text(
                label,
                Modifier.offset { IntOffset(p.x.roundToInt() + 10, p.y.roundToInt() - 22) }
                    .background(d.color, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
            )
        }
        // 확대·축소 단추
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            for ((t, dz) in listOf("+" to 1, "−" to -1)) {
                Box(
                    Modifier.padding(bottom = 6.dp).size(36.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .clickable { state.zoomAt(dz, Offset(size.width / 2f, size.height / 2f)) },
                    contentAlignment = Alignment.Center,
                ) { Text(t, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        Text(
            MAP_ATTRIBUTION,
            Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 10.sp, color = Color(0xFF333333),
        )
    }
}
