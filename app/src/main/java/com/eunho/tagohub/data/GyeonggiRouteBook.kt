package com.eunho.tagohub.data

import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 경기 노선 번호부 (노선 ID → 번호·종류·기점·종점).
 * 앱 안 경기도_BMS노선인가정보(5,260개)로 바로 쓰고, 처음 한 번만 노선 검색을 숫자 0~9 로 10번 해서 최신 목록으로 고쳐 휴대폰에 계속 저장한다
 * (경기 노선 검색은 번호에 그 글자가 들어간 노선을 모두 돌려줌 — "12" → 12, 1200, 1241, H120A, G1200 …).
 * 앱을 업데이트하면 저장소가 비워져서 다시 받는다. 10번 중 하나라도 실패하면 저장하지 않는다(빠진 노선 없게).
 * 지금 운행 중인 노선만 들어 있어서, 앱 안 노선 정류장 파일(2023)에 남은 폐지 노선을 걸러내는 데도 쓴다.
 */
object GyeonggiRouteBook {
    private const val KEY = "gbis_book"
    @Volatile private var book: Map<String, BusRoute>? = null
    private val lock = Mutex()

    /**
     * 번호부 = 앱 안 노선 번호(경기도_BMS노선인가정보) + 처음 한 번 받은 최신 목록(이게 우선).
     * 앱 안 파일은 예전 자료라(2026 실제 응답과 비교: 46개 중 번호 바뀜 1, 새 노선 14) 최신 목록으로 고치고 더한다.
     * 최신 목록을 못 받으면(오프라인 등) 앱 안 파일만으로 보여주고, 다음에 다시 받아본다.
     */
    suspend fun get(repo: GyeonggiRepository): Map<String, BusRoute>? {
        book?.let { return it }
        return lock.withLock {
            book ?: run {
                val file = withContext(Dispatchers.IO) { StopDb.peek()?.gyeonggiFileRoutes() }.orEmpty()
                val latest = withContext(Dispatchers.IO) { StaticCache.routes(KEY) } ?: fetch(repo)
                val all = combine(file, latest.orEmpty())
                if (latest != null) book = all // 최신 목록까지 합쳤을 때만 기억 (못 받았으면 다음에 다시)
                all.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun fetch(repo: GyeonggiRepository): List<BusRoute>? = coroutineScope {
        val lists = ('0'..'9').map { k -> async { repo.searchRoutes(k.toString()) } }.awaitAll()
        if (lists.any { it !is TagoResult.Success }) return@coroutineScope null
        val all = merge(lists.map { (it as TagoResult.Success).data }).values.toList()
        if (all.isEmpty()) return@coroutineScope null
        withContext(Dispatchers.IO) { StaticCache.putRoutes(KEY, all) }
        all
    }

    /** 앱 안 파일 + 최신 목록: 같은 노선은 최신 목록 쪽 (바뀐 번호·기점·종점), 파일에만 있는 노선도 유지 */
    fun combine(file: List<BusRoute>, latest: List<BusRoute>): Map<String, BusRoute> =
        file.associateBy { it.routeId } + latest.associateBy { it.routeId }

    /** 숫자별 검색 결과 합치기 (같은 노선은 한 번) */
    fun merge(lists: List<List<BusRoute>>): Map<String, BusRoute> = lists.flatten().associateBy { it.routeId }

    /** 번호 검색: 그대로 같은 번호 → 짧은 번호 → 번호순 */
    fun search(book: Map<String, BusRoute>, q: String): List<BusRoute> {
        val k = q.trim()
        if (k.isEmpty()) return emptyList()
        return book.values.filter { k in it.routeNo }
            .sortedWith(compareBy<BusRoute>({ it.routeNo != k }, { it.routeNo.length }, { it.routeNo }))
    }

    /** 앱 안 노선 정류장(2023 파일)이 지금 노선과 같은지: 첫 정류장 이름이 노선 정보의 기점과 맞는지 */
    fun sameStop(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true // 모르면 그대로 씀
        fun n(s: String) = s.replace(Regex("[\\s.·()]"), "")
        val x = n(a)
        val y = n(b)
        return x == y || x.startsWith(y.take(4)) || y.startsWith(x.take(4))
    }
}
