package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.io.Importer
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Truncated and byte-corrupted versions of the bundled samples must either load or fail with an
 * [ImportException]; any other exception type would surface to the user as a crash.
 */
class TextFormatsRobustnessTest {
    private val cases: List<Pair<Importer, String>> = listOf(
        AmfImporter() to "amf/split_pyramid.amf",
        ThreeMfImporter() to "3mf/box.3mf",
        DaeImporter() to "dae/cube_triangulate.dae",
        X3dImporter() to "x3d/HelloX3dTrademark.x3d",
        VrmlImporter() to "wrl/meshWithLines.wrl",
        VtkImporter() to "vtk/250.vtk",
        UsdaImporter() to "usda/simpleShading.usda",
        DxfImporter() to "dxf/PinkEggFromLW.dxf",
    )

    private fun attempt(importer: Importer, name: String, bytes: ByteArray, what: String) {
        try { TestUtil.load(importer, TestUtil.tempFile(name, bytes)) }
        catch (e: ImportException) { /* expected */ }
        catch (e: Throwable) { fail("${importer.formatName} $what: ${e.javaClass.name}: ${e.message}") }
    }

    @Test fun truncatedFiles() {
        for ((imp, sample) in cases) {
            val bytes = TestUtil.sample(sample).readBytes()
            for (f in listOf(0.1, 0.3, 0.5, 0.7, 0.9, 0.97)) {
                attempt(imp, sample.substringAfterLast('/'), bytes.copyOf((bytes.size * f).toInt()), "truncated at $f")
            }
        }
    }

    @Test fun corruptedFiles() {
        val rnd = Random(42)
        for ((imp, sample) in cases) {
            val bytes = TestUtil.sample(sample).readBytes()
            repeat(12) { round ->
                val b = bytes.copyOf()
                val hits = 1 + rnd.nextInt(20)
                for (k in 0 until hits) { val i = rnd.nextInt(b.size); b[i] = "0123456789-.e<>[]{}()\"#,xyzABC ".let { it[rnd.nextInt(it.length)].code.toByte() } }
                attempt(imp, sample.substringAfterLast('/'), b, "corrupted round $round")
            }
        }
    }
}
