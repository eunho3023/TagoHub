package com.eunho.tagohub.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.eunho.tagohub.data.RouteDiagram
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import kotlinx.coroutines.delay

/** 노선 배치도: 정류장을 줄 세워 한눈에 보는 그림 (서울시 배치 좌표가 있는 노선은 그대로, 나머지는 ㄹ자). 정류장을 누르면 그 정류장으로 */
@Composable
fun RouteDiagramView(
    routeId: String,
    stops: List<RouteStop>,
    color: Color,
    /** 버스 위치: GPS 는 고정, GPS 없는 버스는 시간에 따라 정류장 사이를 움직인다 */
    busMotions: List<RouteLive.Motion>,
    focusedNodeId: String?,
    onStop: (RouteStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cells = remember(routeId, stops) {
        RouteDiagram.layout(routeId, stops.map { it.nodeId }, RouteDiagram.official(context), PER_ROW)
    }
    DiagramCanvas(stops, cells, color, busMotions, focusedNodeId, onStop, modifier)
}

private const val PER_ROW = 4

/** 배치도 애니메이션 프레임 간격 (30fps) */
private const val FRAME_MS = 33L

/**
 * 배치도는 두 겹의 Canvas 로 그린다.
 *  - 아래(정적): 경로 선, 정류장 점·이름, 방향 화살표, 기·종점 표시. 정류장·색·크기가 바뀔 때만 다시 그린다.
 *  - 위(동적): 버스 원만. 30fps 로 바뀌는 시각을 읽는 곳을 이 Canvas 로 한정해, 버스가 움직이는 동안
 *    정류장 이름 측정과 경로 Path 생성이 매 프레임 반복되지 않게 한다.
 */
@Composable
private fun DiagramCanvas(
    stops: List<RouteStop>,
    cells: List<RouteDiagram.Cell>,
    color: Color,
    busMotions: List<RouteLive.Motion>,
    focusedNodeId: String?,
    onStop: (RouteStop) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val rows = ((cells.maxOfOrNull { it.y } ?: 0f).toInt() + 1)
    val rowH = 92.dp
    val animated = busMotions.any { it.animated }
    // 동적 Canvas 만 이 값을 읽는다 (화면 전체 재구성 없이 버스 레이어만 무효화)
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(animated, lifecycleOwner) {
        if (!animated) return@LaunchedEffect
        // 화면이 보일 때만 돈다: 백그라운드에서 30fps 로 그리지 않는다
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(FRAME_MS)
                now.longValue = System.currentTimeMillis()
            }
        }
    }
    BoxWithConstraints(modifier.verticalScroll(rememberScrollState())) {
        val widthPx = constraints.maxWidth.toFloat()
        val rhPx = with(density) { rowH.toPx() }
        val geometry = remember(cells, widthPx, rhPx) { DiagramGeometry.of(cells, widthPx, rhPx) }
        // 정류장 이름은 폭이 같으면 매 프레임 다시 측정할 필요가 없다
        val labels = remember(stops, widthPx, ink, measurer) {
            val colW = (widthPx / PER_ROW).coerceAtLeast(1f)
            stops.map { s ->
                measurer.measure(
                    s.name,
                    TextStyle(fontSize = 11.sp, color = ink),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = (colW * 0.9f).toInt().coerceAtLeast(1)),
                )
            }
        }
        val endLabels = remember(geometry.points.size, color, measurer) {
            if (geometry.points.size < 2) emptyList()
            else listOf("기점", "종점").map { text ->
                measurer.measure(text, TextStyle(fontSize = 10.sp, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
        }
        val canvasModifier = Modifier.fillMaxWidth().height(rowH * (rows + 1))
        // 정적 레이어: 탭 처리도 여기에 둔다 (버스 레이어는 입력을 받지 않는다)
        Canvas(
            canvasModifier.pointerInput(cells, rhPx) {
                detectTapGestures { tap ->
                    val hit = cells.withIndex().minByOrNull { (_, c) -> (pos(size.width.toFloat(), c, rhPx) - tap).getDistance() }
                    if (hit != null && (pos(size.width.toFloat(), hit.value, rhPx) - tap).getDistance() < rhPx * 0.6f) stops.getOrNull(hit.index)?.let(onStop)
                }
            },
        ) {
            val pts = geometry.points
            geometry.routePath?.let { path ->
                drawPath(path, line, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, color.copy(alpha = 0.55f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            pts.forEachIndexed { i, p ->
                val s = stops.getOrNull(i) ?: return@forEachIndexed
                val focused = s.nodeId == focusedNodeId
                drawCircle(Color.White, radius = if (focused) 11.dp.toPx() else 8.dp.toPx(), center = p)
                drawCircle(if (focused) Color(0xFFE53935) else color, radius = if (focused) 9.dp.toPx() else 6.dp.toPx(), center = p)
                labels.getOrNull(i)?.let { label ->
                    drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + 12.dp.toPx()))
                }
            }
            geometry.arrows.forEach { drawPath(it, color) }
            // 기점·종점 표시
            if (endLabels.size == 2) {
                listOf(0 to 0, pts.lastIndex to 1).forEach { (i, k) ->
                    val t = endLabels[k]
                    drawText(t, topLeft = Offset(pts[i].x - t.size.width / 2f, pts[i].y - 24.dp.toPx()))
                }
            }
        }
        // 동적 레이어: 버스만. now 를 읽는 곳을 여기로 한정한다
        Canvas(canvasModifier) {
            if (geometry.points.isEmpty()) return@Canvas
            val pts = geometry.points
            val at = now.longValue
            busMotions.forEach { m ->
                val f = m.fraction(at)
                val i = kotlin.math.floor(f).toInt().coerceIn(0, pts.lastIndex)
                val t = (f - i).coerceIn(0f, 1f)
                val from = pts[i]
                val to = pts.getOrNull(i + 1) ?: from
                // 줄이 바뀌는 곳(다른 줄로 건너뜀)은 사이에 그리면 엉뚱한 곳이라 출발 정류장 쪽에 그림
                val p = if (kotlin.math.abs(from.y - to.y) > 1f) from else Offset(from.x + (to.x - from.x) * t, from.y)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = p)
                drawCircle(Color(0xFFFFB300), radius = 7.dp.toPx(), center = p)
            }
        }
    }
}

/** 배치도 정류장 자리: 폭·높이가 바뀔 때만 만든다 */
private class DiagramGeometry(
    val points: List<Offset>,
    val routePath: Path?,
    /** 정류장 사이 방향 화살표 (한 줄 안에서만) */
    val arrows: List<Path>,
) {
    companion object {
        fun of(cells: List<RouteDiagram.Cell>, width: Float, rowHeight: Float): DiagramGeometry {
            val pts = cells.map { pos(width, it, rowHeight) }
            val path = if (pts.size >= 2) {
                Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
            } else null
            val arrows = ArrayList<Path>(pts.size)
            val r = rowHeight / 92f * 4f // 4.dp 를 행 높이에 맞춘 값 (rowHeight 는 92.dp)
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                if (kotlin.math.abs(a.y - b.y) > 1f || a.x == b.x) continue
                val dir = if (b.x > a.x) 1f else -1f
                val m = Offset((a.x + b.x) / 2f, a.y)
                arrows += Path().apply {
                    moveTo(m.x + dir * r, m.y)
                    lineTo(m.x - dir * r * 0.6f, m.y - r)
                    lineTo(m.x - dir * r * 0.6f, m.y + r)
                    close()
                }
            }
            return DiagramGeometry(pts, path, arrows)
        }
    }
}

/** 셀(열·행) → 화면 좌표 */
private fun pos(width: Float, c: RouteDiagram.Cell, rowHeight: Float): Offset {
    val colW = width / PER_ROW
    return Offset(colW * (c.x + 0.5f).coerceAtMost(PER_ROW - 0.5f), rowHeight * (c.y + 0.5f))
}
