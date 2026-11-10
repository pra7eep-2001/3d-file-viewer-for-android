package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Autodesk 3DS importer (binary chunk format, little endian).
 *
 * Reads named objects (0x4000) with triangle meshes (vertices, faces, per-material face groups) and the
 * material library (0xAFFF: name and diffuse colour). Vertices are kept exactly as stored (3DS meshes are
 * already in world space; the 0x4160 local axis matrix is not re-applied). Objects that use several
 * materials become a group node with one child per material. The keyframer section, texture maps,
 * smoothing groups and the master scale are ignored.
 */
class ThreeDsImporter : Importer {
    override val formatName = "3DS"
    override val extensions = listOf("3ds")

    private class Truncated : Exception()

    private class MatGroup(val material: String, val faces: IntArray)
    private class Obj(val name: String) {
        var positions: FloatArray? = null
        var faces: IntArray? = null          // 3 indices per face
        val groups = ArrayList<MatGroup>()
        var hasMatrix = false
        var truncated = false
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val size = source.file.length()
        if (size < 6) throw ImportException("File is too small to be a 3DS model")
        if (size > 512L * 1024 * 1024) throw ImportException("3DS file is too large to load (${size / (1024 * 1024)} MB)")
        val bytes = source.file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val id = bb.getShort(0).toInt() and 0xffff
        if (id != 0x4D4D) throw ImportException("Not a 3DS file: the main chunk id is 0x${Integer.toHexString(id)} instead of 0x4d4d")
        val objects = ArrayList<Obj>()
        val materials = HashMap<String, Int>()
        val notes = ArrayList<String>()
        var masterScale = 1f
        var truncated = false
        try {
            val mainEnd = chunkEnd(bb, 0)
            var p = 6
            while (p + 6 <= mainEnd) {
                val cid = bb.getShort(p).toInt() and 0xffff
                val end = chunkEnd(bb, p)
                if (end <= p) break
                when (cid) {
                    0x3D3D -> { // editor
                        var q = p + 6
                        while (q + 6 <= end) {
                            val sid = bb.getShort(q).toInt() and 0xffff
                            val send = chunkEnd(bb, q)
                            if (send <= q) break
                            when (sid) {
                                0x0100 -> if (send - q >= 10) masterScale = bb.getFloat(q + 6)
                                0xAFFF -> try { readMaterial(bb, q + 6, send, materials) } catch (e: Truncated) { truncated = true }
                                0x4000 -> readObject(bb, q + 6, send)?.let { objects.add(it); if (it.truncated) truncated = true }
                            }
                            q = send
                            progress.progress(0.7f * q / size, "Reading objects"); progress.checkCancelled()
                        }
                    }
                    0xB000 -> {} // keyframer: ignored
                }
                p = end
            }
        } catch (e: ImportException) { throw e
        } catch (e: Exception) { truncated = true }

        progress.progress(0.8f, "Building parts")
        val roots = ArrayList<Node>()
        var anyMatrix = false
        for (o in objects) {
            val pos = o.positions ?: continue
            val faces = o.faces ?: continue
            if (pos.isEmpty() || faces.isEmpty()) continue
            if (o.hasMatrix) anyMatrix = true
            val faceCount = faces.size / 3
            // Which faces are covered by material groups?
            val assigned = BooleanArray(faceCount)
            val groups = ArrayList<Pair<String, IntList>>()
            for (g in o.groups) {
                val l = IntList(g.faces.size * 3)
                for (f in g.faces) if (f in 0 until faceCount && !assigned[f]) { assigned[f] = true; l.add(faces[f * 3], faces[f * 3 + 1], faces[f * 3 + 2]) }
                if (l.size > 0) groups.add(Pair(g.material, l))
            }
            val rest = IntList(16)
            for (f in 0 until faceCount) if (!assigned[f]) rest.add(faces[f * 3], faces[f * 3 + 1], faces[f * 3 + 2])
            if (groups.isEmpty()) {
                roots.add(Node(o.name, Mesh(pos, null, rest.toArray()), 0))
            } else if (groups.size == 1 && rest.size == 0) {
                val (mat, l) = groups[0]
                roots.add(Node(o.name, Mesh(pos, null, l.toArray()), materials[mat] ?: 0))
            } else {
                val parent = Node(o.name)
                for ((mat, l) in groups) parent.add(Node(mat, Mesh(pos, null, l.toArray()), materials[mat] ?: 0))
                if (rest.size > 0) parent.add(Node("${o.name} (no material)", Mesh(pos, null, rest.toArray()), 0))
                roots.add(parent)
            }
        }
        if (roots.isEmpty()) throw ImportException(if (truncated) "The 3DS file is truncated and contains no complete mesh" else "The 3DS file contains no geometry")
        if (truncated) notes.add("File is truncated or has corrupt chunks; some objects may be missing")
        if (masterScale != 1f) notes.add("Master scale $masterScale ignored (geometry is not scaled)")
        if (anyMatrix) notes.add("Local axis matrices ignored; vertices are used as stored")
        notes.add("No vertex normals in 3DS; flat shading")
        progress.progress(0.95f, "Finalising")
        return Model(roots, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, notes)
    }

    /** End offset of the chunk at [p], clamped to the buffer. */
    private fun chunkEnd(bb: ByteBuffer, p: Int): Int {
        if (p + 6 > bb.limit()) return p
        val len = bb.getInt(p + 2).toLong() and 0xffffffffL
        if (len < 6) return p + 6 // corrupt length: skip header only
        return minOf(p + len, bb.limit().toLong()).toInt()
    }

    private fun asciiz(bb: ByteBuffer, start: Int, end: Int): Pair<String, Int> {
        var q = start
        val sb = StringBuilder()
        while (q < end) { val c = bb.get(q).toInt() and 0xff; q++; if (c == 0) return Pair(sb.toString(), q); sb.append(c.toChar()) }
        return Pair(sb.toString(), q)
    }

    private fun readMaterial(bb: ByteBuffer, start: Int, end: Int, out: HashMap<String, Int>) {
        var name: String? = null
        var color = 0
        var q = start
        while (q + 6 <= end) {
            val sid = bb.getShort(q).toInt() and 0xffff
            val send = chunkEnd(bb, q); if (send <= q) break
            when (sid) {
                0xA000 -> name = asciiz(bb, q + 6, send).first
                0xA020 -> color = readColor(bb, q + 6, send)
            }
            q = send
        }
        if (name != null) out[name] = color
    }

    private fun readColor(bb: ByteBuffer, start: Int, end: Int): Int {
        var q = start
        var gamma = 0; var linear = 0
        while (q + 6 <= end) {
            val sid = bb.getShort(q).toInt() and 0xffff
            val send = chunkEnd(bb, q); if (send <= q) break
            when (sid) {
                0x0010, 0x0012 -> if (send - q >= 18) {
                    val c = argb(bb.getFloat(q + 6), bb.getFloat(q + 10), bb.getFloat(q + 14)); if (sid == 0x0010) gamma = c else linear = c
                }
                0x0011, 0x0013 -> if (send - q >= 9) {
                    val c = argb((bb.get(q + 6).toInt() and 0xff) / 255f, (bb.get(q + 7).toInt() and 0xff) / 255f, (bb.get(q + 8).toInt() and 0xff) / 255f)
                    if (sid == 0x0011) gamma = c else linear = c
                }
            }
            q = send
        }
        return if (gamma != 0) gamma else linear
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xff shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun readObject(bb: ByteBuffer, start: Int, end: Int): Obj? {
        val (name, after) = asciiz(bb, start, end)
        val obj = Obj(name.ifEmpty { "Object" })
        var q = after
        var hasMesh = false
        while (q + 6 <= end) {
            val sid = bb.getShort(q).toInt() and 0xffff
            val send = chunkEnd(bb, q); if (send <= q) break
            if (sid == 0x4100) { hasMesh = true; try { readMesh(bb, q + 6, send, obj) } catch (e: Truncated) { obj.truncated = true } }
            q = send
        }
        return if (hasMesh) obj else null
    }

    private fun readMesh(bb: ByteBuffer, start: Int, end: Int, obj: Obj) {
        var q = start
        while (q + 6 <= end) {
            val sid = bb.getShort(q).toInt() and 0xffff
            val send = chunkEnd(bb, q); if (send <= q) break
            when (sid) {
                0x4110 -> {
                    if (send - q < 8) throw Truncated()
                    val n = bb.getShort(q + 6).toInt() and 0xffff
                    val avail = (send - q - 8) / 12
                    val count = minOf(n, avail)
                    if (count < n && avail == 0) throw Truncated()
                    val pos = FloatArray(count * 3)
                    val fb = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN); fb.position(q + 8); fb.asFloatBuffer().get(pos)
                    obj.positions = pos
                }
                0x4120 -> readFaces(bb, q + 6, send, obj)
                0x4160 -> obj.hasMatrix = true
            }
            q = send
        }
    }

    private fun readFaces(bb: ByteBuffer, start: Int, end: Int, obj: Obj) {
        if (end - start < 2) throw Truncated()
        val n = bb.getShort(start).toInt() and 0xffff
        val avail = (end - start - 2) / 8
        val count = minOf(n, avail)
        val vcount = obj.positions?.let { it.size / 3 } ?: Int.MAX_VALUE
        val faces = IntList(count * 3)
        var p = start + 2
        for (i in 0 until count) {
            val a = bb.getShort(p).toInt() and 0xffff; val b = bb.getShort(p + 2).toInt() and 0xffff; val c = bb.getShort(p + 4).toInt() and 0xffff
            // keep face slots aligned with material-group face numbers: invalid faces become degenerate
            if (a < vcount && b < vcount && c < vcount) faces.add(a, b, c) else faces.add(0, 0, 0)
            p += 8
        }
        obj.faces = faces.toArray()
        // sub-chunks: material groups, smoothing groups
        var q = p
        while (q + 6 <= end) {
            val sid = bb.getShort(q).toInt() and 0xffff
            val send = chunkEnd(bb, q); if (send <= q) break
            if (sid == 0x4130) {
                val (mat, after) = asciiz(bb, q + 6, send)
                if (send - after >= 2) {
                    val m = bb.getShort(after).toInt() and 0xffff
                    val mcount = minOf(m, (send - after - 2) / 2)
                    val list = IntArray(mcount) { bb.getShort(after + 2 + it * 2).toInt() and 0xffff }
                    obj.groups.add(MatGroup(mat, list))
                }
            }
            q = send
        }
        if (count < n) throw Truncated()
    }
}
