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
import com.eunho.tagohub.data.RouteDiagram
import com.eunho.tagohub.data.RouteStop

/** 노선 배치도: 정류장을 줄 세워 한눈에 보는 그림 (서울시 배치 좌표가 있는 노선은 그대로, 나머지는 ㄹ자). 정류장을 누르면 그 정류장으로 */
@Composable
fun RouteDiagramView(
    routeId: String,
    stops: List<RouteStop>,
    color: Color,
    busOrds: Set<Int>,
    focusedNodeId: String?,
    onStop: (RouteStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cells = remember(routeId, stops) {
        RouteDiagram.layout(routeId, stops.map { it.nodeId }, RouteDiagram.official(context), PER_ROW)
    }
    DiagramCanvas(stops, cells, color, busOrds, focusedNodeId, onStop, modifier)
}

private const val PER_ROW = 4

@Composable
private fun DiagramCanvas(
    stops: List<RouteStop>,
    cells: List<RouteDiagram.Cell>,
    color: Color,
    busOrds: Set<Int>,
    focusedNodeId: String?,
    onStop: (RouteStop) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val rows = ((cells.maxOfOrNull { it.y } ?: 0f).toInt() + 1)
    val rowH = 92.dp
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
                if (s.ord in busOrds) drawCircle(Color(0xFFFFB300), radius = 4.dp.toPx(), center = p)
                val label = measurer.measure(
                    s.name,
                    TextStyle(fontSize = 11.sp, color = ink),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = (colW * 0.9f).toInt()),
                )
                drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + 12.dp.toPx()))
            }
        }
    }
}
