package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 인천국제공항공사 항공기 운항 현황 상세 (실시간, 조회일 기준 -3일 ~ +6일).
 * 출발: /B551177/statusOfAllFltDeOdp/getFltDeparturesDeOdp, 도착: …/getFltArrivalsDeOdp
 * 요청: searchdtCode(S 예정 기준), searchDate(yyyyMMdd), searchFrom·searchTo(HHmm), airportCode(상대 공항 IATA), passengerOrCargo(P), type=json
 * 응답(가이드): flightId, airline, airport, airportCode, scheduleDatetime·estimatedDatetime(yyyyMMddHHmm), gateNumber, terminalId,
 *   remark(도착·결항·지연·회항·착륙), codeshare(Master/Slave), masterFlightId
 */
object IcnFlights {
    const val BASE = "https://apis.data.go.kr/B551177/statusOfAllFltDeOdp"

    data class Flight(
        val flightId: String, val airline: String, val airport: String, val airportCode: String,
        val scheduled: LocalDateTime?, val estimated: LocalDateTime?, val gate: String?, val terminal: String?,
        val remark: String?, val master: Boolean,
    )

    private val DT = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    fun parse(text: String): List<Flight> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val body = ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>) ?: return emptyList()
        // items 가 목록이거나 {item: 목록|객체} 둘 다 받음 (가이드와 실제가 다를 때 대비)
        val items = body["items"]
        val raw: List<*> = when (items) {
            is List<*> -> items
            is Map<*, *> -> when (val one = items["item"]) { is List<*> -> one; is Map<*, *> -> listOf<Any?>(one); else -> emptyList<Any?>() }
            else -> emptyList<Any?>()
        }
        fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "-" }
        fun dt(v: String?) = v?.takeIf { it.length >= 12 }?.let { runCatching { LocalDateTime.parse(it.take(12), DT) }.getOrNull() }
        return raw.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            Flight(
                flightId = m.s("flightId") ?: return@mapNotNull null, airline = m.s("airline").orEmpty(),
                airport = m.s("airport").orEmpty(), airportCode = m.s("airportCode").orEmpty(),
                scheduled = dt(m.s("scheduleDatetime")), estimated = dt(m.s("estimatedDatetime")),
                gate = m.s("gateNumber"), terminal = m.s("terminalId"), remark = m.s("remark"),
                master = m.s("codeshare")?.equals("Slave", ignoreCase = true) != true,
            )
        }
    }

    private fun terminal(t: String?) = when (t) { "P01" -> "T1"; "P02" -> "탑승동"; "P03" -> "T2"; else -> null }

    /** 앱 시간표 형식: 공동운항(Slave) 빼고, 항공사 칸에 운항 상태·터미널·탑승구 */
    fun toTrips(list: List<Flight>, departure: Boolean, date: LocalDate): List<Trip> = list
        .filter { it.master && (it.scheduled == null || it.scheduled.toLocalDate() == date) }
        .distinctBy { it.flightId + it.scheduled }
        .sortedBy { it.scheduled ?: LocalDateTime.MAX }
        .map { f ->
            val shown = f.estimated ?: f.scheduled
            val info = listOfNotNull(f.airline, f.remark, terminal(f.terminal), f.gate?.let { "탑승구 $it" },
                f.estimated?.takeIf { f.scheduled != null && it != f.scheduled }?.let { "예정 %02d:%02d".format(f.scheduled!!.hour, f.scheduled.minute) })
            Trip(kind = info.joinToString(" · "), number = f.flightId,
                depName = if (departure) "인천" else f.airport, arrName = if (departure) f.airport else "인천",
                dep = if (departure) shown else null, arr = if (departure) null else shown, prices = emptyList())
        }
}
