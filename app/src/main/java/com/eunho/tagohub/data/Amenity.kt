package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 편의시설 (공공데이터포털 표준데이터: 공중화장실·전동휠체어 급속충전기·졸음쉼터·교통약자 이동지원센터·환승센터). 웹 site/js/amenity.js 와 같은 자료.
 * 서버 정적 파일 amenity/<종류>/<칸>.json (pipeline/build_amenities.mjs, 위도·경도 0.2도 칸, 적은 종류는 all.json 하나) 중 화면에 걸친 칸만 받고, 받은 칸은 기억.
 * 한 곳 = [이름, 위도, 경도, 첫째 줄, 둘째 줄]
 */
object Amenity {
    data class Kind(val key: String, val label: String, val color: Int)

    /** 지도 층 id → 종류 (글자·색은 웹과 같음) */
    val KINDS = linkedMapOf(
        "amenity_toilet" to Kind("toilet", "화", 0xFF00838F.toInt()),
        "amenity_wheel" to Kind("wheel", "휠", 0xFF6A1B9A.toInt()),
        "amenity_drowsy" to Kind("drowsy", "졸", 0xFFEF6C00.toInt()),
        "amenity_mobility" to Kind("mobility", "약", 0xFFAD1457.toInt()),
        "amenity_transfer" to Kind("transfer", "환", 0xFF1565C0.toInt()),
        "amenity_repair" to Kind("repair", "정", 0xFF5D4037.toInt()),
        "amenity_buslane" to Kind("buslane", "전", 0xFF0277BD.toInt()),
        "amenity_oneway" to Kind("oneway", "일", 0xFFC62828.toInt()),
        "amenity_crosswalk" to Kind("crosswalk", "횡", 0xFF455A64.toInt()),
    )
    val LAYERS: Set<String> get() = KINDS.keys

    /** 한 종류에서 한 번에 보여 줄 수 (화면 가운데에서 가까운 것부터) */
    private const val MAX = 150
    private const val MAX_TILES = 12

    data class Place(val id: String, val name: String, val lat: Double, val lng: Double, val lines: List<String>)

    /** 화면 범위에 걸친 칸 이름들, 가운데 칸부터 */
    fun tilesIn(s: Double, w: Double, n: Double, e: Double): List<String> {
        val cla = (s + n) / 2 * 5
        val clo = (w + e) / 2 * 5
        val out = ArrayList<Pair<String, Double>>()
        for (a in floor(s * 5).toInt()..floor(n * 5).toInt()) for (o in floor(w * 5).toInt()..floor(e * 5).toInt()) {
            out += "${a}_$o" to hypot(a + 0.5 - cla, o + 0.5 - clo)
        }
        return out.sortedBy { it.second }.map { it.first }
    }

    /** 칸 자료 읽기 (MiniJson: 단위 테스트에서도 돌게) */
    fun parseTile(layer: String, tile: String, body: String?): List<Place> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? List<*> ?: return emptyList()
        return root.mapIndexedNotNull { i, v ->
            val a = v as? List<*> ?: return@mapIndexedNotNull null
            val s = { k: Int -> a.getOrNull(k)?.toString().orEmpty() }
            val lat = s(1).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lng = s(2).toDoubleOrNull() ?: return@mapIndexedNotNull null
            Place("$tile:$i", s(0).ifBlank { "편의시설" }, lat, lng, listOf(s(3), s(4)).filter { it.isNotBlank() })
        }
    }

    /** 지도 점: 누르면 이름과 두 줄 */
    fun feature(layer: String, p: Place) = ItsRoadFeature("$layer:${p.id}", layer, p.name, p.lines.joinToString(" · "), p.lat, p.lng)

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    private val indexes = HashMap<String, List<String>>()
    private val tiles = HashMap<String, List<Place>>()

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val base = DataProxy.current?.url?.trimEnd('/') ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    /** 화면 범위의 한 종류 점 */
    suspend fun features(layer: String, s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> = coroutineScope {
        val kind = KINDS[layer]?.key ?: return@coroutineScope emptyList()
        val have = lock.withLock {
            indexes[kind] ?: get("amenity/$kind/index.json")?.let { body ->
                val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                (root?.get("tiles") as? List<*>)?.mapNotNull { it?.toString() }
            }?.also { indexes[kind] = it }
        } ?: return@coroutineScope emptyList()
        val want = if ("all" in have) listOf("all") else tilesIn(s, w, n, e).filter { it in have }.take(MAX_TILES)
        val places = want.map { t ->
            async {
                val key = "$kind/$t"
                lock.withLock { tiles[key] } ?: parseTile(layer, t, get("amenity/$key.json")).also { got -> if (got.isNotEmpty()) lock.withLock { tiles[key] = got } }
            }
        }.awaitAll().flatten()
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        places.filter { it.lat in s..n && it.lng in w..e }
            .sortedBy { hypot(it.lat - cla, (it.lng - clo) * 0.8) }.take(MAX).map { feature(layer, it) }
    }
}
