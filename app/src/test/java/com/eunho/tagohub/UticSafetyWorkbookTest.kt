package com.eunho.tagohub

import com.eunho.tagohub.data.UticSafetyRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UticSafetyWorkbookTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsCachedWorkbookRowsAndTheirCoordinates() {
        val workbook = zip(
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>L94100058408</t></si>
                  <si><t>Y</t></si>
                  <si><t>급커브 구간</t></si>
                  <si><t>경북 고령군</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                    <row r="2">
                      <c r="A2" t="s"><v>0</v></c>
                      <c r="B2" t="s"><v>1</v></c>
                      <c r="D2" t="s"><v>3</v></c>
                      <c r="M2"><v>128.1781791</v></c>
                      <c r="N2"><v>35.6728639</v></c>
                      <c r="O2" t="s"><v>2</v></c>
                    </row>
                  </sheetData>
                </worksheet>
            """.trimIndent(),
        )
        val archive = File(temporaryFolder.root, "safety.zip")
        ZipOutputStream(archive.outputStream()).use { outer ->
            outer.putNextEntry(ZipEntry("tsdms_data/위험요소_도로환경별.xlsx"))
            outer.write(workbook)
            outer.closeEntry()
        }

        val points = UticSafetyRepository(temporaryFolder.root, key = "test").parseArchive(archive)

        assertEquals(1, points.size)
        assertEquals("급커브 구간", points.single().description)
        assertEquals("경북 고령군", points.single().address)
        assertEquals(128.1781791, points.single().lng, 0.000001)
        assertEquals(35.6728639, points.single().lat, 0.000001)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
