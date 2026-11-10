package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThreeDsImporterTest {
    /** Minimal 3DS chunk writer. */
    private fun chunk(id: Int, payload: ByteArray, vararg children: ByteArray): ByteArray {
        val total = 6 + payload.size + children.sumOf { it.size }
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(id.toShort()); bb.putInt(total); bb.put(payload); for (c in children) bb.put(c)
        return bb.array()
    }
    private fun asciiz(s: String) = s.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
    private fun le(block: ByteBuffer.() -> Unit, size: Int): ByteArray { val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); bb.block(); return bb.array() }

    private fun material(name: String, r: Int, g: Int, b: Int) =
        chunk(0xAFFF, ByteArray(0), chunk(0xA000, asciiz(name)), chunk(0xA020, ByteArray(0), chunk(0x0011, byteArrayOf(r.toByte(), g.toByte(), b.toByte()))))

    private fun quadObject(name: String, groups: Map<String, IntArray>, withMatrix: Boolean = false): ByteArray {
        val verts = le({ putShort(4); for (v in floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f)) putFloat(v) }, 2 + 4 * 12)
        val faces = le({ putShort(2); for (i in intArrayOf(0,1,2,7, 0,2,3,7)) putShort(i.toShort()) }, 2 + 2 * 8)
        val groupChunks = groups.map { (m, f) -> chunk(0x4130, asciiz(m) + le({ putShort(f.size.toShort()); for (i in f) putShort(i.toShort()) }, 2 + f.size * 2)) }
        val children = ArrayList<ByteArray>()
        children.add(chunk(0x4110, verts))
        children.add(chunk(0x4120, faces, *groupChunks.toTypedArray()))
        if (withMatrix) children.add(chunk(0x4160, le({ for (i in 0 until 12) putFloat(if (i % 4 == 0) 5f else 0f) }, 48)))
        return chunk(0x4000, asciiz(name), chunk(0x4100, ByteArray(0), *children.toTypedArray()))
    }

    private fun file(vararg editorChildren: ByteArray): ByteArray =
        chunk(0x4D4D, ByteArray(0), chunk(0x0002, le({ putInt(3) }, 4)), chunk(0x3D3D, ByteArray(0), chunk(0x0100, le({ putFloat(2.5f) }, 4)), *editorChildren), chunk(0xB000, ByteArray(0)))

    @Test fun singleMaterialObject() {
        val bytes = file(material("red", 255, 0, 0), quadObject("Quad", mapOf("red" to intArrayOf(0, 1)), withMatrix = true))
        val m = TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", bytes))
        assertEquals(1, m.nodes.size)
        assertEquals("Quad", m.nodes[0].name)
        assertEquals(2L, m.triangleCount); assertEquals(4L, m.vertexCount)
        assertEquals(0xffff0000.toInt(), m.nodes[0].color)
        assertEquals(1f, m.bounds.maxX, 0f) // matrix not applied, master scale not applied
        assertTrue(m.notes.any { it.contains("Master scale") })
    }

    @Test fun multiMaterialObjectBecomesGroup() {
        val bytes = file(material("red", 255, 0, 0), material("blue", 0, 0, 255), quadObject("Quad", mapOf("red" to intArrayOf(0), "blue" to intArrayOf(1))))
        val m = TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", bytes))
        assertEquals(3, m.nodes.size)
        assertEquals("Quad", m.roots[0].name); assertNull(m.roots[0].mesh)
        assertEquals(setOf("red", "blue"), m.roots[0].children.map { it.name }.toSet())
        assertEquals(2L, m.triangleCount)
        assertEquals(0xff0000ff.toInt(), m.roots[0].children.first { it.name == "blue" }.color)
    }

    @Test fun noMaterial() {
        val m = TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", file(quadObject("Q", emptyMap()))))
        assertEquals(2L, m.triangleCount); assertEquals(0, m.nodes[0].color)
    }

    @Test fun truncatedFileKeepsCompleteObjects() {
        val full = file(quadObject("A", emptyMap()), quadObject("B", emptyMap()))
        val cut = full.copyOf(full.size - 30) // chops into object B's face chunk
        val m = TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", cut))
        assertTrue(m.triangleCount >= 2)
        assertEquals("A", m.roots[0].name)
        assertTrue(m.notes.any { it.contains("truncated") })
    }

    @Test fun notA3ds() {
        try { TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", "solid x\n".toByteArray())); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("Not a 3DS")) }
    }

    @Test fun emptyFileIsReported() {
        try { TestUtil.load(ThreeDsImporter(), TestUtil.tempFile("a.3ds", file())); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("no geometry")) }
    }

    @Test fun assimpCubeWithTexture() {
        val m = TestUtil.load(ThreeDsImporter(), TestUtil.sample("3ds/cube_with_diffuse_texture.3DS"))
        assertEquals(12L, m.triangleCount)
        assertEquals(1, m.roots.size)
    }

    @Test fun assimpSamplesLoad() {
        for (name in listOf("3ds/fels.3ds", "3ds/mar_rifle.3ds", "3ds/jeep1.3ds", "3ds/cubes_with_alpha.3DS", "3ds/test1.3ds")) {
            val m = TestUtil.load(ThreeDsImporter(), TestUtil.sample(name))
            assertTrue(name, m.triangleCount > 0)
            assertFalse(name, m.bounds.isEmpty)
        }
    }

    @Test fun cameraOnlyFileHasNoGeometry() {
        try { TestUtil.load(ThreeDsImporter(), TestUtil.sample("3ds/CameraRollAnim.3ds")); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("no geometry")) }
    }
}
