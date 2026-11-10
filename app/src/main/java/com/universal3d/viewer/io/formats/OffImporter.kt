package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.io.BufferedInputStream
import java.io.FileInputStream

/**
 * Object File Format importer (OFF / COFF / NOFF / STOFF / 4OFF / nOFF and combinations such as CNOFF).
 *
 * The header keyword is optional (some files start directly with the counts) and the counts may follow the
 * keyword on the same line. Vertex lines carry x y z, optional normals (N), optional colours (C, 0..255 or 0..1,
 * with optional alpha) and optional texture coordinates (ST, ignored). Faces are arbitrary polygons which are
 * triangulated; trailing face colours are averaged when no vertex colours exist. Vertex colours are averaged
 * into a single mesh colour. Homogeneous 4OFF coordinates are divided by w; nOFF is accepted for dimension 3 only.
 */
class OffImporter : Importer {
    override val formatName = "OFF"
    override val extensions = listOf("off")

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val fileSize = source.file.length()
        if (fileSize < 8) throw ImportException("File is too small to be an OFF model")
        try {
            FastReader(BufferedInputStream(FileInputStream(source.file), 1 shl 16)).let { r -> return parse(r, source, fileSize, progress) }
        } catch (e: ImportException) { throw e
        } catch (e: Exception) { throw ImportException("The OFF file is corrupt: ${e.message ?: e.javaClass.simpleName}", e) }
    }

    private fun skipCommentsAndBlank(r: FastReader) {
        while (true) {
            r.skipWhitespace()
            if (r.peek() == '#'.code) r.skipLine() else return
        }
    }

    /** True when the rest of the current line holds no more values (newline, EOF or a comment). */
    private fun lineDone(r: FastReader): Boolean = r.endOfLine() || r.peek() == '#'.code

    private fun parse(r: FastReader, source: ImportSource, fileSize: Long, progress: ProgressSink): Model {
        skipCommentsAndBlank(r)
        var hasColor = false; var hasNormal = false; var hasTex = false; var homogeneous = false; var nDim = false
        var dim = 3
        val first = r.token()
        val headerIsKeyword = first.endsWith("OFF")
        if (headerIsKeyword) {
            val flags = first.substring(0, first.length - 3)
            var i = 0
            while (i < flags.length) {
                when {
                    flags.startsWith("ST", i) -> { hasTex = true; i += 2 }
                    flags[i] == 'C' -> { hasColor = true; i++ }
                    flags[i] == 'N' -> { hasNormal = true; i++ }
                    flags[i] == '4' -> { homogeneous = true; i++ }
                    flags[i] == 'n' -> { nDim = true; i++ }
                    else -> throw ImportException("Unknown OFF header keyword '$first'")
                }
            }
            if (nDim) {
                skipCommentsAndBlank(r)
                dim = r.int()
                if (dim != 3) throw ImportException("nOFF files with dimension $dim are not supported (only 3D geometry can be displayed)")
            }
            skipCommentsAndBlank(r)
        } else {
            // No keyword: the first token must already be the vertex count.
            if (first.toIntOrNull() == null) throw ImportException("Not an OFF file: expected 'OFF' header but found '$first'")
        }
        val nv = if (headerIsKeyword) r.int() else first.toInt()
        skipCommentsAndBlank(r)
        val nf = r.int()
        skipCommentsAndBlank(r)
        val ne = if (!lineDone(r)) r.int() else 0
        if (nv < 0 || nf < 0 || ne < 0) throw ImportException("OFF header declares negative counts")
        if (nv.toLong() * 6 > fileSize + 64) throw ImportException("OFF header declares $nv vertices which cannot fit in a ${fileSize} byte file")
        if (nf.toLong() * 4 > fileSize + 64) throw ImportException("OFF header declares $nf faces which cannot fit in a ${fileSize} byte file")
        if (nv == 0) throw ImportException("The OFF file contains no vertices")

        val positions = FloatArray(nv * 3)
        val normals = if (hasNormal) FloatArray(nv * 3) else null
        var colorCount = 0
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var sumA = 0.0
        val extra = FloatArray(8)
        val step = maxOf(1, nv / 50)
        for (v in 0 until nv) {
            skipCommentsAndBlank(r)
            if (r.atEnd()) throw ImportException("OFF file ends after ${v} of $nv vertices")
            val x = r.float(); val y = r.float(); val z = r.float()
            var w = 1f
            if (homogeneous) { w = r.float(); if (w == 0f) w = 1f }
            positions[v * 3] = x / w; positions[v * 3 + 1] = y / w; positions[v * 3 + 2] = z / w
            if (normals != null) { normals[v * 3] = r.float(); normals[v * 3 + 1] = r.float(); normals[v * 3 + 2] = r.float() }
            // remaining values on the line: colour (r g b [a]) then optional texture coordinates
            var n = 0
            while (!lineDone(r)) { val f = r.float(); if (n < extra.size) extra[n] = f; n++ }
            val colorComponents = if (hasTex) n - 2 else n
            if (colorComponents >= 3 && (hasColor || !hasTex)) {
                val scale = if (extra[0] > 1f || extra[1] > 1f || extra[2] > 1f || (colorComponents >= 4 && extra[3] > 1f)) 1f / 255f else 1f
                sumR += extra[0] * scale; sumG += extra[1] * scale; sumB += extra[2] * scale
                sumA += if (colorComponents >= 4) extra[3] * scale else 1f
                colorCount++
            }
            if (v % step == 0) { progress.progress(0.5f * v / nv, "Reading vertices"); progress.checkCancelled() }
        }
        // vertex colours averaged into one mesh colour
        var color = if (colorCount > 0) argb(sumR / colorCount, sumG / colorCount, sumB / colorCount, sumA / colorCount) else 0

        val tris = IntList(maxOf(16, nf * 3))
        val poly = IntList(16)
        var faceColorCount = 0; sumR = 0.0; sumG = 0.0; sumB = 0.0; sumA = 0.0
        val fstep = maxOf(1, nf / 50)
        var facesRead = 0
        for (f in 0 until nf) {
            skipCommentsAndBlank(r)
            if (r.atEnd()) break // truncated: keep the faces we have
            val n = r.int()
            if (n < 0 || n > nv) throw ImportException("Face $f declares $n vertices at line ${r.line}")
            poly.clear()
            for (k in 0 until n) {
                val idx = r.int()
                if (idx < 0 || idx >= nv) throw ImportException("Face $f references vertex $idx but only $nv vertices exist (line ${r.line})")
                poly.add(idx)
            }
            var c = 0
            while (!lineDone(r)) { val v = r.float(); if (c < extra.size) extra[c] = v; c++ }
            if (c >= 3 && colorCount == 0) {
                val scale = if (extra[0] > 1f || extra[1] > 1f || extra[2] > 1f || (c >= 4 && extra[3] > 1f)) 1f / 255f else 1f
                sumR += extra[0] * scale; sumG += extra[1] * scale; sumB += extra[2] * scale
                sumA += if (c >= 4) extra[3] * scale else 1f
                faceColorCount++
            }
            when {
                n == 3 -> tris.add(poly[0], poly[1], poly[2])
                n > 3 -> MeshOps.triangulatePolygon(poly.toArray(), positions, tris)
            }
            facesRead++
            if (f % fstep == 0) { progress.progress(0.5f + 0.45f * f / nf, "Reading faces"); progress.checkCancelled() }
        }
        if (color == 0 && faceColorCount > 0) color = argb(sumR / faceColorCount, sumG / faceColorCount, sumB / faceColorCount, sumA / faceColorCount)
        if (tris.size == 0) throw ImportException("The OFF file contains no faces")
        val notes = ArrayList<String>()
        if (facesRead < nf) notes.add("File is truncated: $facesRead of $nf faces read")
        if (normals == null) notes.add("No vertex normals in file; flat shading")
        if (colorCount > 0) notes.add("Vertex colours averaged into one mesh colour")
        if (homogeneous) notes.add("Homogeneous coordinates divided by w")
        progress.progress(0.95f, "Finalising")
        val mesh = Mesh(positions, normals, tris.toArray())
        val name = source.displayName.substringBeforeLast('.')
        return Model(listOf(Node(name, mesh, color)), formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, notes)
    }

    private fun argb(r: Double, g: Double, b: Double, a: Double): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
