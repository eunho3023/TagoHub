package com.eunho.tagohub.data

import java.time.LocalDateTime

// ── 기차·고속·시외·항공 공통 운행편 ─────────────────────────
data class Trip(
    /** 열차등급 / 버스등급 / 항공사 */
    val kind: String,
    /** 열차번호·편명. 버스는 없음 */
    val number: String?,
    val depName: String,
    val arrName: String,
    val dep: LocalDateTime?,
    val arr: LocalDateTime?,
    /** (라벨, 요금). 0원은 "정보 없음"으로 보고 제외 */
    val prices: List<Pair<String, Int>>,
    /** 기차: 조회한 출발·도착역 ID (정차역·환승 찾기에 씀) */
    val depId: String? = null,
    val arrId: String? = null,
)

// ── 고속버스 실시간 도착 ───────────────────────────────────
data class ExpArrival(
    val depName: String,
    val arrName: String,
    val depMinute: Int?,
    val arrPredict: LocalDateTime?,
    val remainText: String,
    val remainMinutes: Int?,
    val arrived: Boolean,
    val grade: String?,
    val corp: String?,
    val location: String?,
)
