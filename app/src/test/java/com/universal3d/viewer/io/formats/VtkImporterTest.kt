package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater

class VtkImporterTest {
    private val cubePoints = "0 0 0  1 0 0  1 1 0  0 1 0  0 0 1  1 0 1  1 1 1  0 1 1"

    @Test fun legacyAsciiPolydata() {
        val vtk = """
            # vtk DataFile Version 3.0
            cube
            ASCII
            DATASET POLYDATA
            POINTS 8 float
            $cubePoints
            POLYGONS 6 30
            4 0 3 2 1
            4 4 5 6 7
            4 0 1 5 4
            4 1 2 6 5
            4 2 3 7 6
            4 3 0 4 7
            LINES 1 4
            3 0 6 2
            TRIANGLE_STRIPS 1 5
            4 0 1 3 2
            POINT_DATA 8
            SCALARS s float
            LOOKUP_TABLE default
            0 1 2 3 4 5 6 7
        """.trimIndent()
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("c.vtk", vtk))
        assertEquals("cube", m.roots[0].name)
        assertEquals(14L, m.triangleCount) // 12 cube + 2 strip
        assertEquals(2L, m.edgeSegmentCount)
        assertEquals(8L, m.vertexCount)
        assertEquals(1f, m.bounds.sizeZ, 1e-6f)
    }

    @Test fun legacyAsciiOffsetsForm() {
        val vtk = """
            # vtk DataFile Version 5.1
            vtk output
            ASCII
            DATASET POLYDATA
            POINTS 4 float
            0 0 0 1 0 0 1 1 0 0 1 0
            METADATA
            INFORMATION 1
            NAME L2_NORM_RANGE LOCATION vtkDataArray
            DATA 2 0 1.41421

            POLYGONS 3 6
            OFFSETS vtktypeint64
            0 3 6
            CONNECTIVITY vtktypeint64
            0 1 2 0 2 3
        """.trimIndent()
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("o.vtk", vtk))
        assertEquals(2L, m.triangleCount)
    }

    @Test fun legacyBinaryPolydata() {
        val bos = ByteArrayOutputStream()
        bos.write("# vtk DataFile Version 3.0\nbinary test\nBINARY\nDATASET POLYDATA\nPOINTS 4 float\n".toByteArray())
        val pts = ByteBuffer.allocate(4 * 3 * 4).order(ByteOrder.BIG_ENDIAN)
        for (v in floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)) pts.putFloat(v)
        bos.write(pts.array())
        bos.write("\nPOLYGONS 1 5\n".toByteArray())
        val cells = ByteBuffer.allocate(5 * 4).order(ByteOrder.BIG_ENDIAN)
        for (v in intArrayOf(4, 0, 1, 2, 3)) cells.putInt(v)
        bos.write(cells.array())
        bos.write("\nLINES 1 3\n".toByteArray())
        val lines = ByteBuffer.allocate(3 * 4).order(ByteOrder.BIG_ENDIAN)
        for (v in intArrayOf(2, 0, 2)) lines.putInt(v)
        bos.write(lines.array())
        bos.write("\n".toByteArray())
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("b.vtk", bos.toByteArray()))
        assertEquals(2L, m.triangleCount)
        assertEquals(1L, m.edgeSegmentCount)
        assertTrue(m.notes[0].contains("binary"))
    }

    @Test fun legacyBinaryOffsetsInt64() {
        val bos = ByteArrayOutputStream()
        bos.write("# vtk DataFile Version 5.1\nbinary test\nBINARY\nDATASET POLYDATA\nPOINTS 3 double\n".toByteArray())
        val pts = ByteBuffer.allocate(3 * 3 * 8).order(ByteOrder.BIG_ENDIAN)
        for (v in doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) pts.putDouble(v)
        bos.write(pts.array())
        bos.write("\nPOLYGONS 2 3\nOFFSETS vtktypeint64\n".toByteArray())
        val off = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN); off.putLong(0); off.putLong(3); bos.write(off.array())
        bos.write("\nCONNECTIVITY vtktypeint64\n".toByteArray())
        val con = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN); con.putLong(0); con.putLong(1); con.putLong(2); bos.write(con.array())
        bos.write("\n".toByteArray())
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("b51.vtk", bos.toByteArray()))
        assertEquals(1L, m.triangleCount)
    }

    @Test fun unstructuredGridCells() {
        val vtk = """
            # vtk DataFile Version 2.0
            cells
            ASCII
            DATASET UNSTRUCTURED_GRID
            POINTS 13 float
            $cubePoints
            0 0 2  1 0 2  0 1 2
            0.5 0.5 3
            2 0 0
            CELLS 7 39
            8 0 1 2 3 4 5 6 7
            4 0 1 2 8
            6 4 5 7 8 9 10
            5 0 1 2 3 11
            4 0 1 2 3
            3 0 1 2
            2 0 12
            CELL_TYPES 7
            12
            10
            13
            14
            9
            5
            3
        """.trimIndent()
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("u.vtk", vtk))
        // hex 12 + tetra 4 + wedge 8 + pyramid 6 + quad 2 + tri 1 = 33
        assertEquals(33L, m.triangleCount)
        assertEquals(1L, m.edgeSegmentCount)
    }

    @Test fun badPointIndex() {
        val vtk = "# vtk DataFile Version 2.0\nx\nASCII\nDATASET POLYDATA\nPOINTS 3 float\n0 0 0 1 0 0 0 1 0\nPOLYGONS 1 4\n3 0 1 9\n"
        try { TestUtil.load(VtkImporter(), TestUtil.tempFile("bad.vtk", vtk)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("9")) }
    }

    @Test fun truncated() {
        val vtk = "# vtk DataFile Version 2.0\nx\nASCII\nDATASET POLYDATA\nPOINTS 30 float\n0 0 0 1 0 0 0 1 0\n"
        try { TestUtil.load(VtkImporter(), TestUtil.tempFile("bad.vtk", vtk)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("POINTS")) }
    }

    @Test fun structuredPointsRejected() {
        val vtk = "# vtk DataFile Version 2.0\nx\nASCII\nDATASET STRUCTURED_POINTS\nDIMENSIONS 2 2 2\n"
        try { TestUtil.load(VtkImporter(), TestUtil.tempFile("sp.vtk", vtk)); fail() }
        catch (e: ImportException) { assertTrue(e.message, e.message!!.contains("STRUCTURED_POINTS")) }
    }

    @Test fun xmlAscii() {
        val vtp = """
            <?xml version="1.0"?>
            <VTKFile type="PolyData" version="0.1" byte_order="LittleEndian">
              <PolyData><Piece NumberOfPoints="4" NumberOfPolys="1" NumberOfLines="1">
                <Points><DataArray type="Float32" NumberOfComponents="3" format="ascii">0 0 0 1 0 0 1 1 0 0 1 0</DataArray></Points>
                <Polys>
                  <DataArray type="Int64" Name="connectivity" format="ascii">0 1 2 3</DataArray>
                  <DataArray type="Int64" Name="offsets" format="ascii">4</DataArray>
                </Polys>
                <Lines>
                  <DataArray type="Int32" Name="connectivity" format="ascii">0 2</DataArray>
                  <DataArray type="Int32" Name="offsets" format="ascii">2</DataArray>
                </Lines>
              </Piece></PolyData>
            </VTKFile>
        """.trimIndent()
        val m = TestUtil.load(VtkImporter(), TestUtil.tempFile("a.vtp", vtp))
        assertEquals(2L, m.triangleCount)
        assertEquals(1L, m.edgeSegmentCount)
    }

    private fun le(vararg f: Float): ByteArray { val b = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (v in f) b.putFloat(v); return b.array() }
    private fun leInts(vararg i: Int): ByteArray { val b = ByteBuffer.allocate(i.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (v in i) b.putInt(v); return b.array() }
    private fun header32(n: Int): ByteArray = leInts(n)

    private fun b64Uncompressed(data: ByteArray): String = Base64.getEncoder().encodeToString(header32(data.size)) + Base64.getEncoder().encodeToString(data)

    private fun zlibBlocks(data: ByteArray, blockSize: Int): Pair<ByteArray, ByteArray> {
        val nblocks = (data.size + blockSize - 1) / blockSize
        val last = data.size - (nblocks - 1) * blockSize
        val sizes = ArrayList<Int>()
        val comp = ByteArrayOutputStream()
        for (b in 0 until nblocks) {
            val start = b * blockSize; val len = if (b == nblocks - 1) last else blockSize
            val d = Deflater(); d.setInput(data, start, len); d.finish()
            val buf = ByteArray(len + 64); val k = d.deflate(buf); d.end()
            comp.write(buf, 0, k); sizes.add(k)
        }
        val hdr = ByteBuffer.allocate((3 + nblocks) * 4).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(nblocks); hdr.putInt(blockSize); hdr.putInt(if (last == blockSize) 0 else last); for (s in sizes) hdr.putInt(s)
        return Pair(hdr.array(), comp.toByteArray())
    }

    @Test fun xmlInlineBase64AndZlib() {
        val pts = le(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 2f, 2f, 2f)
        val conn = leInts(0, 1, 2, 3, 4, 2, 1)
        val offs = leInts(4, 7)
        val (ph, pd) = zlibBlocks(pts, 20)
        val ptsB64 = Base64.getEncoder().encodeToString(ph) + Base64.getEncoder().encodeToString(pd)
        val (ch, cd) = zlibBlocks(conn, 1000)
        val connB64 = Base64.getEncoder().encodeToString(ch) + Base64.getEncoder().encodeToString(cd)
        val (oh, od) = zlibBlocks(offs, 1000)
        val offB64 = Base64.getEncoder().encodeToString(oh) + Base64.getEncoder().encodeToString(od)
        val vtpZ = """
            <VTKFile type="PolyData" version="1.0" byte_order="LittleEndian" compressor="vtkZLibDataCompressor" header_type="UInt32">
              <PolyData><Piece NumberOfPoints="5" NumberOfPolys="2">
                <Points><DataArray type="Float32" NumberOfComponents="3" format="binary">$ptsB64</DataArray></Points>
                <Polys>
                  <DataArray type="Int32" Name="connectivity" format="binary">$connB64</DataArray>
                  <DataArray type="Int32" Name="offsets" format="binary">$offB64</DataArray>
                </Polys>
              </Piece></PolyData>
            </VTKFile>
        """.trimIndent()
        val mz = TestUtil.load(VtkImporter(), TestUtil.tempFile("z.vtp", vtpZ))
        assertEquals(3L, mz.triangleCount)
        assertEquals(2f, mz.bounds.maxZ, 1e-6f)
        val vtpU = """
            <VTKFile type="PolyData" version="1.0" byte_order="LittleEndian">
              <PolyData><Piece NumberOfPoints="5" NumberOfPolys="2">
                <Points><DataArray type="Float32" NumberOfComponents="3" format="binary">${b64Uncompressed(pts)}</DataArray></Points>
                <Polys>
                  <DataArray type="Int32" Name="connectivity" format="binary">${b64Uncompressed(conn)}</DataArray>
                  <DataArray type="Int32" Name="offsets" format="binary">${b64Uncompressed(offs)}</DataArray>
                </Polys>
              </Piece></PolyData>
            </VTKFile>
        """.trimIndent()
        val mu = TestUtil.load(VtkImporter(), TestUtil.tempFile("u.vtp", vtpU))
        assertEquals(3L, mu.triangleCount)
    }

    @Test fun xmlAppendedRawAndBase64() {
        val pts = le(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f)
        val conn = leInts(0, 1, 2)
        val offs = leInts(3)
        val blob = ByteArrayOutputStream()
        val o1 = 0; blob.write(header32(pts.size)); blob.write(pts)
        val o2 = blob.size(); blob.write(header32(conn.size)); blob.write(conn)
        val o3 = blob.size(); blob.write(header32(offs.size)); blob.write(offs)
        fun xml(enc: String, o1: Int, o2: Int, o3: Int) = """
            <VTKFile type="UnstructuredGrid" version="1.0" byte_order="LittleEndian">
              <UnstructuredGrid><Piece NumberOfPoints="3" NumberOfCells="1">
                <Points><DataArray type="Float32" NumberOfComponents="3" format="appended" offset="$o1"/></Points>
                <Cells>
                  <DataArray type="Int32" Name="connectivity" format="appended" offset="$o2"/>
                  <DataArray type="Int32" Name="offsets" format="appended" offset="$o3"/>
                  <DataArray type="UInt8" Name="types" format="ascii">5</DataArray>
                </Cells>
              </Piece></UnstructuredGrid>
              <AppendedData encoding="$enc">
               _
        """.trimIndent()
        val raw = ByteArrayOutputStream()
        raw.write(xml("raw", o1, o2, o3).toByteArray()); raw.write(blob.toByteArray()); raw.write("\n  </AppendedData>\n</VTKFile>\n".toByteArray())
        val mr = TestUtil.load(VtkImporter(), TestUtil.tempFile("raw.vtu", raw.toByteArray()))
        assertEquals(1L, mr.triangleCount)
        // base64 appended: each array is encoded separately; offsets are into the base64 text
        val e = Base64.getEncoder()
        val a1 = e.encodeToString(header32(pts.size)) + e.encodeToString(pts)
        val a2 = e.encodeToString(header32(conn.size)) + e.encodeToString(conn)
        val a3 = e.encodeToString(header32(offs.size)) + e.encodeToString(offs)
        val text = xml("base64", 0, a1.length, a1.length + a2.length) + a1 + a2 + a3 + "\n  </AppendedData>\n</VTKFile>\n"
        val mb = TestUtil.load(VtkImporter(), TestUtil.tempFile("b64.vtu", text))
        assertEquals(1L, mb.triangleCount)
    }

    @Test fun realWorldSamples() {
        val dir = File("/tmp/work/samples-xmltext"); if (!dir.isDirectory) return
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".vtk") || it.name.endsWith(".vtp") }.sortedBy { it.name }) {
            try {
                val m = TestUtil.load(VtkImporter(), f)
                println("${f.name}: tris=${m.triangleCount} edges=${m.edgeSegmentCount} verts=${m.vertexCount} notes=${m.notes}")
                assertTrue(f.name, m.triangleCount > 0 || m.edgeSegmentCount > 0)
            } catch (e: ImportException) {
                println("${f.name}: ImportException ${e.message}")
                assertTrue(f.name + ": " + e.message, e.message!!.contains("STRUCTURED_POINTS") || e.message!!.contains("RECTILINEAR"))
            }
        }
    }

    @Test fun bundledSamples() {
        val lines = TestUtil.load(VtkImporter(), TestUtil.sample("vtk/hello.vtk"))
        assertEquals(0L, lines.triangleCount)
        assertEquals(15L, lines.edgeSegmentCount)
        val poly = TestUtil.load(VtkImporter(), TestUtil.sample("vtk/250.vtk"))
        assertEquals(374L, poly.triangleCount)
        assertEquals(226L, poly.vertexCount)
    }

    @Test fun largeAsciiThroughput() {
        // 200k triangles over a 100x1000 grid: must parse in a few seconds without per-token allocation blow-ups
        val nx = 101; val ny = 1001
        val sb = StringBuilder(16 shl 20)
        sb.append("# vtk DataFile Version 3.0\nbig\nASCII\nDATASET POLYDATA\nPOINTS ").append(nx * ny).append(" float\n")
        for (j in 0 until ny) for (i in 0 until nx) sb.append(i).append(' ').append(j).append(" 0.5\n")
        val quads = (nx - 1) * (ny - 1)
        sb.append("POLYGONS ").append(quads).append(' ').append(quads * 5).append('\n')
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) { val a = j * nx + i; sb.append("4 ").append(a).append(' ').append(a + 1).append(' ').append(a + nx + 1).append(' ').append(a + nx).append('\n') }
        val f = TestUtil.tempFile("big.vtk", sb.toString())
        val t0 = System.nanoTime()
        val m = TestUtil.load(VtkImporter(), f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(quads * 2L, m.triangleCount)
        assertTrue("took $ms ms", ms < 15_000)
    }
}
