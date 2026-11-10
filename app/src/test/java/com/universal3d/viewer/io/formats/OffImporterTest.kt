package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import org.junit.Assert.*
import org.junit.Test

class OffImporterTest {
    private val cube = """
        OFF
        # a unit cube
        8 6 12
        0 0 0
        1 0 0
        1 1 0
        0 1 0
        0 0 1
        1 0 1
        1 1 1
        0 1 1
        4 0 3 2 1
        4 4 5 6 7
        4 0 1 5 4
        4 1 2 6 5
        4 2 3 7 6
        4 3 0 4 7
    """.trimIndent()

    @Test fun plainOff() {
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("cube.off", cube))
        assertEquals(12L, m.triangleCount)
        assertEquals(8L, m.vertexCount)
        assertEquals(1, m.nodes.size)
        assertEquals("cube", m.nodes[0].name)
        assertEquals(0, m.nodes[0].color)
        assertNull(m.nodes[0].mesh!!.normals)
        assertEquals(1f, m.bounds.sizeX, 1e-6f); assertEquals(1f, m.bounds.sizeZ, 1e-6f)
    }

    @Test fun countsOnKeywordLine() {
        val src = "OFF 3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(1L, m.triangleCount)
    }

    @Test fun noKeyword() {
        val src = "3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(1L, m.triangleCount)
    }

    @Test fun coffVertexColours255() {
        val src = "COFF\n3 1 0\n0 0 0 255 0 0 255\n1 0 0 255 0 0 255\n0 1 0 255 0 0 255\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(0xffff0000.toInt(), m.nodes[0].color)
    }

    @Test fun coffVertexColoursFloat() {
        val src = "COFF\n3 1 0\n0 0 0 0.0 0.0 1.0 1.0\n1 0 0 0.0 0.0 1.0 1.0\n0 1 0 0.0 0.0 1.0 1.0\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(0xff0000ff.toInt(), m.nodes[0].color)
    }

    @Test fun noffNormals() {
        val src = "NOFF\n3 1 0\n0 0 0 0 0 1\n1 0 0 0 0 1\n0 1 0 0 0 1\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        val n = m.nodes[0].mesh!!.normals!!
        assertEquals(9, n.size); assertEquals(1f, n[2], 0f)
    }

    @Test fun faceColoursWhenNoVertexColours() {
        val src = "OFF\n3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2 0 255 0\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(0xff00ff00.toInt(), m.nodes[0].color)
    }

    @Test fun homogeneous4Off() {
        val src = "4OFF\n3 1 0\n0 0 0 1\n2 0 0 2\n0 2 0 2\n3 0 1 2\n"
        val m = TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src))
        assertEquals(1f, m.bounds.sizeX, 1e-6f)
    }

    @Test fun nOffDimension3() {
        val src = "nOFF\n3\n3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n"
        assertEquals(1L, TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src)).triangleCount)
        val bad = "nOFF\n4\n3 1 0\n0 0 0 0\n1 0 0 0\n0 1 0 0\n3 0 1 2\n"
        try { TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", bad)); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("dimension 4")) }
    }

    @Test fun polygonsTriangulated() {
        val src = "OFF\n5 1 0\n0 0 0\n2 0 0\n2 2 0\n1 3 0\n0 2 0\n5 0 1 2 3 4\n"
        assertEquals(3L, TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src)).triangleCount)
    }

    @Test fun badIndexIsReported() {
        val src = "OFF\n3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 7\n"
        try { TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src)); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("vertex 7")) }
    }

    @Test fun absurdCountsFailFast() {
        val src = "OFF\n2000000000 1 0\n0 0 0\n"
        try { TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", src)); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("cannot fit")) }
    }

    @Test fun garbageFails() {
        try { TestUtil.load(OffImporter(), TestUtil.tempFile("t.off", "hello world\n1 2 3\n")); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("Not an OFF")) }
    }

    @Test fun assimpInvalidOffIsRejected() {
        // vertex count 3, face "0" with zero vertices, then garbage counts: must not crash with an internal error
        try { TestUtil.load(OffImporter(), TestUtil.sample("off/invalid.off")); fail() } catch (e: ImportException) { assertNotNull(e.message) }
    }

    @Test fun wuson() {
        val m = TestUtil.load(OffImporter(), TestUtil.sample("off/Wuson.off"))
        assertEquals(3205L, m.vertexCount)
        assertEquals(3732L, m.triangleCount)
    }
}
