package com.eunho.tagohub.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.RemoteLayouts
import com.eunho.tagohub.data.RouteDiagram
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import kotlinx.coroutines.delay

/**
 * 노선 배치도: 정류장을 줄 세워 한눈에 보는 그림.
 * 앱에 들어 있는 기본 배치 위에 관리자 페이지에서 저장한 배치(/w/layouts.tsv)를 덮어써서 그린다.
 * 정류장을 누르면 그 정류장으로.
 */
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
    // 서버 배치가 늦게 도착하면 값이 바뀌므로, 그때 다시 계산해 바로 반영한다
    val remote by RemoteLayouts.layouts.collectAsStateWithLifecycle()
    val cells = remember(routeId, stops, remote) {
        val merged = RemoteLayouts.overlay(RouteDiagram.official(context), remote)
        RouteDiagram.layout(
            routeId,
            stops.map { it.nodeId },
            merged,
            PER_ROW,
            acceptPartial = remote.containsKey(routeId.removePrefix("SEB")),
        )
    }
    DiagramCanvas(stops, cells, color, busMotions, focusedNodeId, onStop, modifier)
}

private const val PER_ROW = 4

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
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val rows = ((cells.maxOfOrNull { it.y } ?: 0f).toInt() + 1)
    val rowH = 92.dp
    // 배치도만 30fps 로 다시 그린다 (화면 전체 재구성 없이 Canvas draw 만 무효화)
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busMotions) {
        if (busMotions.any { it.animated }) {
            while (true) {
                delay(33)
                now.longValue = System.currentTimeMillis()
            }
        }
    }
    Box(modifier.verticalScroll(rememberScrollState())) {
        val pos = { w: Float, c: RouteDiagram.Cell, rh: Float ->
            val colW = w / PER_ROW
            Offset(colW * (c.x + 0.5f).coerceAtMost(PER_ROW - 0.5f), rh * (c.y + 0.5f))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(rowH * (rows + 1) * 1f)
                .pointerInput(cells) {
                    detectTapGestures { tap ->
                        val rh = rowH.toPx()
                        val hit = cells.withIndex().minByOrNull { (_, c) -> (pos(size.width.toFloat(), c, rh) - tap).getDistance() }
                        if (hit != null && (pos(size.width.toFloat(), hit.value, rh) - tap).getDistance() < rh * 0.6f) stops.getOrNull(hit.index)?.let(onStop)
                    }
                },
        ) {
            val rh = rowH.toPx()
            val w = size.width
            val pts = cells.map { pos(w, it, rh) }
            if (pts.size >= 2) {
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, line, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, color.copy(alpha = 0.55f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            val colW = w / PER_ROW
            pts.forEachIndexed { i, p ->
                val s = stops.getOrNull(i) ?: return@forEachIndexed
                val focused = s.nodeId == focusedNodeId
                drawCircle(Color.White, radius = if (focused) 11.dp.toPx() else 8.dp.toPx(), center = p)
                drawCircle(if (focused) Color(0xFFE53935) else color, radius = if (focused) 9.dp.toPx() else 6.dp.toPx(), center = p)
                val label = measurer.measure(
                    s.name,
                    TextStyle(fontSize = 11.sp, color = ink),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = (colW * 0.9f).toInt()),
                )
                drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + 12.dp.toPx()))
            }
            // 가는 방향: 정류장 사이 가운데에 작은 화살표 (한 줄 안에서만)
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                if (kotlin.math.abs(a.y - b.y) > 1f || a.x == b.x) continue
                val dir = if (b.x > a.x) 1f else -1f
                val m = Offset((a.x + b.x) / 2f, a.y)
                val r = 4.dp.toPx()
                val arrow = Path().apply { moveTo(m.x + dir * r, m.y); lineTo(m.x - dir * r * 0.6f, m.y - r); lineTo(m.x - dir * r * 0.6f, m.y + r); close() }
                drawPath(arrow, color)
            }
            // 기점·종점 표시
            if (pts.size >= 2) {
                listOf(0 to "기점", pts.lastIndex to "종점").forEach { (i, text) ->
                    val t = measurer.measure(text, TextStyle(fontSize = 10.sp, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    drawText(t, topLeft = Offset(pts[i].x - t.size.width / 2f, pts[i].y - 24.dp.toPx()))
                }
            }
            // 버스: 정류장 위, 또는 떠난 버스는 정류장 사이 (노란 원)
            if (pts.isNotEmpty()) {
                busMotions.forEach { m ->
                    val f = m.fraction(now.longValue)
                    val i = kotlin.math.floor(f).toInt().coerceIn(0, pts.lastIndex)
                    val t = (f - i).coerceIn(0f, 1f)
                    val from = pts[i]
                    val to = pts.getOrNull(i + 1) ?: from
                    // 줄이 바뀌는 곳(다른 줄로 건너뜀)은 사이에 그리면 엉뚱한 곳이라 출발 정류장 쪽에 그림
                    val at = if (kotlin.math.abs(from.y - to.y) > 1f) from else Offset(from.x + (to.x - from.x) * t, from.y)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = at)
                    drawCircle(Color(0xFFFFB300), radius = 7.dp.toPx(), center = at)
                }
            }
        }
    }
}
