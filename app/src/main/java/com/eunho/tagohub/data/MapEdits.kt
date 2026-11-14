package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 지도 고치기: 사람이 손으로 고친 버스 노선 모양·정류장 위치·지하철역 위치·지하철 노선 모양.
 * 중계 서버 /w/edits (server/tago-proxy/src/edits.js) 에 저장돼 웹 버전과 같이 씀.
 * 앱을 열 때 한 번 받고(5분 기억) 노선 선·정류장 좌표·역 좌표를 그릴 때 고친 것이 있으면 그것을 씀.
 */
object MapEdits {
    enum class Kind(val key: String) { Bus("bus"), Rail("rail"), Stop("stop"), Station("station") }

    private val lines = ConcurrentHashMap<String, List<LatLng>>()   // "bus|id", "rail|노선"
    private val points = ConcurrentHashMap<String, LatLng>()        // "stop|id", "station|id"
    @Volatile private var loadedAt = 0L
    /** 서버 판 번호 (고친 기록 마지막 번호). 같으면 서버가 204 로 짧게 답함 */
    @Volatile private var serverV: Long = -1
    /** 이만큼 지나면 다시 확인 (고치면 바로 보이게 짧게, 안 바뀌었으면 서버가 목록 없이 답함) */
    const val POLL_MS = 15_000L

    // ── 관리자 키: 고치기는 관리자만 (서버 비밀 값 EDIT_ADMIN_KEY 와 같아야 저장됨). 이 기기에만 저장 ──
    private const val PREFS = "ui"
    private const val ADMIN_PREF = "edit_admin_key"
    const val ADMIN_HEADER = "X-TagoHub-Admin"
    @Volatile private var adminKey: String = ""
    /** 관리자 키를 넣은 기기인지 (고치기 단추를 보일지) */
    val admin = MutableStateFlow(false)

    fun loadAdmin(context: android.content.Context) {
        adminKey = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(ADMIN_PREF, null).orEmpty()
        admin.value = adminKey.length >= 16
    }

    /** 키가 서버와 맞으면 저장하고 true. 빈 글자면 지우고(관리자 끄기) false */
    suspend fun setAdminKey(context: android.content.Context, key: String): Boolean {
        val k = key.trim()
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (k.isEmpty()) { prefs.edit().remove(ADMIN_PREF).apply(); adminKey = ""; admin.value = false; return false }
        val b = base() ?: return false
        val ok = withContext(Dispatchers.IO) {
            runCatching { client.newCall(Request.Builder().url("$b/w/edits/check").header(ADMIN_HEADER, k).build()).execute().use { it.code == 200 } }.getOrDefault(false)
        }
        if (ok) { prefs.edit().putString(ADMIN_PREF, k).apply(); adminKey = k; admin.value = true }
        return ok
    }

    /** 고친 것이 바뀌면 올라감 (화면이 다시 그리게) */
    val version = MutableStateFlow(0L)

    private val client: OkHttpClient by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    fun busShape(routeId: String): List<LatLng>? = lines["bus|$routeId"] ?: lines["bus|${TagoIds.legacy(routeId)}"]
    /** 지하철 노선 고침 id: 권역마다 같은 이름(부산·서울 1호선)이 있어 "권역 노선" */
    fun railKey(region: SubwayRegion?, line: String): String = listOfNotNull(region?.label, line).joinToString(" ").take(40)
    /** 이 권역 이 노선의 고친 모양. 길찾기 노선 이름("수도권 2호선")과 역 자료 이름("2호선")이 달라도 같은 노선이면 */
    fun railShape(region: SubwayRegion?, line: String): List<LatLng>? = lines["rail|${railKey(region, line)}"]
        ?: lines.entries.firstOrNull { (k, _) ->
            val rest = k.removePrefix("rail|")
            val pre = region?.label?.let { "$it " }.orEmpty()
            k.startsWith("rail|") && rest.startsWith(pre) && StationName.sameLine(rest.removePrefix(pre), line)
        }?.value
    fun stop(id: String): LatLng? = points["stop|$id"] ?: points["stop|${TagoIds.legacy(id)}"]
    fun station(key: String): LatLng? = points["station|$key"]
    /** 역 고침 id: 역 코드는 권역마다 겹쳐서(0101 …) 노선 이름과 같이 */
    fun stationKey(s: MetroStation): String = "${s.line}:${s.code}:${s.name}".take(40)
    fun has(kind: Kind, id: String): Boolean = if (kind == Kind.Bus || kind == Kind.Rail) lines.containsKey("${kind.key}|$id") else points.containsKey("${kind.key}|$id")

    private fun base(): String? = DataProxy.current?.url?.trimEnd('/')

    /** 서버에서 고친 것 목록 받기 (15초 안에 확인했으면 그대로, 판 번호가 같으면 그대로). 실패하면 가진 것 유지 */
    suspend fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < POLL_MS) return
        val b = base() ?: return
        val q = if (serverV >= 0) "?v=$serverV" else ""
        val body = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$b/w/edits$q").header("Cache-Control", "no-cache").build()).execute().use { r ->
                    when { r.code == 204 -> ""; r.isSuccessful -> r.body.string(); else -> null }
                }
            }.getOrNull()
        } ?: return
        loadedAt = System.currentTimeMillis()
        if (body.isEmpty()) return // 바뀐 것 없음
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return
        serverV = o.optLong("v", -1)
        val newLines = HashMap<String, List<LatLng>>()
        val newPoints = HashMap<String, LatLng>()
        for (k in listOf(Kind.Bus, Kind.Rail)) o.optJSONObject(k.key)?.let { m ->
            m.keys().forEach { id -> Polyline.decode(m.optString(id), 5).takeIf { it.size >= 2 }?.let { newLines["${k.key}|$id"] = it } }
        }
        for (k in listOf(Kind.Stop, Kind.Station)) o.optJSONObject(k.key)?.let { m ->
            m.keys().forEach { id -> m.optJSONArray(id)?.let { a -> if (a.length() == 2) newPoints["${k.key}|$id"] = LatLng(a.optDouble(0), a.optDouble(1)) } }
        }
        val changed = newLines != HashMap(lines) || newPoints != HashMap(points)
        lines.keys.retainAll(newLines.keys); lines.putAll(newLines)
        points.keys.retainAll(newPoints.keys); points.putAll(newPoints)
        loadedAt = System.currentTimeMillis()
        if (changed) version.value = version.value + 1
    }

    /** 저장 (pts 가 null 이면 고친 것을 지우고 원래대로). 실패하면 이유 글 */
    suspend fun save(kind: Kind, id: String, pts: List<LatLng>?): String? {
        val b = base() ?: return "서버 주소가 없어 저장할 수 없어요"
        val data: Any = when {
            pts == null -> JSONObject.NULL
            kind == Kind.Bus || kind == Kind.Rail -> JSONArray(pts.map { JSONArray(listOf(round(it.lat, 5), round(it.lng, 5))) })
            else -> JSONArray(listOf(round(pts.first().lat, 6), round(pts.first().lng, 6)))
        }
        val json = JSONObject().put("kind", kind.key).put("id", id).put("data", data).toString()
        val code = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$b/w/edits").header(ADMIN_HEADER, adminKey).post(json.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    // 서버 판 번호를 맞춰 둠 (내가 고친 것을 다시 받지 않게)
                    if (r.code == 200) runCatching { JSONObject(r.body.string()).optLong("v", -1) }.getOrNull()?.takeIf { it >= 0 }?.let { serverV = it }
                    r.code
                }
            }.getOrDefault(-1)
        }
        if (code != 200) return when (code) { 429 -> "잠시 뒤에 다시 저장해 주세요"; 403 -> "관리자만 고칠 수 있어요. 설정에서 관리자 키를 확인해 주세요"; -1 -> "인터넷 연결을 확인해 주세요"; else -> "저장하지 못했어요 ($code)" }
        val k = "${kind.key}|$id"
        if (kind == Kind.Bus || kind == Kind.Rail) { if (pts == null) lines.remove(k) else lines[k] = pts }
        else { if (pts == null) points.remove(k) else points[k] = pts.first() }
        version.value = version.value + 1
        return null
    }

    private fun round(v: Double, d: Int): Double = Math.round(v * Math.pow(10.0, d.toDouble())) / Math.pow(10.0, d.toDouble())
}

/** 선 고치기 계산 (웹 site/js/edits.js 와 같음) */
object EditPath {
    private fun mx(lat: Double) = 111000.0 * Math.cos(Math.toRadians(lat))
    fun dist(a: LatLng, b: LatLng): Double = Math.hypot((b.lng - a.lng) * mx(a.lat), (b.lat - a.lat) * 111000.0)

    /** 점 p 에서 선분 a-b 까지 거리(m) */
    fun toSeg(p: LatLng, a: LatLng, b: LatLng): Double {
        val k = mx(p.lat)
        val ax = (a.lng - p.lng) * k; val ay = (a.lat - p.lat) * 111000.0
        val bx = (b.lng - p.lng) * k; val by = (b.lat - p.lat) * 111000.0
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        return Math.hypot(ax + t * dx, ay + t * dy)
    }

    /** 점 줄이기 (더글러스-포커, tol m) */
    fun simplify(pts: List<LatLng>, tol: Double = 6.0): List<LatLng> {
        if (pts.size < 3) return pts.toList()
        val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.size - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.size - 1) }
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            var best = -1; var bd = tol
            for (k in i + 1 until j) { val d = toSeg(pts[k], pts[i], pts[j]); if (d > bd) { bd = d; best = k } }
            if (best >= 0) { keep[best] = true; stack.add(i to best); stack.add(best to j) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** 누른 곳에 새 점: 가장 가까운 선분 사이 (선에서 maxM 넘게 떨어지면 가까운 끝에 이음) → (새 목록, 넣은 자리) */
    fun insertNear(pts: List<LatLng>, p: LatLng, maxM: Double = 80.0): Pair<List<LatLng>, Int> {
        var best = -1; var bd = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) { val d = toSeg(p, pts[i], pts[i + 1]); if (d < bd) { bd = d; best = i } }
        val out = pts.toMutableList()
        if (best >= 0 && bd <= maxM) { out.add(best + 1, p); return out to best + 1 }
        if (out.isEmpty() || dist(p, out.first()) < dist(p, out.last())) { out.add(0, p); return out to 0 }
        out.add(p); return out to out.lastIndex
    }

    /**
     * 선이 정류장(노선 순서대로)을 지나게: 정류장마다 선의 가까운 구간(maxM 안)을 찾아 그 자리에 정류장 좌표 점을 넣음
     * (가까운 점이 3m 안이면 그 점을 정류장 자리로 옮김). 앞 정류장보다 뒤쪽 구간에서만 찾아 순환 노선도 순서가 꼬이지 않음.
     * 선에서 너무 먼 정류장은 건너뜀. 웹 site/js/edits.js passStops 와 같음
     */
    fun passStops(line: List<LatLng>, stops: List<LatLng>, maxM: Double = 80.0): List<LatLng> {
        val out = line.toMutableList()
        if (out.size < 2) return out
        var cur = 0
        for (s in stops) {
            var best = -1
            var bd = maxM
            var k = cur
            while (k < out.size - 1) {
                val d = toSeg(s, out[k], out[k + 1])
                if (d <= bd) { bd = d; best = k } else if (best >= 0 && d > bd + 30) break   // 가장 가까운 곳을 지나 멀어지면 그만
                k++
            }
            if (best < 0) continue
            val a = out[best]
            val b = out[best + 1]
            cur = when {
                dist(a, s) <= 3.0 -> { out[best] = s; best }
                dist(b, s) <= 3.0 -> { out[best + 1] = s; best + 1 }
                else -> { out.add(best + 1, s); best + 1 }
            }
        }
        return out
    }

    /** 누른 곳에서 maxM 안의 가장 가까운 정류장 자리 (없으면 null) — 선을 정류장에 딱 붙이기 */
    fun nearestStop(stops: List<LatLng>, p: LatLng, maxM: Double): LatLng? {
        var best: LatLng? = null; var bd = maxM
        for (s in stops) { val d = dist(p, s); if (d <= bd) { bd = d; best = s } }
        return best
    }

    /** 누른 곳에서 maxM 안의 가장 가까운 점 번호 (없으면 -1) */
    fun nearestVertex(pts: List<LatLng>, p: LatLng, maxM: Double): Int {
        var best = -1; var bd = maxM
        pts.forEachIndexed { i, q -> val d = dist(p, q); if (d <= bd) { bd = d; best = i } }
        return best
    }
}
