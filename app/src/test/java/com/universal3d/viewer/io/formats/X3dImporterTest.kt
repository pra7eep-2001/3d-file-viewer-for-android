package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class X3dImporterTest {
    private val x3d = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE X3D PUBLIC "ISO//Web3D//DTD X3D 3.2//EN" "http://www.web3d.org/specifications/x3d-3.2.dtd">
        <X3D profile='Immersive' version='3.2'>
          <head><meta name='title' content='test'/></head>
          <Scene>
            <Transform DEF='Root' translation='0 0 10'>
              <Transform DEF='QuadT' translation='5 0 0' rotation='0 0 1 1.5707963' scale='2 2 2'>
                <Shape DEF='Quad'>
                  <Appearance><Material DEF='Red' diffuseColor='1 0 0'/></Appearance>
                  <IndexedFaceSet DEF='QuadGeo' coordIndex='0 1 2 3 -1' solid='false'>
                    <Coordinate DEF='QuadCoord' point='0 0 0, 1 0 0, 1 1 0, 0 1 0'/>
                    <Normal vector='0 0 1 0 0 1 0 0 1 0 0 1'/>
                  </IndexedFaceSet>
                </Shape>
              </Transform>
              <Group>
                <Shape>
                  <Appearance><Material USE='Red'/></Appearance>
                  <IndexedTriangleSet index='0 1 2'>
                    <Coordinate USE='QuadCoord'/>
                  </IndexedTriangleSet>
                </Shape>
                <Shape>
                  <IndexedLineSet coordIndex='0 1 2 -1 2 3 -1'>
                    <Coordinate USE='QuadCoord'/>
                  </IndexedLineSet>
                </Shape>
              </Group>
              <Transform USE='QuadT'/>
              <Shape><Box size='2 4 6'/></Shape>
              <Shape><Sphere radius='3'/></Shape>
            </Transform>
          </Scene>
        </X3D>
    """.trimIndent()

    @Test fun sceneGraph() {
        val m = TestUtil.load(X3dImporter(), TestUtil.tempFile("t.x3d", x3d))
        assertEquals(1, m.roots.size)
        val root = m.roots[0]
        assertEquals("Root", root.name)
        assertEquals(5, root.children.size)
        val quad = root.children[0]
        assertEquals("QuadT", quad.name)   // Transform -> single Shape collapsed, keeps the transform's DEF
        assertEquals(0xffff0000.toInt(), quad.color)
        assertEquals(2, quad.mesh!!.triangleCount)
        assertNotNull(quad.mesh!!.normals)
        // (1,1,0) rotated 90 deg about Z and scaled 2 -> (-2, 2), translated by (5,0,10)
        assertEquals(3f, quad.mesh!!.bounds.minX, 1e-4f)
        assertEquals(5f, quad.mesh!!.bounds.maxX, 1e-4f)
        assertEquals(2f, quad.mesh!!.bounds.maxY, 1e-4f)
        assertEquals(10f, quad.mesh!!.bounds.minZ, 1e-4f)
        val group = root.children[1]
        assertEquals(2, group.children.size)
        assertEquals(0xffff0000.toInt(), group.children[0].color) // USE'd material
        assertEquals(1, group.children[0].mesh!!.triangleCount)
        assertEquals(3, group.children[1].mesh!!.edgeSegmentCount)
        // USE'd transform duplicates geometry
        assertEquals(2, root.children[2].mesh!!.triangleCount)
        assertEquals(12, root.children[3].mesh!!.triangleCount)
        assertEquals(6f, root.children[3].mesh!!.bounds.sizeZ, 1e-5f)
        assertEquals(6f, root.children[4].mesh!!.bounds.sizeX, 1e-3f)
    }

    @Test fun ccwFalseFlipsWinding() {
        val a = "<X3D><Scene><Shape><IndexedFaceSet coordIndex='0 1 2 -1'><Coordinate point='0 0 0 1 0 0 0 1 0'/></IndexedFaceSet></Shape></Scene></X3D>"
        val b = a.replace("coordIndex", "ccw='false' coordIndex")
        val ma = TestUtil.load(X3dImporter(), TestUtil.tempFile("a.x3d", a)).roots[0].mesh!!
        val mb = TestUtil.load(X3dImporter(), TestUtil.tempFile("b.x3d", b)).roots[0].mesh!!
        fun zNormal(m: com.universal3d.viewer.model.Mesh): Float {
            val p = m.positions; val i = m.indices
            val ax = p[i[1] * 3] - p[i[0] * 3]; val ay = p[i[1] * 3 + 1] - p[i[0] * 3 + 1]
            val bx = p[i[2] * 3] - p[i[0] * 3]; val by = p[i[2] * 3 + 1] - p[i[0] * 3 + 1]
            return ax * by - ay * bx
        }
        assertTrue(zNormal(ma) > 0f)
        assertTrue(zNormal(mb) < 0f)
    }

    @Test fun badIndex() {
        val a = "<X3D><Scene><Shape><IndexedFaceSet coordIndex='0 1 7 -1'><Coordinate point='0 0 0 1 0 0 0 1 0'/></IndexedFaceSet></Shape></Scene></X3D>"
        try { TestUtil.load(X3dImporter(), TestUtil.tempFile("a.x3d", a)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("7")) }
    }

    @Test fun missingUse() {
        val a = "<X3D><Scene><Shape><IndexedFaceSet coordIndex='0 1 2 -1'><Coordinate USE='nope'/></IndexedFaceSet></Shape></Scene></X3D>"
        try { TestUtil.load(X3dImporter(), TestUtil.tempFile("a.x3d", a)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("nope")) }
    }

    @Test fun noGeometry() {
        try { TestUtil.load(X3dImporter(), TestUtil.tempFile("a.x3d", "<X3D><Scene><Viewpoint/></Scene></X3D>")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("no geometry")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".x3d") }.sortedBy { it.name }) {
            val m = TestUtil.load(X3dImporter(), f)
            println("${f.name}: nodes=${m.nodes.size} tris=${m.triangleCount} edges=${m.edgeSegmentCount} notes=${m.notes}")
            assertTrue(f.name, m.triangleCount > 0)
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(X3dImporter(), TestUtil.sample("x3d/HelloX3dTrademark.x3d"))
        assertTrue(m.triangleCount > 100) // the sphere
        assertTrue(m.notes.any { it.contains("Text") })
    }
}
