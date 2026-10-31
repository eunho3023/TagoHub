package com.eunho.tagohub.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/** 클라우드에 올릴 설정: (저장 파일 이름, 항목 이름). 위치 기록·캐시처럼 기기마다 다른 것은 넣지 않음 */
internal object CloudSyncSpec {
    val items: List<Pair<String, String>> = listOf(
        "bus" to "favorites",
        "bus" to "overlays",
        "bus" to "baseMap",
        "bus" to "cityCode",
        "bus" to "cityName",
        "bus" to "saved_home",
        "bus" to "saved_work",
        "drive_guidance" to "off_groups",
        "ui" to "theme_mode",
        "ui" to "night_mode",
        "ui" to "vehicle_icon",
        "search_history" to "items",
        "route_history" to "items",
    )

    /** Firestore 필드 이름 (점이 들어가면 경로로 해석되니 밑줄 두 개로 잇는다) */
    fun field(prefs: String, key: String) = "${prefs}__$key"
}

/**
 * 즐겨찾기·집/회사·안내 설정 등을 로그인한 계정(처음엔 익명)의 Firestore 문서 users/{uid} 에 맞춰 둠.
 * - 시작할 때: 클라우드가 더 새것이면 이 기기 저장값을 덮어쓰고(다음 실행부터 반영), 아니면 이 기기 값을 올림
 * - 이후: 위 항목이 바뀌면 2초 뒤에 올림
 * 네트워크나 로그인이 안 되면 조용히 건너뜀 (앱 동작에는 영향 없음)
 */
object CloudSync {
    private const val TAG = "TagoSync"
    private const val META = "cloud_sync"
    private const val KEY_LAST = "last_sync_ms"
    private const val FIELD_UPDATED = "updatedAt"

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) pull(app, user.uid) else auth.signInAnonymously()
                .addOnSuccessListener { r -> r.user?.uid?.let { pull(app, it) } }
                .addOnFailureListener { Log.w(TAG, "익명 로그인 실패", it) }
        }.onFailure { Log.w(TAG, "Firebase 시작 실패", it) }
    }

    /** 올리기·받기를 멈춤 (데이터 삭제 때: 지운 직후에 비운 값이 다시 올라가거나 옛 값이 되돌아오지 않게. [restart] 로 다시 시작) */
    fun stop(context: Context) {
        handler.removeCallbacksAndMessages(null)
        for ((file, l) in listeners) runCatching { context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(l) }
        listeners.clear()
        started = false
    }

    /** 계정이 바뀐 뒤(구글 연결·로그아웃) 새 계정 기준으로 다시 맞춤. 클라우드에 값이 있으면 그것이 이기도록 기록을 지움 */
    fun restart(context: Context) {
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        for ((file, l) in listeners) app.getSharedPreferences(file, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(l)
        listeners.clear()
        app.getSharedPreferences(META, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        started = false
        start(app)
    }

    private fun doc(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid)

    private fun pull(app: Context, uid: String) {
        val meta = app.getSharedPreferences(META, Context.MODE_PRIVATE)
        doc(uid).get().addOnSuccessListener { snap ->
            val cloudAt = snap.getLong(FIELD_UPDATED) ?: 0L
            if (snap.exists() && cloudAt > meta.getLong(KEY_LAST, 0L)) {
                applyToLocal(app, snap.data.orEmpty())
                meta.edit().putLong(KEY_LAST, cloudAt).apply()
            } else {
                push(app, uid)
            }
            watch(app, uid)
        }.addOnFailureListener { Log.w(TAG, "클라우드 읽기 실패", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyToLocal(app: Context, data: Map<String, Any?>) {
        for ((file, key) in CloudSyncSpec.items) {
            val name = CloudSyncSpec.field(file, key)
            if (!data.containsKey(name)) continue
            val ed = app.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            when (val v = data[name]) {
                is String -> ed.putString(key, v)
                is List<*> -> ed.putStringSet(key, v.filterIsInstance<String>().toSet())
                null -> ed.remove(key)
            }
            ed.apply()
        }
    }

    private fun snapshot(app: Context): Map<String, Any> {
        val out = HashMap<String, Any>()
        for ((file, key) in CloudSyncSpec.items) {
            val all = app.getSharedPreferences(file, Context.MODE_PRIVATE).all
            when (val v = all[key]) {
                is String -> out[CloudSyncSpec.field(file, key)] = v
                is Set<*> -> out[CloudSyncSpec.field(file, key)] = v.filterIsInstance<String>()
            }
        }
        return out
    }

    private fun push(app: Context, uid: String) {
        val now = System.currentTimeMillis()
        val data = snapshot(app) + (FIELD_UPDATED to now)
        doc(uid).set(data).addOnSuccessListener {
            app.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, now).apply()
        }.addOnFailureListener { Log.w(TAG, "클라우드 쓰기 실패", it) }
    }

    /** 바뀌면 잠시 모았다가 한 번에 올림 (참조를 들고 있어야 해서 보관) */
    private val listeners = ArrayList<Pair<String, SharedPreferences.OnSharedPreferenceChangeListener>>()

    private fun watch(app: Context, uid: String) {
        val wanted = CloudSyncSpec.items.groupBy({ it.first }, { it.second })
        val pushLater = Runnable { push(app, uid) }
        for ((file, keys) in wanted) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in keys) {
                    handler.removeCallbacks(pushLater)
                    handler.postDelayed(pushLater, 2000)
                }
            }
            listeners += file to l
            app.getSharedPreferences(file, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
        }
    }
}
