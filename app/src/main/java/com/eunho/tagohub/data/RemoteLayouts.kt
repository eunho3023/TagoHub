package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 관리자 페이지(admin.html)에서 저장한 노선 배치도 — 서버 D1 의 /w/layouts.tsv.
 *
 * 앱에 들어 있는 기본 배치(bus_route_layouts.tsv)를 먼저 쓰고, 서버에 저장된 노선이 있으면
 * 그 노선만 통째로 덮어쓴다. 앱을 켤 때 저장본을 바로 읽고, 화면이 보이는 동안 주기적으로
 * 새것이 있는지 확인한다(ETag 라 바뀐 것이 없으면 본문을 받지 않는다).
 */
object RemoteLayouts {
    private const val FILE = "layouts.tsv"
    private const val PREFS = "remote_layouts"
    private const val KEY_ETAG = "etag"

    /** 서버가 Cache-Control(max-age=60)로 알려 주는 것과 같게: 이보다 자주 묻지 않는다 */
    const val MIN_GAP_MS = 60_000L

    private val _layouts = MutableStateFlow<Map<String, Map<String, Pair<Int, Int>>>>(emptyMap())
    /** 지금 쓰는 서버 배치 (노선ID → 정류장ID → 칸). 값이 바뀌면 배치도를 다시 그린다 */
    val layouts: StateFlow<Map<String, Map<String, Pair<Int, Int>>>> = _layouts.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var lastTry = 0L
    private val lock = Mutex()

    /** 앱을 켤 때: 저장해 둔 배치를 바로 읽는다 (네트워크를 기다리지 않음) */
    fun init(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        _layouts.value = RouteDiagram.parseOfficial(RemoteTsv.read(app, FILE))
        loaded = true
    }

    /** 앱 기본 배치 위에 서버 배치를 덮어쓴다 (노선 단위로 통째 교체) */
    fun overlay(
        base: Map<String, Map<String, Pair<Int, Int>>>,
        remote: Map<String, Map<String, Pair<Int, Int>>> = _layouts.value,
    ): Map<String, Map<String, Pair<Int, Int>>> {
        if (remote.isEmpty()) return base
        val out = HashMap<String, Map<String, Pair<Int, Int>>>(base.size + remote.size)
        out.putAll(base)
        out.putAll(remote)
        return out
    }

    fun has(routeId: String): Boolean = _layouts.value.containsKey(routeId.removePrefix("SEB"))

    /**
     * 서버에서 새 배치를 받아 둔다. 바뀐 것이 없으면(304) 저장본을 그대로 두고 true.
     * 실패해도 예전 저장본으로 계속 그린다.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean = lock.withLock {
        val app = context.applicationContext
        if (!loaded) init(app)
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < MIN_GAP_MS) return@withLock false
        lastTry = now
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val got = RemoteTsv.fetch(app, "/w/layouts.tsv", prefs.getString(KEY_ETAG, null), "X-Layout-Version")
        if (!got.ok) return@withLock false
        if (got.notModified) return@withLock true
        val parsed = RouteDiagram.parseOfficial(got.text)
        // 형식이 깨진 응답으로 저장본을 지우지 않는다 (빈 응답은 "저장된 배치 없음"이라 그대로 반영)
        if (parsed.isEmpty() && got.text.isNotBlank()) return@withLock false
        RemoteTsv.write(app, FILE, got.text)
        _layouts.value = parsed
        prefs.edit().putString(KEY_ETAG, got.etag).apply()
        true
    }
}
