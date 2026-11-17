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
 * 주차장 (전국주차장정보표준데이터 1만 7천여 곳: 이름·면 수·요금·운영시간·전화). 웹 site/js/parking.js 와 같은 자료.
 * 서버 정적 파일 parking/<칸>.json (pipeline/build_parking.mjs, 위도·경도 0.2도 칸) 중 화면에 걸친 칸만 받고, 받은 칸은 기억.
 */
object Parking {
    const val LAYER = "parking"
    /** 한 번에 보여 줄 수 (화면 가운데에서 가까운 것부터) */
    private const val MAX = 200
    /** 한 번에 받는 칸 수 (넓게 보면 안 받음) */
    private const val MAX_TILES = 12

    data class Lot(
        val id: String, val name: String, val lat: Double, val lng: Double,
        val spaces: Int, val kind: String, val fee: String, val hours: String, val phone: String,
    )

    /** 칸 이름 (pipeline/build_parking.mjs 와 같음) */
    fun tile(lat: Double, lng: Double) = "${floor(lat * 5).toInt()}_${floor(lng * 5).toInt()}"

    /** 화면 범위에 걸친 칸 이름들 */
    fun tilesIn(s: Double, w: Double, n: Double, e: Double): List<String> {
        val out = ArrayList<String>()
        for (a in floor(s * 5).toInt()..floor(n * 5).toInt()) for (o in floor(w * 5).toInt()..floor(e * 5).toInt()) out += "${a}_$o"
        return out
    }

    /** 칸 자료 [[이름, 위도, 경도, 면 수, 구분, 요금, 운영시간, 전화]] 읽기 (MiniJson: 단위 테스트에서도 돌게) */
    fun parseTile(tile: String, body: String?): List<Lot> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? List<*> ?: return emptyList()
        return root.mapIndexedNotNull { i, v ->
            val a = v as? List<*> ?: return@mapIndexedNotNull null
            val s = { k: Int -> a.getOrNull(k)?.toString().orEmpty() }
            val lat = s(1).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lng = s(2).toDoubleOrNull() ?: return@mapIndexedNotNull null
            Lot("$tile:$i", s(0).ifBlank { "주차장" }, lat, lng, s(3).toDoubleOrNull()?.toInt() ?: 0, s(4), s(5), s(6), s(7))
        }
    }

    /** 지도 점: 누르면 "공영 노외 · 169면 · 5분 250원 · 24시간 · 02-…" */
    fun feature(l: Lot) = ItsRoadFeature(
        "$LAYER:${l.id}", LAYER, l.name,
        listOf(l.kind, if (l.spaces > 0) "${l.spaces}면" else "", l.fee, l.hours, l.phone).filter { it.isNotBlank() }.joinToString(" · "),
        l.lat, l.lng,
    )

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    private var have: Set<String>? = null
    private val tiles = HashMap<String, List<Lot>>()

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val base = DataProxy.current?.url?.trimEnd('/') ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    /** 화면 범위의 주차장 점 */
    suspend fun features(s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> = coroutineScope {
        val index = lock.withLock {
            have ?: get("parking/index.json")?.let { body ->
                val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                (root?.get("tiles") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()
            }?.also { have = it }
        } ?: return@coroutineScope emptyList()
        val want = tilesIn(s, w, n, e).filter { it in index }.take(MAX_TILES)
        val lots = want.map { t ->
            async {
                lock.withLock { tiles[t] } ?: parseTile(t, get("parking/$t.json")).also { got -> if (got.isNotEmpty()) lock.withLock { tiles[t] = got } }
            }
        }.awaitAll().flatten()
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        lots.filter { it.lat in s..n && it.lng in w..e }
            .sortedBy { hypot(it.lat - cla, (it.lng - clo) * 0.8) }.take(MAX).map(::feature)
    }
}
