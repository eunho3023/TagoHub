package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 최근 길찾기한 목적지 (기기에 3개까지 저장). 내비게이션 탭 "최근 목적지"에 보임 */
object RecentDestinations {
    data class Item(val name: String, val sub: String?, val lat: Double, val lng: Double)

    private const val PREFS = "recent_destinations"
    private const val KEY = "items"
    private const val MAX = 3

    private var prefs: android.content.SharedPreferences? = null
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val arr = JSONArray(p.getString(KEY, null) ?: return@runCatching emptyList())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.getString("n"), o.optString("s").ifBlank { null }, o.getDouble("a"), o.getDouble("o"))
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        _items.value = emptyList()
        prefs?.edit()?.remove(KEY)?.apply()
    }

    fun add(name: String, sub: String?, lat: Double, lng: Double) {
        if (name.isBlank() || name == "내 위치" || name == "지도 중심") return
        val next = (listOf(Item(name, sub, lat, lng)) + _items.value.filterNot { it.name == name }).take(MAX)
        if (next == _items.value) return
        save(next)
    }

    /** 목적지 하나만 지움 */
    fun remove(name: String) {
        val next = _items.value.filterNot { it.name == name }
        if (next != _items.value) save(next)
    }

    private fun save(next: List<Item>) {
        _items.value = next
        val json = JSONArray().also { a -> next.forEach { a.put(JSONObject().put("n", it.name).put("s", it.sub ?: "").put("a", it.lat).put("o", it.lng)) } }
        prefs?.edit()?.putString(KEY, json.toString())?.apply()
    }
}
