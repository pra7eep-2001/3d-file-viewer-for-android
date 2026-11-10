package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DxfImporterTest {
    /** Builds DXF text from (code, value) pairs. */
    private fun dxf(vararg pairs: Any): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pairs.size) { sb.append(pairs[i]).append("\r\n").append(pairs[i + 1]).append("\r\n"); i += 2 }
        return sb.toString()
    }

    private val header = arrayOf<Any>(0, "SECTION", 2, "HEADER", 9, "\$ACADVER", 1, "AC1015", 9, "\$INSUNITS", 70, 4, 0, "ENDSEC")

    @Test fun facesLinesAndLayers() {
        val text = dxf(*header,
            0, "SECTION", 2, "ENTITIES",
            // quad 3DFACE on layer A
            0, "3DFACE", 8, "A", 10, 0, 20, 0, 30, 0, 11, 1, 21, 0, 31, 0, 12, 1, 22, 1, 32, 0, 13, 0, 23, 1, 33, 0,
            // triangle 3DFACE (4th corner equals 3rd), layer A
            0, "3DFACE", 8, "A", 10, 0, 20, 0, 30, 5, 11, 1, 21, 0, 31, 5, 12, 1, 22, 1, 32, 5, 13, 1, 23, 1, 33, 5,
            // line on layer B
            0, "LINE", 8, "B", 10, 0, 20, 0, 30, 0, 11, 0, 21, 0, 31, 9,
            // closed lwpolyline with 4 vertices on layer B
            0, "LWPOLYLINE", 8, "B", 90, 4, 70, 1, 38, 2, 10, 0, 20, 0, 10, 1, 20, 0, 10, 1, 20, 1, 10, 0, 20, 1,
            // circle
            0, "CIRCLE", 8, "C", 10, 0, 20, 0, 30, 0, 40, 2,
            0, "TEXT", 8, "C", 1, "hello", 10, 0, 20, 0, 30, 0,
            0, "ENDSEC", 0, "EOF")
        val m = TestUtil.load(DxfImporter(), TestUtil.tempFile("t.dxf", text))
        assertEquals(ModelUnits.MILLIMETER, m.declaredUnits)
        assertEquals(3, m.roots.size)
        val a = m.roots.first { it.name == "A" }; val b = m.roots.first { it.name == "B" }; val c = m.roots.first { it.name == "C" }
        assertEquals(3, a.mesh!!.triangleCount)
        assertEquals(1 + 4, b.mesh!!.edgeSegmentCount)
        assertEquals(9f, b.mesh!!.bounds.maxZ, 1e-6f)
        assertEquals(0f, b.mesh!!.bounds.minZ, 1e-6f)
        val ez = b.mesh!!.edgePositions!!.filterIndexed { i, _ -> i % 3 == 2 }
        assertTrue(ez.count { it == 2f } >= 4) // lwpolyline vertices sit at its elevation
        assertTrue(c.mesh!!.edgeSegmentCount >= 8)
        assertEquals(4f, c.mesh!!.bounds.sizeX, 1e-3f)
        assertTrue(m.notes.any { it.contains("text") })
    }

    @Test fun polyfaceMeshAndPolygonMesh() {
        val text = dxf(0, "SECTION", 2, "ENTITIES",
            0, "POLYLINE", 8, "M", 66, 1, 70, 64, 71, 4, 72, 1,
            0, "VERTEX", 8, "M", 10, 0, 20, 0, 30, 0, 70, 192,
            0, "VERTEX", 8, "M", 10, 1, 20, 0, 30, 0, 70, 192,
            0, "VERTEX", 8, "M", 10, 1, 20, 1, 30, 0, 70, 192,
            0, "VERTEX", 8, "M", 10, 0, 20, 1, 30, 0, 70, 192,
            0, "VERTEX", 8, "M", 10, 0, 20, 0, 30, 0, 70, 128, 71, 1, 72, 2, 73, -3, 74, 4,
            0, "SEQEND",
            // 2x3 polygon mesh
            0, "POLYLINE", 8, "G", 66, 1, 70, 16, 71, 2, 72, 3,
            0, "VERTEX", 8, "G", 10, 0, 20, 0, 30, 1, 70, 64,
            0, "VERTEX", 8, "G", 10, 1, 20, 0, 30, 1, 70, 64,
            0, "VERTEX", 8, "G", 10, 2, 20, 0, 30, 1, 70, 64,
            0, "VERTEX", 8, "G", 10, 0, 20, 1, 30, 1, 70, 64,
            0, "VERTEX", 8, "G", 10, 1, 20, 1, 30, 1, 70, 64,
            0, "VERTEX", 8, "G", 10, 2, 20, 1, 30, 1, 70, 64,
            0, "SEQEND",
            // 3D polyline
            0, "POLYLINE", 8, "P", 66, 1, 70, 8,
            0, "VERTEX", 8, "P", 10, 0, 20, 0, 30, 0, 70, 32,
            0, "VERTEX", 8, "P", 10, 1, 20, 1, 30, 1, 70, 32,
            0, "VERTEX", 8, "P", 10, 2, 20, 0, 30, 2, 70, 32,
            0, "SEQEND",
            0, "ENDSEC", 0, "EOF")
        val m = TestUtil.load(DxfImporter(), TestUtil.tempFile("p.dxf", text))
        assertEquals(ModelUnits.UNKNOWN, m.declaredUnits)
        assertEquals(2, m.roots.first { it.name == "M" }.mesh!!.triangleCount)
        assertEquals(4, m.roots.first { it.name == "G" }.mesh!!.triangleCount)
        assertEquals(2, m.roots.first { it.name == "P" }.mesh!!.edgeSegmentCount)
    }

    @Test fun meshEntity() {
        val text = dxf(0, "SECTION", 2, "ENTITIES",
            0, "MESH", 8, "0", 71, 2, 72, 0, 91, 0, 92, 4, 10, 0, 20, 0, 30, 0, 10, 1, 20, 0, 30, 0, 10, 1, 20, 1, 30, 0, 10, 0, 20, 1, 30, 0,
            93, 9, 90, 4, 90, 0, 90, 1, 90, 2, 90, 3, 90, 3, 90, 0, 90, 2, 90, 3, 94, 0,
            0, "ENDSEC", 0, "EOF")
        val m = TestUtil.load(DxfImporter(), TestUtil.tempFile("m.dxf", text))
        assertEquals(3L, m.triangleCount)
    }

    @Test fun blockInsertWithScaleRotationAndNesting() {
        val text = dxf(0, "SECTION", 2, "BLOCKS",
            0, "BLOCK", 8, "0", 2, "TRI", 70, 0, 10, 0, 20, 0, 30, 0,
            0, "3DFACE", 8, "0", 10, 0, 20, 0, 30, 0, 11, 1, 21, 0, 31, 0, 12, 0, 22, 1, 32, 0, 13, 0, 23, 1, 33, 0,
            0, "ENDBLK",
            0, "BLOCK", 8, "0", 2, "OUTER", 70, 0, 10, 0, 20, 0, 30, 0,
            0, "INSERT", 8, "0", 2, "TRI", 10, 0, 20, 0, 30, 0,
            0, "INSERT", 8, "0", 2, "TRI", 10, 0, 20, 0, 30, 5,
            0, "ENDBLK",
            0, "ENDSEC",
            0, "SECTION", 2, "ENTITIES",
            0, "INSERT", 8, "PARTS", 2, "TRI", 10, 10, 20, 0, 30, 0, 41, 2, 42, 2, 43, 2, 50, 90,
            0, "INSERT", 8, "ASM", 2, "OUTER", 10, 0, 20, 0, 30, 100,
            0, "INSERT", 8, "ASM", 2, "MISSING", 10, 0, 20, 0, 30, 0,
            0, "ENDSEC", 0, "EOF")
        val m = TestUtil.load(DxfImporter(), TestUtil.tempFile("b.dxf", text))
        val parts = m.roots.first { it.name == "PARTS" }
        assertEquals(1, parts.mesh!!.triangleCount)
        // (1,0,0) scaled 2 -> (2,0,0), rotated 90 about Z -> (0,2,0), translated (10,0,0)
        assertEquals(10f, parts.mesh!!.bounds.maxX, 1e-4f)
        assertEquals(8f, parts.mesh!!.bounds.minX, 1e-4f)
        assertEquals(2f, parts.mesh!!.bounds.maxY, 1e-4f)
        val asm = m.roots.first { it.name == "ASM" }
        assertEquals(2, asm.mesh!!.triangleCount)
        assertEquals(100f, asm.mesh!!.bounds.minZ, 1e-4f)
        assertEquals(105f, asm.mesh!!.bounds.maxZ, 1e-4f)
        assertTrue(m.notes.any { it.contains("MISSING") })
    }

    @Test fun twoDimensionalDrawingNote() {
        val text = dxf(0, "SECTION", 2, "ENTITIES",
            0, "LINE", 8, "0", 10, 0, 20, 0, 30, 0, 11, 5, 21, 5, 31, 0,
            0, "ARC", 8, "0", 10, 0, 20, 0, 30, 0, 40, 1, 50, 0, 51, 90,
            0, "ENDSEC", 0, "EOF")
        val m = TestUtil.load(DxfImporter(), TestUtil.tempFile("2d.dxf", text))
        assertTrue(m.notes.any { it.contains("2D") })
        assertEquals(0L, m.triangleCount)
        assertTrue(m.edgeSegmentCount > 5)
        assertEquals(1f, m.bounds.maxY.coerceAtMost(1f), 1e-5f)
    }

    @Test fun binaryRejected() {
        val bytes = "AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(32)
        try { TestUtil.load(DxfImporter(), TestUtil.tempFile("bin.dxf", bytes)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("Binary DXF")) }
    }

    @Test fun badNumberReported() {
        val text = dxf(0, "SECTION", 2, "ENTITIES", 0, "LINE", 8, "0", 10, "abc", 20, 0, 30, 0, 11, 5, 21, 5, 31, 0, 0, "ENDSEC", 0, "EOF")
        try { TestUtil.load(DxfImporter(), TestUtil.tempFile("bad.dxf", text)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("abc") && e.message!!.contains("line")) }
    }

    @Test fun onlySolidsRejected() {
        val text = dxf(0, "SECTION", 2, "ENTITIES", 0, "3DSOLID", 8, "0", 1, "ACIS data", 0, "ENDSEC", 0, "EOF")
        try { TestUtil.load(DxfImporter(), TestUtil.tempFile("s.dxf", text)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("ACIS")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".dxf") }.sortedBy { it.name }) {
            val m = TestUtil.load(DxfImporter(), f)
            println("${f.name}: nodes=${m.nodes.size} tris=${m.triangleCount} edges=${m.edgeSegmentCount} units=${m.declaredUnits} notes=${m.notes}")
            assertTrue(f.name, m.triangleCount > 0 || m.edgeSegmentCount > 0)
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(DxfImporter(), TestUtil.sample("dxf/PinkEggFromLW.dxf"))
        assertEquals(528L, m.triangleCount)
        assertEquals(1, m.roots.size)
    }

    @Test fun manyFacesThroughput() {
        val sb = StringBuilder(8 shl 20)
        sb.append("0\nSECTION\n2\nENTITIES\n")
        val n = 60_000
        for (k in 0 until n) {
            val x = (k % 300).toFloat(); val y = (k / 300).toFloat()
            sb.append("0\n3DFACE\n8\nL\n10\n").append(x).append("\n20\n").append(y).append("\n30\n0\n11\n").append(x + 1).append("\n21\n").append(y).append("\n31\n0\n12\n").append(x + 1).append("\n22\n").append(y + 1).append("\n32\n0\n13\n").append(x).append("\n23\n").append(y + 1).append("\n33\n0\n")
        }
        sb.append("0\nENDSEC\n0\nEOF\n")
        val f = TestUtil.tempFile("many.dxf", sb.toString())
        val t0 = System.nanoTime()
        val m = TestUtil.load(DxfImporter(), f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n * 2L, m.triangleCount)
        assertTrue("took $ms ms", ms < 15_000)
    }
}
