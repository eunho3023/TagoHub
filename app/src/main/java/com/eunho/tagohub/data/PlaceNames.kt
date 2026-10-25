package com.eunho.tagohub.data

/** 터미널·역·공항 이름 맞추기: 서로 다른 API 가 "서울경부"/"서울" 처럼 조금씩 다르게 부르는 이름을 같은 곳으로 본다 */
object PlaceNames {
    private val noise = Regex("""[\s()·/-]|터미널|버스|역$|공항$""")

    fun norm(s: String) = noise.replace(s, "")

    fun same(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.length < 2 || y.length < 2) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /** [candidates] 중 [allowed] 이름 중 하나와 같은 곳만 */
    fun keepConnected(candidates: List<Place>, allowed: List<String>): List<Place> =
        candidates.filter { c -> allowed.any { same(c.name, it) } }
}
