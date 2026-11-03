package com.eunho.tagohub.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 검색 화면의 최근 기록 한 줄: 검색어, 고른 장소·정류장·노선·역. [atMs] 는 고른 시각 (예전 검색어를 옮겨 온 것은 0 = 날짜 모름).
 * 정류장·노선·역은 즐겨찾기와 같은 칸을 써서 [toFavorite] 로 다시 열 수 있다.
 */
data class RecentItem(
    val kind: Kind,
    /** 검색어 / 장소 이름 / 정류장 이름 / 노선 번호 / 역 이름 */
    val title: String,
    /** 장소 주소 / 정류장 번호 / "기점 ↔ 종점" / 지나는 노선들 */
    val sub: String? = null,
    /** 정류장·노선·역 ID (검색어·장소는 비움) */
    val id: String = "",
    val cityCode: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    /** 정류장 번호(ARS) / 노선 종류 / 장소 분류 */
    val extra: String? = null,
    val atMs: Long = 0L,
) {
    enum class Kind { Query, Place, Stop, Route, Station }

    /** 같은 것인지 (다시 고르면 맨 앞으로 올리고 하나로) */
    val key: String
        get() = when (kind) {
            Kind.Query -> "q:" + title.trim().lowercase()
            Kind.Place -> "p:" + title + "|" + (lat?.let { "%.5f".format(java.util.Locale.ROOT, it) } ?: "") + "," + (lng?.let { "%.5f".format(java.util.Locale.ROOT, it) } ?: "")
            else -> kind.name + ":" + id
        }

    /** 정류장·노선·역이면 즐겨찾기 모양으로 (지도에서 여는 데 그대로 씀) */
    fun toFavorite(): Favorite? = when (kind) {
        Kind.Stop -> Favorite(Favorite.Kind.Stop, id, title, sub, cityCode, lat, lng, extra)
        Kind.Route -> Favorite(Favorite.Kind.Route, id, title, sub, cityCode, extra = extra)
        Kind.Station -> Favorite(Favorite.Kind.Station, id, title, sub, "", lat, lng)
        else -> null
    }

    fun toPlace(): Place2? = if (kind == Kind.Place && lat != null && lng != null) Place2(title, sub, lat, lng, category = extra) else null

    companion object {
        fun query(q: String, at: Long) = RecentItem(Kind.Query, q.trim().replace(Regex("\\s+"), " "), atMs = at)
        fun place(p: Place2, at: Long) = RecentItem(Kind.Place, p.name, p.address, lat = p.lat, lng = p.lng, extra = p.category, atMs = at)
        fun of(f: Favorite, at: Long) = RecentItem(
            when (f.kind) {
                Favorite.Kind.Stop -> Kind.Stop
                Favorite.Kind.Route -> Kind.Route
                Favorite.Kind.Station -> Kind.Station
            },
            f.name, f.sub, f.id, f.cityCode, f.lat, f.lng, f.extra, at,
        )
    }
}

/** 최근 기록 목록 규칙과 저장 형식 (저장·화면과 따로 둬서 시험하기 쉽게) */
object RecentLogic {
    const val MAX = 60

    /** 맨 앞에 넣음. 같은 것은 하나로, 한 글자 검색어는 안 넣음 */
    fun add(list: List<RecentItem>, item: RecentItem, max: Int = MAX): List<RecentItem> {
        if (item.title.isBlank() || (item.kind == RecentItem.Kind.Query && item.title.trim().length < 2)) return list
        return (listOf(item) + list.filterNot { it.key == item.key }).take(max)
    }

    /** 두 목록을 합침 (계정 합치기): 같은 것은 최근 것 하나, 최근 순 */
    fun merge(a: List<RecentItem>, b: List<RecentItem>, max: Int = MAX): List<RecentItem> =
        (a + b).sortedByDescending { it.atMs }.distinctBy { it.key }.take(max)

    private fun esc(s: String?) = s?.replace("\\", "\\\\")?.replace("\t", "\\t")?.replace("\n", "\\n") ?: ""
    private fun unesc(s: String): String? {
        if (s.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** 한 줄에 하나, 칸은 탭: 종류 제목 부제 ID 도시 위도 경도 부가 시각 */
    fun encode(list: List<RecentItem>): String = list.joinToString("\n") { r ->
        listOf(r.kind.name, esc(r.title), esc(r.sub), esc(r.id), esc(r.cityCode), r.lat?.toString() ?: "", r.lng?.toString() ?: "", esc(r.extra), r.atMs.toString())
            .joinToString("\t")
    }

    fun decode(text: String?): List<RecentItem> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 9) return@mapNotNull null
        val kind = runCatching { RecentItem.Kind.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null
        RecentItem(
            kind, unesc(p[1]) ?: return@mapNotNull null, unesc(p[2]), unesc(p[3]).orEmpty(), unesc(p[4]).orEmpty(),
            p[5].toDoubleOrNull(), p[6].toDoubleOrNull(), unesc(p[7]), p[8].toLongOrNull() ?: 0L,
        )
    }
}

/**
 * 최근 기록 (검색 화면의 "최근"): 검색해서 고른 장소·정류장·노선·역과 검색어를 날짜와 함께, 최근 순 [RecentLogic.MAX] 개.
 * 기기에 저장하고 클라우드에도 맞춰 둠 ([CloudSyncSpec]). 검색 기록 저장을 끄면([SearchHistory.enabled]) 새로 남기지 않음.
 */
object RecentItems {
    private const val PREFS = "recent_items"
    private const val KEY = "items"
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<RecentItem>>(emptyList())
    val items: StateFlow<List<RecentItem>> = _items

    /** 앱 시작 때 ([SearchHistory.load] 다음에). 처음이면 예전 검색어를 날짜 없이 옮겨 옴 */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _items.value = if (p.contains(KEY)) RecentLogic.decode(p.getString(KEY, null)) else {
            SearchHistory.items.value.map { RecentItem.query(it, 0L) }.also { if (it.isNotEmpty()) save(it) }
        }
    }

    fun add(item: RecentItem) {
        if (!SearchHistory.enabled.value) return
        val next = RecentLogic.add(_items.value, item)
        if (next != _items.value) save(next)
    }

    fun remove(item: RecentItem) = save(_items.value.filterNot { it.key == item.key })
    fun clear() = save(emptyList())

    private fun save(list: List<RecentItem>) {
        _items.value = list
        prefs?.edit()?.putString(KEY, RecentLogic.encode(list))?.apply()
    }
}
