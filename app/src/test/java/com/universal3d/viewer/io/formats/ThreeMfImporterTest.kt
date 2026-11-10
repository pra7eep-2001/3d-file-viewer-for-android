package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThreeMfImporterTest {
    private val model = """
        <?xml version="1.0" encoding="UTF-8"?>
        <model unit="inch" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:m="http://schemas.microsoft.com/3dmanufacturing/material/2015/02">
          <resources>
            <basematerials id="5">
              <base name="Green" displaycolor="#00FF00" />
              <base name="Blue" displaycolor="#0000FFFF" />
            </basematerials>
            <object id="1" name="Tri" type="model" pid="5" pindex="1">
              <mesh>
                <vertices>
                  <vertex x="0" y="0" z="0" />
                  <vertex x="1" y="0" z="0" />
                  <vertex x="0" y="1" z="0" />
                </vertices>
                <triangles>
                  <triangle v1="0" v2="1" v3="2" />
                </triangles>
              </mesh>
            </object>
            <object id="2" name="Pair" type="model">
              <components>
                <component objectid="1" />
                <component objectid="1" transform="1 0 0 0 1 0 0 0 1 5 0 0" />
              </components>
            </object>
          </resources>
          <build>
            <item objectid="2" transform="0 1 0 -1 0 0 0 0 1 0 0 10" />
          </build>
        </model>
    """.trimIndent()

    private fun pack(modelXml: String, partName: String = "3D/3dmodel.model", withRels: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml" /><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" /></Types>""".toByteArray()); z.closeEntry()
            if (withRels) { z.putNextEntry(ZipEntry("_rels/.rels")); z.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/$partName" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel" /></Relationships>""".toByteArray()); z.closeEntry() }
            z.putNextEntry(ZipEntry(partName)); z.write(modelXml.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test fun componentsTransformsColours() {
        val m = TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", pack(model)))
        assertEquals(ModelUnits.INCH, m.declaredUnits)
        assertEquals(1, m.roots.size)
        assertEquals("Pair", m.roots[0].name)
        assertEquals(2, m.roots[0].children.size)
        assertEquals("Tri", m.roots[0].children[0].name)
        assertEquals(0xff0000ff.toInt(), m.roots[0].children[0].color)
        assertEquals(2L, m.triangleCount)
        // item rotates 90 degrees about Z (row-vector matrix: x -> y) and lifts by 10; second component shifted by x=5
        val b = m.bounds
        assertEquals(10f, b.minZ, 1e-5f)
        assertEquals(6f, b.maxY, 1e-5f)   // x=1 (+5) maps to y=6
        assertEquals(-1f, b.minX, 1e-5f)  // y=1 maps to x=-1
    }

    @Test fun customPartNameViaRels() {
        val m = TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", pack(model, "3D/custom.model")))
        assertEquals(2L, m.triangleCount)
    }

    @Test fun fallbackWithoutRels() {
        val m = TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", pack(model, withRels = false)))
        assertEquals(2L, m.triangleCount)
    }

    @Test fun perTriangleColourAndNoBuild() {
        val xml = """
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:m="http://schemas.microsoft.com/3dmanufacturing/material/2015/02">
              <resources>
                <m:colorgroup id="3"><m:color color="#FF0000" /><m:color color="#123456" /></m:colorgroup>
                <object id="1"><mesh>
                  <vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices>
                  <triangles><triangle v1="0" v2="1" v3="2" pid="3" p1="1"/></triangles>
                </mesh></object>
              </resources>
            </model>
        """.trimIndent()
        val m = TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", pack(xml)))
        assertEquals(ModelUnits.MILLIMETER, m.declaredUnits)
        assertEquals(0xff123456.toInt(), m.roots[0].color)
        assertEquals("Object 1", m.roots[0].name)
    }

    @Test fun notAZip() {
        try { TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", "hello")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("zip")) }
    }

    @Test fun badIndex() {
        try { TestUtil.load(ThreeMfImporter(), TestUtil.tempFile("t.3mf", pack(model.replace("v3=\"2\"", "v3=\"7\"")))); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("7")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".3mf") }.sortedBy { it.name }) {
            val m = TestUtil.load(ThreeMfImporter(), f)
            assertTrue(f.name, m.triangleCount > 0)
            assertEquals(f.name, ModelUnits.MILLIMETER, m.declaredUnits)
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(ThreeMfImporter(), TestUtil.sample("3mf/box.3mf"))
        assertEquals(ModelUnits.MILLIMETER, m.declaredUnits)
        assertEquals(12L, m.triangleCount)
        assertEquals(30f, m.bounds.sizeZ, 1e-5f)
    }
}
