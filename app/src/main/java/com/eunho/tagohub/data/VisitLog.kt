package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 사용자가 직접 저장한 방문 장소. 기록은 이 기기에만 보관하고 클라우드로 보내지 않습니다. */
data class VisitRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: String,
    val detail: String? = null,
    val lat: Double,
    val lng: Double,
    val atMs: Long = System.currentTimeMillis(),
    val stopId: String? = null,
    val cityCode: String? = null,
)

/** 방문 장소 기록: 최신순, 최대 500건. 사용자가 저장 버튼을 누른 경우에만 위치를 기록합니다. */
object VisitLog {
    private const val PREFS = "visit_places"
    private const val KEY = "items"
    private const val MAX_ITEMS = 500
    private var prefs: android.content.SharedPreferences? = null
    private val _items = MutableStateFlow<List<VisitRecord>>(emptyList())
    val items: StateFlow<List<VisitRecord>> = _items

    fun load(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _items.value = decode(prefs?.getString(KEY, null))
    }

    fun add(record: VisitRecord) {
        if (record.name.isBlank() || !record.lat.isFinite() || !record.lng.isFinite()) return
        val next = (listOf(record.copy(name = record.name.trim())) + _items.value.filterNot { it.id == record.id }).take(MAX_ITEMS)
        save(next)
    }

    fun addStop(stop: BusStop): Boolean {
        val lat = stop.lat ?: return false
        val lng = stop.lng ?: return false
        val detail = listOfNotNull(stop.nodeNo?.let { "정류장 번호 $it" }, stop.cityCode.takeIf { it.isNotBlank() }).joinToString(" · ")
        add(
            VisitRecord(
                name = stop.name,
                source = "버스 정류장",
                detail = detail.ifBlank { null },
                lat = lat,
                lng = lng,
                stopId = stop.nodeId,
                cityCode = stop.cityCode,
            ),
        )
        return true
    }

    fun remove(record: VisitRecord) = save(_items.value.filterNot { it.id == record.id })

    fun clear() = save(emptyList())

    private fun save(list: List<VisitRecord>) {
        _items.value = list
        prefs?.edit()?.putString(KEY, encode(list))?.apply()
    }

    private fun encode(list: List<VisitRecord>): String = JSONArray().apply {
        list.forEach { r ->
            put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("source", r.source)
                    .put("detail", r.detail)
                    .put("lat", r.lat)
                    .put("lng", r.lng)
                    .put("at", r.atMs)
                    .put("stopId", r.stopId)
                    .put("cityCode", r.cityCode),
            )
        }
    }.toString()

    private fun decode(text: String?): List<VisitRecord> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            if (!lat.isFinite() || !lng.isFinite()) return@mapNotNull null
            VisitRecord(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").trim().ifBlank { return@mapNotNull null },
                source = o.optString("source", "방문 장소"),
                detail = o.optString("detail").ifBlank { null },
                lat = lat,
                lng = lng,
                atMs = o.optLong("at", 0L),
                stopId = o.optString("stopId").ifBlank { null },
                cityCode = o.optString("cityCode").ifBlank { null },
            )
        }.take(MAX_ITEMS)
    }.getOrDefault(emptyList())
}
