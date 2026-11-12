package com.eunho.tagohub.ui.map

/**
 * 배치도에서 지금 그릴 행 범위. 스크롤 위치·뷰포트 높이·행 높이로 정하고, 위아래 한 행씩 여유를 둔다
 * (정류장 이름이 점 아래에 그려져 경계에서 잘리지 않게).
 *
 * 그림을 내용 전체 높이로 그리면 긴 노선에서 레이어가 수천 dp 가 되므로, 뷰포트 크기 Canvas 에
 * 이 범위의 행만 그린다. 계산만 따로 두어 화면 없이도 검증한다.
 */
internal fun visibleRows(scrollPx: Int, viewportPx: Float, rowHeightPx: Float, rows: Int): IntRange {
    if (rows <= 0 || rowHeightPx <= 0f || viewportPx <= 0f) return IntRange.EMPTY
    val lastRow = rows - 1
    val first = (scrollPx / rowHeightPx).toInt() - 1
    val last = ((scrollPx + viewportPx) / rowHeightPx).toInt() + 1
    return first.coerceIn(0, lastRow)..last.coerceIn(0, lastRow)
}
