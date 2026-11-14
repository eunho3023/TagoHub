package com.eunho.tagohub

import com.eunho.tagohub.data.RemoteLayouts
import com.eunho.tagohub.data.RouteDiagram
import com.eunho.tagohub.data.RouteShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 관리자 페이지에서 저장한 배치도·노선 선형을 앱 기본 자료 위에 덮어쓰는 부분 */
class RemoteEditsTest {
    @Test
    fun parseOfficialNormalizesPrefixAndSpaces() {
        val m = RouteDiagram.parseOfficial("SEB100\t SEB1:0:0 , 2:3:4 \r\n\r\nbad\n")
        val route = m.getValue("100")
        assertEquals(0 to 0, route["1"])
        assertEquals(3 to 4, route["2"])
        assertEquals(setOf("1", "2"), route.keys)
    }

    @Test
    fun partialRemoteLayoutKeepsOneCellPerStop() {
        val off = mapOf("100" to mapOf("a" to (0 to 0), "b" to (2 to 0)))
        val ids = listOf("SEBa", "SEBb", "SEBc", "SEBd")
        val cells = RouteDiagram.layout("SEB100", ids, off, 4, acceptPartial = true)
        assertEquals(ids, cells.map { it.nodeId })
        // 채워 넣은 칸까지 서로 겹치지 않는다
        assertEquals(4, cells.map { it.x to it.y }.toSet().size)
    }

    @Test
    fun bundledRuleStillNeedsMostStops() {
        val off = mapOf("100" to mapOf("a" to (0 to 0), "b" to (2 to 0)))
        val ids = listOf("SEBa", "SEBb", "SEBc", "SEBd", "SEBe")
        // 40% 만 덮으면 관리자 저장분이 아니면 기본 ㄹ자 배치
        val cells = RouteDiagram.layout("SEB100", ids, off, 4)
        assertEquals(listOf(0f, 1f, 2f, 3f, 3f), cells.map { it.x })
    }

    @Test
    fun overlayReplacesWholeRouteAndKeepsOthers() {
        val base = mapOf("100" to mapOf("a" to (0 to 0)), "200" to mapOf("b" to (1 to 1)))
        val remote = mapOf("100" to mapOf("a" to (3 to 3), "c" to (0 to 1)))
        val merged = RemoteLayouts.overlay(base, remote)
        assertEquals(remote.getValue("100"), merged["100"])
        assertEquals(base.getValue("200"), merged["200"])
        assertEquals(base, RemoteLayouts.overlay(base, emptyMap()))
    }

    @Test
    fun remoteShapeUsesBundledTsvFormat() {
        val m = RouteShapes.parse("100\t37.50000,127.00000\t10,20,-5,5\n")
        val p = m.getValue("100")
        assertEquals(3, p.size)
        assertEquals(37.50010, p[1].lat, 1e-7)
        assertEquals(127.00020, p[1].lng, 1e-7)
        assertEquals(37.50005, p[2].lat, 1e-7)
        assertEquals(127.00025, p[2].lng, 1e-7)
    }

    @Test
    fun adminShapeLineRoundTripsThroughBundledFormat() {
        // 관리자 페이지 encodeShape 와 같은 규칙 (1e-5도 정수, 첫 점은 소수 5자리)
        val line = "100100091\t37.50000,127.00000\t10,20,-5,5"
        val pts = RouteShapes.parse(line).getValue("100100091")
        assertEquals(3, pts.size)
        assertTrue(pts.all { it.lat in 37.4..37.6 && it.lng in 126.9..127.1 })
    }
}
