package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlyImporterTest {
    private val asciiHeader = """
        ply
        format ascii 1.0
        comment made by hand
        obj_info whatever
        element vertex 4
        property float x
        property float y
        property float z
        property float nx
        property float ny
        property float nz
        property uchar red
        property uchar green
        property uchar blue
        property float confidence
        element face 2
        property list uchar int vertex_indices
        property uchar flags
        element edge 1
        property int vertex1
        property int vertex2
        element custom 2
        property float a
        property list uchar float b
        end_header
    """.trimIndent()

    private val asciiBody = """
        0 0 0 0 0 1 255 0 0 0.5
        1 0 0 0 0 1 255 0 0 0.5
        1 1 0 0 0 1 255 0 0 0.5
        0 1 0 0 0 1 255 0 0 0.5
        3 0 1 2 7
        4 0 1 2 3 7
        0 1
        1.5 2 3 4
        2.5 0
    """.trimIndent()

    @Test fun ascii() {
        val m = TestUtil.load(PlyImporter(), TestUtil.tempFile("a.ply", asciiHeader + "\n" + asciiBody + "\n"))
        assertEquals(4L, m.vertexCount)
        assertEquals(3L, m.triangleCount) // triangle + quad
        assertEquals(1L, m.edgeSegmentCount)
        assertEquals(0xffff0000.toInt(), m.nodes[0].color)
        assertNotNull(m.nodes[0].mesh!!.normals)
        assertEquals(ModelUnits.UNKNOWN, m.declaredUnits)
        assertTrue(m.notes.contains("ASCII PLY"))
    }

    private fun binaryCube(order: ByteOrder): ByteArray {
        val fmt = if (order == ByteOrder.LITTLE_ENDIAN) "binary_little_endian" else "binary_big_endian"
        val header = "ply\nformat $fmt 1.0\nelement vertex 8\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nproperty uchar alpha\nelement face 6\nproperty list uchar uint vertex_indices\nend_header\n"
        val bos = ByteArrayOutputStream(); bos.write(header.toByteArray())
        val bb = ByteBuffer.allocate(8 * 16 + 6 * 17).order(order)
        val p = floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f, 0f,0f,1f, 1f,0f,1f, 1f,1f,1f, 0f,1f,1f)
        for (i in 0 until 8) { bb.putFloat(p[i*3]); bb.putFloat(p[i*3+1]); bb.putFloat(p[i*3+2]); bb.put(0); bb.put(0); bb.put(255.toByte()); bb.put(128.toByte()) }
        val faces = arrayOf(intArrayOf(0,3,2,1), intArrayOf(4,5,6,7), intArrayOf(0,1,5,4), intArrayOf(1,2,6,5), intArrayOf(2,3,7,6), intArrayOf(3,0,4,7))
        for (f in faces) { bb.put(4); for (i in f) bb.putInt(i) }
        bos.write(bb.array()); return bos.toByteArray()
    }

    @Test fun binaryLittleEndian() {
        val m = TestUtil.load(PlyImporter(), TestUtil.tempFile("b.ply", binaryCube(ByteOrder.LITTLE_ENDIAN)))
        assertEquals(8L, m.vertexCount); assertEquals(12L, m.triangleCount)
        assertEquals(0x800000ff.toInt(), m.nodes[0].color)
        assertEquals(1f, m.bounds.sizeZ, 0f)
    }

    @Test fun binaryBigEndian() {
        val m = TestUtil.load(PlyImporter(), TestUtil.tempFile("b.ply", binaryCube(ByteOrder.BIG_ENDIAN)))
        assertEquals(8L, m.vertexCount); assertEquals(12L, m.triangleCount)
        assertEquals(1f, m.bounds.sizeX, 0f)
    }

    @Test fun truncatedBinaryIsReported() {
        val full = binaryCube(ByteOrder.LITTLE_ENDIAN)
        val cut = full.copyOf(full.size - 20)
        try { TestUtil.load(PlyImporter(), TestUtil.tempFile("b.ply", cut)); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("truncated") || e.message!!.contains("declares")) }
    }

    @Test fun absurdCountsFailFast() {
        val src = "ply\nformat binary_little_endian 1.0\nelement vertex 1000000000\nproperty float x\nproperty float y\nproperty float z\nend_header\n\u0000\u0000"
        try { TestUtil.load(PlyImporter(), TestUtil.tempFile("b.ply", src)); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("declares more data")) }
    }

    @Test fun pointCloudIsRejected() {
        val src = "ply\nformat ascii 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n1 1 1\n"
        try { TestUtil.load(PlyImporter(), TestUtil.tempFile("p.ply", src)); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("point clouds")) }
    }

    @Test fun badIndexIsReported() {
        val src = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_index\nend_header\n0 0 0\n1 0 0\n0 1 0\n3 0 1 9\n"
        try { TestUtil.load(PlyImporter(), TestUtil.tempFile("p.ply", src)); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("vertex 9")) }
    }

    @Test fun notPly() {
        try { TestUtil.load(PlyImporter(), TestUtil.tempFile("p.ply", "solid foo\nfacet normal 0 0 0\n")); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("Not a PLY")) }
    }

    @Test fun assimpCubeAscii() {
        val m = TestUtil.load(PlyImporter(), TestUtil.sample("ply/cube.ply"))
        assertEquals(8L, m.vertexCount); assertEquals(12L, m.triangleCount)
    }

    @Test fun assimpCubeBinary() {
        val m = TestUtil.load(PlyImporter(), TestUtil.sample("ply/cube_binary.ply"))
        assertEquals(8L, m.vertexCount); assertEquals(12L, m.triangleCount)
    }

    @Test fun blenderCubeWithUv() {
        val m = TestUtil.load(PlyImporter(), TestUtil.sample("ply/cube_uv.ply"))
        assertEquals(24L, m.vertexCount); assertEquals(12L, m.triangleCount)
        assertNotNull(m.nodes[0].mesh!!.normals)
    }
}
