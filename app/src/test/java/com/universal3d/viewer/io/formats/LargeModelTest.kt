package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ProgressSink
import com.universal3d.viewer.model.MeshOps
import com.universal3d.viewer.render.Picker
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Large-file handling: a 1,000,000-triangle binary STL (50 MB) must import, weld, produce feature edges
 * and support picking within sensible time and memory on the host JVM (the phone build runs the same code).
 */
class LargeModelTest {
    @Test fun millionTriangleStl() {
        val n = 1_000_000
        val f = File.createTempFile("large", ".stl"); f.deleteOnExit()
        BufferedOutputStream(FileOutputStream(f), 1 shl 20).use { out ->
            out.write(ByteArray(80))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array())
            val rec = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            // a 1000 x 500 grid of quads (2 triangles each) forming a wavy sheet with shared vertices
            val cols = 1000; val rows = 500
            fun z(x: Int, y: Int) = (Math.sin(x * 0.05) * 3 + Math.cos(y * 0.07) * 2).toFloat()
            for (y in 0 until rows) for (x in 0 until cols) {
                val x0 = x.toFloat(); val x1 = x + 1f; val y0 = y.toFloat(); val y1 = y + 1f
                for (t in 0 until 2) {
                    rec.clear(); rec.putFloat(0f); rec.putFloat(0f); rec.putFloat(1f)
                    if (t == 0) { rec.putFloat(x0); rec.putFloat(y0); rec.putFloat(z(x, y)); rec.putFloat(x1); rec.putFloat(y0); rec.putFloat(z(x + 1, y)); rec.putFloat(x1); rec.putFloat(y1); rec.putFloat(z(x + 1, y + 1)) }
                    else { rec.putFloat(x0); rec.putFloat(y0); rec.putFloat(z(x, y)); rec.putFloat(x1); rec.putFloat(y1); rec.putFloat(z(x + 1, y + 1)); rec.putFloat(x0); rec.putFloat(y1); rec.putFloat(z(x, y + 1)) }
                    rec.putShort(0); out.write(rec.array())
                }
            }
        }
        assertEquals(84L + 50L * n, f.length())
        val t0 = System.currentTimeMillis()
        var last = -1f
        val m = StlImporter().load(TestUtil.source(f), object : ProgressSink { override fun progress(fraction: Float, message: String?) { assertTrue(fraction >= last - 1e-6f); last = fraction }; override val isCancelled = false })
        val t1 = System.currentTimeMillis()
        assertEquals(n.toLong(), m.triangleCount)
        assertEquals(1001L * 501L, m.vertexCount) // welded
        val mesh = m.meshNodes[0].mesh!!
        val edges = MeshOps.featureEdges(mesh.positions, mesh.indices, 32f)!!
        val t2 = System.currentTimeMillis()
        assertTrue("boundary edges expected", edges.size / 2 >= 2 * (1000 + 500))
        // picking through the whole sheet
        val hit = Picker.pick(m, floatArrayOf(500.5f, 250.5f, 100f, 0f, 0f, -1f), null, false)
        val t3 = System.currentTimeMillis()
        assertNotNull(hit)
        println("LARGE STL: import ${t1 - t0} ms, feature edges ${t2 - t1} ms (${edges.size / 2} segments), pick ${t3 - t2} ms, heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576} MB")
    }
}
