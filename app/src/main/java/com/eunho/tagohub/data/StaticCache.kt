package com.eunho.tagohub.data

import java.io.File

/**
 * 잘 안 바뀌는 정보(노선 모양·노선 정류장 순서·노선 정보·정류장 경유노선·도시 목록)를 휴대폰에 저장.
 * 한 번 받으면 계속 쓴다 (앱을 업데이트하면 한 번 비우고 새로 받음).
 * 파일 하나 = 키 하나, 첫 줄은 저장 시각, 나머지는 탭으로 나눈 행. null 은 "\N".
 */
object StaticCache {
    /** 기한 없음: 한 번 받으면 계속 사용. 앱을 업데이트하면(새 데이터) 한 번 비우고 다시 받음 */
    const val TTL_MS = Long.MAX_VALUE
    @Volatile private var dir: File? = null
    /** 테스트용으로 바꿀 수 있는 현재 시각 */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * appVersion: 앱 버전(versionCode). 지난번과 다르면(업데이트) 저장된 것을 모두 지운다
     * → 노선 개편이 반영된 새 앱에서 예전 노선이 남지 않게.
     */
    fun init(root: File, appVersion: Long = 0) {
        val d = File(root, "static_cache").apply { mkdirs() }
        val mark = File(d, ".version")
        val saved = runCatching { mark.readText().trim().toLong() }.getOrNull()
        if (saved != appVersion) {
            d.listFiles()?.forEach { it.delete() }
            runCatching { mark.writeText(appVersion.toString()) }
        }
        dir = d
    }

    /** 저장한 것 모두 지우기 (설정 등에서 직접 새로 받고 싶을 때) */
    fun clear() {
        dir?.listFiles()?.filter { it.name != ".version" }?.forEach { it.delete() }
    }

    private fun file(key: String): File? = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv") }

    private fun read(key: String): List<List<String?>>? = runCatching {
        val f = file(key) ?: return null
        if (!f.exists()) return null
        val lines = f.readLines(Charsets.UTF_8)
        val at = lines.firstOrNull()?.toLongOrNull() ?: return null
        if (TTL_MS != Long.MAX_VALUE && clock() - at > TTL_MS) return null
        lines.drop(1).map { line -> line.split('\t').map { if (it == "\\N") null else it } }
    }.getOrNull()

    private fun write(key: String, rows: List<List<Any?>>) {
        val f = file(key) ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(buildString {
                append(clock()).append('\n')
                rows.forEach { r -> append(r.joinToString("\t") { v -> v?.toString()?.replace('\t', ' ')?.replace('\n', ' ') ?: "\\N" }).append('\n') }
            }, Charsets.UTF_8)
            tmp.renameTo(f)
        }
    }

    // ── 지하철 (TAGO 역 ID · 시간표 · 출구) — 역을 볼 때마다 부르던 것을 한 번만 ──
    private const val SEP = "\u001F"
    /** 파일 이름에는 영문·숫자만 남으므로 한글 역명은 16진수로 (안 그러면 서로 다른 역이 같은 파일이 됨) */
    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun subwayStations(q: String): List<SubwayStation>? = read("sstn_" + hex(q))?.mapNotNull { r ->
        val id = r.getOrNull(0) ?: return@mapNotNull null
        SubwayStation(
            id,
            r.getOrNull(1).orEmpty(),
            r.getOrNull(2).orEmpty(),
            r.getOrNull(3).orEmpty(),
        )
    }?.takeIf { it.isNotEmpty() }

    fun putSubwayStations(q: String, list: List<SubwayStation>) {
        if (list.isNotEmpty()) write("sstn_" + hex(q), list.map { listOf(it.id, it.name, it.routeName, it.routeId) })
    }

    fun schedule(stationId: String, day: String, upDown: String): List<SubwayDeparture>? =
        read("ssch_${stationId}_${day}_$upDown")?.mapNotNull { r ->
            SubwayDeparture(r.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null, r.getOrNull(1).orEmpty(), r.getOrNull(2).orEmpty(), r.getOrNull(3) == "1")
        }?.takeIf { it.isNotEmpty() }

    fun putSchedule(stationId: String, day: String, upDown: String, list: List<SubwayDeparture>) {
        if (list.isNotEmpty()) write("ssch_${stationId}_${day}_$upDown",
            list.map { listOf(it.serviceMinute, it.endStationId, it.endStationName, if (it.arrivalOnly) 1 else 0) })
    }

    fun exits(stationId: String): List<ExitInfo>? = read("sexit_$stationId")?.mapNotNull { r ->
        fun split(v: String?) = v?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
        ExitInfo(r.getOrNull(0) ?: return@mapNotNull null, split(r.getOrNull(1)), split(r.getOrNull(2)))
    }?.takeIf { it.isNotEmpty() }

    fun putExits(stationId: String, list: List<ExitInfo>) {
        if (list.isNotEmpty()) write("sexit_$stationId", list.map { listOf(it.exitNo, it.buses.joinToString(SEP), it.facilities.joinToString(SEP)) })
    }

    // ── 서울 노선 회차 정류장 (getStaionByRoute transYn) — 없으면 "" 로 저장 (다시 묻지 않음) ──
    fun seoulTurn(routeId: String): String? = read("sturn_$routeId")?.firstOrNull()?.getOrNull(0)
    fun putSeoulTurn(routeId: String, stationId: String?) = write("sturn_$routeId", listOf(listOf(stationId.orEmpty())))

    // ── 노선 모양 ── (shape3_: 모든 노선을 카카오 도로 경로 우선으로 바꾸면서 예전 저장분은 다시 받음)
    fun shape(id: String): List<LatLng>? = read("shape3_$id")?.mapNotNull { r ->
        val lat = r.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        LatLng(lat, r.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null)
    }?.takeIf { it.size >= 2 }
    fun putShape(id: String, pts: List<LatLng>) { if (pts.size >= 2) write("shape3_$id", pts.map { listOf(it.lat, it.lng) }) }

    // ── 노선 정류장 순서 ──
    fun routeStops(id: String): List<RouteStop>? = read("stops_$id")?.mapNotNull { r ->
        RouteStop(
            nodeId = r.getOrNull(0) ?: return@mapNotNull null, name = r.getOrNull(1).orEmpty(), nodeNo = r.getOrNull(2),
            ord = r.getOrNull(3)?.toIntOrNull() ?: 0, upDown = r.getOrNull(4),
            lat = r.getOrNull(5)?.toDoubleOrNull(), lng = r.getOrNull(6)?.toDoubleOrNull(),
        )
    }?.takeIf { it.isNotEmpty() }
    fun putRouteStops(id: String, stops: List<RouteStop>) {
        if (stops.isNotEmpty()) write("stops_$id", stops.map { listOf(it.nodeId, it.name, it.nodeNo, it.ord, it.upDown, it.lat, it.lng) })
    }

    // ── 노선 정보 / 노선 목록 ──
    private fun toRoute(r: List<String?>): BusRoute? {
        val id = r.getOrNull(0) ?: return null
        return BusRoute(
        routeId = id, routeNo = r.getOrNull(1).orEmpty(), routeType = r.getOrNull(2),
        startName = r.getOrNull(3), endName = r.getOrNull(4), firstTime = r.getOrNull(5), lastTime = r.getOrNull(6),
        interval = r.getOrNull(7)?.toIntOrNull(), intervalSat = r.getOrNull(8)?.toIntOrNull(), intervalSun = r.getOrNull(9)?.toIntOrNull(),
        cityCode = r.getOrNull(10).orEmpty(),
        )
    }
    private fun fromRoute(b: BusRoute) = listOf(b.routeId, b.routeNo, b.routeType, b.startName, b.endName, b.firstTime, b.lastTime,
        b.interval, b.intervalSat, b.intervalSun, b.cityCode)

    fun routes(key: String): List<BusRoute>? = read("routes_$key")?.mapNotNull(::toRoute)?.takeIf { it.isNotEmpty() }
    fun putRoutes(key: String, list: List<BusRoute>) { if (list.isNotEmpty()) write("routes_$key", list.map(::fromRoute)) }

    fun route(id: String): BusRoute? = routes("info_$id")?.firstOrNull()
    fun putRoute(id: String, r: BusRoute?) { if (r != null) putRoutes("info_$id", listOf(r)) }

    // ── 문자열 쌍 (도시코드 → 이름 등) ──
    fun pairs(key: String): List<Pair<String, String>>? = read("pairs_$key")?.mapNotNull { r ->
        (r.getOrNull(0) ?: return@mapNotNull null) to r.getOrNull(1).orEmpty()
    }?.takeIf { it.isNotEmpty() }
    fun putPairs(key: String, list: List<Pair<String, String>>) { if (list.isNotEmpty()) write("pairs_$key", list.map { listOf(it.first, it.second) }) }
}
