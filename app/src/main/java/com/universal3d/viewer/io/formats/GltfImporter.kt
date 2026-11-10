package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.io.MiniJson.asDouble
import com.universal3d.viewer.io.MiniJson.asInt
import com.universal3d.viewer.io.MiniJson.asList
import com.universal3d.viewer.io.MiniJson.asMap
import com.universal3d.viewer.io.MiniJson.asString
import com.universal3d.viewer.model.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * glTF 2.0 importer for .glb (binary container) and .gltf (JSON with data: URI or external buffers).
 *
 * Supported: accessors of every component type including normalized / quantized (KHR_mesh_quantization)
 * and sparse accessors; triangle, strip and fan primitives (lines become edges, points are skipped);
 * POSITION / NORMAL / COLOR_0; material base colour (pbrMetallicRoughness.baseColorFactor, or
 * KHR_materials_pbrSpecularGlossiness.diffuseFactor); node hierarchy with matrix or TRS transforms baked
 * into the geometry (instances are duplicated). Draco / meshopt compressed data is rejected with a clear
 * error. Textures, skins, morph targets, cameras, lights and animation are ignored. Units are metres.
 */
class GltfImporter : Importer {
    override val formatName = "glTF"
    override val extensions = listOf("glb", "gltf")

    private class Ctx(val json: Map<String, Any?>, val buffers: Array<ByteArray>) {
        val bufferViews = json["bufferViews"].asList() ?: emptyList()
        val accessors = json["accessors"].asList() ?: emptyList()
        val meshes = json["meshes"].asList() ?: emptyList()
        val nodes = json["nodes"].asList() ?: emptyList()
        val materials = json["materials"].asList() ?: emptyList()
        val meshCache = HashMap<Int, List<Prim>>()
        var primitivesDone = 0
        var totalPrimitives = 1
        var skippedPoints = false
        var anyTexture = false
        var anyVertexColor = false
    }

    /** A converted primitive (mesh space, before node transforms). */
    private class Prim(val mesh: Mesh, val color: Int, val name: String)

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val size = source.file.length()
        if (size < 20) throw ImportException("File is too small to be a glTF model")
        try {
            return loadInner(source, progress)
        } catch (e: ImportException) { throw e
        } catch (e: OutOfMemoryError) { throw ImportException("Not enough memory to load this glTF file")
        } catch (e: Exception) { throw ImportException("The glTF file is corrupt: ${e.message ?: e.javaClass.simpleName}", e) }
    }

    private fun loadInner(source: ImportSource, progress: ProgressSink): Model {
        progress.progress(0.02f, "Reading file")
        val bytes = source.file.readBytes()
        val isGlb = bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() && bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val jsonText: String
        var binChunk: ByteArray? = null
        if (isGlb) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = bb.getInt(4)
            if (version != 2) throw ImportException("GLB version $version is not supported (only glTF 2.0)")
            val total = minOf(bb.getInt(8).toLong() and 0xffffffffL, bytes.size.toLong()).toInt()
            var p = 12
            var json: String? = null
            while (p + 8 <= total) {
                val len = bb.getInt(p).toLong() and 0xffffffffL
                val type = bb.getInt(p + 4)
                val start = p + 8
                if (start + len > total) throw ImportException("GLB chunk at offset $p extends beyond the end of the file (file truncated?)")
                val l = len.toInt()
                when (type) {
                    0x4E4F534A -> json = String(bytes, start, l, Charsets.UTF_8)
                    0x004E4942 -> if (binChunk == null) binChunk = bytes.copyOfRange(start, start + l)
                }
                p = start + l
                p += (4 - p % 4) % 4
            }
            jsonText = json ?: throw ImportException("GLB file has no JSON chunk")
        } else {
            var s = String(bytes, Charsets.UTF_8)
            if (s.startsWith("﻿")) s = s.substring(1)
            if (!s.trimStart().startsWith("{")) throw ImportException("Not a glTF file: expected JSON or a GLB container")
            jsonText = s
        }
        progress.progress(0.08f, "Parsing")
        val json = MiniJson.parse(jsonText).asMap() ?: throw ImportException("glTF JSON root is not an object")
        val assetVersion = json["asset"].asMap()?.get("version").asString()
        if (assetVersion != null && !assetVersion.startsWith("2")) throw ImportException("glTF version $assetVersion is not supported (only glTF 2.0)")
        val required = json["extensionsRequired"].asList()?.mapNotNull { it.asString() } ?: emptyList()
        for (ext in required) {
            if (ext == "KHR_draco_mesh_compression") throw ImportException("This glTF uses Draco mesh compression, which is not supported. Re-export without compression.")
            if (ext == "EXT_meshopt_compression") throw ImportException("This glTF uses meshopt compression, which is not supported. Re-export without compression.")
        }
        // Buffers
        val bufDefs = json["buffers"].asList() ?: emptyList()
        val buffers = Array(bufDefs.size) { i ->
            val b = bufDefs[i].asMap() ?: throw ImportException("glTF buffer $i is malformed")
            val uri = b["uri"].asString()
            val len = b["byteLength"].asInt() ?: 0
            val data: ByteArray = when {
                uri == null -> binChunk ?: throw ImportException("glTF buffer $i has no URI and the file has no binary chunk")
                uri.startsWith("data:") -> decodeDataUri(uri, i)
                else -> {
                    val name = percentDecode(uri)
                    if (name.contains("://")) throw ImportException("glTF buffer $i references a remote URI ($uri), which cannot be loaded")
                    val f = File(source.file.parentFile, name)
                    if (!f.isFile) throw ImportException("The glTF file references '$name' which was not found next to it. Copy the .bin file alongside the .gltf or use a .glb file.")
                    f.readBytes()
                }
            }
            if (data.size < len) throw ImportException("glTF buffer $i holds ${data.size} bytes but declares $len")
            data
        }
        val ctx = Ctx(json, buffers)
        for (bv in ctx.bufferViews) {
            if (bv.asMap()?.get("extensions").asMap()?.containsKey("EXT_meshopt_compression") == true)
                throw ImportException("This glTF uses meshopt compression, which is not supported. Re-export without compression.")
        }
        if (json["textures"].asList()?.isNotEmpty() == true || json["images"].asList()?.isNotEmpty() == true) ctx.anyTexture = true
        ctx.totalPrimitives = maxOf(1, ctx.meshes.sumOf { it.asMap()?.get("primitives").asList()?.size ?: 0 })

        // Scene roots
        val sceneIdx = json["scene"].asInt() ?: 0
        val scenes = json["scenes"].asList()
        val rootIndices: List<Int> = if (scenes != null && scenes.isNotEmpty()) {
            val sc = scenes.getOrNull(sceneIdx).asMap() ?: scenes[0].asMap()
            sc?.get("nodes").asList()?.mapNotNull { it.asInt() } ?: emptyList()
        } else {
            // no scenes: every node that is not someone's child is a root
            val isChild = BooleanArray(ctx.nodes.size)
            for (n in ctx.nodes) n.asMap()?.get("children").asList()?.forEach { c -> c.asInt()?.let { if (it in isChild.indices) isChild[it] = true } }
            ctx.nodes.indices.filter { !isChild[it] }
        }
        val roots = ArrayList<Node>()
        val visiting = HashSet<Int>()
        for (ri in rootIndices) buildNode(ctx, ri, Mat4.identity(), visiting, 0, progress)?.let { roots.add(it) }
        if (roots.isEmpty() && ctx.nodes.isEmpty() && ctx.meshes.isNotEmpty()) {
            // meshes without any nodes: show them all at the origin
            for (mi in ctx.meshes.indices) nodeForMesh(ctx, mi, Mat4.identity(), null, progress)?.let { roots.add(it) }
        }
        val notes = ArrayList<String>()
        if (ctx.anyTexture) notes.add("Textures ignored; base colours only")
        if (json["animations"].asList()?.isNotEmpty() == true) notes.add("Animations ignored (rest pose shown)")
        if (json["skins"].asList()?.isNotEmpty() == true) notes.add("Skinning ignored (bind pose shown)")
        if (ctx.skippedPoints) notes.add("Point primitives skipped")
        if (ctx.anyVertexColor) notes.add("Vertex colours averaged per primitive")
        val model = Model(roots, formatName, source.displayName, source.sizeBytes, ModelUnits.METER, notes)
        if (model.isEmpty) throw ImportException("The glTF file contains no geometry")
        progress.progress(0.98f, "Finalising")
        return model
    }

    private fun decodeDataUri(uri: String, i: Int): ByteArray {
        val comma = uri.indexOf(',')
        if (comma < 0) throw ImportException("glTF buffer $i has a malformed data URI")
        val meta = uri.substring(5, comma)
        if (!meta.endsWith(";base64")) throw ImportException("glTF buffer $i data URI is not base64 encoded")
        return try { Base64.getDecoder().decode(uri.substring(comma + 1).trim()) }
        catch (e: IllegalArgumentException) { try { Base64.getMimeDecoder().decode(uri.substring(comma + 1)) } catch (e2: Exception) { throw ImportException("glTF buffer $i has invalid base64 data") } }
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        return try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
    }

    // ---- scene graph -------------------------------------------------------------------------------------

    private fun buildNode(ctx: Ctx, index: Int, parentWorld: Mat4, visiting: HashSet<Int>, depth: Int, progress: ProgressSink): Node? {
        val n = ctx.nodes.getOrNull(index).asMap() ?: throw ImportException("glTF scene references node $index which does not exist")
        if (!visiting.add(index) || depth > 256) throw ImportException("glTF node hierarchy contains a cycle at node $index")
        try {
            val world = parentWorld.times(localMatrix(n, index))
            val meshIdx = n["mesh"].asInt()
            val children = n["children"].asList()?.mapNotNull { it.asInt() } ?: emptyList()
            val name = n["name"].asString()?.ifBlank { null }
            val node: Node = if (meshIdx != null) {
                nodeForMesh(ctx, meshIdx, world, name ?: "Node $index", progress) ?: Node(name ?: "Node $index")
            } else Node(name ?: "Node $index")
            for (c in children) buildNode(ctx, c, world, visiting, depth + 1, progress)?.let { node.add(it) }
            return if (node.hasGeometryInSubtree) node else null
        } finally { visiting.remove(index) }
    }

    private fun localMatrix(n: Map<String, Any?>, index: Int): Mat4 {
        val m = n["matrix"].asList()
        if (m != null) {
            if (m.size != 16) throw ImportException("glTF node $index has a matrix with ${m.size} values instead of 16")
            return Mat4.fromColumnMajor(FloatArray(16) { m[it].asDouble()?.toFloat() ?: 0f })
        }
        var r = Mat4.identity()
        n["translation"].asList()?.let { if (it.size >= 3) r = r.times(Mat4.translation(f(it[0]), f(it[1]), f(it[2]))) }
        n["rotation"].asList()?.let { if (it.size >= 4) r = r.times(Mat4.fromQuaternion(f(it[0]), f(it[1]), f(it[2]), f(it[3]))) }
        n["scale"].asList()?.let { if (it.size >= 3) r = r.times(Mat4.scale(f(it[0]), f(it[1]), f(it[2]))) }
        return r
    }

    private fun f(v: Any?): Float = v.asDouble()?.toFloat() ?: 0f

    private fun nodeForMesh(ctx: Ctx, meshIdx: Int, world: Mat4, nodeName: String?, progress: ProgressSink): Node? {
        val meshDef = ctx.meshes.getOrNull(meshIdx).asMap() ?: throw ImportException("glTF node references mesh $meshIdx which does not exist")
        val prims = ctx.meshCache.getOrPut(meshIdx) { convertMesh(ctx, meshIdx, meshDef, progress) }
        val meshName = meshDef["name"].asString()?.ifBlank { null }
        val name = nodeName ?: meshName ?: "Mesh $meshIdx"
        if (prims.isEmpty()) return null
        if (prims.size == 1) return Node(name, prims[0].mesh.transformed(world), prims[0].color)
        val parent = Node(name)
        for (p in prims) parent.add(Node(p.name, p.mesh.transformed(world), p.color))
        return parent
    }

    private fun convertMesh(ctx: Ctx, meshIdx: Int, meshDef: Map<String, Any?>, progress: ProgressSink): List<Prim> {
        val out = ArrayList<Prim>()
        val prims = meshDef["primitives"].asList() ?: emptyList()
        for ((pi, pAny) in prims.withIndex()) {
            val p = pAny.asMap() ?: continue
            progress.progress(0.1f + 0.85f * ctx.primitivesDone / ctx.totalPrimitives, "Reading meshes"); progress.checkCancelled()
            ctx.primitivesDone++
            val ext = p["extensions"].asMap()
            if (ext?.containsKey("KHR_draco_mesh_compression") == true) throw ImportException("This glTF uses Draco mesh compression, which is not supported. Re-export without compression.")
            val attrs = p["attributes"].asMap() ?: continue
            val posAcc = attrs["POSITION"].asInt() ?: continue
            val mode = p["mode"].asInt() ?: 4
            if (mode == 0) { ctx.skippedPoints = true; continue }
            val positions = readFloatAccessor(ctx, posAcc, 3)
            val vcount = positions.size / 3
            var normals: FloatArray? = attrs["NORMAL"].asInt()?.let { readFloatAccessor(ctx, it, 3) }
            if (normals != null && normals.size != positions.size) normals = null
            val indices: IntArray = p["indices"].asInt()?.let { readIndexAccessor(ctx, it) } ?: IntArray(vcount) { it }
            for (i in indices) if (i < 0 || i >= vcount) throw ImportException("glTF mesh $meshIdx primitive $pi references vertex $i but only $vcount exist")
            val matIdx = p["material"].asInt()
            val matDef = matIdx?.let { ctx.materials.getOrNull(it).asMap() }
            var color = matDef?.let { materialColor(it) } ?: 0
            val matName = matDef?.get("name").asString()?.ifBlank { null } ?: (if (matIdx != null) "Material $matIdx" else null)
            if (color == 0 || (color and 0xffffff) == 0xffffff) {
                attrs["COLOR_0"].asInt()?.let { vc -> averageVertexColor(ctx, vc)?.let { color = it; ctx.anyVertexColor = true } }
            }
            val name = matName ?: "Primitive $pi"
            val mesh: Mesh = when (mode) {
                4, 5, 6 -> {
                    val tris = toTriangleList(indices, mode)
                    if (tris.isEmpty()) continue
                    if (p["indices"] == null && normals == null && mode == 4) MeshOps.fromSoup(positions)
                    else Mesh(positions, normals, tris)
                }
                1, 2, 3 -> {
                    val lines = toLineList(indices, mode)
                    if (lines.isEmpty()) continue
                    Mesh(FloatArray(0), null, IntArray(0), positions, lines)
                }
                else -> throw ImportException("glTF mesh $meshIdx primitive $pi has unknown mode $mode")
            }
            out.add(Prim(mesh, color, name))
        }
        return out
    }

    private fun toTriangleList(idx: IntArray, mode: Int): IntArray = when (mode) {
        4 -> if (idx.size % 3 == 0) idx else idx.copyOf(idx.size - idx.size % 3)
        5 -> { // strip
            val n = maxOf(0, idx.size - 2); val out = IntList(n * 3)
            for (i in 0 until n) { val a = idx[i]; val b = idx[i + 1]; val c = idx[i + 2]; if (a == b || b == c || a == c) continue; if (i % 2 == 0) out.add(a, b, c) else out.add(b, a, c) }
            out.toArray()
        }
        else -> { // fan
            val n = maxOf(0, idx.size - 2); val out = IntList(n * 3)
            for (i in 0 until n) { val a = idx[0]; val b = idx[i + 1]; val c = idx[i + 2]; if (a == b || b == c || a == c) continue; out.add(a, b, c) }
            out.toArray()
        }
    }

    private fun toLineList(idx: IntArray, mode: Int): IntArray = when (mode) {
        1 -> if (idx.size % 2 == 0) idx else idx.copyOf(idx.size - 1)
        2 -> { val out = IntList(idx.size * 2); for (i in idx.indices) out.add(idx[i], idx[(i + 1) % idx.size]); if (idx.size < 2) IntArray(0) else out.toArray() }
        else -> { val out = IntList(idx.size * 2); for (i in 0 until idx.size - 1) out.add(idx[i], idx[i + 1]); out.toArray() }
    }

    private fun materialColor(m: Map<String, Any?>): Int {
        val pbr = m["pbrMetallicRoughness"].asMap()
        val f = pbr?.get("baseColorFactor").asList()
            ?: m["extensions"].asMap()?.get("KHR_materials_pbrSpecularGlossiness").asMap()?.get("diffuseFactor").asList()
            ?: return 0
        if (f.size < 3) return 0
        val a = if (f.size >= 4) f(f[3]) else 1f
        return argb(f(f[0]), f(f[1]), f(f[2]), a)
    }

    private fun argb(r: Float, g: Float, b: Float, a: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun averageVertexColor(ctx: Ctx, accIdx: Int): Int? {
        val acc = ctx.accessors.getOrNull(accIdx).asMap() ?: return null
        val comps = when (acc["type"].asString()) { "VEC3" -> 3; "VEC4" -> 4; else -> return null }
        val data = readFloatAccessor(ctx, accIdx, comps)
        val n = data.size / comps
        if (n == 0) return null
        var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
        for (i in 0 until n) { r += data[i * comps]; g += data[i * comps + 1]; b += data[i * comps + 2]; a += if (comps == 4) data[i * comps + 3].toDouble() else 1.0 }
        return argb((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat(), (a / n).toFloat())
    }

    // ---- accessors ---------------------------------------------------------------------------------------

    private fun componentSize(ct: Int): Int = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw ImportException("glTF accessor has unknown componentType $ct") }
    private fun typeComponents(t: String?): Int = when (t) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16; else -> throw ImportException("glTF accessor has unknown type '$t'") }

    /** Resolves a bufferView to (bytes, offset, length, stride). */
    private class View(val data: ByteArray, val offset: Int, val length: Int, val stride: Int)

    private fun view(ctx: Ctx, bvIdx: Int): View {
        val bv = ctx.bufferViews.getOrNull(bvIdx).asMap() ?: throw ImportException("glTF bufferView $bvIdx does not exist")
        val bi = bv["buffer"].asInt() ?: throw ImportException("glTF bufferView $bvIdx has no buffer")
        val buf = ctx.buffers.getOrNull(bi) ?: throw ImportException("glTF bufferView $bvIdx references buffer $bi which does not exist")
        val off = bv["byteOffset"].asInt() ?: 0
        val len = bv["byteLength"].asInt() ?: throw ImportException("glTF bufferView $bvIdx has no byteLength")
        if (off < 0 || len < 0 || off.toLong() + len > buf.size) throw ImportException("glTF bufferView $bvIdx (offset $off, length $len) exceeds buffer $bi (${buf.size} bytes)")
        return View(buf, off, len, bv["byteStride"].asInt() ?: 0)
    }

    /** Reads a numeric element [i], component [c] from a view. */
    private fun readComponent(bb: ByteBuffer, pos: Int, ct: Int, normalized: Boolean): Float = when (ct) {
        5126 -> bb.getFloat(pos)
        5120 -> { val v = bb.get(pos).toFloat(); if (normalized) maxOf(v / 127f, -1f) else v }
        5121 -> { val v = (bb.get(pos).toInt() and 0xff).toFloat(); if (normalized) v / 255f else v }
        5122 -> { val v = bb.getShort(pos).toFloat(); if (normalized) maxOf(v / 32767f, -1f) else v }
        5123 -> { val v = (bb.getShort(pos).toInt() and 0xffff).toFloat(); if (normalized) v / 65535f else v }
        5125 -> { val v = (bb.getInt(pos).toLong() and 0xffffffffL).toFloat(); if (normalized) v / 4294967295f else v }
        else -> throw ImportException("glTF accessor has unknown componentType $ct")
    }

    private fun readIntComponent(bb: ByteBuffer, pos: Int, ct: Int): Int = when (ct) {
        5121 -> bb.get(pos).toInt() and 0xff
        5123 -> bb.getShort(pos).toInt() and 0xffff
        5125 -> { val v = bb.getInt(pos).toLong() and 0xffffffffL; if (v > Int.MAX_VALUE) throw ImportException("glTF index $v is out of range"); v.toInt() }
        5120 -> bb.get(pos).toInt()
        5122 -> bb.getShort(pos).toInt()
        5126 -> bb.getFloat(pos).toInt()
        else -> throw ImportException("glTF index accessor has unknown componentType $ct")
    }

    /** Reads an accessor as floats with [wantComps] components per element (extra components dropped, missing padded with 0). */
    private fun readFloatAccessor(ctx: Ctx, accIdx: Int, wantComps: Int): FloatArray {
        val acc = ctx.accessors.getOrNull(accIdx).asMap() ?: throw ImportException("glTF accessor $accIdx does not exist")
        val count = acc["count"].asInt() ?: throw ImportException("glTF accessor $accIdx has no count")
        if (count < 0 || count > 200_000_000) throw ImportException("glTF accessor $accIdx has an impossible count $count")
        val ct = acc["componentType"].asInt() ?: throw ImportException("glTF accessor $accIdx has no componentType")
        val comps = typeComponents(acc["type"].asString())
        val normalized = acc["normalized"] == true
        val cs = componentSize(ct)
        val elemSize = cs * comps
        val out = FloatArray(count * wantComps)
        val bvIdx = acc["bufferView"].asInt()
        if (bvIdx != null) {
            val v = view(ctx, bvIdx)
            val stride = if (v.stride > 0) v.stride else elemSize
            val accOff = acc["byteOffset"].asInt() ?: 0
            if (count > 0 && accOff.toLong() + stride.toLong() * (count - 1) + elemSize > v.length)
                throw ImportException("glTF accessor $accIdx reads past the end of bufferView $bvIdx (file truncated or corrupt)")
            val bb = ByteBuffer.wrap(v.data).order(ByteOrder.LITTLE_ENDIAN)
            val base = v.offset + accOff
            val n = minOf(comps, wantComps)
            if (ct == 5126 && stride == elemSize && n == comps && comps == wantComps) {
                bb.position(base); bb.asFloatBuffer().get(out, 0, count * comps)
            } else {
                for (i in 0 until count) {
                    val p = base + i * stride
                    for (c in 0 until n) out[i * wantComps + c] = readComponent(bb, p + c * cs, ct, normalized)
                }
            }
        }
        acc["sparse"].asMap()?.let { sp -> applySparse(ctx, sp, accIdx, out, count, ct, comps, wantComps, normalized) }
        return out
    }

    private fun applySparse(ctx: Ctx, sp: Map<String, Any?>, accIdx: Int, out: FloatArray, count: Int, ct: Int, comps: Int, wantComps: Int, normalized: Boolean) {
        val n = sp["count"].asInt() ?: 0
        val idxDef = sp["indices"].asMap() ?: throw ImportException("glTF sparse accessor $accIdx has no indices")
        val valDef = sp["values"].asMap() ?: throw ImportException("glTF sparse accessor $accIdx has no values")
        val iv = view(ctx, idxDef["bufferView"].asInt() ?: throw ImportException("glTF sparse accessor $accIdx indices have no bufferView"))
        val ict = idxDef["componentType"].asInt() ?: 5125
        val ics = componentSize(ict)
        val ioff = idxDef["byteOffset"].asInt() ?: 0
        val vv = view(ctx, valDef["bufferView"].asInt() ?: throw ImportException("glTF sparse accessor $accIdx values have no bufferView"))
        val voff = valDef["byteOffset"].asInt() ?: 0
        val cs = componentSize(ct); val elemSize = cs * comps
        if (ioff.toLong() + n.toLong() * ics > iv.length || voff.toLong() + n.toLong() * elemSize > vv.length) throw ImportException("glTF sparse accessor $accIdx reads past its bufferViews")
        val ib = ByteBuffer.wrap(iv.data).order(ByteOrder.LITTLE_ENDIAN); val vb = ByteBuffer.wrap(vv.data).order(ByteOrder.LITTLE_ENDIAN)
        val nc = minOf(comps, wantComps)
        for (k in 0 until n) {
            val target = readIntComponent(ib, iv.offset + ioff + k * ics, ict)
            if (target < 0 || target >= count) throw ImportException("glTF sparse accessor $accIdx has index $target beyond its count $count")
            for (c in 0 until nc) out[target * wantComps + c] = readComponent(vb, vv.offset + voff + k * elemSize + c * cs, ct, normalized)
        }
    }

    private fun readIndexAccessor(ctx: Ctx, accIdx: Int): IntArray {
        val acc = ctx.accessors.getOrNull(accIdx).asMap() ?: throw ImportException("glTF index accessor $accIdx does not exist")
        val count = acc["count"].asInt() ?: throw ImportException("glTF accessor $accIdx has no count")
        if (count < 0 || count > 600_000_000) throw ImportException("glTF accessor $accIdx has an impossible count $count")
        val ct = acc["componentType"].asInt() ?: throw ImportException("glTF accessor $accIdx has no componentType")
        val comps = typeComponents(acc["type"].asString())
        if (comps != 1) throw ImportException("glTF index accessor $accIdx must be SCALAR")
        val cs = componentSize(ct)
        val out = IntArray(count)
        val bvIdx = acc["bufferView"].asInt()
        if (bvIdx != null) {
            val v = view(ctx, bvIdx)
            val stride = if (v.stride > 0) v.stride else cs
            val accOff = acc["byteOffset"].asInt() ?: 0
            if (count > 0 && accOff.toLong() + stride.toLong() * (count - 1) + cs > v.length)
                throw ImportException("glTF index accessor $accIdx reads past the end of bufferView $bvIdx (file truncated or corrupt)")
            val bb = ByteBuffer.wrap(v.data).order(ByteOrder.LITTLE_ENDIAN)
            val base = v.offset + accOff
            for (i in 0 until count) out[i] = readIntComponent(bb, base + i * stride, ct)
        }
        acc["sparse"].asMap()?.let { sp ->
            val tmp = FloatArray(count) { out[it].toFloat() }
            applySparse(ctx, sp, accIdx, tmp, count, ct, 1, 1, false)
            for (i in 0 until count) out[i] = tmp[i].toInt()
        }
        return out
    }
}
