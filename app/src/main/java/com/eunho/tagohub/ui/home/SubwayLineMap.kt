package com.eunho.tagohub.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.LineGeo
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.ui.theme.subwayLineColor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 지하철 노선도: 한 권역(수도권·부산·대구·광주·대전)의 모든 노선을 노선 색으로 그림.
 * 두 손가락으로 확대·이동하고, 확대하면 역 이름이 보임. 역을 누르면 [onStation].
 * [highlight] 노선이 있으면 그 노선만 진하게.
 */
@Composable
fun SubwayLineMap(
    lines: List<LineGeo>,
    highlight: String?,
    onStation: (MetroStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var scale by remember(lines) { mutableFloatStateOf(1f) }
    var offset by remember(lines) { mutableStateOf(Offset.Zero) }
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceVariant

    // 경도·위도를 평면 좌표로 (경도는 위도에 따라 줄어듦)
    val all = remember(lines) { lines.flatMap { it.stations } }
    val bounds = remember(all) {
        if (all.isEmpty()) null else {
            val midLat = all.map { it.lat }.average()
            val k = cos(Math.toRadians(midLat))
            val minX = all.minOf { it.lng * k }; val maxX = all.maxOf { it.lng * k }
            val minY = all.minOf { -it.lat }; val maxY = all.maxOf { -it.lat }
            floatArrayOf(minX.toFloat(), maxX.toFloat(), minY.toFloat(), maxY.toFloat(), k.toFloat())
        }
    }
    val transferNames = remember(lines) {
        val perName = HashMap<String, MutableSet<String>>()
        lines.forEach { l -> l.stations.forEach { perName.getOrPut(it.norm) { HashSet() } += l.name } }
        perName.filterValues { it.size > 1 }.keys
    }
    val colors = remember(lines) { lines.associate { it.name to subwayLineColor(it.name) } }

    Box(modifier.background(bg)) {
        if (bounds == null) return@Box
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(lines) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 24f)
                        val real = newScale / scale
                        offset = (offset - centroid) * real + centroid + pan
                        scale = newScale
                    }
                }
                .pointerInput(lines) {
                    detectTapGestures { tap ->
                        // 누른 곳에서 가장 가까운 역 (손가락 크기 정도 안에서)
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val pad = 24f * density
                        val spanX = max(bounds[1] - bounds[0], 1e-6f); val spanY = max(bounds[3] - bounds[2], 1e-6f)
                        val fit = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
                        var best: MetroStation? = null
                        var bestD = (28f * density) * (28f * density)
                        for (s in all) {
                            val sx = (pad + (s.lng.toFloat() * bounds[4] - bounds[0]) * fit) * scale + offset.x
                            val sy = (pad + (-s.lat.toFloat() - bounds[2]) * fit) * scale + offset.y
                            val d = (sx - tap.x) * (sx - tap.x) + (sy - tap.y) * (sy - tap.y)
                            if (d < bestD) { bestD = d; best = s }
                        }
                        best?.let(onStation)
                    }
                },
        ) {
            val w = size.width; val h = size.height
            val pad = 24f * density
            val spanX = max(bounds[1] - bounds[0], 1e-6f); val spanY = max(bounds[3] - bounds[2], 1e-6f)
            val fit = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            fun sx(s: MetroStation) = (pad + (s.lng.toFloat() * bounds[4] - bounds[0]) * fit) * scale + offset.x
            fun sy(s: MetroStation) = (pad + (-s.lat.toFloat() - bounds[2]) * fit) * scale + offset.y

            val lineW = (2.2f + min(scale, 6f) * 0.5f) * density
            for (l in lines) {
                val dim = highlight != null && highlight != l.name
                val c = colors.getValue(l.name).copy(alpha = if (dim) 0.14f else 1f)
                val path = Path()
                for ((a, b) in l.edges) {
                    path.moveTo(sx(a), sy(a)); path.lineTo(sx(b), sy(b))
                }
                drawPath(path, c, style = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val r = (2.4f + min(scale, 6f) * 0.6f) * density
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11f * density
                this.color = android.graphics.Color.argb((ink.alpha * 255).toInt(), (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
                setShadowLayer(3f * density, 0f, 0f, android.graphics.Color.argb((surface.alpha * 255).toInt(), (surface.red * 255).toInt(), (surface.green * 255).toInt(), (surface.blue * 255).toInt()))
            }
            val drawn = HashSet<String>()
            for (l in lines) {
                val dim = highlight != null && highlight != l.name
                val c = colors.getValue(l.name)
                for (s in l.stations) {
                    val x = sx(s); val y = sy(s)
                    if (x < -20f || y < -20f || x > w + 20f || y > h + 20f) continue
                    val transfer = s.norm in transferNames
                    drawCircle(surface, radius = r * (if (transfer) 1.5f else 1f), center = Offset(x, y))
                    drawCircle(
                        (if (transfer) ink else c).copy(alpha = if (dim) 0.2f else 1f),
                        radius = r * (if (transfer) 1.5f else 1f),
                        center = Offset(x, y),
                        style = Stroke(width = 1.6f * density),
                    )
                    // 확대하면 역 이름 (환승역은 덜 확대해도). 같은 역 이름은 한 번만
                    if (!dim && (scale >= 5f || (transfer && scale >= 2.5f)) && drawn.add(s.norm)) {
                        drawContext.canvas.nativeCanvas.drawText(s.norm, x + r + 3f * density, y + 4f * density, paint)
                    }
                }
            }
        }
    }
}
