package com.eunho.tagohub.data

/**
 * 같은 사람의 두 계정(예: 구글로 가입한 것과 이메일로 가입한 것)을 하나로 합칠 때 클라우드 설정(users/{uid} 문서)을 합치는 규칙.
 * 값은 [CloudSyncSpec.field] 이름 → 문자열 또는 문자열 목록. Firebase 호출과 따로 두어 시험하기 쉽게.
 * - 즐겨찾기·지도 레이어·검색 기록·경로 기록: 두 계정 것을 모두 모음 (같은 것은 하나로, 지금 계정 것이 앞)
 * - 그 밖의 설정(집/회사·지역·테마 등): 지금 계정에 값이 있으면 그것, 없으면 다른 계정 것
 */
object AccountMerge {
    private val FAVORITES = CloudSyncSpec.field("bus", "favorites")
    private val OVERLAYS = CloudSyncSpec.field("bus", "overlays")
    private val SEARCHES = CloudSyncSpec.field("search_history", "items")
    private val ROUTES = CloudSyncSpec.field("route_history", "items")

    /** [mine] 은 지금 로그인한 계정, [other] 는 합쳐 들어올 계정. 동기화 항목이 아닌 값(updatedAt 등)은 버림 */
    fun merge(mine: Map<String, Any?>, other: Map<String, Any?>): Map<String, Any> {
        val out = HashMap<String, Any>()
        for ((file, key) in CloudSyncSpec.items) {
            val name = CloudSyncSpec.field(file, key)
            val a = mine[name]?.takeIf(::present)
            val b = other[name]?.takeIf(::present)
            val v: Any? = when {
                a == null -> b
                b == null -> a
                a is String && b is String -> when (name) {
                    FAVORITES -> favorites(a, b)
                    OVERLAYS -> (a.split(',') + b.split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
                    SEARCHES -> searches(a, b)
                    ROUTES -> routes(a, b)
                    else -> a
                }
                else -> a
            }
            if (v != null) out[name] = v
        }
        return out
    }

    private fun present(v: Any): Boolean = when (v) {
        is String -> v.isNotBlank()
        is List<*> -> v.isNotEmpty()
        else -> false
    }

    private fun favorites(a: String, b: String): String {
        val mine = FavoriteCodec.decode(a)
        val more = FavoriteCodec.decode(b).filter { f -> mine.none { it.kind == f.kind && it.id == f.id } }
        return FavoriteCodec.encode(mine + more)
    }

    private fun searches(a: String, b: String): String {
        val mine = HistoryLogic.decodeSearches(a)
        val more = HistoryLogic.decodeSearches(b).filter { q -> mine.none { it.equals(q, ignoreCase = true) } }
        return HistoryLogic.encodeSearches((mine + more).take(HistoryLogic.MAX_SEARCHES))
    }

    /** 같은 경로는 더 최근 것 하나로 (어느 쪽에서든 안내를 시작했었다면 그 표시는 남김), 최근 순 */
    private fun routes(a: String, b: String): String {
        val all = (HistoryLogic.decodeRoutes(a) + HistoryLogic.decodeRoutes(b)).sortedByDescending { it.atMs }
        val out = ArrayList<RouteRecord>()
        for (r in all) {
            val i = out.indexOfFirst { it.sameRoute(r) }
            if (i < 0) out += r else if (r.guided && !out[i].guided) out[i] = out[i].copy(guided = true)
        }
        return HistoryLogic.encodeRoutes(out.take(HistoryLogic.MAX_ROUTES))
    }
}
