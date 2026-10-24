package com.eunho.tagohub

import com.eunho.tagohub.data.RouteDiagram
import com.eunho.tagohub.data.RouteShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 앱 안 서울 노선 모양·노선 배치도 */
class RouteAssetsTest {
    private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.exists() }

    @Test
    fun decodesShapeDeltas() {
        val m = RouteShapes.parse("100\t37.50000,127.00000\t10,20,-5,5\nbad\n")
        val p = m.getValue("100")
        assertEquals(3, p.size)
        assertEquals(37.50010, p[1].lat, 1e-7)
        assertEquals(127.00020, p[1].lng, 1e-7)
        assertEquals(37.50005, p[2].lat, 1e-7)
        assertEquals(127.00025, p[2].lng, 1e-7)
    }

    @Test
    fun bundledShapesCoverSeoulRoutes() {
        val m = RouteShapes.parse(asset("bus_route_shapes.tsv").readText(Charsets.UTF_8))
        assertTrue("노선 ${m.size}개", m.size > 600)
        assertTrue(m.values.all { l -> l.all { it.lat in 36.5..38.5 && it.lng in 126.0..128.0 } })
    }

    @Test
    fun snakeAlternatesDirection() {
        val c = RouteDiagram.snake(listOf("a", "b", "c", "d", "e", "f"), 3)
        assertEquals(listOf(0f, 1f, 2f, 2f, 1f, 0f), c.map { it.x })
        assertEquals(listOf(0f, 0f, 0f, 1f, 1f, 1f), c.map { it.y })
    }

    @Test
    fun layoutUsesOfficialWhenCoveringMostStopsElseSnake() {
        val off = RouteDiagram.parseOfficial("R1\tn1:0:0,n2:100:0,n3:100:50\n")
        val a = RouteDiagram.layout("SEBR1", listOf("SEBn1", "SEBn2", "SEBn3"), off, 4)
        assertEquals(listOf(0f, 3f, 3f), a.map { it.x })
        assertEquals(1.5f, a[2].y, 1e-5f)
        val b = RouteDiagram.layout("SEBR9", listOf("x", "y"), off, 4)
        assertEquals(listOf(0f, 1f), b.map { it.x })
    }
}
