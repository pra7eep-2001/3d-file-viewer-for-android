package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stanford PLY importer: ASCII, binary little endian and binary big endian.
 *
 * Supported: element vertex (x y z, optional nx ny nz, optional red green blue [alpha] as uchar or float,
 * any other properties skipped), element face (vertex_indices / vertex_index list, polygons triangulated,
 * optional per-face colours), element edge (vertex1 / vertex2 shown as edges). Other elements are skipped
 * using their property definitions. Vertex colours are averaged into one mesh colour.
 */
class PlyImporter : Importer {
    override val formatName = "PLY"
    override val extensions = listOf("ply")

    private enum class Fmt { ASCII, LE, BE }

    /** Scalar type sizes/kinds. kind: 0 = signed int, 1 = unsigned int, 2 = float. */
    private class PType(val size: Int, val kind: Int)

    private class Prop(val name: String, val type: PType, val listCount: PType?)
    private class Element(val name: String, val count: Int) { val props = ArrayList<Prop>() }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val fileSize = source.file.length()
        if (fileSize < 16) throw ImportException("File is too small to be a PLY model")
        try {
            BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { ins -> return parse(FastReader(ins), source, fileSize, progress) }
        } catch (e: ImportException) { throw e
        } catch (e: EOFException) { throw ImportException("The PLY file is truncated: it ends before all declared elements were read", e)
        } catch (e: Exception) { throw ImportException("The PLY file is corrupt: ${e.message ?: e.javaClass.simpleName}", e) }
    }

    private fun ptype(name: String): PType = when (name) {
        "char", "int8" -> PType(1, 0); "uchar", "uint8" -> PType(1, 1)
        "short", "int16" -> PType(2, 0); "ushort", "uint16" -> PType(2, 1)
        "int", "int32" -> PType(4, 0); "uint", "uint32" -> PType(4, 1)
        "float", "float32" -> PType(4, 2); "double", "float64" -> PType(8, 2)
        else -> throw ImportException("Unknown PLY property type '$name'")
    }

    private fun parse(r: FastReader, source: ImportSource, fileSize: Long, progress: ProgressSink): Model {
        val magic = r.readLine()?.trim() ?: ""
        if (magic != "ply") throw ImportException("Not a PLY file: the first line must be 'ply'")
        var fmt: Fmt? = null
        val elements = ArrayList<Element>()
        var headerDone = false
        while (!headerDone) {
            val line = r.readLine() ?: throw ImportException("PLY header is missing 'end_header'")
            val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            when (parts[0]) {
                "format" -> fmt = when (parts.getOrNull(1)) {
                    "ascii" -> Fmt.ASCII; "binary_little_endian" -> Fmt.LE; "binary_big_endian" -> Fmt.BE
                    else -> throw ImportException("Unknown PLY format '${parts.getOrNull(1)}'")
                }
                "comment", "obj_info" -> {}
                "element" -> {
                    if (parts.size < 3) throw ImportException("Malformed PLY element line: '$line'")
                    val cnt = parts[2].toLongOrNull() ?: throw ImportException("Bad element count in '$line'")
                    if (cnt < 0 || cnt > Int.MAX_VALUE) throw ImportException("PLY element '${parts[1]}' declares an impossible count $cnt")
                    elements.add(Element(parts[1], cnt.toInt()))
                }
                "property" -> {
                    val el = elements.lastOrNull() ?: throw ImportException("PLY property before any element at line ${r.line}")
                    if (parts.size >= 5 && parts[1] == "list") el.props.add(Prop(parts[4], ptype(parts[3]), ptype(parts[2])))
                    else if (parts.size >= 3) el.props.add(Prop(parts[2], ptype(parts[1]), null))
                    else throw ImportException("Malformed PLY property line: '$line'")
                }
                "end_header" -> headerDone = true
                else -> {} // unknown header keyword: ignore
            }
        }
        if (fmt == null) throw ImportException("PLY header has no 'format' line")
        val dataBytes = fileSize - r.bytesRead
        // Sanity check of declared counts against the file size (fail fast on corrupt headers).
        var minBytes = 0L
        for (el in elements) {
            var rec = 0L
            for (p in el.props) rec += if (fmt == Fmt.ASCII) 2 else (p.listCount?.size ?: p.type.size)
            minBytes += rec * el.count
        }
        if (minBytes > dataBytes + 16) throw ImportException("PLY header declares more data (${minBytes} bytes minimum) than the file holds ($dataBytes bytes)")

        val src: ValueSource = if (fmt == Fmt.ASCII) AsciiSource(r) else BinarySource(r, if (fmt == Fmt.LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN, dataBytes)

        var positions: FloatArray? = null
        var normals: FloatArray? = null
        var colorSum = DoubleArray(4); var colorCount = 0
        val faceSizes = IntList(1024); val faceIdx = IntList(4096)
        val edges = IntList(0)
        val faceColorSum = DoubleArray(4); var faceColorCount = 0
        val totalRecords = elements.sumOf { it.count.toLong() }.coerceAtLeast(1)
        var recordsDone = 0L
        val vals = DoubleArray(64)

        for (el in elements) {
            val np = el.props.size
            if (np > vals.size && el.props.any { it.listCount == null }) throw ImportException("PLY element '${el.name}' has too many properties ($np)")
            when (el.name) {
                "vertex" -> {
                    val ix = idx(el, "x"); val iy = idx(el, "y"); val iz = idx(el, "z")
                    if (ix < 0 || iy < 0 || iz < 0) throw ImportException("PLY vertex element has no x/y/z properties")
                    val inx = idx(el, "nx"); val iny = idx(el, "ny"); val inz = idx(el, "nz")
                    val ir = idx(el, "red", "diffuse_red", "r"); val ig = idx(el, "green", "diffuse_green", "g"); val ib = idx(el, "blue", "diffuse_blue", "b"); val ia = idx(el, "alpha", "diffuse_alpha", "a")
                    val hasN = inx >= 0 && iny >= 0 && inz >= 0
                    val hasC = ir >= 0 && ig >= 0 && ib >= 0
                    val pos = FloatArray(el.count * 3); val nrm = if (hasN) FloatArray(el.count * 3) else null
                    val cScale = if (hasC && el.props[ir].type.kind == 2) 1.0 else 1.0 / 255.0
                    val aScale = if (ia >= 0 && el.props[ia].type.kind == 2) 1.0 else 1.0 / 255.0
                    val step = maxOf(1, el.count / 100)
                    for (v in 0 until el.count) {
                        readRecord(src, el, vals)
                        pos[v * 3] = vals[ix].toFloat(); pos[v * 3 + 1] = vals[iy].toFloat(); pos[v * 3 + 2] = vals[iz].toFloat()
                        if (nrm != null) { nrm[v * 3] = vals[inx].toFloat(); nrm[v * 3 + 1] = vals[iny].toFloat(); nrm[v * 3 + 2] = vals[inz].toFloat() }
                        if (hasC) {
                            colorSum[0] += vals[ir] * cScale; colorSum[1] += vals[ig] * cScale; colorSum[2] += vals[ib] * cScale
                            colorSum[3] += if (ia >= 0) vals[ia] * aScale else 1.0
                            colorCount++
                        }
                        if (v % step == 0) { progress.progress(0.9f * (recordsDone + v) / totalRecords, "Reading vertices"); progress.checkCancelled() }
                    }
                    if (positions == null) { positions = pos; normals = nrm }
                }
                "face" -> {
                    val il = el.props.indexOfFirst { it.listCount != null && (it.name == "vertex_indices" || it.name == "vertex_index") }
                    val ir = idx(el, "red", "diffuse_red"); val ig = idx(el, "green", "diffuse_green"); val ib = idx(el, "blue", "diffuse_blue"); val ia = idx(el, "alpha")
                    val hasC = ir >= 0 && ig >= 0 && ib >= 0
                    val cScale = if (hasC && el.props[ir].type.kind == 2) 1.0 else 1.0 / 255.0
                    val aScale = if (ia >= 0 && el.props[ia].type.kind == 2) 1.0 else 1.0 / 255.0
                    val step = maxOf(1, el.count / 100)
                    for (f in 0 until el.count) {
                        var listStart = 0
                        for (p in 0 until np) {
                            val pr = el.props[p]
                            if (pr.listCount != null) {
                                val n = src.read(pr.listCount).toInt()
                                if (n < 0 || n > 1_000_000) throw ImportException("PLY face $f declares $n vertices")
                                if (p == il) {
                                    listStart = faceIdx.size
                                    faceIdx.ensure(n)
                                    for (k in 0 until n) faceIdx.add(src.read(pr.type).toInt())
                                    faceSizes.add(n)
                                } else for (k in 0 until n) src.read(pr.type)
                            } else vals[p] = src.read(pr.type)
                        }
                        if (il < 0) continue
                        if (hasC) {
                            faceColorSum[0] += vals[ir] * cScale; faceColorSum[1] += vals[ig] * cScale; faceColorSum[2] += vals[ib] * cScale
                            faceColorSum[3] += if (ia >= 0) vals[ia] * aScale else 1.0
                            faceColorCount++
                        }
                        if (f % step == 0) { progress.progress(0.9f * (recordsDone + f) / totalRecords, "Reading faces"); progress.checkCancelled() }
                    }
                }
                "edge" -> {
                    val i1 = idx(el, "vertex1"); val i2 = idx(el, "vertex2")
                    for (e in 0 until el.count) {
                        readRecord(src, el, vals)
                        if (i1 >= 0 && i2 >= 0) edges.add(vals[i1].toInt(), vals[i2].toInt())
                    }
                }
                else -> { for (e in 0 until el.count) readRecord(src, el, vals) }
            }
            recordsDone += el.count
        }
        val pos = positions ?: throw ImportException("The PLY file has no vertex element")
        val nv = pos.size / 3
        progress.progress(0.92f, "Triangulating")
        val tris = IntList(maxOf(16, faceSizes.size * 3))
        var o = 0
        val poly = IntList(16)
        for (f in 0 until faceSizes.size) {
            val n = faceSizes[f]
            for (k in 0 until n) { val v = faceIdx[o + k]; if (v < 0 || v >= nv) throw ImportException("PLY face $f references vertex $v but only $nv vertices exist") }
            if (n == 3) tris.add(faceIdx[o], faceIdx[o + 1], faceIdx[o + 2])
            else if (n > 3) { poly.clear(); for (k in 0 until n) poly.add(faceIdx[o + k]); MeshOps.triangulatePolygon(poly.toArray(), pos, tris) }
            o += n
        }
        val edgeArr = if (edges.size > 0) {
            for (i in 0 until edges.size) { val v = edges[i]; if (v < 0 || v >= nv) throw ImportException("PLY edge references vertex $v but only $nv vertices exist") }
            edges.toArray()
        } else null
        if (tris.size == 0 && edgeArr == null) throw ImportException(if (nv > 0) "The PLY file contains no faces or edges (point clouds cannot be displayed)" else "The PLY file contains no geometry")
        var color = 0
        if (colorCount > 0) color = argb(colorSum, colorCount) else if (faceColorCount > 0) color = argb(faceColorSum, faceColorCount)
        val notes = ArrayList<String>()
        notes.add(when (fmt) { Fmt.ASCII -> "ASCII PLY"; Fmt.LE -> "Binary PLY (little endian)"; Fmt.BE -> "Binary PLY (big endian)" })
        if (colorCount > 0) notes.add("Vertex colours averaged into one mesh colour")
        if (normals == null) notes.add("No vertex normals in file; flat shading")
        val mesh = Mesh(pos, normals, tris.toArray(), null, edgeArr)
        val name = source.displayName.substringBeforeLast('.')
        return Model(listOf(Node(name, mesh, color)), formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, notes)
    }

    private fun idx(el: Element, vararg names: String): Int {
        for (n in names) { val i = el.props.indexOfFirst { it.listCount == null && it.name == n }; if (i >= 0) return i }
        return -1
    }

    /** Reads one record of a scalar-only (or mixed) element; scalar values land in [vals], lists are skipped. */
    private fun readRecord(src: ValueSource, el: Element, vals: DoubleArray) {
        val props = el.props
        for (p in props.indices) {
            val pr = props[p]
            if (pr.listCount != null) {
                val n = src.read(pr.listCount).toInt()
                if (n < 0 || n > 1_000_000) throw ImportException("PLY list property '${pr.name}' declares $n items")
                for (k in 0 until n) src.read(pr.type)
            } else vals[p] = src.read(pr.type)
        }
    }

    private fun argb(sum: DoubleArray, n: Int): Int {
        fun c(v: Double) = ((v / n).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (c(sum[3]) shl 24) or (c(sum[0]) shl 16) or (c(sum[1]) shl 8) or c(sum[2])
    }

    private interface ValueSource { fun read(t: PType): Double }

    private class AsciiSource(val r: FastReader) : ValueSource {
        override fun read(t: PType): Double {
            r.skipWhitespace()
            if (r.atEnd()) throw EOFException()
            return if (t.kind == 2) r.float().toDouble() else r.int().toDouble()
        }
    }

    /** Buffered binary reader over the remainder of the stream after the header. */
    private class BinarySource(val r: FastReader, order: ByteOrder, var remaining: Long) : ValueSource {
        private val buf = ByteArray(1 shl 16)
        private val bb: ByteBuffer = ByteBuffer.wrap(buf).order(order)
        init { bb.limit(0) }
        private fun ensure(n: Int) {
            if (bb.remaining() >= n) return
            val left = bb.remaining()
            System.arraycopy(buf, bb.position(), buf, 0, left)
            val want = minOf((buf.size - left).toLong(), remaining).toInt()
            if (left + want < n) throw EOFException()
            r.readFully(buf, left, want)
            remaining -= want
            bb.limit(left + want); bb.position(0)
        }
        override fun read(t: PType): Double {
            ensure(t.size)
            return when (t.kind) {
                2 -> if (t.size == 4) bb.getFloat().toDouble() else bb.getDouble()
                0 -> when (t.size) { 1 -> bb.get().toDouble(); 2 -> bb.getShort().toDouble(); else -> bb.getInt().toDouble() }
                else -> when (t.size) { 1 -> (bb.get().toInt() and 0xff).toDouble(); 2 -> (bb.getShort().toInt() and 0xffff).toDouble(); else -> (bb.getInt().toLong() and 0xffffffffL).toDouble() }
            }
        }
    }
}
