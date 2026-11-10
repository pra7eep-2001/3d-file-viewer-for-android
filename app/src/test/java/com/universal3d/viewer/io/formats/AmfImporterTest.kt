package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AmfImporterTest {
    private val pyramid = """
        <?xml version="1.0" encoding="utf-8"?>
        <amf unit="inch" version="1.1">
          <metadata type="name">Split Pyramid</metadata>
          <object id="1">
            <metadata type="name">Pyramid</metadata>
            <mesh>
              <vertices>
                <vertex><coordinates><x>0</x><y>0</y><z>0</z></coordinates></vertex>
                <vertex><coordinates><x>1</x><y>0</y><z>0</z></coordinates></vertex>
                <vertex><coordinates><x>0</x><y>1</y><z>0</z></coordinates></vertex>
                <vertex><coordinates><x>1</x><y>1</y><z>0</z></coordinates></vertex>
                <vertex><coordinates><x>0.5</x><y>0.5</y><z>1</z></coordinates></vertex>
              </vertices>
              <volume materialid="2">
                <metadata type="name">Hard side</metadata>
                <triangle><v1>2</v1><v2>1</v2><v3>0</v3></triangle>
                <triangle><v1>0</v1><v2>1</v2><v3>4</v3></triangle>
                <triangle><v1>4</v1><v2>1</v2><v3>2</v3></triangle>
                <triangle><v1>0</v1><v2>4</v2><v3>2</v3></triangle>
              </volume>
              <volume materialid="3">
                <metadata type="name">Soft side</metadata>
                <color><r>1</r><g>0</g><b>0</b></color>
                <triangle><v1>2</v1><v2>3</v2><v3>1</v3></triangle>
                <triangle><v1>1</v1><v2>3</v2><v3>4</v3></triangle>
                <triangle><v1>4</v1><v2>3</v2><v3>2</v3></triangle>
                <triangle><v1>4</v1><v2>2</v2><v3>1</v3></triangle>
              </volume>
            </mesh>
          </object>
          <material id="2">
            <metadata type="name">Hard material</metadata>
            <color><r>0</r><g>1</g><b>0</b></color>
          </material>
          <material id="3">
            <color><r>0</r><g>0.9</g><b>0.9</b><a>0.5</a></color>
          </material>
        </amf>
    """.trimIndent()

    @Test fun plainAmf() {
        val m = TestUtil.load(AmfImporter(), TestUtil.tempFile("p.amf", pyramid))
        assertEquals(ModelUnits.INCH, m.declaredUnits)
        assertEquals(1, m.roots.size)
        assertEquals("Pyramid", m.roots[0].name)
        assertEquals(2, m.roots[0].children.size)
        assertEquals("Hard side", m.roots[0].children[0].name)
        assertEquals(0xff00ff00.toInt(), m.roots[0].children[0].color) // material colour
        assertEquals(0xffff0000.toInt(), m.roots[0].children[1].color) // volume colour wins over material
        assertEquals(8L, m.triangleCount)
        assertEquals(1f, m.bounds.sizeZ, 1e-6f)
    }

    @Test fun zippedAmf() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("p.amf")); z.write(pyramid.toByteArray()); z.closeEntry() }
        val m = TestUtil.load(AmfImporter(), TestUtil.tempFile("z.amf", bos.toByteArray()))
        assertEquals(8L, m.triangleCount)
    }

    @Test fun constellationAndObjectColour() {
        val amf = """
            <amf unit="millimeter">
              <object id="0">
                <color><r>0</r><g>0</g><b>1</b></color>
                <mesh><vertices>
                  <vertex><coordinates><x>0</x><y>0</y><z>0</z></coordinates></vertex>
                  <vertex><coordinates><x>1</x><y>0</y><z>0</z></coordinates></vertex>
                  <vertex><coordinates><x>0</x><y>1</y><z>0</z></coordinates></vertex>
                </vertices><volume><triangle><v1>0</v1><v2>1</v2><v3>2</v3></triangle></volume></mesh>
              </object>
              <constellation id="5">
                <instance objectid="0"><deltax>10</deltax><deltay>0</deltay><deltaz>0</deltaz><rx>0</rx><ry>0</ry><rz>0</rz></instance>
                <instance objectid="0"><deltax>0</deltax><deltay>0</deltay><deltaz>0</deltaz><rx>0</rx><ry>0</ry><rz>90</rz></instance>
              </constellation>
            </amf>
        """.trimIndent()
        val m = TestUtil.load(AmfImporter(), TestUtil.tempFile("c.amf", amf))
        assertEquals(ModelUnits.MILLIMETER, m.declaredUnits)
        assertEquals(1, m.roots.size)
        assertEquals(2, m.roots[0].children.size)
        assertEquals(2L, m.triangleCount)
        assertEquals(0xff0000ff.toInt(), m.roots[0].children[0].color)
        assertEquals(11f, m.bounds.maxX, 1e-5f)
        assertEquals(-1f, m.bounds.minX, 1e-5f) // rotated instance
    }

    @Test fun badIndexIsReported() {
        val amf = pyramid.replace("<v3>4</v3>", "<v3>99</v3>")
        try { TestUtil.load(AmfImporter(), TestUtil.tempFile("bad.amf", amf)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("99")) }
    }

    @Test fun malformedXml() {
        try { TestUtil.load(AmfImporter(), TestUtil.tempFile("bad.amf", "<amf><object id=\"1\"><mesh>")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("XML")) }
    }

    @Test fun noGeometry() {
        try { TestUtil.load(AmfImporter(), TestUtil.tempFile("e.amf", "<amf><object id=\"1\"><mesh><vertices/></mesh></object></amf>")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("no geometry")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".amf") }.sortedBy { it.name }) {
            val m = TestUtil.load(AmfImporter(), f)
            assertTrue(f.name, m.triangleCount > 0)
        }
    }
}
