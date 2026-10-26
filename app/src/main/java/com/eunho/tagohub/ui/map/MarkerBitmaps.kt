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

    /**
     * 지하철 열차: 동그라미 대신 작은 전동차 모양 (노선색 몸통 + 흰 테두리, 앞유리, 아래 두 개의 바퀴).
     * [glyph] 가 있으면(급행·막차) 앞유리 자리에 글자. 선택하면 조금 크게. anchor (0.5, 0.5)
     */
    fun train(color: Int, glyph: String, selected: Boolean): Bitmap {
        val w = if (selected) 22f else 17f
        val h = if (selected) 26f else 20f
        val b = bmp(w + 4f, h + 4f)
        val c = Canvas(b)
        val left = px(2f)
        val top = px(2f)
        val right = px(2f + w)
        val bottom = px(2f + h)
        val corner = px(if (selected) 6f else 5f)
        fill.color = color
        c.drawRoundRect(left, top, right, bottom, corner, corner, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = px(if (selected) 2.5f else 2f)
        c.drawRoundRect(left, top, right, bottom, corner, corner, stroke)
        // 앞유리
        val wl = left + px(w * 0.18f)
        val wr = right - px(w * 0.18f)
        val wt = top + px(h * 0.16f)
        val wb = top + px(h * 0.52f)
        fill.color = Color.WHITE
        c.drawRoundRect(wl, wt, wr, wb, px(2f), px(2f), fill)
        if (glyph.isNotBlank()) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textAlign = Paint.Align.CENTER
                textSize = px(if (selected) 10f else 8f)
                isFakeBoldText = true
            }
            c.drawText(glyph, (wl + wr) / 2f, (wt + wb) / 2f - (t.ascent() + t.descent()) / 2, t)
        }
        // 전조등(바퀴) 두 개
        val ly = bottom - px(h * 0.2f)
        c.drawCircle(left + px(w * 0.3f), ly, px(if (selected) 2.2f else 1.8f), fill)
        c.drawCircle(right - px(w * 0.3f), ly, px(if (selected) 2.2f else 1.8f), fill)
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

    /** 내 위치 방향: 위(북쪽)를 향한 반투명 파란 부채꼴. 아래쪽 가운데가 내 위치 (anchor 0.5, 1). 라벨을 돌리면 방향이 바뀜 */
    fun meHeading(): Bitmap {
        val b = bmp(72f, 52f)
        val c = Canvas(b)
        val cx = b.width / 2f
        val bottom = b.height.toFloat()
        val r = px(50f)
        val sweep = 70f   // 부채꼴 각도
        val oval = RectF(cx - r, bottom - r, cx + r, bottom + r)
        fill.shader = android.graphics.RadialGradient(
            cx, bottom, r,
            intArrayOf(Color.argb(150, 26, 115, 232), Color.argb(0, 26, 115, 232)), floatArrayOf(0.25f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawArc(oval, -90f - sweep / 2, sweep, true, fill)
        fill.shader = null
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

    private val ink = Color.rgb(34, 34, 34)

    /** 작은 흰 원(그림자 + 옅은 테두리). 아이콘은 호출한 쪽에서 가운데에 그림 */
    private fun whiteDisc(sizeDp: Float, draw: (Canvas, Float, Float, Float) -> Unit): Bitmap {
        val b = bmp(sizeDp + 6f, sizeDp + 6f)
        val c = Canvas(b)
        val cx = b.width / 2f
        val r = px(sizeDp / 2)
        fill.color = Color.argb(46, 0, 0, 0)
        c.drawCircle(cx, cx + px(1f), r + px(1.5f), fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cx, r, fill)
        stroke.color = Color.argb(60, 0, 0, 0)
        stroke.strokeWidth = px(0.8f)
        c.drawCircle(cx, cx, r, stroke)
        draw(c, cx, cx, r)
        return forKakao(b)
    }

    /** CCTV: 흰 원 + 검은 카메라. anchor (0.5, 0.5) */
    fun cameraBadge(): Bitmap = whiteDisc(24f) { c, cx, cy, r ->
        fill.color = ink
        val w = r * 1.0f
        val h = r * 0.62f
        c.drawRoundRect(RectF(cx - w * 0.78f, cy - h / 2, cx + w * 0.34f, cy + h / 2), px(1.5f), px(1.5f), fill)
        val tri = Path().apply {
            moveTo(cx + w * 0.42f, cy - h * 0.18f)
            lineTo(cx + w * 0.82f, cy - h * 0.5f)
            lineTo(cx + w * 0.82f, cy + h * 0.5f)
            lineTo(cx + w * 0.42f, cy + h * 0.18f)
            close()
        }
        c.drawPath(tri, fill)
    }

    /** 돌발·주의: 노란(색) 삼각형 + 검은 느낌표. anchor (0.5, 0.5) */
    fun triangleBadge(color: Int): Bitmap {
        val b = bmp(28f, 28f)
        val c = Canvas(b)
        val cx = b.width / 2f
        val cy = b.height / 2f
        val s = px(11.5f)
        val p = Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s * 1.08f, cy + s * 0.82f)
            lineTo(cx - s * 1.08f, cy + s * 0.82f)
            close()
        }
        fill.color = Color.argb(46, 0, 0, 0)
        c.save(); c.translate(0f, px(1f)); c.drawPath(p, fill); c.restore()
        fill.color = color
        c.drawPath(p, fill)
        stroke.color = ink
        stroke.strokeWidth = px(1.6f)
        stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(p, stroke)
        fill.color = ink
        c.drawRoundRect(RectF(cx - px(1.1f), cy - s * 0.38f, cx + px(1.1f), cy + s * 0.22f), px(1f), px(1f), fill)
        c.drawCircle(cx, cy + s * 0.5f, px(1.25f), fill)
        return forKakao(b)
    }

    /** 그 밖의 정보: 흰 원 + 색 글자. anchor (0.5, 0.5) */
    fun letterBadge(color: Int, glyph: String): Bitmap = whiteDisc(22f) { c, cx, cy, _ ->
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = px(if (glyph.length > 1) 9f else 12f)
            isFakeBoldText = true
        }
        c.drawText(glyph, cx, cy - (t.ascent() + t.descent()) / 2, t)
    }

    /**
     * 마커를 눌렀을 때 마커 위에 뜨는 말풍선: 흰 둥근 상자 + 제목 + ">" (누르면 자세히).
     * 아래 끝이 마커 중심이 되도록 [liftDp] 만큼 투명 여백을 두고 anchor (0.5, 1) 로 놓는다.
     */
    fun callout(title: String, liftDp: Float = 16f): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = px(14f)
        }
        val shown = android.text.TextUtils.ellipsize(title, android.text.TextPaint(text), px(220f), android.text.TextUtils.TruncateAt.END).toString()
        val tw = text.measureText(shown)
        val padH = px(14f)
        val chevron = px(16f)
        val boxW = tw + padH * 2 + chevron
        val boxH = px(36f)
        val pointer = px(6f)
        val shadow = px(3f)
        val w = (boxW + shadow * 2).toInt()
        val h = (boxH + pointer + px(liftDp) + shadow).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val left = shadow
        val bottom = h - px(liftDp) - pointer
        val top = bottom - boxH
        val rect = RectF(left, top, left + boxW, bottom)
        fill.color = Color.argb(46, 0, 0, 0)
        c.drawRoundRect(RectF(rect.left, rect.top + px(1.5f), rect.right, rect.bottom + px(1.5f)), px(8f), px(8f), fill)
        fill.color = Color.WHITE
        c.drawRoundRect(rect, px(8f), px(8f), fill)
        val mid = w / 2f
        val tri = Path().apply {
            moveTo(mid - px(6f), bottom - 0.5f)
            lineTo(mid, bottom + pointer)
            lineTo(mid + px(6f), bottom - 0.5f)
            close()
        }
        c.drawPath(tri, fill)
        c.drawText(shown, left + padH, rect.centerY() - (text.ascent() + text.descent()) / 2, text)
        stroke.color = Color.rgb(120, 124, 130)
        stroke.strokeWidth = px(1.8f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val ax = rect.right - padH * 0.55f - px(2f)
        val ay = rect.centerY()
        val chev = Path().apply {
            moveTo(ax - px(2.5f), ay - px(5f))
            lineTo(ax + px(2.5f), ay)
            lineTo(ax - px(2.5f), ay + px(5f))
        }
        c.drawPath(chev, stroke)
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
