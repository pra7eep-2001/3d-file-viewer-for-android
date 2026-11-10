package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UsdaImporterTest {
    private val usda = """
        #usda 1.0
        (
            doc = "test layer"
            defaultPrim = "World"
            metersPerUnit = 0.01
            upAxis = "Z"
        )

        def Xform "World" (
            kind = "assembly"
        )
        {
            float3 xformOp:translate = (0, 0, 10)
            uniform token[] xformOpOrder = ["xformOp:translate"]

            def Scope "Looks"
            {
                def Material "Red"
                {
                    token outputs:surface.connect = </World/Looks/Red/Shader.outputs:surface>
                    def Shader "Shader"
                    {
                        uniform token info:id = "UsdPreviewSurface"
                        color3f inputs:diffuseColor = (1, 0, 0)
                        float inputs:roughness = 0.5
                        token outputs:surface
                    }
                }
            }

            def Xform "QuadT"
            {
                double3 xformOp:translate = (5, 0, 0)
                float3 xformOp:rotateXYZ = (0, 0, 90)
                float3 xformOp:scale = (2, 2, 2)
                uniform token[] xformOpOrder = ["xformOp:translate", "xformOp:rotateXYZ", "xformOp:scale"]

                def Mesh "Quad" (
                    prepend apiSchemas = ["MaterialBindingAPI"]
                )
                {
                    float3[] extent = [(0, 0, 0), (1, 1, 0)]
                    int[] faceVertexCounts = [4]
                    int[] faceVertexIndices = [0, 1, 2, 3]
                    rel material:binding = </World/Looks/Red>
                    point3f[] points = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)]
                    normal3f[] normals = [(0, 0, 1), (0, 0, 1), (0, 0, 1), (0, 0, 1)] (
                        interpolation = "vertex"
                    )
                    texCoord2f[] primvars:st = [(0, 0), (1, 0), (1, 1), (0, 1)] (
                        interpolation = "faceVarying"
                    )
                    uniform token subdivisionScheme = "none"
                    double3.timeSamples = { 0: (1, 2, 3), 1: (4, 5, 6) }
                }
            }

            def Mesh "Tri"
            {
                int[] faceVertexCounts = [3]
                int[] faceVertexIndices = [0, 1, 2]
                point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
                color3f[] primvars:displayColor = [(0, 0, 1)]
                uniform token orientation = "leftHanded"
                matrix4d xformOp:transform = ( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (100, 0, 0, 1) )
                uniform token[] xformOpOrder = ["xformOp:transform"]
                variantSet "shape" = {
                    "big" { double size = 2 }
                    "small" { double size = 1 }
                }
            }

            over "Ghost"
            {
                def Mesh "Hidden" (active = false)
                {
                    int[] faceVertexCounts = [3]
                    int[] faceVertexIndices = [0, 1, 2]
                    point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
                }
            }

            def Sphere "Ball" (
                references = @./other.usda@</Thing>
            )
            {
                double radius = 2
                color3f[] primvars:displayColor = [(0, 1, 0)]
            }
        }
    """.trimIndent()

    @Test fun hierarchyTransformsColours() {
        val m = TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usda", usda))
        assertEquals(ModelUnits.CENTIMETER, m.declaredUnits)
        assertTrue(m.notes.any { it.contains("upAxis = Z") })
        assertTrue(m.notes.any { it.contains("References") })
        assertTrue(m.notes.any { it.contains("Variant") })
        assertEquals(1, m.roots.size)
        val world = m.roots[0]
        assertEquals("World", world.name)
        assertEquals(3, world.children.size) // QuadT, Tri, Ball (Looks has no geometry, Ghost is inactive)
        val quadT = world.children[0]
        assertEquals("QuadT", quadT.name)
        assertEquals(1, quadT.children.size)
        val quad = quadT.children[0]
        assertEquals("Quad", quad.name)
        assertEquals(0xffff0000.toInt(), quad.color) // via material binding
        assertEquals(2, quad.mesh!!.triangleCount)
        assertNotNull(quad.mesh!!.normals)
        // (1,1,0) scaled 2 -> (2,2,0), rotated 90 about Z -> (-2,2,0), translated (5,0,0)+(0,0,10)
        assertEquals(3f, quad.mesh!!.bounds.minX, 1e-4f)
        assertEquals(5f, quad.mesh!!.bounds.maxX, 1e-4f)
        assertEquals(2f, quad.mesh!!.bounds.maxY, 1e-4f)
        assertEquals(10f, quad.mesh!!.bounds.minZ, 1e-4f)
        val tri = world.children[1]
        assertEquals("Tri", tri.name)
        assertEquals(0xff0000ff.toInt(), tri.color)
        assertEquals(100f, tri.mesh!!.bounds.minX, 1e-4f)
        assertEquals(10f, tri.mesh!!.bounds.minZ, 1e-4f)
        // leftHanded: winding reversed -> triangle normal points to -Z
        run {
            val p = tri.mesh!!.positions; val i = tri.mesh!!.indices
            val ax = p[i[1] * 3] - p[i[0] * 3]; val ay = p[i[1] * 3 + 1] - p[i[0] * 3 + 1]
            val bx = p[i[2] * 3] - p[i[0] * 3]; val by = p[i[2] * 3 + 1] - p[i[0] * 3 + 1]
            assertTrue(ax * by - ay * bx < 0f)
        }
        val ball = world.children[2]
        assertEquals(0xff00ff00.toInt(), ball.color)
        assertEquals(4f, ball.mesh!!.bounds.sizeX, 1e-3f)
    }

    @Test fun usdExtensionWithAsciiContent() {
        val m = TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usd", usda))
        assertEquals("World", m.roots[0].name)
        assertEquals(3, m.roots[0].children.size)
    }

    @Test fun binaryRejected() {
        try { TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usd", "PXR-USDC\u0000\u0000\u0000\u0000garbage")); fail() }
        catch (e: ImportException) { assertEquals("Binary USD (.usdc) is not supported; export as .usda", e.message) }
    }

    @Test fun badIndex() {
        val s = "#usda 1.0\ndef Mesh \"m\" {\n int[] faceVertexCounts = [3]\n int[] faceVertexIndices = [0, 1, 7]\n point3f[] points = [(0,0,0),(1,0,0),(0,1,0)]\n}\n"
        try { TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usda", s)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("7") && e.message!!.contains("/m")) }
    }

    @Test fun syntaxError() {
        val s = "#usda 1.0\ndef Mesh \"m\" {\n int[] faceVertexCounts = [3\n"
        try { TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usda", s)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("line")) }
    }

    @Test fun noGeometry() {
        try { TestUtil.load(UsdaImporter(), TestUtil.tempFile("t.usda", "#usda 1.0\ndef Xform \"x\" { }\n")); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("no geometry")) }
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".usda") }.sortedBy { it.name }) {
            try {
                val m = TestUtil.load(UsdaImporter(), f)
                println("${f.name}: nodes=${m.nodes.size} tris=${m.triangleCount} units=${m.declaredUnits} notes=${m.notes}")
                assertTrue(f.name, m.triangleCount > 0)
            } catch (e: ImportException) {
                println("${f.name}: ImportException ${e.message}")
                assertTrue(f.name + ": " + e.message, e.message!!.contains("no geometry"))
            }
        }
    }

    @Test fun bundledSample() {
        val m = TestUtil.load(UsdaImporter(), TestUtil.sample("usda/simpleShading.usda"))
        assertEquals(2L, m.triangleCount)
        assertEquals("TexModel", m.roots[0].name)
        assertEquals("card", m.roots[0].children[0].name)
    }

    @Test fun largeMeshThroughput() {
        val nx = 101; val ny = 1001
        val sb = StringBuilder(16 shl 20)
        sb.append("#usda 1.0\ndef Mesh \"grid\" {\n int[] faceVertexCounts = [")
        val quads = (nx - 1) * (ny - 1)
        for (q in 0 until quads) { if (q > 0) sb.append(", "); sb.append(4) }
        sb.append("]\n int[] faceVertexIndices = [")
        var first = true
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) { val a = j * nx + i; if (!first) sb.append(", "); first = false; sb.append(a).append(", ").append(a + 1).append(", ").append(a + nx + 1).append(", ").append(a + nx) }
        sb.append("]\n point3f[] points = [")
        for (j in 0 until ny) for (i in 0 until nx) { if (i > 0 || j > 0) sb.append(", "); sb.append('(').append(i).append(", ").append(j).append(", 0.5)") }
        sb.append("]\n}\n")
        val f = TestUtil.tempFile("big.usda", sb.toString())
        val t0 = System.nanoTime()
        val m = TestUtil.load(UsdaImporter(), f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(quads * 2L, m.triangleCount)
        assertTrue("took $ms ms", ms < 15_000)
    }
}
