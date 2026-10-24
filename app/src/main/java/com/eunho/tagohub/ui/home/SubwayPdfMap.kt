package com.eunho.tagohub.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 앱에 든 서울교통공사 공식 노선도(PDF, assets/seoul_metro_map.pdf) 한 쪽을 그림으로 바꿔 주는 도구 */
internal class MetroPdf(context: Context) {
    private val fd: ParcelFileDescriptor
    private val renderer: PdfRenderer
    private val page: PdfRenderer.Page
    val pageWidth: Int
    val pageHeight: Int

    init {
        // PdfRenderer 는 파일이 필요해서 처음 한 번 캐시 폴더에 풀어 둠
        val file = File(context.cacheDir, "seoul_metro_map.pdf")
        if (!file.exists() || file.length() == 0L) {
            context.assets.open("seoul_metro_map.pdf").use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        }
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        page = renderer.openPage(0)
        pageWidth = page.width
        pageHeight = page.height
    }

    /** [matrix](쪽 좌표 → 그림 픽셀)로 [width]x[height] 그림을 그림. 한 번에 하나씩만 */
    @Synchronized
    fun render(width: Int, height: Int, matrix: Matrix): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    @Synchronized
    fun close() {
        runCatching { page.close() }
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}

/**
 * 서울교통공사 공식 노선도(수도권). 두 손가락으로 확대·이동하며, 손을 떼면 보이는 부분을 그 배율로 다시 그려서
 * (벡터 PDF 라) 많이 확대해도 선명하다. 움직이는 동안은 미리 그려 둔 전체 그림을 늘려서 보여 줌.
 */
@Composable
fun SubwayPdfMap(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pdf by produceState<MetroPdf?>(null) { value = withContext(Dispatchers.IO) { runCatching { MetroPdf(context) }.getOrNull() } }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }

    Box(modifier.background(Color.White)) {
        val doc = pdf
        if (doc == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
            if (size.width == 0 || size.height == 0) return@Box
            val w = size.width
            val h = size.height
            // 쪽을 화면에 꼭 맞게 놓는 배율과 가운데 맞춤 여백
            val fit = min(w.toFloat() / doc.pageWidth, h.toFloat() / doc.pageHeight)
            val padX = (w - doc.pageWidth * fit) / 2f
            val padY = (h - doc.pageHeight * fit) / 2f

            // 전체를 미리 한 번 (움직이는 동안 보여 줄 그림)
            val baseScale = 2048f / max(doc.pageWidth, doc.pageHeight)
            val base by produceState<Bitmap?>(null, doc) {
                value = withContext(Dispatchers.Default) {
                    doc.render((doc.pageWidth * baseScale).roundToInt(), (doc.pageHeight * baseScale).roundToInt(), Matrix().apply { setScale(baseScale, baseScale) })
                }
            }

            // 확대한 뒤 멈추면 보이는 부분만 선명하게 다시
            var sharp by remember { mutableStateOf<Triple<Bitmap, Float, Offset>?>(null) }
            LaunchedEffect(scale, offset, size, doc) {
                delay(220)
                val s = scale
                val o = offset
                val m = Matrix().apply {
                    setScale(fit * s, fit * s)
                    postTranslate(padX * s + o.x, padY * s + o.y)
                }
                val bmp = withContext(Dispatchers.Default) { doc.render(w, h, m) }
                sharp = Triple(bmp, s, o)
            }

            fun clamp(s: Float, o: Offset): Offset {
                val cw = doc.pageWidth * fit * s
                val ch = doc.pageHeight * fit * s
                fun axis(v: Float, view: Int, content: Float, pad: Float): Float =
                    if (content <= view) (view - content) / 2f - pad * s   // 화면보다 작으면 가운데
                    else v.coerceIn(view - content - pad * s, -pad * s)
                return Offset(axis(o.x, w, cw, padX), axis(o.y, h, ch, padY))
            }

            Canvas(
                Modifier.fillMaxSize().pointerInput(doc, size) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 10f)
                        val real = ns / scale
                        offset = clamp(ns, (offset - centroid) * real + centroid + pan)
                        scale = ns
                    }
                },
            ) {
                val cur = sharp
                if (cur != null && cur.second == scale && cur.third == offset) {
                    drawImage(cur.first.asImageBitmap(), dstOffset = IntOffset.Zero, dstSize = IntSize(w, h))
                } else {
                    base?.let { b ->
                        drawImage(
                            b.asImageBitmap(),
                            dstOffset = IntOffset((padX * scale + offset.x).roundToInt(), (padY * scale + offset.y).roundToInt()),
                            dstSize = IntSize((doc.pageWidth * fit * scale).roundToInt(), (doc.pageHeight * fit * scale).roundToInt()),
                        )
                    }
                }
            }
        }
    }
}
