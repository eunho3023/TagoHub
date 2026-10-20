package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson

/**
 * 카카오 로컬 API — 키워드로 장소 검색 (GET https://dapi.kakao.com/v2/local/search/keyword.json).
 * 요청: query, x(경도)·y(위도) = 중심 좌표, size(최대 15). 헤더 Authorization: KakaoAK {REST API 키}.
 * 응답: documents[] 의 place_name, road_address_name / address_name, x(경도)·y(위도) — 좌표는 문자열.
 */
object KakaoLocal {
    const val KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"

    fun parse(body: String): List<Place2> {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() ?: return emptyList()
        return MiniJson.findRows(root, "place_name").mapNotNull { r ->
            val lat = r.text("y")?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = r.text("x")?.toDoubleOrNull() ?: return@mapNotNull null
            Place2(
                name = r.text("place_name")?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                address = r.text("road_address_name")?.ifBlank { null } ?: r.text("address_name")?.ifBlank { null },
                lat = lat,
                lng = lng,
                category = r.text("category_name")?.substringAfterLast(" > ")?.ifBlank { null },
                phone = r.text("phone")?.ifBlank { null },
                url = r.text("place_url")?.ifBlank { null },
                distance = r.text("distance")?.toIntOrNull(),
            )
        }
    }

    const val ADDRESS_URL = "https://dapi.kakao.com/v2/local/search/address.json"
    const val REGION_URL = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json"
    const val CATEGORY_URL = "https://dapi.kakao.com/v2/local/search/category.json"

    /** 카테고리로 장소 검색 코드 (문서) — 장소 카드의 '주변 찾기' */
    val CATEGORIES = listOf("SW8" to "지하철역", "CS2" to "편의점", "CE7" to "카페", "FD6" to "음식점", "PK6" to "주차장",
        "PM9" to "약국", "HP8" to "병원", "OL7" to "주유소", "BK9" to "은행")

    /** 주소로 좌표 변환 (search/address): 도로명 주소를 이름으로, 지번을 부가 정보로 */
    fun parseAddress(body: String): List<Place2> {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return emptyList()
        return (root["documents"] as? List<*>).orEmpty().mapNotNull { d ->
            val m = d as? Map<*, *> ?: return@mapNotNull null
            fun Map<*, *>?.s(k: String) = this?.get(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val lat = m.s("y")?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = m.s("x")?.toDoubleOrNull() ?: return@mapNotNull null
            val road = (m["road_address"] as? Map<*, *>).s("address_name")
            val jibun = (m["address"] as? Map<*, *>).s("address_name") ?: m.s("address_name")
            Place2(road ?: jibun ?: return@mapNotNull null, if (road != null) jibun else null, lat, lng, category = "주소")
        }
    }

    /** 좌표로 행정구역 (coord2regioncode): 행정동(H) 우선, 없으면 법정동(B) */
    /** 좌표로 행정구역 → 법정동 코드 10자리 (region_type B 의 code) — 초정밀 버스 stdgCd 구하기 */
    fun legalCode(body: String): String? {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return null
        val docs = (root["documents"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        return (docs.firstOrNull { it["region_type"]?.toString() == "B" } ?: docs.firstOrNull())
            ?.get("code")?.toString()?.trim()?.takeIf { it.length == 10 && it.all(Char::isDigit) }
    }

    fun regionName(body: String): String? {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return null
        val docs = (root["documents"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val pick = docs.firstOrNull { it["region_type"]?.toString() == "H" } ?: docs.firstOrNull()
        return pick?.get("address_name")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    const val COORD2ADDRESS_URL = "https://dapi.kakao.com/v2/local/geo/coord2address.json"

    /**
     * 좌표 → 이름 (지도에서 꾹 누른 곳): 건물 이름 → 도로명 주소 → 지번 주소.
     * 응답: documents[].road_address(address_name, building_name) / address(address_name)
     */
    fun addressName(body: String): String? {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return null
        val doc = (root["documents"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val road = doc["road_address"] as? Map<*, *>
        val addr = doc["address"] as? Map<*, *>
        fun Map<*, *>?.s(k: String) = this?.get(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return road.s("building_name") ?: road.s("address_name") ?: addr.s("address_name")
    }

    /** 오류 응답: {"errorType":"...","message":"App(...) disabled OPEN_MAP_AND_LOCAL service."} */
    fun error(body: String): String? {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() ?: return null
        return MiniJson.findRows(root, "errorType").firstOrNull()?.let { "${it.text("errorType")}: ${it.text("message").orEmpty()}" }
    }
}
