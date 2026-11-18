package com.eunho.tagohub.data

import android.content.Context

/**
 * 거의 안 바뀌는 API 응답을 넣어 둔 앱 파일 읽기 (지하철 시간표·휴게소·항공 소요시간·울산 교차로).
 * 매주 새로 만든 것이 노선 DB 의 files 표에 있으면(데이터 갱신이 넣음, 앱이 DB 와 같이 받음) 그것, 없으면 앱 안 파일(assets).
 * 앱 시작 때 [init]. 둘 다 없으면 null → 부르는 쪽이 예전처럼 API
 */
object AssetText {
    @Volatile private var app: Context? = null
    fun init(context: Context) { if (app == null) app = context.applicationContext }

    fun read(name: String): String? =
        StopDb.peek()?.let { db -> runCatching { db.file(name) }.getOrNull() }
            ?: app?.let { c -> runCatching { c.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }

    /** 지금 [read] 가 어느 판을 주는지 (DB 판이 바뀌면 다시 읽으려고): "db:버전" 또는 "asset" */
    fun stamp(name: String): String =
        StopDb.peek()?.let { db -> runCatching { if (db.hasFile(name)) "db:" + db.version() else null }.getOrNull() } ?: "asset"
}
