package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DaeImporterTest {
    private val dae = """
        <?xml version="1.0" encoding="utf-8"?>
        <COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
          <asset><unit name="centimeter" meter="0.01"/><up_axis>Z_UP</up_axis></asset>
          <library_effects>
            <effect id="red-fx"><profile_COMMON><technique sid="common"><phong>
              <diffuse><color>1 0 0 1</color></diffuse>
            </phong></technique></profile_COMMON></effect>
          </library_effects>
          <library_materials>
            <material id="red" name="Red"><instance_effect url="#red-fx"/></material>
          </library_materials>
          <library_geometries>
            <geometry id="quad-geo" name="Quad">
              <mesh>
                <source id="quad-pos"><float_array id="quad-pos-array" count="12">0 0 0 1 0 0 1 1 0 0 1 0</float_array>
                  <technique_common><accessor source="#quad-pos-array" count="4" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
                <source id="quad-nrm"><float_array id="quad-nrm-array" count="3">0 0 1</float_array>
                  <technique_common><accessor source="#quad-nrm-array" count="1" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
                <vertices id="quad-vtx"><input semantic="POSITION" source="#quad-pos"/></vertices>
                <polylist material="mat-sym" count="1">
                  <input semantic="VERTEX" source="#quad-vtx" offset="0"/>
                  <input semantic="NORMAL" source="#quad-nrm" offset="1"/>
                  <vcount>4</vcount>
                  <p>0 0 1 0 2 0 3 0</p>
                </polylist>
              </mesh>
            </geometry>
            <geometry id="tri-geo" name="Tri">
              <mesh>
                <source id="tri-pos"><float_array id="tri-pos-array" count="9">0 0 0 1 0 0 0 1 0</float_array>
                  <technique_common><accessor source="#tri-pos-array" count="3" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
                <vertices id="tri-vtx"><input semantic="POSITION" source="#tri-pos"/></vertices>
                <triangles count="1"><input semantic="VERTEX" source="#tri-vtx" offset="0"/><p>0 1 2</p></triangles>
              </mesh>
            </geometry>
          </library_geometries>
          <library_nodes>
            <node id="shared" name="Shared"><instance_geometry url="#tri-geo"/></node>
          </library_nodes>
          <library_visual_scenes>
            <visual_scene id="scene" name="Scene">
              <node id="root" name="Assembly">
                <translate>0 0 5</translate>
                <node id="n1" name="QuadNode">
                  <matrix>1 0 0 10  0 1 0 0  0 0 1 0  0 0 0 1</matrix>
                  <instance_geometry url="#quad-geo">
                    <bind_material><technique_common><instance_material symbol="mat-sym" target="#red"/></technique_common></bind_material>
                  </instance_geometry>
                </node>
                <node id="n2" name="TriNode">
                  <rotate>0 0 1 90</rotate>
                  <scale>2 2 2</scale>
                  <instance_node url="#shared"/>
                </node>
              </node>
            </visual_scene>
          </library_visual_scenes>
          <scene><instance_visual_scene url="#scene"/></scene>
        </COLLADA>
    """.trimIndent()

    @Test fun sceneHierarchyTransformsAndMaterials() {
        val m = TestUtil.load(DaeImporter(), TestUtil.tempFile("t.dae", dae))
        assertEquals(ModelUnits.CENTIMETER, m.declaredUnits)
        assertTrue(m.notes.any { it.contains("Z_UP") })
        assertEquals(1, m.roots.size)
        assertEquals("Assembly", m.roots[0].name)
        assertEquals(2, m.roots[0].children.size)
        val quad = m.roots[0].children[0]
        assertEquals("QuadNode", quad.name)
        assertEquals(0xffff0000.toInt(), quad.color)
        assertEquals(2, quad.mesh!!.triangleCount)
        assertNotNull(quad.mesh!!.normals)
        assertEquals(10f, quad.mesh!!.bounds.minX, 1e-5f)
        assertEquals(5f, quad.mesh!!.bounds.minZ, 1e-5f)
        val tri = m.roots[0].children[1]
        assertEquals("TriNode", tri.name)
        assertEquals(1, tri.mesh!!.triangleCount)
        // rotated 90 about Z and scaled by 2: x in [-2, 0], y in [0, 2]
        assertEquals(-2f, tri.mesh!!.bounds.minX, 1e-5f)
        assertEquals(2f, tri.mesh!!.bounds.maxY, 1e-5f)
        assertEquals(3L, m.triangleCount)
    }

    @Test fun geometriesWithoutScene() {
        val noScene = dae.substringBefore("<library_nodes>") + "</COLLADA>"
        val m = TestUtil.load(DaeImporter(), TestUtil.tempFile("t.dae", noScene))
        assertEquals(2, m.roots.size)
        assertEquals("Quad", m.roots[0].name)
        assertEquals(3L, m.triangleCount)
    }

    @Test fun badIndex() {
        try { TestUtil.load(DaeImporter(), TestUtil.tempFile("t.dae", dae.replace("<p>0 1 2</p>", "<p>0 1 9</p>"))); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("9")) }
    }

    @Test fun notCollada() {
        try { TestUtil.load(DaeImporter(), TestUtil.tempFile("t.dae", "<X3D></X3D>")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("COLLADA")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        val files = dir.listFiles()!!.filter { it.name.lowercase().endsWith(".dae") }.sortedBy { it.name }
        for (f in files) {
            try {
                val m = TestUtil.load(DaeImporter(), f)
                println("${f.name}: nodes=${m.nodes.size} tris=${m.triangleCount} edges=${m.edgeSegmentCount} units=${m.declaredUnits} notes=${m.notes}")
                assertTrue(f.name, m.triangleCount > 0 || m.edgeSegmentCount > 0)
            } catch (e: ImportException) {
                println("${f.name}: ImportException ${e.message}")
                // files that legitimately have no geometry (cameras/lights only) are fine
                assertTrue(f.name + ": " + e.message, e.message!!.contains("no geometry"))
            }
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(DaeImporter(), TestUtil.sample("dae/cube_triangulate.dae"))
        assertEquals(ModelUnits.CENTIMETER, m.declaredUnits)
        assertEquals(12L, m.triangleCount)
        assertEquals(1, m.roots.size)
    }
}
