package com.eunho.tagohub.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 경로 기록 한 줄: 어디서 어디로, 어떤 방법으로, 언제. [guided] 는 실제로 안내를 시작한 경로 */
data class RouteRecord(
    val fromName: String, val toName: String, val toLat: Double, val toLng: Double,
    val fromLat: Double? = null, val fromLng: Double? = null,
    val mode: String = "", val guided: Boolean = false, val atMs: Long = 0L,
) {
    /** 같은 경로(출발·도착 이름이 같음)인지 */
    fun sameRoute(o: RouteRecord) = fromName == o.fromName && toName == o.toName
}

/** 기록 목록을 만드는 규칙 (저장·화면과 따로 둬서 시험하기 쉽게) */
object HistoryLogic {
    const val MAX_SEARCHES = 30
    const val MAX_ROUTES = 50

    /** 검색어를 맨 앞에 넣음. 공백만이거나 한 글자뿐이면 안 넣고, 같은 말(대소문자 무시)은 하나로 */
    fun addSearch(list: List<String>, query: String, max: Int = MAX_SEARCHES): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 2) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    /** 경로를 맨 앞에 넣음. 같은 경로는 하나로(안내를 시작했었다면 그 표시는 남김). 출발·도착이 같은 이름이면 안 넣음 */
    fun addRoute(list: List<RouteRecord>, r: RouteRecord, max: Int = MAX_ROUTES): List<RouteRecord> {
        if (r.toName.isBlank() || r.fromName == r.toName) return list
        val old = list.firstOrNull { it.sameRoute(r) }
        val merged = r.copy(guided = r.guided || old?.guided == true)
        return (listOf(merged) + list.filterNot { it.sameRoute(r) }).take(max)
    }

    /** JSON 문자열 값: 따옴표·역슬래시·줄바꿈 같은 글자를 이스케이프 (org.json 은 단위 시험에서 못 쓰므로 직접) */
    private fun q(v: String): String = buildString {
        append('"')
        for (c in v) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun encodeSearches(list: List<String>): String = list.joinToString(",", "[", "]") { q(it) }

    fun decodeSearches(text: String?): List<String> = runCatching {
        (com.eunho.tagohub.core.MiniJson.parse(text ?: return emptyList()) as List<*>).map { it.toString() }
    }.getOrDefault(emptyList())

    fun encodeRoutes(list: List<RouteRecord>): String = list.joinToString(",", "[", "]") { r ->
        "{" + listOf(
            "\"f\":" + q(r.fromName), "\"t\":" + q(r.toName), "\"a\":${r.toLat}", "\"o\":${r.toLng}",
            "\"fa\":${r.fromLat ?: "null"}", "\"fo\":${r.fromLng ?: "null"}",
            "\"m\":" + q(r.mode), "\"g\":${r.guided}", "\"ms\":${r.atMs}",
        ).joinToString(",") + "}"
    }

    fun decodeRoutes(text: String?): List<RouteRecord> = runCatching {
        (com.eunho.tagohub.core.MiniJson.parse(text ?: return emptyList()) as List<*>).mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            fun d(k: String) = o[k]?.toString()?.toDoubleOrNull()
            RouteRecord(
                o["f"]?.toString() ?: return@mapNotNull null, o["t"]?.toString() ?: return@mapNotNull null,
                d("a") ?: return@mapNotNull null, d("o") ?: return@mapNotNull null, d("fa"), d("fo"),
                o["m"]?.toString().orEmpty(), o["g"]?.toString() == "true", o["ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 검색 기록: 결과를 눌러 연 검색어를 기기에 저장 (최근 순, 30개까지). 클라우드 동기화 대상.
 * [enabled] 를 끄면 새로 저장하지 않음(이미 있는 것은 그대로 두고 지우기로 정리).
 */
object SearchHistory {
    private const val PREFS = "search_history"
    private const val KEY = "items"
    private const val ON = "on"
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _items.value = HistoryLogic.decodeSearches(p.getString(KEY, null))
        _enabled.value = p.getBoolean(ON, true)
    }

    fun add(query: String) {
        if (!_enabled.value) return
        val next = HistoryLogic.addSearch(_items.value, query)
        if (next != _items.value) save(next)
    }

    fun remove(query: String) = save(_items.value.filterNot { it == query })
    fun clear() = save(emptyList())
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(ON, on)?.apply()
    }

    private fun save(list: List<String>) {
        _items.value = list
        prefs?.edit()?.putString(KEY, HistoryLogic.encodeSearches(list))?.apply()
    }
}

/** 경로 기록: 길찾기를 한 경로와 안내를 시작한 경로 (최근 순, 50개까지). 클라우드 동기화 대상. */
object RouteHistory {
    private const val PREFS = "route_history"
    private const val KEY = "items"
    private const val ON = "on"
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<RouteRecord>>(emptyList())
    val items: StateFlow<List<RouteRecord>> = _items
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _items.value = HistoryLogic.decodeRoutes(p.getString(KEY, null))
        _enabled.value = p.getBoolean(ON, true)
    }

    fun add(r: RouteRecord) {
        if (!_enabled.value) return
        val next = HistoryLogic.addRoute(_items.value, r.copy(atMs = if (r.atMs == 0L) System.currentTimeMillis() else r.atMs))
        if (next != _items.value) save(next)
    }

    fun remove(r: RouteRecord) = save(_items.value.filterNot { it == r })
    fun clear() = save(emptyList())
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(ON, on)?.apply()
    }

    private fun save(list: List<RouteRecord>) {
        _items.value = list
        prefs?.edit()?.putString(KEY, HistoryLogic.encodeRoutes(list))?.apply()
    }
}
