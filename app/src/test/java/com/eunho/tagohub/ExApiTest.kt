package com.eunho.tagohub

import com.eunho.tagohub.data.ExCatalog
import com.eunho.tagohub.data.ExMapLayers
import com.eunho.tagohub.data.ExParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ExApiTest {
    private fun catalogText(): String {
        val f = listOf("src/main/assets/ex_catalog.json", "app/src/main/assets/ex_catalog.json").map { File(it) }.first { it.exists() }
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun bundledCatalogHasAll93Apis() {
        val apis = ExCatalog.parse(catalogText())
        assertEquals(93, apis.size)
        assertEquals(93, apis.map { it.id }.toSet().size)
        assertTrue(apis.all { it.url.startsWith("/openapi/") && it.name.isNotBlank() && it.service.isNotBlank() })
        // 필수 변수가 있는 API 와 한글 출력 이름이 읽히는지
        val intercity = apis.first { it.id == "0153" }
        assertEquals("iYear", intercity.params.first { it.required }.code)
        assertEquals("기준년도", intercity.params.first().label)
        assertEquals("통행시간(분)", intercity.outputs["trtm"])
    }

    @Test
    fun parserReadsRowsAndDropsNulls() {
        val r = ExParser.parse("""{"list":[{"unitName":"서울만남(부산)휴게소","pageNo":null,"routeName":"경부선","xValue":"127.042514","yValue":"37.459939"}],"count":203,"code":"SUCCESS","message":"인증키가 유효합니다."}""")
        assertEquals(203, r.total)
        assertEquals(1, r.rows.size)
        assertEquals("경부선", r.rows[0]["routeName"])
        assertTrue("pageNo" !in r.rows[0])
    }

    @Test
    fun parserTurnsApiErrorsIntoMessages() {
        val e = try {
            ExParser.parse("""{"code":"ERROR","message":"노선코드 값이 없습니다.","count":0}""")
            null
        } catch (e: IOException) {
            e.message
        }
        assertEquals("노선코드 값이 없습니다.", e)
        assertNotNull(try { ExParser.parse("<html>x</html>"); null } catch (e: IOException) { e.message })
    }

    @Test
    fun mapLayersBuildFeaturesFromRealRows() {
        val rest = ExMapLayers.rest(listOf(mapOf("unitName" to "서울만남(부산)휴게소", "stdRestCd" to "000001", "routeName" to "경부선", "xValue" to "127.042514", "yValue" to "37.459939")))
        assertEquals("서울만남(부산)휴게소", rest.single().title)
        assertEquals(37.459939, rest.single().lat, 1e-9)
        assertEquals(127.042514, rest.single().lng, 1e-9)

        val ic = ExMapLayers.ic(listOf(mapOf("icName" to "구서IC", "icCode" to "0010I00001", "routeName" to "경부선", "xValue" to "129.097294", "yValue" to "35.250563")))
        assertEquals("구서IC", ic.single().title)

        // 전광표지판: xCoord 가 위도, yCoord 가 경도 (이름과 반대)
        val vms = ExMapLayers.vms(listOf(mapOf("vmsId" to "0010VME02750", "vmsMessage" to " 07시-21시 안성-한남", "vmsMessage2" to " 급진로 변경 절 대 금 지", "xCoord" to "37.004680", "yCoord" to "127.150280", "routeName" to "경부선", "updownType" to "E")))
        assertEquals(37.00468, vms.single().lat, 1e-9)
        assertEquals(127.15028, vms.single().lng, 1e-9)
        assertEquals("07시-21시 안성-한남", vms.single().title)
        assertTrue("급진로 변경 절 대 금 지" in vms.single().details)

        // 좌표가 이상하거나 없으면 건너뜀
        assertTrue(ExMapLayers.toll(listOf(mapOf("unitName" to "x", "xValue" to "0", "yValue" to "0"), mapOf("unitName" to "y"))).isEmpty())
    }

    @Test
    fun restDetailParsersUseRealFieldShapes() {
        val gasRows = listOf(
            mapOf("serviceAreaName" to "서울만남(부산)주유소", "oilCompany" to "AD", "gasolinePrice" to "1,864원", "diselPrice" to "1,853원", "lpgPrice" to "1,232원", "lpgYn" to "Y", "telNo" to "02-573-7430"),
            mapOf("serviceAreaName" to "죽전(서울)주유소", "oilCompany" to "SK", "gasolinePrice" to "1,900원", "lpgYn" to "N", "lpgPrice" to "0원"),
        )
        val g = com.eunho.tagohub.data.ExRestParser.gas(gasRows, "서울만남(부산)휴게소")!!
        assertEquals("1,864원", g.gasoline)
        assertEquals("1,232원", g.lpg)
        // LPG 를 안 파는 곳은 LPG 가격을 보여주지 않음
        assertEquals(null, com.eunho.tagohub.data.ExRestParser.gas(gasRows, "죽전(서울)휴게소")!!.lpg)
        assertEquals(null, com.eunho.tagohub.data.ExRestParser.gas(gasRows, "없는곳휴게소"))

        val conv = listOf(mapOf("psName" to "수유실"), mapOf("psName" to "ATM"), mapOf("psName" to "수유실"))
        assertEquals(listOf("수유실", "ATM"), com.eunho.tagohub.data.ExRestParser.facilities(conv))
        val foods = com.eunho.tagohub.data.ExRestParser.foods(
            listOf(
                mapOf("foodNm" to "순두부찌개", "foodCost" to "9500", "recommendyn" to "N", "bestfoodyn" to "N"),
                mapOf("foodNm" to "유부우동", "foodCost" to "7500", "recommendyn" to "Y", "bestfoodyn" to "N"),
            ),
        )
        assertEquals("유부우동", foods.first().name)  // 추천 메뉴가 앞
        assertTrue(foods.first().recommended)
    }
}
