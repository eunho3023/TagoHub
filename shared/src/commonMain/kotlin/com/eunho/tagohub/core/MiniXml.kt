package com.eunho.tagohub.core

/**
 * 작은 XML 해석기 (폰·웹·PC 공용: 자바의 DOM 해석기는 웹에서 못 씀).
 * 공공데이터 응답처럼 단순한 XML 용: 요소·속성·글자·CDATA·주석·선언을 읽고, DOCTYPE 은 건너뜀(외부 자료를 읽지 않음).
 * 이름공간 접두사는 이름에 그대로 남김 (예: "soap:Body").
 */
object MiniXml {
    class Element(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<Element>()
        internal val parts = ArrayList<Any>()   // String(글자) 또는 Element, 나온 차례대로

        /** 안에 든 글자 전부 (자식 요소 글자 포함, DOM 의 textContent 와 같음) */
        val text: String get() = buildString { appendText(this) }

        private fun appendText(sb: StringBuilder) {
            for (p in parts) if (p is String) sb.append(p) else (p as Element).appendText(sb)
        }

        /** 이 요소 아래(자기 포함)에서 이름이 [tag] 인 요소들, 문서 차례대로 */
        fun all(tag: String): List<Element> = ArrayList<Element>().also { collect(tag, it) }

        private fun collect(tag: String, out: MutableList<Element>) {
            if (name == tag) out += this
            for (c in children) c.collect(tag, out)
        }

        /** 이 요소 아래 처음 나오는 [tag] 요소의 글자 (앞뒤 공백 뺌). 없으면 null */
        fun first(tag: String): String? = all(tag).firstOrNull()?.text?.trim()

        /** 바로 아래 자식 중 이름이 [tag] 인 첫 요소 */
        fun child(tag: String): Element? = children.firstOrNull { it.name == tag }
    }

    class XmlException(message: String) : Exception(message)

    /** 뿌리 요소를 돌려줌. 형식이 틀리면 [XmlException] */
    fun parse(text: String): Element {
        val p = Parser(text)
        return p.document()
    }

    private class Parser(val s: String) {
        var i = 0

        fun document(): Element {
            var root: Element? = null
            val stack = ArrayList<Element>()
            while (i < s.length) {
                if (s[i] != '<') {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    val raw = s.substring(i, end)
                    i = end
                    if (stack.isNotEmpty()) stack.last().parts += decode(raw)
                    else if (raw.isNotBlank()) throw XmlException("요소 밖 글자")
                    continue
                }
                when {
                    s.startsWith("<!--", i) -> i = after("-->", i + 4)
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9).takeIf { it >= 0 } ?: throw XmlException("CDATA 가 안 닫힘")
                        stack.lastOrNull()?.parts?.add(s.substring(i + 9, end))
                        i = end + 3
                    }
                    s.startsWith("<?", i) -> i = after("?>", i + 2)
                    s.startsWith("<!", i) -> i = skipDoctype(i + 2)
                    s.startsWith("</", i) -> {
                        val end = s.indexOf('>', i).takeIf { it >= 0 } ?: throw XmlException("닫는 태그가 안 끝남")
                        val name = s.substring(i + 2, end).trim()
                        val open = stack.removeLastOrNull() ?: throw XmlException("여는 태그 없이 닫음: $name")
                        if (open.name != name) throw XmlException("태그 짝이 안 맞음: ${open.name} / $name")
                        i = end + 1
                        if (stack.isEmpty()) root = open
                    }
                    else -> {
                        val (el, selfClose) = startTag()
                        if (stack.isEmpty() && root != null) throw XmlException("뿌리 요소가 둘")
                        stack.lastOrNull()?.let { it.children += el; it.parts += el }
                        if (selfClose) { if (stack.isEmpty()) root = el } else stack += el
                    }
                }
            }
            if (stack.isNotEmpty()) throw XmlException("안 닫힌 태그: ${stack.last().name}")
            return root ?: throw XmlException("요소가 없음")
        }

        private fun after(end: String, from: Int): Int {
            val k = s.indexOf(end, from)
            if (k < 0) throw XmlException("$end 가 없음")
            return k + end.length
        }

        /** <!DOCTYPE ...> (안에 [ ... ] 가 있을 수 있음) 건너뜀 */
        private fun skipDoctype(from: Int): Int {
            var k = from
            var depth = 0
            while (k < s.length) {
                when (s[k]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) return k + 1
                }
                k++
            }
            throw XmlException("DOCTYPE 이 안 닫힘")
        }

        private fun isNameChar(c: Char) = !c.isWhitespace() && c != '>' && c != '/' && c != '=' && c != '<'

        private fun startTag(): Pair<Element, Boolean> {
            i++ // '<'
            val n0 = i
            while (i < s.length && isNameChar(s[i])) i++
            val name = s.substring(n0, i)
            if (name.isEmpty()) throw XmlException("태그 이름이 없음")
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) throw XmlException("태그가 안 끝남: $name")
                when (s[i]) {
                    '>' -> { i++; return Element(name, attrs) to false }
                    '/' -> {
                        if (s.getOrNull(i + 1) != '>') throw XmlException("잘못된 태그: $name")
                        i += 2
                        return Element(name, attrs) to true
                    }
                    else -> {
                        val a0 = i
                        while (i < s.length && isNameChar(s[i])) i++
                        val key = s.substring(a0, i)
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (s.getOrNull(i) != '=') { attrs[key] = ""; continue }
                        i++
                        while (i < s.length && s[i].isWhitespace()) i++
                        val q = s.getOrNull(i)
                        if (q != '"' && q != '\'') throw XmlException("속성 값에 따옴표가 없음: $key")
                        val end = s.indexOf(q, i + 1).takeIf { it >= 0 } ?: throw XmlException("속성 값이 안 닫힘: $key")
                        attrs[key] = decode(s.substring(i + 1, end))
                        i = end + 1
                    }
                }
            }
        }
    }

    /** &amp; &lt; &gt; &quot; &apos; &#10; &#x41; 풀기 (모르는 것은 그대로) */
    fun decode(raw: String): String {
        if ('&' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            val semi = if (c == '&') raw.indexOf(';', k + 1).takeIf { it in (k + 2)..(k + 10) } else null
            if (semi == null) { sb.append(c); k++; continue }
            val ent = raw.substring(k + 1, semi)
            val rep: String? = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (rep == null) { sb.append(c); k++ } else { sb.append(rep); k = semi + 1 }
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
