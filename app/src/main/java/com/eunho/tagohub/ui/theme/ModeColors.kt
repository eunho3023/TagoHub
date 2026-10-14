package com.eunho.tagohub.ui.theme

import androidx.compose.ui.graphics.Color

/** 교통수단별 색. 화면 제목 왼쪽 띠·배지·선택 표시에 일관되게 쓴다. */
object ModeColor {
    val Map = Color(0xFF1F4E8C)
    val Bus = Color(0xFF2E8B57)
    val Subway = Color(0xFF5A6474)
    val Train = Color(0xFF1B3E8A)
    val Coach = Color(0xFF0A7E8C)
    val Suburb = Color(0xFF7A4FB5)
    val Air = Color(0xFFB86A12)
}

/** routetp 문자열 → 노선 색 (전국 공통 관행: 간선 파랑, 지선 초록, 광역·급행 빨강 …) */
fun busRouteColor(type: String?): Color {
    val t = type ?: return Color(0xFF607080)
    return when {
        "간선" in t -> Color(0xFF3D5BAB)
        "지선" in t -> Color(0xFF3E9A2E)
        "광역" in t || "급행" in t || "직행" in t || "좌석" in t -> Color(0xFFD7262E)
        "마을" in t -> Color(0xFF7FA92A)
        "순환" in t -> Color(0xFFC9940A)
        t == "일반" -> Color(0xFF14A0A8)
        "일반형시내" in t -> Color(0xFF1E9E5A) // 경기 일반형시내버스 (초록)
        "심야" in t || "올빼미" in t -> Color(0xFF2B2B5E)
        t == "농어촌" -> Color(0xFFE5622C) // 교통안전공단 농어촌 노선
        "외곽" in t || "농어촌" in t || "시외" in t -> Color(0xFF8E6E3B)
        "공항" in t -> Color(0xFF0090D2)
        else -> Color(0xFF607080)
    }
}

private val seoulLines = mapOf(
    1 to Color(0xFF0052A4), 2 to Color(0xFF00A84D), 3 to Color(0xFFEF7C1C),
    4 to Color(0xFF00A5DE), 5 to Color(0xFF996CAC), 6 to Color(0xFFCD7C2F),
    7 to Color(0xFF747F00), 8 to Color(0xFFE6186C), 9 to Color(0xFFBDB092),
)
private val lineNo = Regex("""^(\d)호선""")

fun subwayLineColor(name: String): Color {
    regionalLineColor(name)?.let { return it }
    lineNo.find(name)?.let { m -> seoulLines[m.groupValues[1].toInt()]?.let { return it } }
    return when {
        "인천1" in name || "인천 1" in name -> Color(0xFF7CA8D5)
        "인천2" in name || "인천 2" in name -> Color(0xFFED8B00)
        "공항" in name -> Color(0xFF0090D2)
        "GTX" in name || "광역급행" in name -> Color(0xFF9A6292) // GTX-A (표준데이터: 수도권 광역급행철도)
        "신분당" in name -> Color(0xFFD4003B)
        "수인" in name || "분당" in name -> Color(0xFFE0A800)
        "경의" in name -> Color(0xFF5FB59A)
        "경춘" in name -> Color(0xFF0C8E72)
        "경강" in name -> Color(0xFF003DA5)
        "우이" in name -> Color(0xFF9DB516)
        "신림" in name -> Color(0xFF6789CA)
        "서해" in name -> Color(0xFF6FA81D)
        "김포" in name -> Color(0xFFA17800)
        "의정부" in name -> Color(0xFFE69500)
        "에버" in name || "용인" in name -> Color(0xFF509F22)
        else -> Color(0xFF4F5B6B)
    }
}

/** 수도권 외 도시철도 (표준데이터 노선명 기준) */
private fun regionalLineColor(name: String): Color? {
    fun has(n: Int) = name.contains("${n}호선")
    return when {
        "부산김해" in name -> Color(0xFF8652A1)
        "부산" in name && has(1) -> Color(0xFFF06A00)
        "부산" in name && has(2) -> Color(0xFF81BF48)
        "부산" in name && has(3) -> Color(0xFFBB8C00)
        "부산" in name && has(4) -> Color(0xFF217DCB)
        "대구" in name && has(1) -> Color(0xFFD93F5C)
        "대구" in name && has(2) -> Color(0xFF00AA80)
        "대구" in name && has(3) -> Color(0xFFFFB100)
        "대경" in name -> Color(0xFF1E4E8C)
        "광주" in name -> Color(0xFF009088)
        "대전" in name -> Color(0xFF007448)
        "인천지하철" in name && has(1) -> Color(0xFF7CA8D5)
        "인천지하철" in name && has(2) -> Color(0xFFED8B00)
        "동해선" in name -> Color(0xFF0054A6)
        "자기부상" in name -> Color(0xFFE0A800)
        else -> null
    }
}
