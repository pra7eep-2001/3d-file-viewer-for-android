package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import java.io.BufferedInputStream
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ASCII DXF importer. Surfaces: 3DFACE, SOLID/TRACE, POLYLINE polyface and polygon meshes, MESH. Curves shown
 * as edges: LINE, 2D/3D POLYLINE, LWPOLYLINE (with bulges), CIRCLE, ARC, ELLIPSE, SPLINE (evaluated).
 * INSERTs of BLOCKs (with scale, rotation, arrays, nesting) are expanded. One node per layer. Units from $INSUNITS.
 * Binary DXF and ACIS solids (3DSOLID/BODY/REGION) are not supported.
 */
class DxfImporter : Importer {
    override val formatName = "DXF"
    override val extensions = listOf("dxf")

    /** Geometry soups of a layer or block, in the owner's coordinate system. */
    private class Sink {
        val tris = FloatList(1 shl 10)         // 9 floats per triangle
        val triLayer = ArrayList<String>()      // only used for blocks
        val edges = FloatList(1 shl 8)         // 6 floats per segment
        val edgeLayer = ArrayList<String>()
    }
    private class Insert(val block: String, val layer: String, val m: Mat4)
    private class Block(val name: String) { val sink = Sink(); val inserts = ArrayList<Insert>(); var bx = 0f; var by = 0f; var bz = 0f }

    /** Reusable entity record: group codes of the entity being read. */
    private class Ent {
        var type = ""; var layer = "0"; var name = ""
        val px = FloatArray(10); val py = FloatArray(10); val pz = FloatArray(10); val has = BooleanArray(10)
        val x = FloatList(16); val y = FloatList(16); val z = FloatList(16)           // repeated code 10/20/30
        val x1 = FloatList(4); val y1 = FloatList(4); val z1 = FloatList(4)           // repeated code 11/21/31
        val bulge = FloatList(16)
        val ints = IntArray(40); val hasInt = BooleanArray(40)                        // codes 60..99
        val dbl = FloatArray(30)                                                        // codes 38..67 -> 38..59 used
        val list90 = IntList(16); val knots = FloatList(16); val weights = FloatList(4)
        var nx = 0f; var ny = 0f; var nz = 1f
        fun clear() {
            type = ""; layer = "0"; name = ""
            java.util.Arrays.fill(has, false); java.util.Arrays.fill(hasInt, false); java.util.Arrays.fill(ints, 0); java.util.Arrays.fill(dbl, 0f); java.util.Arrays.fill(dblSet, false)
            java.util.Arrays.fill(px, 0f); java.util.Arrays.fill(py, 0f); java.util.Arrays.fill(pz, 0f)
            x.clear(); y.clear(); z.clear(); x1.clear(); y1.clear(); z1.clear(); bulge.clear(); list90.clear(); knots.clear(); weights.clear()
            nx = 0f; ny = 0f; nz = 1f
        }
        fun int(code: Int, default: Int = 0): Int = if (hasInt[code - 60]) ints[code - 60] else default
        val dblSet = BooleanArray(30)
        fun d(code: Int, default: Float = 0f): Float = if (code in 38..59 && dblSet[code - 38]) dbl[code - 38] else default
        fun flags() = int(70)
    }

    private class State {
        val layers = LinkedHashMap<String, Sink>()
        val blocks = HashMap<String, Block>()
        var block: Block? = null
        val notes = LinkedHashSet<String>()
        var units = ModelUnits.UNKNOWN
        var acis = 0; var annotations = 0; var unknown = 0
        var triCount = 0L
        // polyline accumulation
        var poly: Ent? = null; var polyLayer = "0"
        val pv = FloatList(64); val pvFlags = IntList(16); val pvBulge = FloatList(16); val pFaces = IntList(64)
        var polyNx = 0f; var polyNy = 0f; var polyNz = 1f
        fun layer(name: String): Sink = layers.getOrPut(name) { Sink() }
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val head = ByteArray(32)
        val n = FileInputStream(source.file).use { it.read(head) }
        if (n > 0 && String(head, 0, n, Charsets.ISO_8859_1).startsWith("AutoCAD Binary DXF")) throw ImportException("Binary DXF files are not supported; save the drawing as ASCII DXF")
        val st = State()
        T.guard("DXF file") { read(source, st, progress) }
        progress.progress(0.9f, "Building meshes")
        val nodes = ArrayList<Node>()
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        for ((layer, sink) in st.layers) {
            if (sink.tris.size == 0 && sink.edges.size == 0) continue
            val mesh: Mesh
            val triPos: FloatArray; val triIdx: IntArray
            if (sink.tris.size > 0) { val m = MeshOps.fromSoup(sink.tris.toArray()); triPos = m.positions; triIdx = m.indices } else { triPos = FloatArray(0); triIdx = IntArray(0) }
            var edgePos: FloatArray? = null; var edgeIdx: IntArray? = null
            if (sink.edges.size > 0) { val (p, remap) = MeshOps.weld(sink.edges.toArray()); edgePos = p; edgeIdx = remap }
            mesh = Mesh(triPos, null, triIdx, edgePos, edgeIdx)
            val ep = sink.edges; var i = 2; while (i < ep.size) { val zz = ep[i]; if (zz < minZ) minZ = zz; if (zz > maxZ) maxZ = zz; i += 3 }
            nodes.add(Node(layer, mesh))
            progress.checkCancelled()
        }
        if (nodes.isEmpty()) {
            if (st.acis > 0) throw ImportException("The DXF contains only ACIS solids (3DSOLID/BODY/REGION) which are not supported; export the solids as 3DFACE meshes, STL or STEP")
            throw ImportException("The DXF file contains no geometry (no faces, meshes or lines)")
        }
        if (st.triCount == 0L && maxZ - minZ == 0f) st.notes.add("2D drawing: only lines, no surfaces (shown as edges)")
        if (st.acis > 0) st.notes.add("${st.acis} ACIS solid entities (3DSOLID/BODY/REGION) skipped; not supported")
        if (st.annotations > 0) st.notes.add("${st.annotations} text/dimension/hatch entities ignored")
        return Model(nodes, formatName, source.displayName, source.sizeBytes, st.units, st.notes.toList())
    }

    // ------------------------------------------------------------------ reading

    private fun read(source: ImportSource, st: State, progress: ProgressSink) {
        val size = source.file.length().toFloat()
        BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { ins ->
            val r = FastReader(ins)
            var section = ""
            var headerVar = ""
            val ent = Ent()
            var inEntity = false
            var pairs = 0
            while (true) {
                val codeLine = r.readLine() ?: break
                val cs = codeLine.trim()
                if (cs.isEmpty()) { if (r.atEnd()) break else continue }
                val code = cs.toIntOrNull() ?: throw ImportException("DXF group code expected at line ${r.line - 1} but found '${cs.take(32)}'")
                val value = (r.readLine() ?: throw ImportException("DXF file ends after group code $code at line ${r.line}")).trim()
                if ((++pairs and 0x3fff) == 0) { progress.progress(0.85f * r.bytesRead / size, "Reading entities"); progress.checkCancelled() }
                if (code == 0) {
                    if (inEntity) { dispatch(ent, st, r.line); inEntity = false }
                    when (value) {
                        "SECTION" -> { section = ""; continue }
                        "ENDSEC" -> { section = ""; continue }
                        "EOF" -> break
                    }
                    if (section == "ENTITIES" || section == "BLOCKS") {
                        ent.clear(); ent.type = value; inEntity = true
                    }
                    continue
                }
                if (code == 2 && section == "" ) { section = value; continue }
                when (section) {
                    "HEADER" -> {
                        if (code == 9) headerVar = value
                        else if (code == 70 && headerVar == "\$INSUNITS") st.units = when (value.toIntOrNull()) { 1 -> ModelUnits.INCH; 2 -> ModelUnits.FOOT; 4 -> ModelUnits.MILLIMETER; 5 -> ModelUnits.CENTIMETER; 6 -> ModelUnits.METER; else -> ModelUnits.UNKNOWN }
                    }
                    "ENTITIES", "BLOCKS" -> if (inEntity) collect(ent, code, value, r.line)
                }
            }
            if (inEntity) dispatch(ent, st, r.line)
        }
        if (st.poly != null) finishPolyline(st)
    }

    private fun num(value: String, code: Int, line: Int): Float = value.toFloatOrNull() ?: throw ImportException("Invalid number '${value.take(32)}' for group code $code at line $line")

    private fun collect(e: Ent, code: Int, value: String, line: Int) {
        when (code) {
            8 -> e.layer = value
            2 -> e.name = value
            in 10..19 -> { val v = num(value, code, line); val i = code - 10; if (!e.has[i]) { e.px[i] = v; e.has[i] = true }; if (i == 0) { e.x.add(v); e.bulge.add(0f) } else if (i == 1) e.x1.add(v) }
            in 20..29 -> { val v = num(value, code, line); val i = code - 20; e.py[i] = v; if (i == 0) e.y.add(v) else if (i == 1) e.y1.add(v) }
            in 30..37 -> { val v = num(value, code, line); val i = code - 30; e.pz[i] = v; if (i == 0) e.z.add(v) else if (i == 1) e.z1.add(v) }
            38, 39 -> { e.dbl[code - 38] = num(value, code, line); e.dblSet[code - 38] = true }
            40 -> { val v = num(value, code, line); if (e.type == "SPLINE") e.knots.add(v) else { e.dbl[2] = v; e.dblSet[2] = true } }
            41 -> { val v = num(value, code, line); if (e.type == "SPLINE") e.weights.add(v) else { e.dbl[3] = v; e.dblSet[3] = true } }
            42 -> { val v = num(value, code, line); if (e.bulge.size > 0) e.bulge.data[e.bulge.size - 1] = v; e.dbl[4] = v; e.dblSet[4] = true }
            in 43..59 -> { e.dbl[code - 38] = num(value, code, line); e.dblSet[code - 38] = true }
            in 60..99 -> { val v = value.toIntOrNull() ?: num(value, code, line).toInt(); if (code == 90) e.list90.add(v); e.ints[code - 60] = v; e.hasInt[code - 60] = true }
            210 -> e.nx = num(value, code, line)
            220 -> e.ny = num(value, code, line)
            230 -> e.nz = num(value, code, line)
        }
    }

    // ------------------------------------------------------------------ entity dispatch

    private fun sinkFor(st: State, layer: String): Sink = st.block?.sink ?: st.layer(layer)

    private fun dispatch(e: Ent, st: State, line: Int) {
        when (e.type) {
            "BLOCK" -> { if (st.poly != null) finishPolyline(st); val b = Block(e.name); b.bx = e.px[0]; b.by = e.py[0]; b.bz = e.pz[0]; st.blocks[e.name] = b; st.block = b }
            "ENDBLK" -> { if (st.poly != null) finishPolyline(st); st.block = null }
            "VERTEX" -> {
                val p = st.poly ?: return
                if ((p.flags() and 64) != 0 && (e.flags() and 128) != 0 && (e.flags() and 64) == 0) {
                    // polyface face record (no coordinates)
                    st.pFaces.add(e.int(71), e.int(72), e.int(73)); st.pFaces.add(e.int(74))
                } else { st.pv.add(e.px[0], e.py[0], e.pz[0]); st.pvFlags.add(e.flags()); st.pvBulge.add(e.d(42)) }
            }
            "SEQEND" -> if (st.poly != null) finishPolyline(st)
            "POLYLINE" -> {
                if (st.poly != null) finishPolyline(st)
                val p = Ent(); copyInto(e, p)
                st.poly = p; st.polyLayer = e.layer; st.pv.clear(); st.pvFlags.clear(); st.pvBulge.clear(); st.pFaces.clear()
                st.polyNx = e.nx; st.polyNy = e.ny; st.polyNz = e.nz
            }
            "3DFACE" -> face(e, st, intArrayOf(0, 1, 2, 3))
            "SOLID", "TRACE" -> face(e, st, intArrayOf(0, 1, 3, 2))
            "LINE" -> { val s = sinkFor(st, e.layer); addEdge(s, e.layer, e.px[0], e.py[0], e.pz[0], e.px[1], e.py[1], e.pz[1]) }
            "LWPOLYLINE" -> lwpolyline(e, st)
            "CIRCLE" -> arc(e, st, 0f, 360f)
            "ARC" -> arc(e, st, e.d(50), e.d(51, 360f))
            "ELLIPSE" -> ellipse(e, st)
            "SPLINE" -> spline(e, st)
            "MESH" -> meshEntity(e, st, line)
            "INSERT" -> insert(e, st)
            "3DSOLID", "BODY", "REGION", "SURFACE", "ACAD_PROXY_ENTITY" -> st.acis++
            "TEXT", "MTEXT", "DIMENSION", "HATCH", "ATTRIB", "ATTDEF", "LEADER", "MLEADER", "TOLERANCE" -> st.annotations++
            "POINT", "VIEWPORT", "XLINE", "RAY", "IMAGE", "OLE2FRAME", "WIPEOUT", "LIGHT", "HELIX", "TABLE" -> {}
            else -> st.unknown++
        }
    }

    private fun copyInto(src: Ent, dst: Ent) {
        dst.type = src.type; dst.layer = src.layer; dst.name = src.name
        System.arraycopy(src.ints, 0, dst.ints, 0, src.ints.size); System.arraycopy(src.hasInt, 0, dst.hasInt, 0, src.hasInt.size)
        System.arraycopy(src.px, 0, dst.px, 0, 10); System.arraycopy(src.py, 0, dst.py, 0, 10); System.arraycopy(src.pz, 0, dst.pz, 0, 10)
        dst.nx = src.nx; dst.ny = src.ny; dst.nz = src.nz
    }

    // ------------------------------------------------------------------ geometry emission

    private fun addTri(s: Sink, layer: String, st: State, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        if ((x0 == x1 && y0 == y1 && z0 == z1) || (x1 == x2 && y1 == y2 && z1 == z2) || (x0 == x2 && y0 == y2 && z0 == z2)) return
        s.tris.add(x0, y0, z0); s.tris.add(x1, y1, z1); s.tris.add(x2, y2, z2)
        if (st.block != null) s.triLayer.add(layer) else st.triCount++
    }

    private fun addEdge(s: Sink, layer: String, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        if (x0 == x1 && y0 == y1 && z0 == z1) return
        s.edges.add(x0, y0, z0); s.edges.add(x1, y1, z1)
        s.edgeLayer.add(layer)
    }

    /** Emits a quad (or triangle when two corners coincide) from a point array (xyz per corner). */
    private fun quad(s: Sink, layer: String, st: State, p: FloatArray) {
        val tri = (p[6] == p[9] && p[7] == p[10] && p[8] == p[11])
        if (tri) { addTri(s, layer, st, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]); return }
        val out = IntList(6)
        MeshOps.triangulatePolygon(intArrayOf(0, 1, 2, 3), p, out)
        var i = 0
        while (i < out.size) { val a = out[i] * 3; val b = out[i + 1] * 3; val c = out[i + 2] * 3; addTri(s, layer, st, p[a], p[a + 1], p[a + 2], p[b], p[b + 1], p[b + 2], p[c], p[c + 1], p[c + 2]); i += 3 }
    }

    private fun face(e: Ent, st: State, order: IntArray) {
        val s = sinkFor(st, e.layer)
        val p = FloatArray(12)
        for ((k, idx) in order.withIndex()) {
            val src = if (e.has[idx]) idx else 2 // missing 4th corner -> triangle
            p[k * 3] = e.px[src]; p[k * 3 + 1] = e.py[src]; p[k * 3 + 2] = e.pz[src]
        }
        if (e.type != "3DFACE" && (e.nx != 0f || e.ny != 0f || e.nz != 1f)) ocsToWcs(p, e.nx, e.ny, e.nz)
        quad(s, e.layer, st, p)
    }

    /** Arbitrary axis algorithm: converts OCS points (xyz triples) to WCS in place. */
    private fun ocsToWcs(p: FloatArray, nx: Float, ny: Float, nz: Float, count: Int = p.size / 3) {
        if (nx == 0f && ny == 0f && nz == 1f) return
        val ax: FloatArray; val ay: FloatArray
        val l = sqrt(nx * nx + ny * ny + nz * nz); if (l < 1e-12f) return
        val n = floatArrayOf(nx / l, ny / l, nz / l)
        ax = if (abs(n[0]) < 1f / 64 && abs(n[1]) < 1f / 64) cross(0f, 1f, 0f, n[0], n[1], n[2]) else cross(0f, 0f, 1f, n[0], n[1], n[2])
        normalize(ax)
        ay = cross(n[0], n[1], n[2], ax[0], ax[1], ax[2]); normalize(ay)
        for (i in 0 until count) {
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            p[i * 3] = ax[0] * x + ay[0] * y + n[0] * z
            p[i * 3 + 1] = ax[1] * x + ay[1] * y + n[1] * z
            p[i * 3 + 2] = ax[2] * x + ay[2] * y + n[2] * z
        }
    }
    private fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) = floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    private fun normalize(v: FloatArray) { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); if (l > 1e-20f) { v[0] /= l; v[1] /= l; v[2] /= l } }

    /** Emits a polyline (OCS xyz triples) as edges, with optional bulge arcs between consecutive vertices. */
    private fun polylineEdges(s: Sink, layer: String, pts: FloatArray, bulges: FloatArray?, closed: Boolean, nx: Float, ny: Float, nz: Float) {
        val n = pts.size / 3
        if (n < 2) return
        val out = FloatList(n * 3 + 16)
        for (i in 0 until n) {
            val j = if (i == n - 1) (if (closed) 0 else -1) else i + 1
            out.add(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
            if (j < 0) break
            val b = bulges?.let { if (i < it.size) it[i] else 0f } ?: 0f
            if (b != 0f) {
                // bulge arc in the OCS XY plane
                val x1 = pts[i * 3]; val y1 = pts[i * 3 + 1]; val x2 = pts[j * 3]; val y2 = pts[j * 3 + 1]; val zz = pts[i * 3 + 2]
                val dx = x2 - x1; val dy = y2 - y1; val d = sqrt(dx * dx + dy * dy)
                if (d > 1e-12f) {
                    val theta = 4.0 * atan(b.toDouble())
                    val sg = abs(b) * d / 2
                    val r = (d * d / 4 + sg * sg) / (2 * sg)
                    val h = r - sg
                    val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                    val ux = dx / d; val uy = dy / d
                    val sign = if (b > 0) 1f else -1f
                    val cx = mx - uy * h * sign; val cy = my + ux * h * sign
                    val a0 = atan2((y1 - cy).toDouble(), (x1 - cx).toDouble())
                    val segs = maxOf(2, (abs(theta) / (2 * Math.PI) * 48).toInt())
                    for (k in 1 until segs) { val a = a0 + theta * k / segs; out.add((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), zz) }
                }
            }
        }
        if (closed) out.add(pts[0], pts[1], pts[2])
        val arr = out.toArray()
        ocsToWcs(arr, nx, ny, nz)
        var i = 3
        while (i < arr.size) { addEdge(s, layer, arr[i - 3], arr[i - 2], arr[i - 1], arr[i], arr[i + 1], arr[i + 2]); i += 3 }
    }

    private fun lwpolyline(e: Ent, st: State) {
        val s = sinkFor(st, e.layer)
        val n = e.x.size
        if (n < 2) return
        val elev = e.d(38)
        val pts = FloatArray(n * 3)
        for (i in 0 until n) { pts[i * 3] = e.x[i]; pts[i * 3 + 1] = if (i < e.y.size) e.y[i] else 0f; pts[i * 3 + 2] = elev }
        polylineEdges(s, e.layer, pts, e.bulge.toArray(), (e.flags() and 1) != 0, e.nx, e.ny, e.nz)
    }

    private fun arc(e: Ent, st: State, startDeg: Float, endDeg: Float) {
        val s = sinkFor(st, e.layer)
        val r = e.d(40); if (r <= 0f) return
        var a0 = Math.toRadians(startDeg.toDouble()); var a1 = Math.toRadians(endDeg.toDouble())
        if (a1 <= a0) a1 += 2 * Math.PI
        val sweep = a1 - a0
        val segs = maxOf(8, (sweep / (2 * Math.PI) * 64).toInt())
        val pts = FloatArray((segs + 1) * 3)
        for (k in 0..segs) { val a = a0 + sweep * k / segs; pts[k * 3] = (e.px[0] + r * cos(a)).toFloat(); pts[k * 3 + 1] = (e.py[0] + r * sin(a)).toFloat(); pts[k * 3 + 2] = e.pz[0] }
        polylineEdges(s, e.layer, pts, null, false, e.nx, e.ny, e.nz)
    }

    private fun ellipse(e: Ent, st: State) {
        val s = sinkFor(st, e.layer)
        // centre 10/20/30 (WCS), major axis endpoint 11/21/31 relative to centre, ratio 40, params 41/42 (radians)
        val mx = e.px[1]; val my = e.py[1]; val mz = e.pz[1]
        val ratio = e.d(40, 1f)
        var p0 = e.d(41).toDouble(); var p1 = e.d(42, (2 * Math.PI).toFloat()).toDouble()
        if (p1 <= p0) p1 += 2 * Math.PI
        // minor axis = N x major
        val minor = cross(e.nx, e.ny, e.nz, mx, my, mz)
        val segs = maxOf(8, ((p1 - p0) / (2 * Math.PI) * 64).toInt())
        val pts = FloatArray((segs + 1) * 3)
        for (k in 0..segs) {
            val a = p0 + (p1 - p0) * k / segs; val c = cos(a).toFloat(); val sn = sin(a).toFloat() * ratio
            pts[k * 3] = e.px[0] + mx * c + minor[0] * sn; pts[k * 3 + 1] = e.py[0] + my * c + minor[1] * sn; pts[k * 3 + 2] = e.pz[0] + mz * c + minor[2] * sn
        }
        polylineEdges(s, e.layer, pts, null, false, 0f, 0f, 1f)
    }

    /** Evaluates a B-spline (control points code 10, knots code 40, degree 71) with de Boor; falls back to the fit points. */
    private fun spline(e: Ent, st: State) {
        val s = sinkFor(st, e.layer)
        val n = e.x.size
        val degree = e.int(71, 3)
        val closed = (e.flags() and 1) != 0
        if (n >= 2 && e.knots.size >= n + degree + 1) {
            val cp = FloatArray(n * 3); for (i in 0 until n) { cp[i * 3] = e.x[i]; cp[i * 3 + 1] = if (i < e.y.size) e.y[i] else 0f; cp[i * 3 + 2] = if (i < e.z.size) e.z[i] else 0f }
            val knots = e.knots.toArray()
            val weights = if (e.weights.size == n) e.weights.toArray() else null
            val samples = maxOf(16, minOf(4096, n * 8))
            val t0 = knots[degree]; val t1 = knots[n]
            val pts = FloatArray((samples + 1) * 3)
            val tmp = FloatArray(4)
            for (k in 0..samples) {
                val t = t0 + (t1 - t0) * k / samples
                deBoor(cp, weights, knots, degree, t.coerceAtMost(t1 - (t1 - t0) * 1e-7f), tmp)
                pts[k * 3] = tmp[0]; pts[k * 3 + 1] = tmp[1]; pts[k * 3 + 2] = tmp[2]
            }
            polylineEdges(s, e.layer, pts, null, closed, 0f, 0f, 1f)
            return
        }
        // no usable knot vector: connect fit points (11) or control points
        val fx = if (e.x1.size >= 2) e.x1 else e.x; val fy = if (e.x1.size >= 2) e.y1 else e.y; val fz = if (e.x1.size >= 2) e.z1 else e.z
        val m = fx.size; if (m < 2) return
        val pts = FloatArray(m * 3); for (i in 0 until m) { pts[i * 3] = fx[i]; pts[i * 3 + 1] = if (i < fy.size) fy[i] else 0f; pts[i * 3 + 2] = if (i < fz.size) fz[i] else 0f }
        polylineEdges(s, e.layer, pts, null, closed, 0f, 0f, 1f)
        st.notes.add("Spline without knots shown as a polyline through its points")
    }

    private fun deBoor(cp: FloatArray, w: FloatArray?, knots: FloatArray, p: Int, t: Float, out: FloatArray) {
        val n = cp.size / 3
        var k = p
        while (k < n - 1 && t >= knots[k + 1]) k++
        if (k < p) k = p
        // homogeneous coordinates
        val d = Array(p + 1) { j -> val i = (k - p + j).coerceIn(0, n - 1); val wi = w?.get(i) ?: 1f; floatArrayOf(cp[i * 3] * wi, cp[i * 3 + 1] * wi, cp[i * 3 + 2] * wi, wi) }
        for (r in 1..p) for (j in p downTo r) {
            val i = k - p + j
            val den = knots[i + p - r + 1] - knots[i]
            val alpha = if (den == 0f) 0f else (t - knots[i]) / den
            for (c in 0 until 4) d[j][c] = (1 - alpha) * d[j - 1][c] + alpha * d[j][c]
        }
        val wv = d[p][3]; val iw = if (wv != 0f) 1f / wv else 1f
        out[0] = d[p][0] * iw; out[1] = d[p][1] * iw; out[2] = d[p][2] * iw
    }

    private fun finishPolyline(st: State) {
        val p = st.poly ?: return
        st.poly = null
        val s = sinkFor(st, st.polyLayer)
        val layer = st.polyLayer
        val flags = p.flags()
        val n = st.pv.size / 3
        val pts = st.pv.toArray()
        when {
            (flags and 64) != 0 -> { // polyface mesh: faces reference vertices 1-based, negative = invisible edge
                val f = st.pFaces
                var i = 0
                while (i + 3 < f.size) {
                    val idx = IntArray(4) { abs(f[i + it]) }
                    val count = if (idx[3] == 0) 3 else 4
                    val q = FloatArray(12)
                    var ok = true
                    for (k in 0 until count) { val v = idx[k] - 1; if (v < 0 || v >= n) { ok = false; break }; q[k * 3] = pts[v * 3]; q[k * 3 + 1] = pts[v * 3 + 1]; q[k * 3 + 2] = pts[v * 3 + 2] }
                    if (ok) { if (count == 3) { q[9] = q[6]; q[10] = q[7]; q[11] = q[8] }; quad(s, layer, st, q) }
                    i += 4
                }
            }
            (flags and 16) != 0 -> { // polygon mesh M x N
                val m = p.int(71); val nn = p.int(72)
                if (m >= 2 && nn >= 2 && m.toLong() * nn <= n) {
                    val closedM = (flags and 1) != 0; val closedN = (flags and 32) != 0
                    val rows = if (closedM) m else m - 1; val cols = if (closedN) nn else nn - 1
                    for (i in 0 until rows) for (j in 0 until cols) {
                        val i1 = (i + 1) % m; val j1 = (j + 1) % nn
                        val a = i * nn + j; val b = i * nn + j1; val c = i1 * nn + j1; val d = i1 * nn + j
                        val q = floatArrayOf(pts[a * 3], pts[a * 3 + 1], pts[a * 3 + 2], pts[b * 3], pts[b * 3 + 1], pts[b * 3 + 2], pts[c * 3], pts[c * 3 + 1], pts[c * 3 + 2], pts[d * 3], pts[d * 3 + 1], pts[d * 3 + 2])
                        quad(s, layer, st, q)
                    }
                }
            }
            else -> { // 2D (OCS, with bulges) or 3D polyline
                val is3d = (flags and 8) != 0
                var sel = pts; var bul: FloatArray? = if (is3d) null else st.pvBulge.toArray()
                if ((flags and 4) != 0 || (flags and 2) != 0) {
                    // curve/spline fit: prefer the fit vertices (flag 8) when present, else skip spline frame points (flag 16)
                    val keep = IntList(n)
                    for (i in 0 until n) if ((st.pvFlags[i] and 8) != 0) keep.add(i)
                    if (keep.size < 2) { keep.clear(); for (i in 0 until n) if ((st.pvFlags[i] and 16) == 0) keep.add(i) }
                    val np = FloatArray(keep.size * 3); val nb = FloatArray(keep.size)
                    for (k in 0 until keep.size) { val i = keep[k]; np[k * 3] = pts[i * 3]; np[k * 3 + 1] = pts[i * 3 + 1]; np[k * 3 + 2] = pts[i * 3 + 2]; nb[k] = st.pvBulge[i] }
                    sel = np; bul = if (is3d) null else nb
                }
                if (is3d) polylineEdges(s, layer, sel, null, (flags and 1) != 0, 0f, 0f, 1f)
                else polylineEdges(s, layer, sel, bul, (flags and 1) != 0, st.polyNx, st.polyNy, st.polyNz)
            }
        }
    }

    private fun meshEntity(e: Ent, st: State, line: Int) {
        val s = sinkFor(st, e.layer)
        val vc = e.int(92)
        val n = e.x.size
        if (n < 3) return
        if (vc > n) throw ImportException("MESH entity declares $vc vertices but has only $n (line $line)")
        val list = e.list90
        // list90 holds: [subdivision level? no: 90 after 91 is...] -> the face list follows code 93; use the 93 count
        val faceListSize = e.int(93)
        if (faceListSize <= 0) return
        // the 90 codes collected in order: everything before the face list belongs to other fields (none use 90 besides faces/edges)
        var i = 0
        var faces = 0
        while (i < list.size && i < faceListSize) {
            val cnt = list[i++]
            if (cnt <= 0 || i + cnt > list.size) break
            val poly = IntArray(cnt)
            var ok = true
            for (k in 0 until cnt) { val v = list[i + k]; if (v < 0 || v >= n) { ok = false }; poly[k] = v }
            i += cnt
            if (!ok) throw ImportException("MESH face $faces references a vertex outside 0..${n - 1} (line $line)")
            faces++
            if (cnt == 3) addTri(s, e.layer, st, e.x[poly[0]], e.y[poly[0]], e.z[poly[0]], e.x[poly[1]], e.y[poly[1]], e.z[poly[1]], e.x[poly[2]], e.y[poly[2]], e.z[poly[2]])
            else if (cnt == 4) quad(s, e.layer, st, floatArrayOf(e.x[poly[0]], e.y[poly[0]], e.z[poly[0]], e.x[poly[1]], e.y[poly[1]], e.z[poly[1]], e.x[poly[2]], e.y[poly[2]], e.z[poly[2]], e.x[poly[3]], e.y[poly[3]], e.z[poly[3]]))
            else {
                val pos = FloatArray(cnt * 3); for (k in 0 until cnt) { pos[k * 3] = e.x[poly[k]]; pos[k * 3 + 1] = e.y[poly[k]]; pos[k * 3 + 2] = e.z[poly[k]] }
                val out = IntList(cnt * 3); MeshOps.triangulatePolygon(IntArray(cnt) { it }, pos, out)
                var t = 0; while (t < out.size) { val a = out[t] * 3; val b = out[t + 1] * 3; val c = out[t + 2] * 3; addTri(s, e.layer, st, pos[a], pos[a + 1], pos[a + 2], pos[b], pos[b + 1], pos[b + 2], pos[c], pos[c + 1], pos[c + 2]); t += 3 }
            }
        }
    }

    // ------------------------------------------------------------------ blocks

    private fun insert(e: Ent, st: State) {
        val name = e.name
        val sx = e.d(41, 1f); val sy = e.d(42, 1f); val sz = e.d(43, 1f)
        val rot = Math.toRadians(e.d(50).toDouble()).toFloat()
        val cols = maxOf(1, e.int(70, 1)); val rows = maxOf(1, e.int(71, 1))
        val colSp = e.d(44); val rowSp = e.d(45)
        val ocs = ocsMatrix(e.nx, e.ny, e.nz)
        for (r in 0 until rows) for (c in 0 until cols) {
            var m = ocs.times(Mat4.translation(e.px[0], e.py[0], e.pz[0]))
            m = m.times(Mat4.rotationAxis(rot, 0f, 0f, 1f))
            if (r != 0 || c != 0) m = m.times(Mat4.translation(c * colSp, r * rowSp, 0f))
            m = m.times(Mat4.scale(sx, sy, sz))
            val ins = Insert(name, e.layer, m)
            val b = st.block
            if (b != null) b.inserts.add(ins) else expand(ins, Mat4.identity(), st, 0)
        }
    }

    private fun ocsMatrix(nx: Float, ny: Float, nz: Float): Mat4 {
        if (nx == 0f && ny == 0f && nz == 1f) return Mat4.identity()
        val p = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        ocsToWcs(p, nx, ny, nz)
        val m = Mat4.identity()
        for (c in 0 until 3) for (r in 0 until 3) m.m[c * 4 + r] = p[c * 3 + r]
        return m
    }

    /** Expands a block insert into the model's layer sinks with transform [parent] * insert matrix. */
    private fun expand(ins: Insert, parent: Mat4, st: State, depth: Int) {
        if (depth > 16) { st.notes.add("Block '${ins.block}' is nested too deeply (recursive INSERT?)"); return }
        val b = st.blocks[ins.block]
        if (b == null) { st.notes.add("Block '${ins.block}' referenced by INSERT is not defined"); return }
        val m = parent.times(ins.m).times(Mat4.translation(-b.bx, -b.by, -b.bz))
        val out = FloatArray(3)
        val tri = b.sink.tris; val tl = b.sink.triLayer
        var i = 0; var t = 0
        val tmp = FloatArray(9)
        while (i + 8 < tri.size) {
            for (k in 0 until 3) { m.transformPoint(tri[i + k * 3], tri[i + k * 3 + 1], tri[i + k * 3 + 2], out); tmp[k * 3] = out[0]; tmp[k * 3 + 1] = out[1]; tmp[k * 3 + 2] = out[2] }
            val layer = if (t < tl.size && tl[t] != "0") tl[t] else ins.layer
            val s = st.layer(layer)
            if (m.det3() < 0f) addTri(s, layer, st, tmp[0], tmp[1], tmp[2], tmp[6], tmp[7], tmp[8], tmp[3], tmp[4], tmp[5])
            else addTri(s, layer, st, tmp[0], tmp[1], tmp[2], tmp[3], tmp[4], tmp[5], tmp[6], tmp[7], tmp[8])
            i += 9; t++
        }
        val ed = b.sink.edges; val el = b.sink.edgeLayer
        i = 0; t = 0
        val tmp2 = FloatArray(6)
        while (i + 5 < ed.size) {
            for (k in 0 until 2) { m.transformPoint(ed[i + k * 3], ed[i + k * 3 + 1], ed[i + k * 3 + 2], out); tmp2[k * 3] = out[0]; tmp2[k * 3 + 1] = out[1]; tmp2[k * 3 + 2] = out[2] }
            val layer = if (t < el.size && el[t] != "0") el[t] else ins.layer
            addEdge(st.layer(layer), layer, tmp2[0], tmp2[1], tmp2[2], tmp2[3], tmp2[4], tmp2[5])
            i += 6; t++
        }
        for (child in b.inserts) expand(Insert(child.block, if (child.layer != "0") child.layer else ins.layer, child.m), m, st, depth + 1)
    }
}
