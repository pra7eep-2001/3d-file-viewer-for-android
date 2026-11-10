package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater

/**
 * VTK importer: legacy ".vtk" files (ASCII and big-endian BINARY) with POLYDATA, UNSTRUCTURED_GRID and
 * STRUCTURED_GRID datasets (legacy "size" and VTK 5.1 OFFSETS/CONNECTIVITY cell layouts), and XML ".vtp" /
 * ".vtu" files with ascii, inline base64 or appended (raw or base64) data, optionally zlib-compressed.
 * Lines become edges; volumetric cells contribute all their faces. Scalars, colours and normals are ignored.
 */
class VtkImporter : Importer {
    override val formatName = "VTK"
    override val extensions = listOf("vtk", "vtp", "vtu")

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val head = ByteArray(256)
        val n = FileInputStream(source.file).use { it.read(head) }
        if (n < 8) throw ImportException("File is too small to be a VTK file")
        val hs = String(head, 0, n, Charsets.ISO_8859_1)
        val notes = ArrayList<String>()
        val nodes: List<Node> = T.guard("VTK file") {
            when {
                hs.startsWith("# vtk DataFile") -> readLegacy(source, progress, notes)
                hs.trimStart().startsWith("<") -> readXml(source, progress, notes)
                else -> throw ImportException("Not a VTK file (expected '# vtk DataFile Version' or an XML <VTKFile> header)")
            }
        }
        if (nodes.isEmpty() || nodes.all { (it.mesh?.triangleCount ?: 0) == 0 && (it.mesh?.edgeSegmentCount ?: 0) == 0 }) throw ImportException("The VTK file contains no geometry (no polygons, strips, cells or lines)")
        return Model(nodes, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, notes)
    }

    // ------------------------------------------------------------------ cell -> triangle/edge builder

    private class Builder(val pointCount: Int) {
        val tris = IntList(1 shl 12)
        val edges = IntList(16)
        var unsupported = 0
        var polyhedra = 0
        lateinit var positions: FloatArray
        private val poly = IntList(16)

        private fun check(id: Int, what: String) { if (id < 0 || id >= pointCount) throw ImportException("$what references point $id but only $pointCount points are defined") }

        fun tri(a: Int, b: Int, c: Int) { check(a, "Cell"); check(b, "Cell"); check(c, "Cell"); tris.add(a, b, c) }
        fun quad(a: Int, b: Int, c: Int, d: Int) { check(a, "Cell"); check(b, "Cell"); check(c, "Cell"); check(d, "Cell"); MeshOps.triangulatePolygon(intArrayOf(a, b, c, d), positions, tris) }
        fun polygon(ids: IntArray, off: Int, n: Int) {
            if (n == 3) { tri(ids[off], ids[off + 1], ids[off + 2]); return }
            if (n == 4) { quad(ids[off], ids[off + 1], ids[off + 2], ids[off + 3]); return }
            if (n < 3) return
            poly.clear(); for (k in 0 until n) { check(ids[off + k], "Polygon"); poly.add(ids[off + k]) }
            MeshOps.triangulatePolygon(poly.toArray(), positions, tris)
        }
        fun strip(ids: IntArray, off: Int, n: Int) {
            for (k in 2 until n) { if (k % 2 == 0) tri(ids[off + k - 2], ids[off + k - 1], ids[off + k]) else tri(ids[off + k - 1], ids[off + k - 2], ids[off + k]) }
        }
        fun line(ids: IntArray, off: Int, n: Int) {
            for (k in 1 until n) { check(ids[off + k - 1], "Line"); check(ids[off + k], "Line"); edges.add(ids[off + k - 1], ids[off + k]) }
        }
        fun cell(type: Int, ids: IntArray, off: Int, n: Int) {
            fun v(i: Int) = ids[off + i]
            when (type) {
                1, 2 -> {} // vertex, poly vertex
                3 -> if (n >= 2) line(ids, off, 2)
                4 -> line(ids, off, n)
                5 -> if (n >= 3) tri(v(0), v(1), v(2))
                6 -> strip(ids, off, n)
                7 -> polygon(ids, off, n)
                8 -> if (n >= 4) quad(v(0), v(1), v(3), v(2)) // pixel
                9 -> if (n >= 4) quad(v(0), v(1), v(2), v(3))
                10, 24 -> if (n >= 4) { tri(v(0), v(2), v(1)); tri(v(0), v(1), v(3)); tri(v(1), v(2), v(3)); tri(v(0), v(3), v(2)) }
                11 -> if (n >= 8) { // voxel: x fastest, then y, then z
                    quad(v(0), v(2), v(3), v(1)); quad(v(4), v(5), v(7), v(6)); quad(v(0), v(1), v(5), v(4)); quad(v(2), v(6), v(7), v(3)); quad(v(0), v(4), v(6), v(2)); quad(v(1), v(3), v(7), v(5))
                }
                12, 25, 29, 33 -> if (n >= 8) {
                    quad(v(0), v(3), v(2), v(1)); quad(v(4), v(5), v(6), v(7)); quad(v(0), v(1), v(5), v(4)); quad(v(1), v(2), v(6), v(5)); quad(v(2), v(3), v(7), v(6)); quad(v(3), v(0), v(4), v(7))
                }
                13, 26, 31, 32 -> if (n >= 6) { tri(v(0), v(1), v(2)); tri(v(3), v(5), v(4)); quad(v(0), v(3), v(4), v(1)); quad(v(1), v(4), v(5), v(2)); quad(v(2), v(5), v(3), v(0)) }
                14, 27 -> if (n >= 5) { quad(v(0), v(3), v(2), v(1)); tri(v(0), v(1), v(4)); tri(v(1), v(2), v(4)); tri(v(2), v(3), v(4)); tri(v(3), v(0), v(4)) }
                21 -> if (n >= 2) line(ids, off, 2)
                22, 34 -> if (n >= 3) tri(v(0), v(1), v(2))
                23, 28, 30 -> if (n >= 4) quad(v(0), v(1), v(2), v(3))
                42 -> polyhedra++
                else -> unsupported++
            }
        }
        fun mesh(): Mesh = Mesh(positions, null, tris.toArray(), null, if (edges.size > 0) edges.toArray() else null)
    }

    // ------------------------------------------------------------------ legacy format

    private fun readLegacy(source: ImportSource, progress: ProgressSink, notes: ArrayList<String>): List<Node> {
        val fileSize = source.file.length()
        BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { ins ->
            val r = FastReader(ins)
            r.skipLine()                                     // # vtk DataFile Version x.y
            val title = r.readLine()?.trim() ?: ""
            val mode = r.tokenAnyLine().uppercase(); r.skipLine()
            val binary = when (mode) { "BINARY" -> true; "ASCII" -> false; else -> throw ImportException("VTK file must declare ASCII or BINARY (found '$mode' at line ${r.line})") }
            var dsKey = r.tokenAnyLine().uppercase()
            // FIELD data may precede DATASET
            while (dsKey == "FIELD") { skipField(r, binary); dsKey = r.tokenAnyLine().uppercase() }
            if (dsKey != "DATASET") throw ImportException("Expected DATASET at line ${r.line} but found '$dsKey'")
            val dataset = r.token().uppercase(); r.skipLine()
            when (dataset) {
                "POLYDATA", "UNSTRUCTURED_GRID", "STRUCTURED_GRID" -> {}
                "STRUCTURED_POINTS", "RECTILINEAR_GRID" -> throw ImportException("VTK $dataset datasets are volumes/images without surface geometry; only POLYDATA, UNSTRUCTURED_GRID and STRUCTURED_GRID are supported")
                else -> throw ImportException("Unsupported VTK dataset type '$dataset'")
            }
            notes.add(if (binary) "Legacy binary VTK $dataset" else "Legacy ASCII VTK $dataset")
            var positions: FloatArray? = null
            var builder: Builder? = null
            var dims: IntArray? = null
            var cells: IntArray? = null; var cellOffsets: IntArray? = null; var cellCount = 0
            var cellTypes: IntArray? = null
            fun remaining() = fileSize - r.bytesRead
            fun b(): Builder { val bb = builder ?: Builder((positions ?: throw ImportException("VTK cells appear before POINTS (line ${r.line})")).size / 3).also { it.positions = positions!!; builder = it }; return bb }

            loop@ while (!r.atEnd()) {
                val key = r.tokenAnyLine().uppercase()
                if (key.isEmpty()) break
                when (key) {
                    "POINTS" -> {
                        val n = r.int(); val type = r.token().lowercase(); r.skipLine()
                        T.checkCount(n.toLong(), "points")
                        if (binary && n.toLong() * 3 * typeSize(type) > remaining()) throw ImportException("VTK file is truncated: $n points declared but the file is too short")
                        positions = readValues(r, n * 3, type, binary, "POINTS")
                        progress.progress(0.3f, "Reading cells")
                    }
                    "VERTICES", "LINES", "POLYGONS", "TRIANGLE_STRIPS", "CELLS" -> {
                        val n = r.int(); val size = r.int(); r.skipLine()
                        T.checkCount(n.toLong(), "cells"); T.checkCount(size.toLong(), "cell indices", 400_000_000L)
                        val bd = b()
                        // VTK 5.1 layout: OFFSETS/CONNECTIVITY arrays follow; legacy: "size" ints follow directly
                        if (!binary) r.skipWhitespace()
                        val isOffsets = r.peek() == 'O'.code
                        val offsets: IntArray; val conn: IntArray; val count: Int
                        if (isOffsets) {
                            val k1 = r.token().uppercase(); if (k1 != "OFFSETS") throw ImportException("Expected OFFSETS at line ${r.line}")
                            val t1 = r.token().lowercase(); r.skipLine()
                            if (binary && n.toLong() * typeSize(t1) > remaining()) throw ImportException("VTK file is truncated in $key OFFSETS")
                            offsets = readInts(r, n, t1, binary, "$key OFFSETS")
                            val k2 = r.tokenAnyLine().uppercase(); if (k2 != "CONNECTIVITY") throw ImportException("Expected CONNECTIVITY at line ${r.line}")
                            val t2 = r.token().lowercase(); r.skipLine()
                            if (binary && size.toLong() * typeSize(t2) > remaining()) throw ImportException("VTK file is truncated in $key CONNECTIVITY")
                            conn = readInts(r, size, t2, binary, "$key CONNECTIVITY")
                            count = n - 1
                        } else {
                            if (binary && size.toLong() * 4 > remaining()) throw ImportException("VTK file is truncated: $key declares $size values but the file is too short")
                            val raw = readInts(r, size, "int", binary, key)
                            // convert "count idx idx ..." into offsets + connectivity
                            offsets = IntArray(n + 1); conn = IntArray(maxOf(0, size - n).coerceAtLeast(0))
                            var at = 0; var o = 0
                            for (c in 0 until n) {
                                if (at >= raw.size) throw ImportException("$key list is shorter than declared (cell $c of $n)")
                                val cnt = raw[at++]
                                if (cnt < 0 || at + cnt > raw.size) throw ImportException("$key cell $c has an invalid point count $cnt")
                                offsets[c] = o
                                for (k in 0 until cnt) conn[o++] = raw[at++]
                            }
                            offsets[n] = o
                            count = n
                        }
                        if (key == "CELLS") { cells = conn; cellOffsets = offsets; cellCount = count }
                        else {
                            for (c in 0 until count) {
                                val s = offsets[c]; val e = offsets[c + 1]
                                if (s < 0 || e > conn.size || e < s) throw ImportException("$key offsets are invalid at cell $c")
                                when (key) {
                                    "POLYGONS" -> bd.polygon(conn, s, e - s)
                                    "TRIANGLE_STRIPS" -> bd.strip(conn, s, e - s)
                                    "LINES" -> bd.line(conn, s, e - s)
                                }
                                if ((c and 0x3fff) == 0) progress.checkCancelled()
                            }
                        }
                    }
                    "CELL_TYPES" -> {
                        val n = r.int(); r.skipLine()
                        T.checkCount(n.toLong(), "cell types")
                        if (binary && n.toLong() * 4 > remaining()) throw ImportException("VTK file is truncated in CELL_TYPES")
                        cellTypes = readInts(r, n, "int", binary, "CELL_TYPES")
                    }
                    "DIMENSIONS" -> { dims = intArrayOf(r.int(), r.int(), r.int()); r.skipLine() }
                    "FIELD" -> skipField(r, binary)
                    "METADATA" -> skipMetadata(r)
                    "POINT_DATA", "CELL_DATA" -> break@loop
                    else -> r.skipLine()
                }
            }
            if (positions == null) throw ImportException("VTK file has no POINTS section")
            val bd = b()
            val ct = cellTypes; val cl = cells; val co = cellOffsets
            if (cl != null && co != null) {
                if (ct == null) throw ImportException("VTK UNSTRUCTURED_GRID has CELLS but no CELL_TYPES")
                for (c in 0 until cellCount) {
                    val type = if (c < ct.size) ct[c] else throw ImportException("CELL_TYPES has fewer entries (${ct.size}) than CELLS ($cellCount)")
                    val s = co[c]; val e = co[c + 1]
                    if (s < 0 || e > cl.size || e < s) throw ImportException("CELLS offsets are invalid at cell $c")
                    bd.cell(type, cl, s, e - s)
                    if ((c and 0x3fff) == 0) progress.checkCancelled()
                }
            }
            val d = dims
            if (dataset == "STRUCTURED_GRID" && d != null) structuredGrid(bd, d)
            if (bd.unsupported > 0) notes.add("${bd.unsupported} cells of unsupported types were skipped")
            if (bd.polyhedra > 0) notes.add("${bd.polyhedra} polyhedron cells were skipped")
            progress.progress(0.9f, "Finalising")
            val name = title.ifBlank { source.displayName.substringBeforeLast('.') }
            return listOf(Node(name, bd.mesh()))
        }
    }

    /** Boundary faces of a structured grid (nx*ny*nz points); 2D grids give one quad sheet. */
    private fun structuredGrid(bd: Builder, d: IntArray) {
        val nx = d[0]; val ny = d[1]; val nz = d[2]
        if (nx < 1 || ny < 1 || nz < 1) return
        if (nx.toLong() * ny * nz != bd.pointCount.toLong()) throw ImportException("STRUCTURED_GRID dimensions $nx x $ny x $nz do not match the ${bd.pointCount} points")
        fun id(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i
        fun sheet(a: (Int, Int) -> Int, n1: Int, n2: Int, flip: Boolean) {
            for (v in 0 until n2 - 1) for (u in 0 until n1 - 1) {
                val p0 = a(u, v); val p1 = a(u + 1, v); val p2 = a(u + 1, v + 1); val p3 = a(u, v + 1)
                if (flip) bd.quad(p0, p3, p2, p1) else bd.quad(p0, p1, p2, p3)
            }
        }
        if (nz == 1) { sheet({ i, j -> id(i, j, 0) }, nx, ny, false); return }
        if (ny == 1) { sheet({ i, k -> id(i, 0, k) }, nx, nz, true); return }
        if (nx == 1) { sheet({ j, k -> id(0, j, k) }, ny, nz, false); return }
        sheet({ i, j -> id(i, j, 0) }, nx, ny, true); sheet({ i, j -> id(i, j, nz - 1) }, nx, ny, false)
        sheet({ i, k -> id(i, 0, k) }, nx, nz, false); sheet({ i, k -> id(i, ny - 1, k) }, nx, nz, true)
        sheet({ j, k -> id(0, j, k) }, ny, nz, true); sheet({ j, k -> id(nx - 1, j, k) }, ny, nz, false)
    }

    private fun typeSize(type: String): Int = when (type) {
        "bit" -> 1
        "char", "unsigned_char", "vtktypeint8", "vtktypeuint8" -> 1
        "short", "unsigned_short", "vtktypeint16", "vtktypeuint16" -> 2
        "int", "unsigned_int", "vtktypeint32", "vtktypeuint32", "float" -> 4
        "long", "unsigned_long", "vtktypeint64", "vtktypeuint64", "double", "vtkidtype" -> 8
        else -> throw ImportException("Unknown VTK data type '$type'")
    }

    private fun readValues(r: FastReader, count: Int, type: String, binary: Boolean, what: String): FloatArray {
        val out = FloatArray(count)
        if (!binary) {
            for (i in 0 until count) { r.skipWhitespace(); if (r.atEnd()) throw ImportException("VTK file ends inside $what (value ${i + 1} of $count)"); out[i] = r.float() }
            return out
        }
        val sz = typeSize(type)
        val bytes = readBytes(r, count.toLong() * sz, what)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        decodeFloats(bb, out, count, type, sz)
        return out
    }

    private fun readInts(r: FastReader, count: Int, type: String, binary: Boolean, what: String): IntArray {
        val out = IntArray(count)
        if (!binary) {
            for (i in 0 until count) { r.skipWhitespace(); if (r.atEnd()) throw ImportException("VTK file ends inside $what (value ${i + 1} of $count)"); out[i] = r.int() }
            return out
        }
        val sz = typeSize(type)
        val bytes = readBytes(r, count.toLong() * sz, what)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        decodeInts(bb, out, count, type, sz)
        return out
    }

    private fun readBytes(r: FastReader, n: Long, what: String): ByteArray {
        if (n > Int.MAX_VALUE - 16) throw ImportException("$what is too large ($n bytes)")
        val bytes = ByteArray(n.toInt())
        try { r.readFully(bytes, 0, bytes.size) } catch (e: java.io.EOFException) { throw ImportException("VTK file is truncated inside $what") }
        return bytes
    }

    private fun decodeFloats(bb: ByteBuffer, out: FloatArray, count: Int, type: String, sz: Int) {
        when (type) {
            "float", "float32" -> for (i in 0 until count) out[i] = bb.getFloat(i * 4)
            "double", "float64" -> for (i in 0 until count) out[i] = bb.getDouble(i * 8).toFloat()
            else -> { val tmp = IntArray(count); decodeInts(bb, tmp, count, type, sz); for (i in 0 until count) out[i] = tmp[i].toFloat() }
        }
    }

    private fun decodeInts(bb: ByteBuffer, out: IntArray, count: Int, type: String, sz: Int) {
        when (type) {
            "float", "float32" -> for (i in 0 until count) out[i] = bb.getFloat(i * 4).toInt()
            "double", "float64" -> for (i in 0 until count) out[i] = bb.getDouble(i * 8).toInt()
            "char", "vtktypeint8", "int8" -> for (i in 0 until count) out[i] = bb.get(i).toInt()
            "unsigned_char", "vtktypeuint8", "uint8", "bit" -> for (i in 0 until count) out[i] = bb.get(i).toInt() and 0xff
            "short", "vtktypeint16", "int16" -> for (i in 0 until count) out[i] = bb.getShort(i * 2).toInt()
            "unsigned_short", "vtktypeuint16", "uint16" -> for (i in 0 until count) out[i] = bb.getShort(i * 2).toInt() and 0xffff
            "int", "unsigned_int", "vtktypeint32", "vtktypeuint32", "int32", "uint32" -> for (i in 0 until count) out[i] = bb.getInt(i * 4)
            else -> for (i in 0 until count) { val v = bb.getLong(i * 8); out[i] = if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) throw ImportException("Index $v is out of range") else v.toInt() }
        }
    }

    private fun skipField(r: FastReader, binary: Boolean) {
        r.token(); val arrays = r.int(); r.skipLine()
        for (a in 0 until arrays) {
            val name = r.tokenAnyLine(); if (name.isEmpty()) return
            if (name.uppercase() == "METADATA") { skipMetadata(r); continue }
            val comps = r.int(); val tuples = r.int(); val type = r.token().lowercase(); r.skipLine()
            val n = comps.toLong() * tuples
            if (binary) { val sz = typeSize(type); var left = n * sz; val buf = ByteArray(1 shl 16); while (left > 0) { val k = minOf(left, buf.size.toLong()).toInt(); r.readFully(buf, 0, k); left -= k } }
            else { for (i in 0 until n) { r.skipWhitespace(); r.token() } }
            r.skipLine()
        }
    }

    private fun skipMetadata(r: FastReader) {
        // METADATA block: lines until an empty line
        r.skipLine()
        while (!r.atEnd()) { val l = r.readLine() ?: return; if (l.isBlank()) return }
    }

    // ------------------------------------------------------------------ XML format

    private class XmlCtx(val littleEndian: Boolean, val headerSize: Int, val compressed: Boolean, val appended: ByteArray?, val appendedBase64: Boolean)

    private fun readXml(source: ImportSource, progress: ProgressSink, notes: ArrayList<String>): List<Node> {
        val size = source.file.length()
        if (size > 1L shl 30) throw ImportException("VTK XML file is too large to load ($size bytes)")
        val bytes = source.file.readBytes()
        // Split off appended data (may be raw binary that is not valid XML)
        var xmlEnd = bytes.size
        var appended: ByteArray? = null
        var appendedBase64 = false
        val tagPos = indexOf(bytes, "<AppendedData".toByteArray(Charsets.ISO_8859_1), 0)
        if (tagPos >= 0) {
            val tagEnd = indexOf(bytes, ">".toByteArray(Charsets.ISO_8859_1), tagPos)
            if (tagEnd < 0) throw ImportException("VTK XML <AppendedData> tag is not closed")
            val tag = String(bytes, tagPos, tagEnd - tagPos, Charsets.ISO_8859_1)
            appendedBase64 = !tag.contains("\"raw\"")
            var start = tagEnd + 1
            while (start < bytes.size && bytes[start] != '_'.code.toByte()) start++
            start++
            if (start > bytes.size) throw ImportException("VTK XML appended data is missing the '_' marker")
            appended = bytes.copyOfRange(start, bytes.size)
            xmlEnd = tagPos
        }
        val xmlBytes = if (xmlEnd == bytes.size) bytes else (String(bytes, 0, xmlEnd, Charsets.ISO_8859_1) + "</VTKFile>").toByteArray(Charsets.ISO_8859_1)
        progress.progress(0.1f, "Parsing XML")
        val doc = T.parseDom(ByteArrayInputStream(xmlBytes), "VTK XML file")
        val root = doc.documentElement ?: throw ImportException("VTK XML file is empty")
        if (T.localName(root) != "VTKFile") throw ImportException("Not a VTK XML file (root element is <${root.nodeName}>, expected <VTKFile>)")
        val type = T.attr(root, "type") ?: ""
        val compressor = T.attr(root, "compressor")
        if (compressor != null && compressor.isNotEmpty() && compressor != "vtkZLibDataCompressor") throw ImportException("VTK XML compressor '$compressor' is not supported (only zlib)")
        val ctx = XmlCtx(T.attr(root, "byte_order") != "BigEndian", if (T.attr(root, "header_type") == "UInt64") 8 else 4, !compressor.isNullOrEmpty(), appended, appendedBase64)
        val grid = T.child(root, type) ?: T.child(root, "PolyData") ?: T.child(root, "UnstructuredGrid")
            ?: throw ImportException(if (type.startsWith("P")) "Parallel VTK files ($type) are not supported; open one of the pieces instead" else "VTK XML type '$type' is not supported (only PolyData and UnstructuredGrid)")
        val gridKind = T.localName(grid)
        if (gridKind != "PolyData" && gridKind != "UnstructuredGrid") throw ImportException("VTK XML type '$gridKind' is not supported (only PolyData and UnstructuredGrid)")
        notes.add("VTK XML $gridKind" + (if (ctx.compressed) " (zlib)" else ""))
        val pieces = T.children(grid, "Piece")
        if (pieces.isEmpty()) throw ImportException("VTK XML file has no <Piece> element")
        val nodes = ArrayList<Node>()
        for ((pi, piece) in pieces.withIndex()) {
            progress.progress(0.2f + 0.7f * pi / pieces.size, "Reading piece ${pi + 1}")
            progress.checkCancelled()
            val pointsEl = T.child(piece, "Points")?.let { T.child(it, "DataArray") } ?: throw ImportException("VTK piece ${pi + 1} has no <Points>")
            val positions = floatsOf(pointsEl, ctx, "Points")
            val comps = T.attrInt(pointsEl, "NumberOfComponents", 3)
            val pos = if (comps == 3) positions else { val n = positions.size / comps; FloatArray(n * 3).also { for (i in 0 until n) for (c in 0 until minOf(3, comps)) it[i * 3 + c] = positions[i * comps + c] } }
            val bd = Builder(pos.size / 3); bd.positions = pos
            if (gridKind == "PolyData") {
                for (sec in listOf("Polys", "Strips", "Lines")) {
                    val el = T.child(piece, sec) ?: continue
                    val conn = dataArray(el, "connectivity") ?: continue
                    val offs = dataArray(el, "offsets") ?: continue
                    val c = intsOf(conn, ctx, "$sec connectivity"); val o = intsOf(offs, ctx, "$sec offsets")
                    var s = 0
                    for ((ci, e) in o.withIndex()) {
                        if (e < s || e > c.size) throw ImportException("$sec offsets are invalid at cell $ci")
                        when (sec) { "Polys" -> bd.polygon(c, s, e - s); "Strips" -> bd.strip(c, s, e - s); "Lines" -> bd.line(c, s, e - s) }
                        s = e
                        if ((ci and 0x3fff) == 0) progress.checkCancelled()
                    }
                }
            } else {
                val el = T.child(piece, "Cells") ?: continue
                val c = intsOf(dataArray(el, "connectivity") ?: continue, ctx, "Cells connectivity")
                val o = intsOf(dataArray(el, "offsets") ?: continue, ctx, "Cells offsets")
                val t = intsOf(dataArray(el, "types") ?: continue, ctx, "Cells types")
                var s = 0
                for ((ci, e) in o.withIndex()) {
                    if (e < s || e > c.size) throw ImportException("Cells offsets are invalid at cell $ci")
                    bd.cell(if (ci < t.size) t[ci] else 0, c, s, e - s)
                    s = e
                    if ((ci and 0x3fff) == 0) progress.checkCancelled()
                }
            }
            if (bd.unsupported > 0) notes.add("${bd.unsupported} cells of unsupported types were skipped")
            if (bd.polyhedra > 0) notes.add("${bd.polyhedra} polyhedron cells were skipped")
            val name = if (pieces.size == 1) source.displayName.substringBeforeLast('.') else "Piece ${pi + 1}"
            nodes.add(Node(name, bd.mesh()))
        }
        return nodes
    }

    private fun dataArray(parent: Element, name: String): Element? = T.children(parent, "DataArray").firstOrNull { T.attr(it, "Name") == name }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = maxOf(0, from)
        val last = hay.size - needle.size
        val first = needle[0]
        while (i <= last) {
            if (hay[i] == first) { var k = 1; while (k < needle.size && hay[i + k] == needle[k]) k++; if (k == needle.size) return i }
            i++
        }
        return -1
    }

    private fun floatsOf(el: Element, ctx: XmlCtx, what: String): FloatArray {
        val type = T.attr(el, "type") ?: "Float32"
        val format = T.attr(el, "format") ?: "ascii"
        if (format == "ascii") return T.floatArray(T.text(el), what)
        val raw = binaryPayload(el, ctx, format, what)
        val sz = xmlTypeSize(type)
        val count = raw.size / sz
        val bb = ByteBuffer.wrap(raw).order(if (ctx.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val out = FloatArray(count)
        decodeFloats(bb, out, count, xmlTypeName(type), sz)
        return out
    }

    private fun intsOf(el: Element, ctx: XmlCtx, what: String): IntArray {
        val type = T.attr(el, "type") ?: "Int32"
        val format = T.attr(el, "format") ?: "ascii"
        if (format == "ascii") return T.intArray(T.text(el), what)
        val raw = binaryPayload(el, ctx, format, what)
        val sz = xmlTypeSize(type)
        val count = raw.size / sz
        val bb = ByteBuffer.wrap(raw).order(if (ctx.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val out = IntArray(count)
        decodeInts(bb, out, count, xmlTypeName(type), sz)
        return out
    }

    private fun xmlTypeSize(t: String): Int = when (t) {
        "Int8", "UInt8" -> 1; "Int16", "UInt16" -> 2; "Int32", "UInt32", "Float32" -> 4; "Int64", "UInt64", "Float64" -> 8
        else -> throw ImportException("Unknown VTK XML data type '$t'")
    }
    private fun xmlTypeName(t: String): String = when (t) {
        "Int8" -> "int8"; "UInt8" -> "uint8"; "Int16" -> "int16"; "UInt16" -> "uint16"; "Int32" -> "int32"; "UInt32" -> "uint32"
        "Float32" -> "float32"; "Float64" -> "float64"; else -> "int64"
    }

    /** Decoded, decompressed payload bytes of a binary (inline base64) or appended data array. */
    private fun binaryPayload(el: Element, ctx: XmlCtx, format: String, what: String): ByteArray {
        return when (format) {
            "binary" -> decodeBase64Block(T.text(el).trim(), 0, ctx, what)
            "appended" -> {
                val app = ctx.appended ?: throw ImportException("$what uses appended data but the file has no <AppendedData> section")
                val offset = T.attr(el, "offset")?.trim()?.toLongOrNull() ?: throw ImportException("$what has no offset into the appended data")
                if (offset < 0 || offset >= app.size) throw ImportException("$what offset $offset is outside the appended data (${app.size} bytes)")
                if (ctx.appendedBase64) decodeBase64Block(String(app, Charsets.ISO_8859_1), offset.toInt(), ctx, what)
                else rawBlock(app, offset.toInt(), ctx, what)
            }
            else -> throw ImportException("$what has unsupported format '$format'")
        }
    }

    private fun headerValue(bb: ByteBuffer, index: Int, hs: Int): Long = if (hs == 8) bb.getLong(index * 8) else (bb.getInt(index * 4).toLong() and 0xffffffffL)

    private fun rawBlock(app: ByteArray, offset: Int, ctx: XmlCtx, what: String): ByteArray {
        val order = if (ctx.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val hs = ctx.headerSize
        if (offset + hs > app.size) throw ImportException("$what appended data is truncated")
        if (!ctx.compressed) {
            val n = headerValue(ByteBuffer.wrap(app, offset, hs).slice().order(order), 0, hs)
            if (n < 0 || offset + hs + n > app.size) throw ImportException("$what appended data is truncated ($n bytes declared)")
            return app.copyOfRange(offset + hs, offset + hs + n.toInt())
        }
        val h0 = ByteBuffer.wrap(app, offset, 3 * hs).slice().order(order)
        val nblocks = headerValue(h0, 0, hs); val blockSize = headerValue(h0, 1, hs); val lastSize = headerValue(h0, 2, hs)
        if (nblocks < 0 || nblocks > 10_000_000) throw ImportException("$what has an invalid compressed block count")
        val hdrBytes = (3 + nblocks.toInt()) * hs
        if (offset + hdrBytes > app.size) throw ImportException("$what appended data is truncated")
        val hb = ByteBuffer.wrap(app, offset, hdrBytes).slice().order(order)
        val sizes = LongArray(nblocks.toInt()) { headerValue(hb, 3 + it, hs) }
        return inflateBlocks(app, offset + hdrBytes, sizes, blockSize, lastSize, what)
    }

    private fun decodeBase64Block(text: String, offset: Int, ctx: XmlCtx, what: String): ByteArray {
        val order = if (ctx.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val hs = ctx.headerSize
        val dec = Base64.getMimeDecoder()
        fun b64len(bytes: Int) = (bytes + 2) / 3 * 4
        fun chunk(from: Int, chars: Int): ByteArray {
            val end = minOf(text.length, from + chars)
            if (from >= text.length) throw ImportException("$what base64 data is truncated")
            return try { dec.decode(text.substring(from, end)) } catch (e: IllegalArgumentException) { throw ImportException("$what contains invalid base64 data") }
        }
        if (!ctx.compressed) {
            val hdr = chunk(offset, b64len(hs))
            if (hdr.size < hs) throw ImportException("$what base64 data is truncated")
            val n = headerValue(ByteBuffer.wrap(hdr).order(order), 0, hs)
            if (n < 0 || n > Int.MAX_VALUE - 16) throw ImportException("$what declares an invalid size $n")
            val data = chunk(offset + b64len(hs), b64len(n.toInt()))
            if (data.size < n) throw ImportException("$what base64 data is truncated (${data.size} of $n bytes)")
            return if (data.size == n.toInt()) data else data.copyOf(n.toInt())
        }
        val h0 = chunk(offset, b64len(3 * hs))
        if (h0.size < 3 * hs) throw ImportException("$what base64 header is truncated")
        val hb0 = ByteBuffer.wrap(h0).order(order)
        val nblocks = headerValue(hb0, 0, hs); val blockSize = headerValue(hb0, 1, hs); val lastSize = headerValue(hb0, 2, hs)
        if (nblocks < 0 || nblocks > 10_000_000) throw ImportException("$what has an invalid compressed block count")
        val hdrBytes = (3 + nblocks.toInt()) * hs
        val hdr = chunk(offset, b64len(hdrBytes))
        if (hdr.size < hdrBytes) throw ImportException("$what base64 header is truncated")
        val hb = ByteBuffer.wrap(hdr).order(order)
        val sizes = LongArray(nblocks.toInt()) { headerValue(hb, 3 + it, hs) }
        var total = 0L; for (s in sizes) total += s
        if (total > Int.MAX_VALUE - 16) throw ImportException("$what compressed data is too large")
        val data = chunk(offset + b64len(hdrBytes), b64len(total.toInt()))
        return inflateBlocks(data, 0, sizes, blockSize, lastSize, what)
    }

    private fun inflateBlocks(src: ByteArray, start: Int, sizes: LongArray, blockSize: Long, lastSize: Long, what: String): ByteArray {
        if (blockSize < 0 || blockSize > Int.MAX_VALUE || lastSize < 0 || lastSize > blockSize) throw ImportException("$what has invalid compressed block sizes")
        val n = sizes.size
        val totalOut = if (n == 0) 0L else (n - 1) * blockSize + (if (lastSize == 0L) blockSize else lastSize)
        if (totalOut > Int.MAX_VALUE - 16) throw ImportException("$what decompresses to more than 2 GB")
        val out = ByteArray(totalOut.toInt())
        var inPos = start; var outPos = 0
        val inflater = Inflater()
        try {
            for (b in 0 until n) {
                val cs = sizes[b]
                if (cs < 0 || inPos + cs > src.size) throw ImportException("$what compressed data is truncated")
                val expect = if (b == n - 1 && lastSize != 0L) lastSize.toInt() else blockSize.toInt()
                inflater.reset()
                inflater.setInput(src, inPos, cs.toInt())
                var got = 0
                while (got < expect && !inflater.finished()) {
                    val k = try { inflater.inflate(out, outPos + got, expect - got) } catch (e: java.util.zip.DataFormatException) { throw ImportException("$what contains corrupt zlib data: ${e.message}") }
                    if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    got += k
                }
                if (got != expect) throw ImportException("$what zlib block $b decompressed to $got bytes, expected $expect")
                inPos += cs.toInt(); outPos += expect
            }
        } finally { inflater.end() }
        return out
    }
}
