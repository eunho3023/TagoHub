package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 관리자 페이지에서 직접 그린 노선 선형 — 서버 D1 의 /w/shapes.tsv.
 *
 * 형식은 앱에 들어 있는 bus_route_shapes.tsv 와 같다 (노선ID \t 첫점 \t 1e-5도 변화량).
 * 서버에 저장된 노선은 지도에서 기본 선형(서울·경기 공식 모양, 없으면 도로 경로)보다 먼저 쓴다.
 */
object RemoteShapes {
    private const val FILE = "shapes.tsv"
    private const val PREFS = "remote_shapes"
    private const val KEY_ETAG = "etag"

    const val MIN_GAP_MS = 60_000L

    private val _shapes = MutableStateFlow<Map<String, List<LatLng>>>(emptyMap())
    /** 지금 쓰는 서버 선형 (노선ID → 좌표). 값이 바뀌면 지도 선을 다시 그린다 */
    val shapes: StateFlow<Map<String, List<LatLng>>> = _shapes.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var lastTry = 0L
    private val lock = Mutex()

    fun init(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        // 앱의 기본 모양과 같은 형식이지만, 관리자 페이지가 "SEB" 없이 저장하므로 양쪽 다 받아 준다
        _shapes.value = RouteShapes.parse(RemoteTsv.read(app, FILE)).mapKeys { it.key.removePrefix("SEB") }
        loaded = true
    }

    /** 서버에 저장된 선형. 없으면 null */
    fun get(routeId: String): List<LatLng>? = _shapes.value[routeId.removePrefix("SEB")]?.takeIf { it.size >= 2 }

    fun has(routeId: String): Boolean = _shapes.value.containsKey(routeId.removePrefix("SEB"))

    suspend fun refresh(context: Context, force: Boolean = false): Boolean = lock.withLock {
        val app = context.applicationContext
        if (!loaded) init(app)
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < MIN_GAP_MS) return@withLock false
        lastTry = now
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val got = RemoteTsv.fetch(app, "/w/shapes.tsv", prefs.getString(KEY_ETAG, null), "X-Shape-Version")
        if (!got.ok) return@withLock false
        if (got.notModified) return@withLock true
        val parsed = RouteShapes.parse(got.text)
        if (parsed.isEmpty() && got.text.isNotBlank()) return@withLock false
        RemoteTsv.write(app, FILE, got.text)
        _shapes.value = parsed.mapKeys { it.key.removePrefix("SEB") }
        prefs.edit().putString(KEY_ETAG, got.etag).apply()
        true
    }
}
