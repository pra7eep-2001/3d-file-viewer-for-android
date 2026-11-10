package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfImporterTest {
    /** Binary buffer: 4 positions (float), 4 normals (float), 6 indices (ushort), 4 short quantized positions. */
    private fun quadBuffer(): ByteArray {
        val bb = ByteBuffer.allocate(48 + 48 + 12 + 4 * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (v in floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f)) bb.putFloat(v)
        for (i in 0 until 4) { bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(1f) }
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) bb.putShort(i.toShort())
        // quantized positions (short, normalized): (0,0,0) (1,0,0) (1,1,0) (0,1,0) with stride 8 (padding)
        for (v in shortArrayOf(0,0,0, 32767,0,0, 32767,32767,0, 0,32767,0).toList().chunked(3)) { for (s in v) bb.putShort(s); bb.putShort(0) }
        return bb.array()
    }

    private fun quadJson(bufferUri: String?, extraNodes: String = "", sceneNodes: String = "[0]", nodes: String? = null, extra: String = ""): String {
        val uri = if (bufferUri != null) "\"uri\": \"$bufferUri\"," else ""
        val nodeList = nodes ?: """[{"name": "Quad", "mesh": 0, "translation": [10, 0, 0]}$extraNodes]"""
        return """
        {
          "asset": {"version": "2.0"},
          "scene": 0,
          "scenes": [{"nodes": $sceneNodes}],
          "nodes": $nodeList,
          "meshes": [{"name": "QuadMesh", "primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2, "material": 0}]}],
          "materials": [{"name": "Red", "pbrMetallicRoughness": {"baseColorFactor": [1, 0, 0, 0.5]}}],
          "buffers": [{$uri "byteLength": 140}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 48},
            {"buffer": 0, "byteOffset": 48, "byteLength": 48},
            {"buffer": 0, "byteOffset": 96, "byteLength": 12},
            {"buffer": 0, "byteOffset": 108, "byteLength": 32, "byteStride": 8}
          ],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5123, "count": 6, "type": "SCALAR"},
            {"bufferView": 3, "componentType": 5122, "normalized": true, "count": 4, "type": "VEC3"}
          ]
          $extra
        }
        """.trimIndent()
    }

    private fun glb(json: String, bin: ByteArray): ByteArray {
        val j = json.toByteArray(); val jp = (4 - j.size % 4) % 4; val bp = (4 - bin.size % 4) % 4
        val total = 12 + 8 + j.size + jp + 8 + bin.size + bp
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67); bb.putInt(2); bb.putInt(total)
        bb.putInt(j.size + jp); bb.putInt(0x4E4F534A); bb.put(j); repeat(jp) { bb.put(0x20) }
        bb.putInt(bin.size + bp); bb.putInt(0x004E4942); bb.put(bin); repeat(bp) { bb.put(0) }
        return bb.array()
    }

    @Test fun glbWithTrs() {
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("q.glb", glb(quadJson(null), quadBuffer())))
        assertEquals(1, m.nodes.size)
        assertEquals("Quad", m.nodes[0].name)
        assertEquals(2L, m.triangleCount); assertEquals(4L, m.vertexCount)
        assertEquals(10f, m.bounds.minX, 1e-6f); assertEquals(11f, m.bounds.maxX, 1e-6f)
        assertEquals(0x80ff0000.toInt(), m.nodes[0].color)
        assertEquals(ModelUnits.METER, m.declaredUnits)
        assertNotNull(m.nodes[0].mesh!!.normals)
    }

    @Test fun gltfWithDataUri() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(quadBuffer())
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("q.gltf", quadJson(uri)))
        assertEquals(2L, m.triangleCount)
    }

    @Test fun gltfWithExternalBin() {
        val f = TestUtil.tempFile("q.gltf", quadJson("my%20data.bin"))
        File(f.parentFile, "my data.bin").writeBytes(quadBuffer())
        assertEquals(2L, TestUtil.load(GltfImporter(), f).triangleCount)
    }

    @Test fun missingExternalBinIsReported() {
        val f = TestUtil.tempFile("q.gltf", quadJson("missing.bin"))
        try { TestUtil.load(GltfImporter(), f); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("missing.bin")) }
    }

    @Test fun hierarchyMatrixAndInstancing() {
        // node 0 = Quad at +10x; node 1 = group with matrix (scale 2) containing node 2 = second instance of mesh 0 with quaternion 90 deg about Z
        val nodes = """[
            {"name": "Quad", "mesh": 0, "translation": [10, 0, 0]},
            {"name": "Group", "matrix": [2,0,0,0, 0,2,0,0, 0,0,2,0, 0,0,0,1], "children": [2]},
            {"mesh": 0, "rotation": [0, 0, 0.7071068, 0.7071068]}
        ]"""
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("h.gltf", quadJson("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(quadBuffer()), nodes = nodes, sceneNodes = "[0, 1]")))
        assertEquals(2, m.roots.size)
        assertEquals("Group", m.roots[1].name)
        assertEquals("QuadMesh", m.roots[1].children[0].name) // unnamed node falls back to the mesh name
        assertEquals(4L, m.triangleCount)
        val inst = m.roots[1].children[0].mesh!!.bounds
        assertEquals(-2f, inst.minX, 1e-5f); assertEquals(0f, inst.maxX, 1e-5f); assertEquals(2f, inst.maxY, 1e-5f)
    }

    @Test fun quantizedPositions() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(quadBuffer())
        val json = quadJson(uri).replace("\"POSITION\": 0", "\"POSITION\": 3")
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("q.gltf", json))
        assertEquals(1f, m.bounds.sizeX, 1e-4f); assertEquals(1f, m.bounds.sizeY, 1e-4f)
    }

    @Test fun stripFanAndLines() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(quadBuffer())
        // strip over 4 vertices without indices -> 2 triangles; fan same; line strip -> 3 edges; triangles without indices -> 1 triangle (4 vertices: last dropped)
        val meshes = """"meshes": [{"primitives": [
            {"attributes": {"POSITION": 0}, "mode": 5},
            {"attributes": {"POSITION": 0}, "mode": 6},
            {"attributes": {"POSITION": 0}, "mode": 3},
            {"attributes": {"POSITION": 0}, "mode": 4},
            {"attributes": {"POSITION": 0}, "mode": 0}
        ]}]"""
        val json = Regex("\"meshes\": \\[.*?\\]\\}\\]").replace(quadJson(uri), Regex.escapeReplacement(meshes))
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("q.gltf", json))
        assertEquals(1, m.roots.size)
        assertEquals(4, m.roots[0].children.size)
        assertEquals(5L, m.triangleCount)
        assertEquals(3L, m.edgeSegmentCount)
        assertTrue(m.notes.any { it.contains("Point") })
    }

    @Test fun sparseAccessor() {
        // Sparse: base from bufferView 0, replace vertex 2 with (5,5,0); indices (ubyte) and values stored in appended views.
        val base = quadBuffer()
        val bb = ByteBuffer.allocate(base.size + 4 + 12).order(ByteOrder.LITTLE_ENDIAN); bb.put(base); bb.put(2); bb.put(0); bb.put(0); bb.put(0); bb.putFloat(5f); bb.putFloat(5f); bb.putFloat(0f)
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bb.array())
        var json = quadJson(uri).replace("\"byteLength\": 140}", "\"byteLength\": 156}")
        json = json.replace("{\"buffer\": 0, \"byteOffset\": 108, \"byteLength\": 32, \"byteStride\": 8}", "{\"buffer\": 0, \"byteOffset\": 108, \"byteLength\": 32, \"byteStride\": 8}, {\"buffer\": 0, \"byteOffset\": 140, \"byteLength\": 4}, {\"buffer\": 0, \"byteOffset\": 144, \"byteLength\": 12}")
        json = json.replace("{\"bufferView\": 0, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC3\"}",
            "{\"bufferView\": 0, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC3\", \"sparse\": {\"count\": 1, \"indices\": {\"bufferView\": 4, \"componentType\": 5121}, \"values\": {\"bufferView\": 5}}}")
        val m = TestUtil.load(GltfImporter(), TestUtil.tempFile("s.gltf", json))
        assertEquals(15f, m.bounds.maxX, 1e-5f)
    }

    @Test fun dracoIsRejected() {
        try { TestUtil.load(GltfImporter(), TestUtil.sample("gltf/draco_Box.gltf")); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("Draco")) }
    }

    @Test fun truncatedGlbIsReported() {
        val full = glb(quadJson(null), quadBuffer())
        try { TestUtil.load(GltfImporter(), TestUtil.tempFile("q.glb", full.copyOf(full.size - 40))); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("truncated") || e.message!!.contains("beyond")) }
    }

    @Test fun badIndexIsReported() {
        val buf = quadBuffer(); val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN); bb.putShort(96, 99)
        try { TestUtil.load(GltfImporter(), TestUtil.tempFile("q.glb", glb(quadJson(null), buf))); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("vertex 99")) }
    }

    @Test fun garbageIsReported() {
        try { TestUtil.load(GltfImporter(), TestUtil.tempFile("q.gltf", "this is not json at all, not even close to it")); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("Not a glTF")) }
        try { TestUtil.load(GltfImporter(), TestUtil.tempFile("q.gltf", "{\"asset\": {\"version\": \"2.0\"}, \"meshes\": [")); fail() } catch (e: ImportException) { assertNotNull(e.message) }
    }

    @Test fun khronosBoxGlb() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/Box.glb"))
        assertEquals(12L, m.triangleCount); assertEquals(24L, m.vertexCount)
        assertEquals(1f, m.bounds.sizeX, 1e-5f)
        assertEquals(0xffcc0000.toInt(), m.meshNodes[0].color) // baseColorFactor [0.8, 0, 0, 1]
    }

    @Test fun khronosBoxGltfExternalBin() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/Box.gltf"))
        assertEquals(12L, m.triangleCount)
    }

    @Test fun khronosDuck() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/Duck.glb"))
        assertEquals(4212L, m.triangleCount); assertEquals(2399L, m.vertexCount)
        assertTrue(m.notes.any { it.contains("Textures") })
    }

    @Test fun khronosBoxAnimated() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/BoxAnimated.glb"))
        assertEquals(2, m.meshNodes.size)
        assertTrue(m.notes.any { it.contains("Animations") })
    }

    @Test fun khronosBoxVertexColors() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/BoxVertexColors.gltf"))
        assertEquals(12L, m.triangleCount)
        assertNotEquals(0, m.meshNodes[0].color)
    }

    @Test fun khronosTriangleWithoutIndices() {
        val m = TestUtil.load(GltfImporter(), TestUtil.sample("gltf/TriangleWithoutIndices.gltf"))
        assertEquals(1L, m.triangleCount); assertEquals(3L, m.vertexCount)
    }
}
