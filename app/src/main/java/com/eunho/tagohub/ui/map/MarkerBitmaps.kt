package com.eunho.tagohub.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 카카오맵 라벨용 그림 (기기 픽셀로 그림 → 라벨에서 setApplyDpScale(false)).
 * 정류장 표지판·역 사각형·노선 정류장 점·차량 원·내 위치·길찾기 표시점.
 */
class MarkerBitmaps(private val density: Float) {
    /**
     * 카카오맵 2.13.0 부터 비트맵이 premultiplied 가 아니어야 알파(투명) 영역이 제대로 적용된다 (공식 변경 내역).
     * Canvas 로는 premultiplied 비트맵에만 그릴 수 있어서, 다 그린 뒤 픽셀(getPixels 는 premultiply 를 푼 값)을
     * premultiplied=false 비트맵으로 옮긴다.
     */
    private fun forKakao(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.isPremultiplied = false
        out.setPixels(px, 0, w, 0, 0, w, h)
        b.recycle()
        return out
    }

    private fun px(dp: Float) = dp * density
    private fun bmp(wDp: Float, hDp: Float) =
        Bitmap.createBitmap(maxOf(1, px(wDp).toInt()), maxOf(1, px(hDp).toInt()), Bitmap.Config.ARGB_8888)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 버스 정류장 표지판: 짧은 기둥(아래 끝 = 정류장 위치) + 둥근 사각 표지판 + 흰 버스. anchor (0.5, 1) */
    fun stopSign(color: Int, scale: Float, highlight: Boolean): Bitmap {
        val sign = 15f * scale
        val pole = 6f * scale
        val pad = 2f * scale
        val b = bmp(sign + pad * 2, sign + pole + pad)
        val c = Canvas(b)
        val d = density * scale
        val cx = b.width / 2f
        val bottom = b.height.toFloat()
        val size = 15f * d
        val top = bottom - 6f * d - size
        // 기둥
        stroke.color = Color.rgb(85, 90, 98)
        stroke.strokeWidth = 2f * d
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, bottom - 1f * d, cx, bottom - 6f * d, stroke)
        // 표지판
        val r = RectF(cx - size / 2, top, cx + size / 2, top + size)
        fill.color = color
        c.drawRoundRect(r, 3.5f * d, 3.5f * d, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = (if (highlight) 2.5f else 1.5f) * d
        c.drawRoundRect(r, 3.5f * d, 3.5f * d, stroke)
        // 버스 그림
        val bw = size * 0.6f
        val bh = size * 0.48f
        val bx = cx - bw / 2
        val by = top + size * 0.2f
        fill.color = Color.WHITE
        c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 2f * d, 2f * d, fill)
        fill.color = color
        c.drawRect(RectF(bx + bw * 0.16f, by + bh * 0.18f, bx + bw * 0.84f, by + bh * 0.5f), fill)
        fill.color = Color.WHITE
        c.drawCircle(bx + bw * 0.27f, by + bh + 0.4f * d, 1.5f * d, fill)
        c.drawCircle(bx + bw * 0.73f, by + bh + 0.4f * d, 1.5f * d, fill)
        return forKakao(b)
    }

    /** 지하철역: 흰 사각형 + 노선색 테두리. anchor (0.5, 0.5) */
    fun station(ring: Int, scale: Float, highlight: Boolean): Bitmap {
        val half = 6f * scale
        val b = bmp(half * 2 + 4f, half * 2 + 4f)
        val c = Canvas(b)
        val cx = b.width / 2f
        val cy = b.height / 2f
        val r = px(half)
        fill.color = if (highlight) ring else Color.WHITE
        c.drawRect(cx - r, cy - r, cx + r, cy + r, fill)
        stroke.color = if (highlight) Color.WHITE else ring
        stroke.strokeWidth = px(if (highlight) 3f else 2.5f)
        c.drawRect(cx - r, cy - r, cx + r, cy + r, stroke)
        return forKakao(b)
    }

    /** 노선 위 정류장 점: 흰 원 + 노선색 테두리 (선택한 정류장은 크게 채움). anchor (0.5, 0.5) */
    fun routeDot(color: Int, focus: Boolean): Bitmap {
        val rDp = if (focus) 7f else 4.5f
        val b = bmp(rDp * 2 + 4f, rDp * 2 + 4f)
        val c = Canvas(b)
        val cx = b.width / 2f
        fill.color = if (focus) color else Color.WHITE
        c.drawCircle(cx, cx, px(rDp), fill)
        stroke.color = if (focus) Color.WHITE else color
        stroke.strokeWidth = px(2f)
        c.drawCircle(cx, cx, px(rDp), stroke)
        return forKakao(b)
    }

    /** 버스·열차: 색 원 + 흰 테두리 + 가운데 글자(없으면 흰 점). anchor (0.5, 0.5) */
    fun vehicle(color: Int, glyph: String, selected: Boolean): Bitmap {
        val rDp = if (selected) 13f else 10f
        val b = bmp(rDp * 2 + 4f, rDp * 2 + 4f)
        val c = Canvas(b)
        val cx = b.width / 2f
        fill.color = color
        c.drawCircle(cx, cx, px(rDp), fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = px(if (selected) 3f else 2f)
        c.drawCircle(cx, cx, px(rDp), stroke)
        if (glyph.isBlank()) {
            fill.color = Color.WHITE
            c.drawCircle(cx, cx, px(3f), fill)
        } else {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = px(if (selected) 12f else 10f)
                isFakeBoldText = true
            }
            c.drawText(glyph, cx, cx - (t.ascent() + t.descent()) / 2, t)
        }
        return forKakao(b)
    }

    /** 내 위치: 파란 원 + 흰 테두리 */
    fun me(): Bitmap {
        val b = bmp(20f, 20f)
        val c = Canvas(b)
        val cx = b.width / 2f
        fill.color = Color.rgb(26, 115, 232)
        c.drawCircle(cx, cx, px(8f), fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = px(2.5f)
        c.drawCircle(cx, cx, px(8f), stroke)
        return forKakao(b)
    }

    /** 꾹 누른 곳 핀: 빨간 물방울 + 흰 점. anchor (0.5, 1) */
    fun pin(): Bitmap {
        val b = bmp(26f, 34f)
        val c = Canvas(b)
        val cx = b.width / 2f
        val r = px(11f)
        val top = px(2f)
        val p = Path().apply {
            moveTo(cx, b.height.toFloat() - px(1f))
            lineTo(cx - r * 0.72f, top + r * 1.55f)
            lineTo(cx + r * 0.72f, top + r * 1.55f)
            close()
        }
        fill.color = Color.rgb(229, 57, 53)
        c.drawPath(p, fill)
        c.drawCircle(cx, top + r, r, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, top + r, r * 0.42f, fill)
        return forKakao(b)
    }

    /** 길찾기 표시점 (출발·환승·도착) */
    fun tripMark(color: Int): Bitmap {
        val b = bmp(18f, 18f)
        val c = Canvas(b)
        val cx = b.width / 2f
        fill.color = color
        c.drawCircle(cx, cx, px(7f), fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = px(2.5f)
        c.drawCircle(cx, cx, px(7f), stroke)
        return forKakao(b)
    }

    /**
     * 노선 진행 방향 화살표 (RouteLinePattern). 경로 패턴 이미지는 위쪽이 진행 방향이라 '^' 로 그린다.
     * (카카오 SDK 는 패턴 이미지 크기에 지도 dp 값을 곱하므로 dp 1배 크기로 만든다)
     */
    fun arrowPattern(): Bitmap {
        val w = 10
        val h = 8
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Path().apply {
            moveTo(1f, h - 1.5f)
            lineTo(w / 2f, 1.5f)
            lineTo(w - 1f, h - 1.5f)
        }
        stroke.color = Color.WHITE
        stroke.strokeWidth = 1.8f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(p, stroke)
        return forKakao(b)
    }

    /** 걷기 점선용 점 (RouteLinePattern) */
    fun walkDot(color: Int): Bitmap {
        val b = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        fill.color = color
        c.drawCircle(3f, 3f, 2.6f, fill)
        return forKakao(b)
    }
}
