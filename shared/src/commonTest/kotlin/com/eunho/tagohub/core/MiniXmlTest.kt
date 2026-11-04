package com.eunho.tagohub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniXmlTest {
    @Test fun readsTagoItems() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!-- c --><response><header><resultCode>00</resultCode><resultMsg>OK</resultMsg></header>
<body><items><item><nodenm>전주역 &amp; 앞</nodenm><routeno>0600</routeno><x><![CDATA[a<b]]></x></item>
<item><nodenm>A&#44;B&#x41;</nodenm><empty/></item></items><totalCount>2</totalCount></body></response>"""
        val page = TagoXml.parse(xml)
        assertEquals(2, page.totalCount)
        assertEquals("전주역 & 앞", page.rows[0].str("nodeNm"))
        assertEquals("0600", page.rows[0].str("routeno"))
        assertEquals("a<b", page.rows[0].str("x"))
        assertEquals("A,BA", page.rows[1].str("nodenm"))
        assertEquals(null, page.rows[1].str("empty"))
    }

    @Test fun attributesAndDoctype() {
        val root = MiniXml.parse("<!DOCTYPE r [<!ENTITY e \"x\">]><r a='1' b=\"&lt;\"><c/></r>")
        assertEquals("1", root.attrs["a"])
        assertEquals("<", root.attrs["b"])
        assertEquals(1, root.children.size)
    }

    @Test fun errorCodeAndBrokenXml() {
        val e = assertFailsWith<TagoApiException> { TagoXml.parse("<response><header><resultCode>30</resultCode></header></response>") }
        assertEquals("30", e.code)
        assertFailsWith<TagoApiException> { TagoXml.parse("<a><b></a>") }
    }
}
