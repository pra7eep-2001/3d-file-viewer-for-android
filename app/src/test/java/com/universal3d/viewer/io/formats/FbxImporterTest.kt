package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.ModelUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class FbxImporterTest {
    private val asciiQuad = """
        ; FBX 7.4.0 project file
        ; ----------------------------------------------------
        FBXHeaderExtension:  {
        	FBXHeaderVersion: 1003
        	FBXVersion: 7400
        	Creator: "hand written"
        }
        GlobalSettings:  {
        	Version: 1000
        	Properties70:  {
        		P: "UpAxis", "int", "Integer", "",2
        		P: "UpAxisSign", "int", "Integer", "",1
        		P: "UnitScaleFactor", "double", "Number", "",100
        	}
        }
        Objects:  {
        	Geometry: 100, "Geometry::Quad", "Mesh" {
        		Vertices: *12 {
        			a: 0,0,0,1,0,0,1,1,0,0,1,0
        		}
        		PolygonVertexIndex: *4 {
        			a: 0,1,2,-4
        		}
        		LayerElementNormal: 0 {
        			Version: 101
        			Name: ""
        			MappingInformationType: "ByPolygonVertex"
        			ReferenceInformationType: "Direct"
        			Normals: *12 {
        				a: 0,0,1,0,0,1,0,0,1,0,0,1
        			}
        		}
        		LayerElementMaterial: 0 {
        			MappingInformationType: "AllSame"
        			ReferenceInformationType: "IndexToDirect"
        			Materials: *1 {
        				a: 0
        			}
        		}
        	}
        	Model: 200, "Model::Quad", "Mesh" {
        		Version: 232
        		Properties70:  {
        			P: "Lcl Translation", "Lcl Translation", "", "A",10,0,0
        			P: "Lcl Scaling", "Lcl Scaling", "", "A",2,2,2
        		}
        		Shading: Y
        		Culling: "CullingOff"
        	}
        	Model: 300, "Model::Parent", "Null" {
        		Properties70:  {
        			P: "Lcl Rotation", "Lcl Rotation", "", "A",0,0,90
        		}
        	}
        	Material: 400, "Material::Red", "" {
        		Version: 102
        		ShadingModel: "phong"
        		Properties70:  {
        			P: "DiffuseColor", "Color", "", "A",1,0,0
        		}
        	}
        }
        Connections:  {
        	;Geometry::Quad, Model::Quad
        	C: "OO",100,200
        	C: "OO",200,300
        	C: "OO",300,0
        	C: "OO",400,200
        }
    """.trimIndent()

    @Test fun asciiHierarchyTransformsAndMaterial() {
        val m = TestUtil.load(FbxImporter(), TestUtil.tempFile("q.fbx", asciiQuad))
        assertEquals(2, m.nodes.size)
        assertEquals("Parent", m.roots[0].name)
        val quad = m.roots[0].children[0]
        assertEquals("Quad", quad.name)
        assertEquals(2L, m.triangleCount); assertEquals(4L, m.vertexCount)
        assertEquals(0xffff0000.toInt(), quad.color)
        // T(10,0,0) * S(2) then parent rotation 90 deg about Z: x in [-2,0], y in [10,12]
        assertEquals(-2f, m.bounds.minX, 1e-4f); assertEquals(0f, m.bounds.maxX, 1e-4f)
        assertEquals(10f, m.bounds.minY, 1e-4f); assertEquals(12f, m.bounds.maxY, 1e-4f)
        assertEquals(ModelUnits.METER, m.declaredUnits)
        assertNotNull(quad.mesh!!.normals)
        assertTrue(m.notes.any { it.contains("Up axis: Z") })
    }

    @Test fun asciiMultiMaterialByPolygon() {
        val src = """
            ; FBX 7.3.0 project file
            Objects:  {
            	Geometry: 100, "Geometry::Quad", "Mesh" {
            		Vertices: *12 {
            			a: 0,0,0,1,0,0,1,1,0,0,1,0
            		}
            		PolygonVertexIndex: *6 {
            			a: 0,1,-3,0,2,-4
            		}
            		LayerElementMaterial: 0 {
            			MappingInformationType: "ByPolygon"
            			ReferenceInformationType: "IndexToDirect"
            			Materials: *2 {
            				a: 0,1
            			}
            		}
            	}
            	Model: 200, "Model::Quad", "Mesh" {
            		Version: 232
            	}
            	Material: 400, "Material::Red", "" {
            		Properties70:  {
            			P: "DiffuseColor", "Color", "", "A",1,0,0
            		}
            	}
            	Material: 500, "Material::Blue", "" {
            		Properties70:  {
            			P: "DiffuseColor", "Color", "", "A",0,0,1
            		}
            	}
            }
            Connections:  {
            	C: "OO",100,200
            	C: "OO",200,0
            	C: "OO",400,200
            	C: "OO",500,200
            }
        """.trimIndent()
        val m = TestUtil.load(FbxImporter(), TestUtil.tempFile("q.fbx", src))
        val quad = m.roots[0]
        assertEquals("Quad", quad.name); assertNull(quad.mesh)
        assertEquals(2, quad.children.size)
        assertEquals(0xffff0000.toInt(), quad.children[0].color)
        assertEquals(0xff0000ff.toInt(), quad.children[1].color)
        assertEquals(2L, m.triangleCount)
        assertNull(quad.children[0].mesh!!.normals)
    }

    @Test fun fbx6AsciiBestEffort() {
        val src = """
            ; FBX 6.1.0 project file
            FBXHeaderExtension:  {
            	FBXHeaderVersion: 1003
            	FBXVersion: 6100
            }
            Objects:  {
            	Model: "Model::Tri", "Mesh" {
            		Version: 232
            		Properties60:  {
            			Property: "Lcl Translation", "Lcl Translation", "A+",0,0,5
            		}
            		Vertices: 0,0,0,1,0,0,0,1,0
            		PolygonVertexIndex: 0,1,-3
            		GeometryVersion: 124
            	}
            }
            Connections:  {
            	Connect: "OO", "Model::Tri", "Model::Scene"
            }
        """.trimIndent()
        val m = TestUtil.load(FbxImporter(), TestUtil.tempFile("six.fbx", src))
        assertEquals(1L, m.triangleCount)
        assertEquals("Tri", m.nodes[0].name)
        assertEquals(5f, m.bounds.minZ, 1e-5f)
        assertTrue(m.notes.any { it.contains("6.1.0") })
    }

    // ---- binary writer ------------------------------------------------------------------------------------

    private class BNode(val name: String, val props: List<Any> = emptyList(), val children: List<BNode> = emptyList())

    private fun prop(out: ByteArrayOutputStream, p: Any, compress: Boolean) {
        fun le(n: Int, block: ByteBuffer.() -> Unit) { val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); b.block(); out.write(b.array()) }
        when (p) {
            is Long -> { out.write('L'.code); le(8) { putLong(p) } }
            is Int -> { out.write('I'.code); le(4) { putInt(p) } }
            is Double -> { out.write('D'.code); le(8) { putDouble(p) } }
            is String -> { out.write('S'.code); val b = p.toByteArray(); le(4) { putInt(b.size) }; out.write(b) }
            is DoubleArray -> { out.write('d'.code); val raw = ByteBuffer.allocate(p.size * 8).order(ByteOrder.LITTLE_ENDIAN); for (v in p) raw.putDouble(v); array(out, p.size, raw.array(), compress) }
            is IntArray -> { out.write('i'.code); val raw = ByteBuffer.allocate(p.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (v in p) raw.putInt(v); array(out, p.size, raw.array(), compress) }
            else -> throw IllegalArgumentException()
        }
    }

    private fun array(out: ByteArrayOutputStream, n: Int, raw: ByteArray, compress: Boolean) {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        if (compress) {
            val d = Deflater(); d.setInput(raw); d.finish(); val buf = ByteArray(raw.size + 64); val len = d.deflate(buf); d.end()
            b.putInt(n); b.putInt(1); b.putInt(len); out.write(b.array()); out.write(buf, 0, len)
        } else { b.putInt(n); b.putInt(0); b.putInt(raw.size); out.write(b.array()); out.write(raw) }
    }

    private fun encode(node: BNode, start: Int, big: Boolean, compress: Boolean): ByteArray {
        val props = ByteArrayOutputStream(); for (p in node.props) prop(props, p, compress)
        val hdr = if (big) 25 else 13
        val nameB = node.name.toByteArray()
        var pos = start + hdr + nameB.size + props.size()
        val kids = ByteArrayOutputStream()
        for (c in node.children) { val b = encode(c, pos, big, compress); kids.write(b); pos += b.size }
        if (node.children.isNotEmpty()) { kids.write(ByteArray(hdr)); pos += hdr }
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(hdr).order(ByteOrder.LITTLE_ENDIAN)
        if (big) { h.putLong(pos.toLong()); h.putLong(node.props.size.toLong()); h.putLong(props.size().toLong()); h.put(nameB.size.toByte()) }
        else { h.putInt(pos); h.putInt(node.props.size); h.putInt(props.size()); h.put(nameB.size.toByte()) }
        out.write(h.array()); out.write(nameB); out.write(props.toByteArray()); out.write(kids.toByteArray())
        return out.toByteArray()
    }

    private fun binaryFile(version: Int, compress: Boolean): ByteArray {
        val big = version >= 7500
        val roots = listOf(
            BNode("FBXHeaderExtension", children = listOf(BNode("FBXVersion", listOf(version)))),
            BNode("GlobalSettings", children = listOf(BNode("Properties70", children = listOf(BNode("P", listOf("UnitScaleFactor", "double", "Number", "", 2.54)))))),
            BNode("Objects", children = listOf(
                BNode("Geometry", listOf(100L, "Cube\u0000\u0001Geometry", "Mesh"), listOf(
                    BNode("Vertices", listOf(doubleArrayOf(0.0,0.0,0.0, 1.0,0.0,0.0, 1.0,1.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0, 1.0,0.0,1.0, 1.0,1.0,1.0, 0.0,1.0,1.0))),
                    BNode("PolygonVertexIndex", listOf(intArrayOf(0,3,2,-2, 4,5,6,-8, 0,1,5,-5, 1,2,6,-6, 2,3,7,-7, 3,0,4,-8))),
                    BNode("LayerElementNormal", listOf(0), listOf(
                        BNode("MappingInformationType", listOf("ByVertice")), BNode("ReferenceInformationType", listOf("Direct")),
                        BNode("Normals", listOf(DoubleArray(24) { if (it % 3 == 2) 1.0 else 0.0 })))))),
                BNode("Model", listOf(200L, "Cube\u0000\u0001Model", "Mesh"), listOf(
                    BNode("Properties70", children = listOf(
                        BNode("P", listOf("Lcl Translation", "Lcl Translation", "", "A", 0.0, 0.0, 100.0)),
                        BNode("P", listOf("RotationOrder", "enum", "", "", 0)),
                        BNode("P", listOf("Lcl Rotation", "Lcl Rotation", "", "A", 90.0, 0.0, 0.0)))))),
                BNode("Material", listOf(300L, "Green\u0000\u0001Material", ""), listOf(
                    BNode("Properties70", children = listOf(BNode("P", listOf("DiffuseColor", "Color", "", "A", 0.0, 1.0, 0.0)))))))),
            BNode("Connections", children = listOf(
                BNode("C", listOf("OO", 100L, 200L)), BNode("C", listOf("OO", 300L, 200L)), BNode("C", listOf("OO", 200L, 0L)))))
        val out = ByteArrayOutputStream()
        out.write("Kaydara FBX Binary  \u0000".toByteArray(Charsets.ISO_8859_1)); out.write(0x1A); out.write(0x00)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array())
        var pos = 27
        for (r in roots) { val b = encode(r, pos, big, compress); out.write(b); pos += b.size }
        out.write(ByteArray(if (big) 25 else 13)) // top-level null record
        out.write(ByteArray(160)) // footer padding
        return out.toByteArray()
    }

    private fun checkBinary(m: com.universal3d.viewer.model.Model) {
        assertEquals(1, m.nodes.size)
        assertEquals("Cube", m.nodes[0].name)
        assertEquals(12L, m.triangleCount); assertEquals(8L, m.vertexCount)
        assertEquals(0xff00ff00.toInt(), m.nodes[0].color)
        assertNotNull(m.nodes[0].mesh!!.normals)
        assertEquals(ModelUnits.INCH, m.declaredUnits)
        // rotate 90 deg about X: y -> z, z -> -y ; then translate z by 100
        assertEquals(-1f, m.bounds.minY, 1e-4f); assertEquals(0f, m.bounds.maxY, 1e-4f)
        assertEquals(100f, m.bounds.minZ, 1e-4f); assertEquals(101f, m.bounds.maxZ, 1e-4f)
    }

    @Test fun binary7400Raw() { checkBinary(TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", binaryFile(7400, false)))) }
    @Test fun binary7400Compressed() { checkBinary(TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", binaryFile(7400, true)))) }
    @Test fun binary7500Compressed() { checkBinary(TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", binaryFile(7500, true)))) }

    @Test fun truncatedBinaryIsReported() {
        val full = binaryFile(7400, false)
        try { TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", full.copyOf(full.size / 2))); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("7.4.0")) }
    }

    @Test fun garbageIsRejected() {
        try { TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", "ply\nformat ascii 1.0\nelement vertex 0\nend_header\n")); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("Not an FBX")) }
        try { TestUtil.load(FbxImporter(), TestUtil.tempFile("b.fbx", "; FBX 7.4.0 project file\nObjects: {\n Geometry: 1, \"Geometry::x\", \"Mesh\" {\n")); fail() } catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("7.4.0")) }
    }

    @Test fun noGeometryIsReported() {
        val src = "; FBX 7.4.0 project file\nObjects:  {\n\tModel: 1, \"Model::Empty\", \"Null\" {\n\t}\n}\nConnections:  {\n\tC: \"OO\",1,0\n}\n"
        try { TestUtil.load(FbxImporter(), TestUtil.tempFile("e.fbx", src)); fail() } catch (e: ImportException) { assertTrue(e.message!!.contains("no mesh geometry")) }
    }

    @Test fun assimpBoxBinary() {
        val m = TestUtil.load(FbxImporter(), TestUtil.sample("fbx/box.fbx"))
        assertEquals(12L, m.triangleCount)
        assertEquals(ModelUnits.METER, m.declaredUnits) // UnitScaleFactor 100
        assertTrue(m.notes.any { it.contains("Binary FBX 7.4.0") })
    }

    @Test fun assimpPhongCubeBinary() {
        val m = TestUtil.load(FbxImporter(), TestUtil.sample("fbx/phong_cube.fbx"))
        assertEquals(12L, m.triangleCount)
        assertNotEquals(0, m.meshNodes[0].color)
    }

    @Test fun assimpCubesWithNamesAscii75() {
        val m = TestUtil.load(FbxImporter(), TestUtil.sample("fbx/cubes_with_names.fbx"))
        assertEquals(4, m.meshNodes.size)
        assertEquals(420L, m.triangleCount)
        assertEquals(setOf("Cube2", "Cube3", "\u041a\u0443\u04311"), m.meshNodes.map { it.name }.toSet()) // Cyrillic names survive the UTF-8 parse
        assertEquals(2, m.roots.size) // two parent cubes each with a child cube
        assertEquals(ModelUnits.CENTIMETER, m.declaredUnits)
        assertTrue(m.notes.any { it.contains("ASCII FBX 7.5.0") })
    }

    @Test fun assimpSamplesLoad() {
        for (name in listOf("fbx/global_settings.fbx", "fbx/spider.fbx", "fbx/maxPbrMaterial_metalRough.fbx", "fbx/cubes_nonames.fbx")) {
            val m = TestUtil.load(FbxImporter(), TestUtil.sample(name))
            assertTrue(name, m.triangleCount > 0)
            assertFalse(name, m.bounds.isEmpty)
        }
    }
}
