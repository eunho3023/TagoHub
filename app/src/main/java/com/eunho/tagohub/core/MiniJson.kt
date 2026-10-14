package com.eunho.tagohub.core

/**
 * 작은 JSON 파서 (org.json 없이 → JVM 테스트 가능). 숫자는 원문 문자열 그대로 보존한다.
 * 객체 = Map<String, Any?>, 배열 = List<Any?>, 문자열·숫자 = String, true/false = "true"/"false", null = null
 */
object MiniJson {
    fun parse(text: String): Any? = Reader(text.trim().removePrefix("\uFEFF")).run {
        value().also {
            skipWs()
            if (i != s.length) throw TagoApiException("FORMAT", "JSON 해석 실패: 뒤에 남는 문자")
        }
    }

    /**
     * 응답 구조를 몰라도 행 목록을 찾는다: key 를 가진 객체들이 든 첫 배열 (깊이 우선).
     * 배열 대신 객체 하나만 있으면 그 하나를 목록으로.
     */
    fun findRows(root: Any?, key: String): List<Row> {
        fun asRow(m: Map<*, *>) = Row(m.entries.associate { (k, v) -> k.toString() to (v?.toString() ?: "") })
        fun walk(n: Any?): List<Row>? = when (n) {
            is List<*> -> {
                val objs = n.filterIsInstance<Map<*, *>>()
                if (objs.any { it.containsKey(key) }) objs.map(::asRow) else n.firstNotNullOfOrNull { walk(it) }
            }
            is Map<*, *> -> if (n.containsKey(key) && n.values.none { it is List<*> }) listOf(asRow(n)) else n.values.firstNotNullOfOrNull { walk(it) }
            else -> null
        }
        return walk(root).orEmpty()
    }

    private class Reader(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("끝")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", "true")
                'f' -> lit("false", "false")
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                m[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("',' 또는 '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("',' 또는 ']'")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("닫는 따옴표")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): String {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (start == i) fail("값")
            return s.substring(start, i)
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail(word)
            i += word.length
            return v
        }

        private fun expect(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) fail("'$c'")
            i++
        }

        private fun fail(what: String): Nothing =
            throw TagoApiException("FORMAT", "JSON 해석 실패: 위치 $i 에서 $what 필요")
    }
}
