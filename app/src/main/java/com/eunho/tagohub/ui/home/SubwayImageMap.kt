package com.eunho.tagohub.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 노선도 그림에서 역 이름이 있는 자리: 이름과, 그림 크기에 대한 위치 비율(0~1) */
data class MapHotspot(val name: String, val x: Float, val y: Float)

object MetroMapAsset {
    const val IMAGE = "seoul_metro_map.jpg"
    const val HOTSPOTS = "seoul_metro_map_hotspots.tsv"

    /** "역이름 \t x \t y" 줄들 (pipeline/build_metro_map_hotspots.py 가 만듦) */
    fun parseHotspots(text: String): List<MapHotspot> = text.lineSequence().mapNotNull { line ->
        val c = line.split('\t')
        if (c.size < 3) return@mapNotNull null
        val x = c[1].toFloatOrNull() ?: return@mapNotNull null
        val y = c[2].toFloatOrNull() ?: return@mapNotNull null
        MapHotspot(c[0].trim(), x, y).takeIf { it.name.isNotEmpty() }
    }.toList()

    /**
     * [tap] (그림에서 누른 자리, 0~1 비율) 에서 [radius] (같은 비율) 안의 가장 가까운 역. 없으면 null.
     * 가로·세로 비율이 다른 그림이면 [aspect] (가로/세로)로 거리를 맞춰서 잼.
     */
    fun nearest(spots: List<MapHotspot>, tapX: Float, tapY: Float, radius: Float, aspect: Float = 1f): MapHotspot? =
        spots.minByOrNull { val dx = (it.x - tapX) * aspect; val dy = it.y - tapY; dx * dx + dy * dy }
            ?.takeIf { val dx = (it.x - tapX) * aspect; val dy = it.y - tapY; dx * dx + dy * dy <= radius * radius }
}

/** 큰 노선도 그림을 필요한 부분만 읽어 오는 도구 (그림 전체를 메모리에 올리지 않음) */
private class MetroImage(context: Context) {
    private val decoder: BitmapRegionDecoder =
        context.assets.open(MetroMapAsset.IMAGE).use { BitmapRegionDecoder.newInstance(it, false)!! }
    val width = decoder.width
    val height = decoder.height

    @Synchronized
    fun decode(region: Rect, sample: Int): Bitmap? = runCatching {
        decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = max(1, sample); inPreferredConfig = Bitmap.Config.RGB_565 })
    }.getOrNull()

    @Synchronized
    fun close() {
        runCatching { decoder.recycle() }
    }
}

/**
 * 서울교통공사 수도권 노선도(그림). 두 손가락으로 확대·이동하고, 멈추면 보이는 부분만 그 배율로 다시 읽어 선명하게 보여 준다.
 * 역 이름 근처를 누르면 [onStation] 에 그 역 이름을 넘긴다.
 */
@Composable
fun SubwayImageMap(onStation: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val image by produceState<MetroImage?>(null) { value = withContext(Dispatchers.IO) { runCatching { MetroImage(context) }.getOrNull() } }
    val spots by produceState(emptyList<MapHotspot>()) {
        value = withContext(Dispatchers.IO) { runCatching { MetroMapAsset.parseHotspots(context.assets.open(MetroMapAsset.HOTSPOTS).bufferedReader().readText()) }.getOrDefault(emptyList()) }
    }
    // 값을 잡아 두고 닫음 (onDispose 안에서 위임 값을 읽으면 '그때의 값'이라 방금 연 것을 닫을 수 있음)
    val opened = image
    DisposableEffect(opened) { onDispose { opened?.close() } }

    Box(modifier.background(Color.White)) {
        val img = image
        if (img == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var picked by remember { mutableStateOf<MapHotspot?>(null) }
        Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
            if (size.width == 0 || size.height == 0) return@Box
            val w = size.width
            val h = size.height
            val fit = min(w.toFloat() / img.width, h.toFloat() / img.height)   // 그림 한 픽셀이 화면에서 몇 픽셀이 되는지 (배율 1일 때)
            val padX = (w - img.width * fit) / 2f
            val padY = (h - img.height * fit) / 2f

            // 전체를 작게 한 번 (움직이는 동안 보여 줌)
            val base by produceState<Bitmap?>(null, img) {
                value = withContext(Dispatchers.Default) {
                    var sample = 1
                    while (max(img.width, img.height) / sample > 2048) sample *= 2
                    img.decode(Rect(0, 0, img.width, img.height), sample)
                }
            }

            // 멈추면 보이는 부분만 선명하게
            class Sharp(val bmp: Bitmap, val scale: Float, val offset: Offset, val dst: Rect)
            var sharp by remember { mutableStateOf<Sharp?>(null) }
            LaunchedEffect(scale, offset, size, img) {
                delay(220)
                val s = scale
                val o = offset
                val k = fit * s                                   // 그림 픽셀 → 화면 픽셀
                val left = -(padX * s + o.x) / k
                val top = -(padY * s + o.y) / k
                val vis = Rect(max(0, left.toInt()), max(0, top.toInt()), min(img.width, (left + w / k).toInt() + 1), min(img.height, (top + h / k).toInt() + 1))
                if (vis.width() <= 0 || vis.height() <= 0) return@LaunchedEffect
                var sample = 1
                while (vis.width() / (sample * 2) >= w && vis.height() / (sample * 2) >= h) sample *= 2   // 화면보다 커지지 않을 만큼만 줄여서 읽음
                val bmp = withContext(Dispatchers.Default) { img.decode(vis, sample) } ?: return@LaunchedEffect
                val dst = Rect(
                    (vis.left * k + padX * s + o.x).roundToInt(), (vis.top * k + padY * s + o.y).roundToInt(),
                    (vis.right * k + padX * s + o.x).roundToInt(), (vis.bottom * k + padY * s + o.y).roundToInt(),
                )
                sharp = Sharp(bmp, s, o, dst)
            }

            fun clamp(s: Float, o: Offset): Offset {
                val cw = img.width * fit * s
                val ch = img.height * fit * s
                fun axis(v: Float, view: Int, content: Float, pad: Float): Float =
                    if (content <= view) (view - content) / 2f - pad * s
                    else v.coerceIn(view - content - pad * s, -pad * s)
                return Offset(axis(o.x, w, cw, padX), axis(o.y, h, ch, padY))
            }

            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(img, size) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val ns = (scale * zoom).coerceIn(1f, 8f)
                            val real = ns / scale
                            offset = clamp(ns, (offset - centroid) * real + centroid + pan)
                            scale = ns
                        }
                    }
                    .pointerInput(img, size, spots) {
                        detectTapGestures { tap ->
                            // 누른 자리 → 그림 안의 비율 → 가까운 역 이름 (손가락 크기만큼)
                            val k = fit * scale
                            val ix = (tap.x - padX * scale - offset.x) / k
                            val iy = (tap.y - padY * scale - offset.y) / k
                            val radius = (30f * density) / k / img.width        // 30dp 를 그림 폭에 대한 비율로
                            MetroMapAsset.nearest(spots, ix / img.width, iy / img.height, radius, aspect = img.width.toFloat() / img.height)?.let {
                                picked = it
                                onStation(it.name)
                            }
                        }
                    },
            ) {
                val cur = sharp
                if (cur != null && cur.scale == scale && cur.offset == offset) {
                    // 읽어 둔 선명한 부분을 제 자리에 (그림 가장자리 밖은 흰 바탕)
                    drawImage(cur.bmp.asImageBitmap(), dstOffset = IntOffset(cur.dst.left, cur.dst.top), dstSize = IntSize(cur.dst.width(), cur.dst.height()))
                } else {
                    base?.let { b ->
                        drawImage(
                            b.asImageBitmap(),
                            dstOffset = IntOffset((padX * scale + offset.x).roundToInt(), (padY * scale + offset.y).roundToInt()),
                            dstSize = IntSize((img.width * fit * scale).roundToInt(), (img.height * fit * scale).roundToInt()),
                        )
                    }
                }
                // 방금 누른 역 표시 (고리)
                picked?.let { p ->
                    val k = fit * scale
                    val cx = padX * scale + offset.x + p.x * img.width * k
                    val cy = padY * scale + offset.y + p.y * img.height * k
                    drawCircle(Color(0xFF1A73E8), radius = 18f * density, center = Offset(cx, cy), style = Stroke(width = 3f * density))
                }
            }
        }
    }
}
