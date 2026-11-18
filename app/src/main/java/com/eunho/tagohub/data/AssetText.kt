package com.eunho.tagohub.data

import android.content.Context

/**
 * 앱 안 파일(assets) 글자 읽기. 거의 안 바뀌는 API 응답을 파일로 넣어 두고(pipeline/build_api_files.py) API 대신 먼저 씀.
 * 앱 시작 때 [init]. 파일이 없거나 못 읽으면 null → 부르는 쪽이 예전처럼 API
 */
object AssetText {
    @Volatile private var app: Context? = null
    fun init(context: Context) { if (app == null) app = context.applicationContext }
    fun read(name: String): String? = app?.let { c -> runCatching { c.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }
}
