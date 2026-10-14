package com.eunho.tagohub.ui.map

import android.view.View

/** 화면에 붙는 지도 엔진 (카카오맵). 생명주기는 화면이 맞춰 부른다 */
interface MapEngine : MapCanvas {
    val view: View
    fun resume()
    fun pause()
    fun release()
}
