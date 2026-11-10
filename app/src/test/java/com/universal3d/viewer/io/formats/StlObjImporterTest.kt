package com.universal3d.viewer.io.formats

import com.universal3d.viewer.model.MeshOps
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlObjImporterTest {
    private val asciiStl = """
        solid cube
          facet normal 0 0 -1
            outer loop
              vertex 0 0 0
              vertex 1 1 0
              vertex 1 0 0
            endloop
          endfacet
          facet normal 0 0 -1
            outer loop
              vertex 0 0 0
              vertex 0 1 0
              vertex 1 1 0
            endloop
          endfacet
        endsolid cube
    """.trimIndent()

    @Test fun asciiStl() {
        val f = TestUtil.tempFile("a.stl", asciiStl)
        val m = TestUtil.load(StlImporter(), f)
        assertEquals(2L, m.triangleCount)
        assertEquals(4L, m.vertexCount) // welded
        assertEquals(1f, m.bounds.sizeX, 1e-6f)
        assertEquals(0f, m.bounds.sizeZ, 1e-6f)
    }

    @Test fun binaryStl() {
        val bos = ByteArrayOutputStream()
        bos.write(ByteArray(80))
        val n = 2
        val bb = ByteBuffer.allocate(4 + 50 * n).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(n)
        val tris = arrayOf(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f))
        for (t in tris) { bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(-1f); for (v in t) bb.putFloat(v); bb.putShort(0) }
        bos.write(bb.array())
        val f = TestUtil.tempFile("b.stl", bos.toByteArray())
        val m = TestUtil.load(StlImporter(), f)
        assertEquals(2L, m.triangleCount)
        assertEquals(4L, m.vertexCount)
        assertEquals("Binary STL", m.notes[0])
    }

    @Test fun obj() {
        val obj = """
            # cube-ish
            mtllib t.mtl
            o Box
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vn 0 0 1
            usemtl red
            f 1//1 2//1 3//1 4//1
            o Lines
            l 1 2 3
        """.trimIndent()
        val f = TestUtil.tempFile("t.obj", obj)
        java.io.File(f.parentFile, "t.mtl").writeText("newmtl red\nKd 1 0 0\n")
        val m = TestUtil.load(ObjImporter(), f)
        assertEquals(2, m.nodes.size)
        assertEquals("Box", m.nodes[0].name)
        assertEquals(2L, m.triangleCount)
        assertEquals(0xffff0000.toInt(), m.nodes[0].color)
        assertNotNull(m.nodes[0].mesh!!.normals)
        assertEquals(2, m.nodes[1].mesh!!.edgeSegmentCount)
    }

    @Test fun featureEdgesOfCube() {
        // closed cube from 12 triangles: expect exactly 12 crease edges (all 90 degrees)
        val p = floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f, 0f,0f,1f, 1f,0f,1f, 1f,1f,1f, 0f,1f,1f)
        val i = intArrayOf(0,2,1, 0,3,2, 4,5,6, 4,6,7, 0,1,5, 0,5,4, 1,2,6, 1,6,5, 2,3,7, 2,7,6, 3,0,4, 3,4,7)
        val e = MeshOps.featureEdges(p, i)!!
        assertEquals(12, e.size / 2)
    }

    @Test fun triangulateConcave() {
        // L-shaped polygon (concave) with 6 vertices -> 4 triangles
        val p = floatArrayOf(0f,0f,0f, 2f,0f,0f, 2f,1f,0f, 1f,1f,0f, 1f,2f,0f, 0f,2f,0f)
        val out = com.universal3d.viewer.model.IntList()
        MeshOps.triangulatePolygon(intArrayOf(0,1,2,3,4,5), p, out)
        assertEquals(4, out.size / 3)
    }
}
