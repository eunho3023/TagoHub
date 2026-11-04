package com.eunho.tagohub.data

/**
 * 서버(Cloudflare Workers 정적 파일)에 올린 정류장·노선 조각의 주소 규칙 (pipeline/build_web_data.py 와 같게).
 * 웹·PC 버전은 120MB DB 대신 필요한 조각만 받는다: `{서버}/data/tiles/{칸}.json`, `/data/routes/{묶음}.json` 등.
 */
object WebData {
    const val BUCKETS = 1024

    /** 노선 묶음 번호: FNV-1a 32비트(노선 id 의 UTF-8) % 1024 */
    fun bucket(routeId: String): Int {
        var h = 0x811C9DC5u
        for (b in routeId.encodeToByteArray()) {
            h = h xor (b.toUInt() and 0xFFu)
            h *= 0x01000193u
        }
        return (h % BUCKETS.toUInt()).toInt()
    }

    /** 0.1도 칸 이름 "위도*10_경도*10" (내림) */
    fun tile(lat: Double, lng: Double): String = "${kotlin.math.floor(lat * 10).toInt()}_${kotlin.math.floor(lng * 10).toInt()}"

    /** 이 범위를 덮는 칸들 */
    fun tiles(south: Double, west: Double, north: Double, east: Double): List<String> {
        val la0 = kotlin.math.floor(south * 10).toInt()
        val la1 = kotlin.math.floor(north * 10).toInt()
        val lo0 = kotlin.math.floor(west * 10).toInt()
        val lo1 = kotlin.math.floor(east * 10).toInt()
        return (la0..la1).flatMap { a -> (lo0..lo1).map { o -> "${a}_$o" } }
    }

    /** 이름 검색 조각: 검색어 첫 글자의 유니코드 16진수 (정류장 이름의 낱말 첫 글자로 나눠 둠) */
    fun nameShard(query: String): String? = query.trim().firstOrNull()?.code?.toString(16)

    fun tilePath(tile: String) = "data/tiles/$tile.json"
    fun routePath(routeId: String) = "data/routes/${bucket(routeId)}.json"
    fun namePath(query: String) = nameShard(query)?.let { "data/names/$it.json" }
    const val CITIES_PATH = "data/cities.json"
    const val ROUTES_BY_NO_PATH = "data/routes_by_no.json"
    fun filePath(name: String) = "data/files/$name"
}
