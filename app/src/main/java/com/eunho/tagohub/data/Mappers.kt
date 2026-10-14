package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.naturalOrder

/** XML Row → 도메인 모델. 안드로이드 의존성이 없어 JVM 단위테스트로 검증한다. */
object Mappers {

    fun city(r: Row) = City(r.text("citycode"), r.text("cityname"))

    // ── 시내버스 ──
    fun busStop(r: Row, fallbackCity: String? = null) = BusStop(
        cityCode = r.str("citycode") ?: fallbackCity.orEmpty(),
        nodeId = r.text("nodeid"),
        name = r.text("nodenm"),
        nodeNo = r.str("nodeno"),
        lat = r.double("gpslati"),
        lng = r.double("gpslong"),
    )

    fun busRoute(r: Row) = BusRoute(
        routeId = r.text("routeid"),
        routeNo = r.text("routeno"),
        routeType = r.str("routetp"),
        startName = r.str("startnodenm"),
        endName = r.str("endnodenm"),
        firstTime = TagoTime.clockText(r.str("startvehicletime")),
        lastTime = TagoTime.clockText(r.str("endvehicletime")),
        interval = r.int("intervaltime"),
        intervalSat = r.int("intervalsattime"),
        intervalSun = r.int("intervalsuntime"),
    )

    fun busArrival(r: Row) = BusArrival(
        routeId = r.text("routeid"),
        routeNo = r.text("routeno"),
        routeType = r.str("routetp"),
        vehicleType = r.str("vehicletp"),
        prevStops = r.int("arrprevstationcnt"),
        arrSeconds = r.int("arrtime"),
    )

    fun routeStop(r: Row) = RouteStop(
        nodeId = r.text("nodeid"),
        name = r.text("nodenm"),
        nodeNo = r.str("nodeno"),
        ord = r.int("nodeord") ?: 0,
        upDown = r.str("updowncd"),
        lat = r.double("gpslati"),
        lng = r.double("gpslong"),
    )

    fun busLocation(r: Row) = BusLocation(
        nodeId = r.str("nodeid"),
        nodeName = r.str("nodenm"),
        ord = r.int("nodeord"),
        vehicleNo = r.str("vehicleno"),
        lat = r.double("gpslati"),
        lng = r.double("gpslong"),
    )

    // ── 기차 ──
    fun trainStation(r: Row, cityName: String?) = Place(r.text("nodeid"), r.text("nodename"), cityName)

    fun trainKind(r: Row) = Grade(r.text("vehiclekndid"), r.text("vehiclekndnm"))

    fun trainTrip(r: Row) = Trip(
        kind = r.text("traingradename"),
        number = r.str("trainno")?.trimStart('0')?.ifEmpty { "0" },
        depName = r.text("depplacename"),
        arrName = r.text("arrplacename"),
        dep = TagoTime.dateTime(r.str("depplandtime")),
        arr = TagoTime.dateTime(r.str("arrplandtime")),
        // 04753 처럼 adultcharge=0 인 열차가 섞여 있다 → 요금 미제공 처리
        prices = listOfNotNull(r.int("adultcharge")?.takeIf { it > 0 }?.let { "어른" to it }),
    )

    // ── 항공 ──
    fun airport(r: Row) = Place(r.text("airportId"), r.text("airportNm"))
    fun airline(r: Row) = Grade(r.text("airlineId"), r.text("airlineNm"))

    fun flight(r: Row) = Trip(
        kind = r.text("airlineNm"),
        number = r.str("vihicleId", "vehicleId"), // 응답 필드명이 실제로 vihicleId (오타 그대로)
        depName = r.text("depAirportNm"),
        arrName = r.text("arrAirportNm"),
        dep = TagoTime.dateTime(r.str("depPlandTime")),
        arr = TagoTime.dateTime(r.str("arrPlandTime")),
        prices = listOfNotNull(
            r.int("economyCharge")?.takeIf { it > 0 }?.let { "일반석" to it },
            r.int("prestigeCharge")?.takeIf { it > 0 }?.let { "비즈니스" to it },
        ),
    )

    // ── 고속·시외 시간표 ──
    fun terminal(r: Row) = Place(r.text("terminalId"), r.text("terminalNm"), r.str("cityName"))
    fun grade(r: Row) = Grade(r.text("gradeId"), r.text("gradeNm"))

    fun coachTrip(r: Row) = Trip(
        kind = r.text("gradeNm"),
        number = null,
        depName = r.text("depPlaceNm"),
        arrName = r.text("arrPlaceNm"),
        dep = TagoTime.dateTime(r.str("depPlandTime")),
        arr = TagoTime.dateTime(r.str("arrPlandTime")),
        prices = listOfNotNull(r.int("charge")?.takeIf { it > 0 }?.let { "요금" to it }),
    )

    // ── 고속버스 도착정보 (ExpBusArrInfo: 터미널 코드 체계가 ExpBusInfo 와 다름!) ──
    fun expArrTerminal(r: Row) = Place(r.text("tmnCd", "arrTmnCd"), r.text("tmnNm", "arrTmnNm"))

    fun expArrival(r: Row): ExpArrival {
        val remain = r.text("rmnTm")
        val loc = r.str("curLocNm")
        return ExpArrival(
            depName = r.text("depTmnNm"),
            arrName = r.text("arrTmnNm"),
            depMinute = TagoTime.minutesOfDay(r.str("depTm")),
            arrPredict = TagoTime.dateTime(r.str("arrPrdtTm")),
            remainText = remain,
            remainMinutes = TagoTime.remainMinutes(remain),
            arrived = "도착" in remain || loc == "도착",
            grade = r.str("busGrdNm"),
            corp = r.str("corpNm"),
            location = loc,
        )
    }

    /** 고속도착: 운행 중(남은시간 오름차순) → 도착완료(출발시각순) */
    val expArrivalOrder: Comparator<ExpArrival> = compareBy<ExpArrival>(
        { it.arrived },
        { if (it.arrived) 0 else it.remainMinutes ?: Int.MAX_VALUE },
        { it.depMinute ?: Int.MAX_VALUE },
    )

    // ── 지하철 ──
    fun subwayStation(r: Row) = SubwayStation(
        id = r.text("subwayStationId"),
        name = r.text("subwayStationName", "subwayStationNm"),
        routeName = r.text("subwayRouteName", "subwayRouteNm"),
    )

    /**
     * depTime "000000" + arrTime "0" 은 실제 운행시각이 아니라 빈 값으로 판단해 버린다.
     * depTime 만 비어 있고 arrTime 이 있으면 종착(도착만) 열차로 표시.
     */
    fun subwayDeparture(r: Row): SubwayDeparture? {
        fun String?.blankIfZero() = this?.takeUnless { s -> s.all { it == '0' } }
        val dep = r.str("depTime").blankIfZero()
        val arr = r.str("arrTime").blankIfZero()
        val raw = dep ?: arr ?: return null
        val min = TagoTime.minutesOfDay(raw) ?: return null
        return SubwayDeparture(
            serviceMinute = TagoTime.serviceMinute(min),
            endStationId = r.text("endSubwayStationId"),
            endStationName = r.text("endSubwayStationNm", "endSubwayStationName"),
            arrivalOnly = dep == null,
        )
    }

    /** 응답에 완전히 같은 행이 반복돼서 온다 → 중복 제거 + 시각순 정렬 */
    fun cleanSchedule(rows: List<Row>): List<SubwayDeparture> =
        rows.mapNotNull(::subwayDeparture)
            .distinctBy { it.serviceMinute to it.endStationId }
            .sortedBy { it.serviceMinute }

    /** 출구별 버스 + 출구별 주변시설을 출구번호로 합친다 */
    fun exits(buses: List<Row>, facilities: List<Row>): List<ExitInfo> {
        val b = buses.groupBy({ it.text("exitNo") }, { it.text("busRouteNo") })
        val f = facilities.groupBy({ it.text("exitNo") }, { it.text("dirDesc") })
        return (b.keys + f.keys)
            .filter { it.isNotBlank() }
            .sortedWith(naturalOrder)
            .map { no ->
                ExitInfo(
                    exitNo = no,
                    buses = b[no].orEmpty().filter { it.isNotBlank() }.distinct().sortedWith(naturalOrder),
                    facilities = f[no].orEmpty().filter { it.isNotBlank() }.distinct(),
                )
            }
    }
}

object PlaceFilter {
    /** 공백 무시 부분일치, 앞글자 일치 우선 → 짧은 이름 우선 */
    fun filter(list: List<Place>, query: String): List<Place> {
        val k = query.replace(" ", "")
        if (k.isEmpty()) return list
        return list
            .filter { p ->
                p.name.replace(" ", "").contains(k, ignoreCase = true) ||
                    p.sub?.contains(k) == true ||
                    p.id.equals(k, ignoreCase = true)
            }
            .sortedWith(compareBy({ !it.name.replace(" ", "").startsWith(k, ignoreCase = true) }, { it.name.length }, { it.name }))
    }
}
