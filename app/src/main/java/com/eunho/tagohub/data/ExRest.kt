package com.eunho.tagohub.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 휴게소 상세 (한국도로공사): 주유 가격·편의시설·입점 브랜드·대표 메뉴 */
data class ExGas(val company: String?, val gasoline: String?, val diesel: String?, val lpg: String?, val tel: String?)

data class ExFood(val name: String, val price: String?, val recommended: Boolean)

data class ExRestDetail(
    val gas: ExGas?,
    val facilities: List<String>,
    val brands: List<String>,
    val foods: List<ExFood>,
) {
    val isEmpty: Boolean get() = gas == null && facilities.isEmpty() && brands.isEmpty() && foods.isEmpty()
}

object ExRestParser {
    /** "서울만남(부산)주유소" 와 "서울만남(부산)휴게소" 를 같은 곳으로 보려고 뒤의 종류 글자를 뗌 */
    fun root(name: String) = name.trim().replace(Regex("(휴게소|주유소|졸음쉼터)$"), "")

    /** 주유 가격은 휴게소 코드가 달라서(주유소 코드) 이름으로 짝지음. 못 찾으면 null */
    fun gas(rows: List<Map<String, String>>, restName: String): ExGas? {
        val key = root(restName)
        val r = rows.firstOrNull { root(it["serviceAreaName"].orEmpty()) == key } ?: return null
        return ExGas(r["oilCompany"], r["gasolinePrice"], r["diselPrice"], r["lpgPrice"]?.takeIf { r["lpgYn"] == "Y" }, r["telNo"])
    }

    fun facilities(rows: List<Map<String, String>>): List<String> = rows.mapNotNull { it["psName"] }.distinct()

    fun brands(rows: List<Map<String, String>>): List<String> = rows.mapNotNull { it["brdName"] }.distinct()

    /** 추천·베스트 메뉴를 앞에, 최대 [limit]개 */
    fun foods(rows: List<Map<String, String>>, limit: Int = 8): List<ExFood> = rows
        .mapNotNull { r ->
            val name = r["foodNm"] ?: return@mapNotNull null
            ExFood(name, r["foodCost"], r["recommendyn"] == "Y" || r["bestfoodyn"] == "Y")
        }
        .distinctBy { it.name }
        .sortedByDescending { it.recommended }
        .take(limit)
}

class ExRestRepository(private val repo: ExRepository = ExRepository()) {
    private var gasCache: Pair<Long, List<Map<String, String>>>? = null

    /** 주유소 가격 전체(약 230건)는 6시간 동안 메모리에 둠 */
    private suspend fun gasRows(): List<Map<String, String>> {
        val now = System.currentTimeMillis()
        gasCache?.takeIf { now - it.first < 6 * 3_600_000L }?.let { return it.second }
        return repo.all("/openapi/business/curStateStation").also { gasCache = now to it }
    }

    /** 네 가지를 동시에 받음. 일부가 실패해도(예: 해당 휴게소에 주유소 없음) 나머지는 보여줌 */
    suspend fun detail(stdRestCd: String, restName: String): ExRestDetail = coroutineScope {
        val p = mapOf("stdRestCd" to stdRestCd)
        val conv = async { runCatching { repo.all("/openapi/restinfo/restConvList", p) }.getOrDefault(emptyList()) }
        val brand = async { runCatching { repo.all("/openapi/restinfo/restBrandList", p) }.getOrDefault(emptyList()) }
        val food = async { runCatching { repo.all("/openapi/restinfo/restBestfoodList", p) }.getOrDefault(emptyList()) }
        val gas = async { runCatching { ExRestParser.gas(gasRows(), restName) }.getOrNull() }
        ExRestDetail(gas.await(), ExRestParser.facilities(conv.await()), ExRestParser.brands(brand.await()), ExRestParser.foods(food.await()))
    }
}
