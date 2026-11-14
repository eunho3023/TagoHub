package com.eunho.tagohub.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eunho.tagohub.core.Chosung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 전국 버스정류장 (국토교통부_전국 버스정류장 위치정보_20251031, 227,059곳).
 * assets/bus_stops.db 를 처음 한 번 앱 저장소로 복사해 읽기 전용으로 연다.
 * 정류장 ID 가 TAGO 정류소 ID 와 같아서 저장된 정류장으로 바로 실시간 도착정보를 요청할 수 있다.
 *
 * 테이블: stops(id, name, lat, lng, no=정류소번호, city=TAGO 도시코드), cities(code, name)
 */
class StopDb private constructor(private val db: SQLiteDatabase) {

    /** 화면 범위 안의 정류장. 중심에서 가까운 순으로 최대 limit 개 */
    fun inBox(south: Double, west: Double, north: Double, east: Double, limit: Int = 800): List<BusStop> {
        val cLat = (south + north) / 2
        val cLng = (west + east) / 2
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?
               ORDER BY (lat - ?) * (lat - ?) + (lng - ?) * (lng - ?) LIMIT $limit""",
            arrayOf(south, north, west, east, cLat, cLat, cLng, cLng).map { it.toString() }.toTypedArray(),
        ) { it.toStop() }
    }

    /** 정류장 이름(공백 무시 부분일치) 또는 정류소번호(완전일치). 정확히 같은 이름·번호가 먼저 */
    fun search(q: String, limit: Int = 200): List<BusStop> {
        val k = q.replace(" ", "")
        if (k.isEmpty()) return emptyList()
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE replace(name, ' ', '') LIKE ? OR no = ?
               ORDER BY (replace(name, ' ', '') = ?) DESC, (no = ?) DESC, length(name), name
               LIMIT $limit""",
            arrayOf("%$k%", k, k, k),
        ) { it.toStop() }
    }

    /** 공백을 뺀 정류장 이름 목록 (초성 검색용. 처음 한 번만 읽어 메모리에 둠) */
    private val distinctNames: List<String> by lazy {
        query("SELECT DISTINCT replace(name, ' ', '') FROM stops", emptyArray()) { it.getString(0) }
    }

    /** 초성("ㄱㄴ")이나 초성과 글자가 섞인 검색어로 정류장 찾기. 이름이 짧고 앞에서 맞는 것부터 */
    fun searchInitials(q: String, limit: Int = 200): List<BusStop> {
        val k = q.replace(" ", "")
        if (k.isEmpty()) return emptyList()
        val names = distinctNames
            .mapNotNull { n -> Chosung.indexOf(n, k).takeIf { it >= 0 }?.let { n to it } }
            .sortedWith(compareBy({ it.second != 0 }, { it.first.length }, { it.first }))
            .take(40)
            .map { it.first }
        if (names.isEmpty()) return emptyList()
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE replace(name, ' ', '') IN (${names.joinToString(",") { "?" }})
               ORDER BY length(name), name LIMIT $limit""",
            names.toTypedArray(),
        ) { it.toStop() }
    }

    /** 이 좌표에서 가장 가까운 정류장의 도시코드 (지역 자동 판별, 약 3km 안) */
    fun nearestCity(lat: Double, lng: Double): String? {
        val d = 0.03
        return query(
            """SELECT city FROM stops WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?
               ORDER BY (lat - ?) * (lat - ?) + (lng - ?) * (lng - ?) LIMIT 1""",
            arrayOf(lat - d, lat + d, lng - d, lng + d, lat, lat, lng, lng).map { it.toString() }.toTypedArray(),
        ) { it.getString(0) }.firstOrNull()
    }

    /**
     * 같은 자리의 다른 시군 정류장 (30m 안, 도시코드가 다른 것, 시군마다 가장 가까운 하나).
     * 시군 경계 근처 정류장은 시군마다 따로 등록돼 있어서(예: 전주 BYC ↔ 김제 BYC, 1m)
     * 이걸 합쳐야 다른 시군 버스 도착정보까지 한 정류장에 보인다.
     */
    fun sameSpot(stop: BusStop, radiusM: Double = 30.0): List<BusStop> {
        val lat = stop.lat ?: return emptyList()
        val lng = stop.lng ?: return emptyList()
        val dLat = radiusM / 111_000.0
        val dLng = radiusM / 90_000.0
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? AND city <> ? AND id <> ?""",
            arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lng - dLng).toString(), (lng + dLng).toString(), stop.cityCode, TagoIds.legacy(stop.nodeId)),
        ) { it.toStop() }
            .map { it to com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat!!, it.lng!!) }
            .filter { it.second <= radiusM }
            // 시군마다 가장 가까운 정류장 하나만: 바로 붙어 있는 게 같은 정류장이고,
            // 같은 이름이라도 20~30m 떨어진 건 대개 길 건너편(반대 방향)이라 섞이지 않게
            .groupBy { it.first.cityCode }
            .map { (_, list) -> list.minBy { it.second }.first }
    }

    /**
     * 근처([radiusM] 안)의 같은 이름(공백 무시) 정류장, 가까운 순. 실시간이 없는 지역 정류장(예: 완주 TSB 우석대학교)을 눌렀을 때
     * 바로 옆 실시간 지역 정류장(전주 우석대학교)으로 이어 주려고 씀
     */
    fun nearbySameName(stop: BusStop, radiusM: Double = 150.0): List<BusStop> {
        val lat = stop.lat ?: return emptyList()
        val lng = stop.lng ?: return emptyList()
        val dLat = radiusM / 111_000.0
        val dLng = radiusM / 90_000.0
        val key = stop.name.replace(" ", "")
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? AND id <> ?""",
            arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lng - dLng).toString(), (lng + dLng).toString(), TagoIds.legacy(stop.nodeId)),
        ) { it.toStop() }
            .filter { it.name.replace(" ", "") == key }
            .map { it to com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat!!, it.lng!!) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .map { it.first }
    }

    private val cityCache: Map<String, String> by lazy { cityNames() }

    // ── 한국교통안전공단 노선 (TSB…, 농어촌·시외 지역) ──

    /** 노선번호로 찾기 (앱 안 목록, 서버 호출 없음) */
    fun searchRoutes(q: String, limit: Int = 200): List<BusRoute> {
        val k = q.trim()
        if (k.isEmpty()) return emptyList()
        return query(
            """SELECT id, no, start_name, end_name, city, rtype FROM routes
               WHERE no LIKE ? ORDER BY (no = ?) DESC, length(no), no LIMIT $limit""",
            arrayOf("%$k%", k),
        ) { it.toRoute() }
    }

    /** 노선 + 노선 정보(첫차·막차·배차 — 데이터 저장소가 채운 route_info) */
    fun routeById(id: String): BusRoute? = runCatching {
        query("""SELECT r.id, r.no, r.start_name, r.end_name, r.city, r.rtype, i.first, i.last, i.interval, i.int_sat, i.int_sun
                 FROM routes r LEFT JOIN route_info i ON i.id = r.id WHERE r.id = ?""", arrayOf(TagoIds.legacy(id))) { c ->
            fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i)
            BusRoute(
                routeId = TagoIds.current(c.getString(0)), routeNo = c.getString(1), routeType = str(5), startName = str(2), endName = str(3),
                firstTime = str(6), lastTime = str(7), interval = int(8), intervalSat = int(9), intervalSun = int(10), cityCode = c.getString(4),
            )
        }.firstOrNull()
    }.getOrElse { // route_info 표가 없는 옛 데이터
        query("SELECT id, no, start_name, end_name, city, rtype FROM routes WHERE id = ?", arrayOf(TagoIds.legacy(id))) { it.toRoute() }.firstOrNull()
    }

    /** 이 데이터의 버전 (meta) */
    fun version(): String? = query("SELECT v FROM meta WHERE k = 'version'", emptyArray()) { it.getString(0) }.firstOrNull()

    /** 앱에 넣어둔 노선의 실시간 연결 방식: tago(같은 ID) / resolve(번호로 TAGO 노선 찾기) / none(실시간 없음) / null(앱에 없음) */
    fun routeRealtime(id: String): String? =
        query("SELECT realtime FROM routes WHERE id = ?", arrayOf(TagoIds.legacy(id))) { if (it.isNull(0)) null else it.getString(0) }.firstOrNull()

    /**
     * 앱에 넣어둔 노선의 정류장 순서 (서울·인천·세종·원주·포항 + 데이터 저장소가 채운 TAGO 노선). 없으면 빈 목록 → 서버에서.
     * TAGO 와 같은 ID 로 실시간이 되는 노선인데 정류장이 군데군데 빠져 있으면([RouteStopsCheck]) 빈 목록 → 서버의 지금 목록을 씀
     * (2026-11-01 데이터: 익산 노선 211개 중 191개가 빠짐 — 65번 53곳 중 19곳만 있어서 노선 선이 중간에 끊김)
     */
    fun routeStops(routeId: String): List<RouteStop> = routeStopsRaw(routeId).let { list ->
        if (list.isNotEmpty() && RouteStopsCheck.incomplete(list.map { it.ord }) && routeRealtime(routeId) == "tago") emptyList() else list
    }

    private fun routeStopsRaw(routeId: String): List<RouteStop> = query(
        // 이름은 정류장 표에서 (노선 정류장 표에는 연결 안 된 정류장만 이름을 둠 → 용량 절약)
        """SELECT r.stop_id, COALESCE(NULLIF(r.name, ''), s.name, '정류장'), r.seq, r.updown, COALESCE(r.lat, s.lat), COALESCE(r.lng, s.lng), s.no
           FROM route_stops r LEFT JOIN stops s ON s.id = r.stop_id WHERE r.route_id = ? ORDER BY r.seq""",
        arrayOf(TagoIds.legacy(routeId)),
    ) {
        // 지도에서 손으로 고친 정류장 위치가 있으면 그것 ([MapEdits])
        val fix = if (it.isNull(0)) null else MapEdits.stop(it.getString(0))
        RouteStop(
            nodeId = if (it.isNull(0)) "x:" + routeId + ":" + it.getInt(2) else TagoIds.current(it.getString(0)),
            name = it.getString(1),
            nodeNo = if (it.isNull(6)) null else it.getString(6),
            ord = it.getInt(2),
            upDown = if (it.isNull(3)) null else it.getString(3),
            lat = fix?.lat ?: if (it.isNull(4)) null else it.getDouble(4),
            lng = fix?.lng ?: if (it.isNull(5)) null else it.getDouble(5),
        )
    }

    /**
     * 정류장을 지나는 노선 (앱에 넣어둔 노선 중 실시간이 같은 ID 이거나 없는 것: 서울·인천).
     * 번호로 연결하는 노선(세종·원주·포항)은 실시간 도착 노선 ID 와 달라서 서버 목록을 쓴다.
     */
    fun stopRoutes(stopId: String): List<BusRoute> = query(
        """SELECT DISTINCT ro.id, ro.no, ro.start_name, ro.end_name, ro.city, ro.rtype FROM route_stops r
           JOIN routes ro ON ro.id = r.route_id WHERE r.stop_id = ? AND ro.realtime IN ('tago', 'none')""",
        arrayOf(TagoIds.legacy(stopId)),
    ) { it.toRoute() }

    /** 앱 안 경기 노선 번호 (경기도_BMS노선인가정보 5,260개 + 노선 정류장 순서의 첫·끝 정류장) */
    fun gyeonggiFileRoutes(): List<BusRoute> = query("SELECT id, no, start_name, end_name FROM gbis_routes", emptyArray()) {
        BusRoute(
            routeId = it.getString(0), routeNo = it.getString(1), routeType = null,
            startName = if (it.isNull(2)) null else it.getString(2), endName = if (it.isNull(3)) null else it.getString(3),
            firstTime = null, lastTime = null, interval = null, intervalSat = null, intervalSun = null, cityCode = "31",
        )
    }

    /** 이 정류장의 다음 정류장 이름 (가장 많은 노선이 가는 쪽) — 검색 결과의 '○○ 방면' */
    fun nextStopName(stopId: String): String? = runCatching {
        query("""SELECT COALESCE(NULLIF(n.name, ''), s.name) nm FROM route_stops a
                 JOIN route_stops n ON n.route_id = a.route_id AND n.seq = a.seq + 1
                 LEFT JOIN stops s ON s.id = n.stop_id
                 WHERE a.stop_id = ? GROUP BY nm ORDER BY COUNT(*) DESC LIMIT 1""", arrayOf(TagoIds.legacy(stopId))) { if (it.isNull(0)) null else it.getString(0) }
            .firstOrNull()
    }.getOrNull()

    /** 경기 노선 모양 (경로이력 2025-10, 6m 단순화, polyline 정밀도 5) */
    fun gyeonggiShape(routeId: String): List<LatLng>? =
        query("SELECT poly FROM gbis_shapes WHERE id = ?", arrayOf(routeId)) { it.getString(0) }.firstOrNull()
            ?.let { Polyline.decode(it, 5) }?.takeIf { it.size >= 2 }

    /** 이 정류장을 지나는 노선 ID (앱 안 노선 정류장 순서에서, prefix 예: "GGB") */
    fun routeIdsAtStop(stopId: String, prefix: String): List<String> =
        query("SELECT DISTINCT route_id FROM route_stops WHERE stop_id = ? AND route_id LIKE ?", arrayOf(TagoIds.legacy(stopId), "$prefix%")) { TagoIds.current(it.getString(0)) }

    /** 앱에 넣어둔 실제 운행 시간표 (광주): 방향 이름 → 출발 시각(분) */
    fun timetable(city: String, routeNo: String): Map<String, List<Int>> =
        query("SELECT dir, minute FROM timetable WHERE city = ? AND route_no = ? ORDER BY minute", arrayOf(city, routeNo)) {
            it.getString(0) to it.getInt(1)
        }.groupBy({ it.first }, { it.second })

    /** 교통안전공단 노선이 있는 TAGO 도시코드 (이 지역은 노선번호 검색을 TAGO 대신 앱 안 목록으로) */
    val routeCities: Set<String> by lazy { query("SELECT DISTINCT city FROM routes", emptyArray()) { it.getString(0) }.toSet() }

    /**
     * 이 좌표 근처(약 15km) TSB 정류장들의 TAGO 도시코드, 가까운 순.
     * TSB 정류장은 노선을 운영하는 시군의 도시코드로 조회해야 그 시군 버스가 나와서 후보로 쓴다.
     */
    fun nearbyTsbCities(lat: Double, lng: Double, limit: Int = 5): List<String> = query(
        """SELECT city FROM stops
           WHERE id LIKE 'TSB%' AND city NOT LIKE 'TS%' AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?
           GROUP BY city ORDER BY MIN((lat - ?) * (lat - ?) + (lng - ?) * (lng - ?)) LIMIT $limit""",
        arrayOf(lat - 0.14, lat + 0.14, lng - 0.17, lng + 0.17, lat, lat, lng, lng).map { it.toString() }.toTypedArray(),
    ) { it.getString(0) }

    /** 별도 코드(TS…) 정류장 → TAGO 도시코드 (이름으로 맞춘 대응표, 없으면 null) */
    fun tagoCity(code: String): String? =
        query("SELECT tago FROM city_alias WHERE code = ?", arrayOf(code)) { it.getString(0) }.firstOrNull()

    /** 파일의 예전 ID 는 TAGO 가 지금 쓰는 ID 로 바꿔 내보냄 ([TagoIds]) */
    private fun Cursor.toRoute() = BusRoute(
        routeId = TagoIds.current(getString(0)),
        routeNo = getString(1),
        routeType = if (columnCount > 5 && !isNull(5)) getString(5) else null, // 농어촌(교통안전공단), 간선·지선(서울) …
        startName = if (isNull(2)) null else getString(2),
        endName = if (isNull(3)) null else getString(3),
        firstTime = null, lastTime = null, interval = null, intervalSat = null, intervalSun = null,
        cityCode = getString(4),
    )

    /** "전라북도 김제시" → "김제시" (목록에 짧게) */
    fun shortCityName(code: String): String? = cityCache[code]?.substringAfterLast(' ')

    /** 이 좌표 근처(반경 m)의 모든 지역 정류장, 가까운 순 (길찾기: 다른 지역으로 가는 경로까지) */
    fun near(lat: Double, lng: Double, radiusM: Double, limit: Int): List<Pair<BusStop, Double>> {
        val dLat = radiusM / 111_000.0
        val dLng = radiusM / 90_000.0
        return query(
            """SELECT id, name, lat, lng, no, city FROM stops
               WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?""",
            arrayOf(lat - dLat, lat + dLat, lng - dLng, lng + dLng).map { it.toString() }.toTypedArray(),
        ) { it.toStop() }
            .map { it to com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat!!, it.lng!!) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .take(limit)
    }

    /** id → 좌표 (전주시 API 는 정류장 좌표를 주지 않아 내장 DB 에서) */
    fun coords(ids: Collection<String>): Map<String, LatLng> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, LatLng>()
        ids.chunked(400).forEach { part ->
            // 파일은 예전 ID 라서 되돌려 찾고, 결과는 물어본 ID 로 돌려줌
            val asked = part.associateBy { TagoIds.legacy(it) }
            query(
                "SELECT id, lat, lng FROM stops WHERE id IN (${part.joinToString(",") { "?" }})",
                asked.keys.toTypedArray(),
            ) { it.getString(0) to (MapEdits.stop(it.getString(0)) ?: LatLng(it.getDouble(1), it.getDouble(2))) }.forEach { (k, v) -> out[asked[k] ?: k] = v }
        }
        return out
    }

    fun byId(id: String): BusStop? =
        query("SELECT id, name, lat, lng, no, city FROM stops WHERE id = ?", arrayOf(TagoIds.legacy(id))) { it.toStop() }.firstOrNull()

    fun cityNames(): Map<String, String> =
        query("SELECT code, name FROM cities", emptyArray()) { it.getString(0) to it.getString(1) }.toMap()

    private fun Cursor.toStop(): BusStop {
        // 지도에서 손으로 고친 정류장 위치가 있으면 그것 ([MapEdits])
        val fix = MapEdits.stop(getString(0))
        return BusStop(
            cityCode = getString(5),
            nodeId = TagoIds.current(getString(0)),
            name = getString(1),
            nodeNo = if (isNull(4)) null else getString(4),
            lat = fix?.lat ?: getDouble(2),
            lng = fix?.lng ?: getDouble(3),
        )
    }

    /** 새 데이터로 바뀌어 이 DB 가 이미 닫혔으면(옛 참조를 들고 있던 곳) 지금 열린 DB 로 다시 물음 */
    private fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> = try {
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            out
        }
    } catch (e: IllegalStateException) {
        val now = instance
        if (!db.isOpen && now != null && now !== this) now.query(sql, args, map) else throw e
    }

    companion object {
        /** 데이터 버전이 바뀌면 기기에서 새로 복사 (교통안전공단 정류장·노선 반영) */
        private const val VERSION = "20261013c"
        @Volatile private var instance: StopDb? = null

        /** 이미 열린 DB (아직 안 열렸으면 null) */
        fun peek(): StopDb? = instance

        suspend fun get(ctx: Context): StopDb? {
            instance?.let { return it }
            return withContext(Dispatchers.IO) {
                synchronized(this@Companion) {
                    instance ?: open(ctx.applicationContext)?.also { instance = it }
                }
            }
        }

        /** 데이터 저장소에서 받은 데이터 파일 이름 (DataUpdater) */
        const val DOWNLOADED = "bus_stops_dl.db"

        /** 파일의 데이터 버전 (열 수 없으면 null) */
        fun versionOf(file: File): String? = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT v FROM meta WHERE k = 'version'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()

        /** 새 데이터를 받았을 때: 새로 열고, 쓰던 것은 잠시 뒤 닫음 (진행 중인 조회가 끊기지 않게) */
        fun reload(ctx: Context) {
            val old: StopDb?
            synchronized(this) {
                old = instance
                instance = open(ctx.applicationContext)
            }
            if (old != null && old !== instance) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { old.db.close() } }, 60_000)
            }
        }

        /**
         * 앱 안 데이터(assets) 와 데이터 저장소에서 받은 데이터(bus_stops_dl.db) 중 더 새것을 연다.
         * 앱 안 데이터는 처음 한 번만 복사 (데이터 버전이 바뀌면 파일 이름이 바뀌어 새로 복사하고 옛 파일은 지운다).
         */
        private fun open(ctx: Context): StopDb? = runCatching {
            val dl = ctx.getDatabasePath(DOWNLOADED)
            if (dl.exists()) {
                if (DataManifest.isNewer(versionOf(dl), VERSION)) {
                    // 받은 데이터를 쓰면 앱 안 데이터 복사본(약 63MB)은 필요 없음 → 지움 (앱 업데이트로 앱 안 데이터가 더 새로워지면 다시 복사)
                    dl.parentFile?.listFiles()
                        ?.filter { it.name.startsWith("bus_stops_") && !it.name.startsWith("bus_stops_dl") }
                        ?.forEach { it.delete() }
                    return@runCatching StopDb(SQLiteDatabase.openDatabase(dl.path, null, SQLiteDatabase.OPEN_READONLY))
                }
                dl.delete() // 앱 업데이트로 앱 안 데이터가 더 새로워짐
            }
            val file = ctx.getDatabasePath("bus_stops_$VERSION.db")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                ctx.assets.open("bus_stops.db").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                if (!tmp.renameTo(file)) error("정류장 DB 복사 실패")
                file.parentFile?.listFiles()
                    ?.filter { it.name.startsWith("bus_stops_") && it.name != file.name && !it.name.startsWith("bus_stops_dl") }
                    ?.forEach { it.delete() }
            }
            StopDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
        }.onFailure { Log.e("TagoMap", "정류장 DB 열기 실패", it) }.getOrNull()
    }
}
