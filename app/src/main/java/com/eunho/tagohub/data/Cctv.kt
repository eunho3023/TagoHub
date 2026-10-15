package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson

/**
 * 전국 CCTV — 브이월드 2D 데이터 API (data=LT_P_UTISCCTV, 도로교통 CCTV).
 * 응답: response.result.featureCollection.features[] { geometry.coordinates [경도, 위도], properties {…} }
 * 속성 이름은 실제 응답으로 확인 전 → 이름처럼 보이는 것(name·nm)·영상 주소처럼 보이는 것(url·http) 을 찾아 읽음.
 */
data class Cctv(val id: String, val name: String, val lat: Double, val lng: Double, val url: String?)

object CctvParse {
    const val URL = "https://api.vworld.kr/req/data"

    fun parse(body: String): List<Cctv> {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val res = root["response"] as? Map<*, *> ?: return emptyList()
        val fc = (res["result"] as? Map<*, *>)?.get("featureCollection") as? Map<*, *> ?: return emptyList()
        return (fc["features"] as? List<*>).orEmpty().mapIndexedNotNull { i, f ->
            val m = f as? Map<*, *> ?: return@mapIndexedNotNull null
            val c = ((m["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*>) ?: return@mapIndexedNotNull null
            // 점이면 [x, y], 다중점이면 [[x, y]]
            val xy = (c.firstOrNull() as? List<*>) ?: c
            val x = xy.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val y = xy.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val p = (m["properties"] as? Map<*, *>).orEmpty().mapKeys { it.key.toString().lowercase() }
                .mapValues { it.value?.toString()?.trim().orEmpty() }.filterValues { it.isNotEmpty() && it != "null" }
            val name = p.entries.firstOrNull { (k, _) -> k.contains("name") || k.endsWith("nm") || k.contains("locate") || k.contains("loc") }?.value
            val url = p.values.firstOrNull { it.startsWith("http") }
            Cctv(m["id"]?.toString() ?: "cctv$i", name ?: "CCTV", y, x, url)
        }
    }
}
