package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class VrmlImporterTest {
    private val wrl = """
        #VRML V2.0 utf8
        # a comment
        PROTO Thing [ field SFColor c 1 0 0 ] { Shape { geometry Box {} } }
        WorldInfo { title "test" }
        DEF Root Transform {
          translation 0 0 10
          children [
            DEF QuadT Transform {
              translation 5 0 0
              rotation 0 0 1 1.5707963
              scale 2 2 2
              children Shape {
                appearance Appearance { material DEF Red Material { diffuseColor 1 0 0, transparency 0 } }
                geometry DEF QuadGeo IndexedFaceSet {
                  solid FALSE
                  coord DEF QuadCoord Coordinate { point [ 0 0 0, 1 0 0, 1 1 0, 0 1 0 ] }
                  coordIndex [ 0, 1, 2, 3, -1 ]
                  normal Normal { vector [ 0 0 1  0 0 1  0 0 1  0 0 1 ] }
                }
              }
            }
            Group {
              children [
                Shape {
                  appearance Appearance { material USE Red }
                  geometry IndexedFaceSet { coord USE QuadCoord coordIndex [ 0 1 2 -1 ] }
                }
                Shape { geometry IndexedLineSet { coord USE QuadCoord coordIndex [ 0 1 2 -1 2 3 -1 ] } }
                Unknown { weird [ 1 2 3 ] nested { a "b" } }
              ]
            }
            USE QuadT
            Shape { geometry Box { size 2 4 6 } }
            Shape { geometry Sphere { radius 3 } }
          ]
        }
        ROUTE Root.translation_changed TO QuadT.set_translation
    """.trimIndent()

    @Test fun sceneGraph() {
        val m = TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", wrl))
        assertEquals(1, m.roots.size)
        val root = m.roots[0]
        assertEquals("Root", root.name)
        assertEquals(5, root.children.size)
        val quad = root.children[0]
        assertEquals("QuadT", quad.name)
        assertEquals(0xffff0000.toInt(), quad.color)
        assertEquals(2, quad.mesh!!.triangleCount)
        assertNotNull(quad.mesh!!.normals)
        assertEquals(3f, quad.mesh!!.bounds.minX, 1e-4f)
        assertEquals(5f, quad.mesh!!.bounds.maxX, 1e-4f)
        assertEquals(2f, quad.mesh!!.bounds.maxY, 1e-4f)
        assertEquals(10f, quad.mesh!!.bounds.minZ, 1e-4f)
        val group = root.children[1]
        assertEquals(2, group.children.size)
        assertEquals(0xffff0000.toInt(), group.children[0].color)
        assertEquals(1, group.children[0].mesh!!.triangleCount)
        assertEquals(3, group.children[1].mesh!!.edgeSegmentCount)
        assertEquals(2, root.children[2].mesh!!.triangleCount) // USE QuadT
        assertEquals(12, root.children[3].mesh!!.triangleCount)
        assertEquals(6f, root.children[3].mesh!!.bounds.sizeZ, 1e-5f)
        assertEquals(6f, root.children[4].mesh!!.bounds.sizeX, 1e-3f)
        assertTrue(m.notes.any { it.contains("PROTO") })
    }

    @Test fun gzipped() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(wrl.toByteArray()) }
        val m = TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", bos.toByteArray()))
        assertEquals(5, m.roots[0].children.size)
    }

    @Test fun vrml1Rejected() {
        try { TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", "#VRML V1.0 ascii\nSeparator { Cube {} }")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("VRML 1.0")) }
    }

    @Test fun syntaxErrorReported() {
        try { TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", "#VRML V2.0 utf8\nShape { geometry Box { size 1 1 1 ")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("line")) }
    }

    @Test fun badIndex() {
        try { TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", "#VRML V2.0 utf8\nShape { geometry IndexedFaceSet { coord Coordinate { point [0 0 0 1 0 0 0 1 0] } coordIndex [0 1 5 -1] } }")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("5")) }
    }

    @Test fun extrusionAndElevationGrid() {
        val s = """
            #VRML V2.0 utf8
            Shape { geometry Extrusion { crossSection [ 1 1, 1 -1, -1 -1, -1 1, 1 1 ] spine [ 0 0 0, 0 2 0 ] } }
            Shape { geometry ElevationGrid { xDimension 3 zDimension 2 xSpacing 1 zSpacing 1 height [ 0 1 0 0 1 0 ] } }
        """.trimIndent()
        val m = TestUtil.load(VrmlImporter(), TestUtil.tempFile("t.wrl", s))
        assertEquals(2, m.roots.size)
        val ex = m.roots[0].mesh!!
        assertEquals(12, ex.triangleCount) // 4 sides * 2 + 2 caps * 2
        assertEquals(2f, ex.bounds.sizeY, 1e-5f)
        assertEquals(2f, ex.bounds.sizeX, 1e-5f)
        val eg = m.roots[1].mesh!!
        assertEquals(4, eg.triangleCount)
        assertEquals(1f, eg.bounds.maxY, 1e-6f)
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".wrl") }.sortedBy { it.name }) {
            val m = TestUtil.load(VrmlImporter(), f)
            println("${f.name}: nodes=${m.nodes.size} tris=${m.triangleCount} edges=${m.edgeSegmentCount} notes=${m.notes}")
            assertTrue(f.name, m.triangleCount > 0)
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(VrmlImporter(), TestUtil.sample("wrl/meshWithLines.wrl"))
        assertEquals(12L, m.triangleCount)
        assertEquals(12L, m.edgeSegmentCount)
    }
}
