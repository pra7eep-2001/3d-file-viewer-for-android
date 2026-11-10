package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test

/** Reads U3DM files produced by the native Open CASCADE converter (native/converter) from the sample STEP files. */
class U3dmImporterTest {
    @Test fun screwSingleSolid() {
        val m = TestUtil.load(U3dmImporter(), TestUtil.sample("u3dm/screw.u3dm"))
        assertEquals(1, m.meshNodes.size)
        assertEquals(814L, m.triangleCount)
        assertEquals(636L, m.vertexCount)
        assertEquals(219L, m.edgeSegmentCount)
        assertNotNull(m.meshNodes[0].mesh!!.normals)
        assertNotNull(m.meshNodes[0].mesh!!.edgePositions)
        assertEquals(ModelUnits.MILLIMETER, m.declaredUnits)
        assertEquals(20.0f, m.bounds.sizeY, 0.01f)
        assertEquals(42.295f, m.bounds.sizeZ, 0.01f)
    }

    @Test fun as1AssemblyTreeNamesAndColours() {
        val m = TestUtil.load(U3dmImporter(), TestUtil.sample("u3dm/as1-oc-214.u3dm"))
        assertEquals(28, m.nodes.size)
        assertEquals(1, m.roots.size)
        assertEquals("as1", m.roots[0].name)
        assertEquals(18, m.meshNodes.size)
        assertEquals(7512L, m.triangleCount)
        val bolt = m.nodes.first { it.name == "bolt_1" }
        assertEquals(0xFF0000FF.toInt(), bolt.color)
        assertEquals("nut-bolt-assembly_1", bolt.parent!!.name)
        assertEquals(3, bolt.depth)
        assertEquals(200f, m.bounds.sizeX, 0.01f)
        // hiding a sub-assembly hides its parts
        bolt.parent!!.visible = false
        assertFalse(bolt.effectivelyVisible)
    }

    @Test fun rejectsGarbage() {
        val f = TestUtil.tempFile("x.u3dm", "not a u3dm file at all".toByteArray())
        try { TestUtil.load(U3dmImporter(), f); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("U3DM")) }
    }
}
