package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Wavefront OBJ importer. Groups (g) and objects (o) become parts; polygons are triangulated;
 * vertex normals are kept when every face references them; MTL diffuse colours are used when the
 * material library is found next to the file. Textures are ignored.
 */
class ObjImporter : Importer {
    override val formatName = "OBJ"
    override val extensions = listOf("obj")

    private class Group(val name: String, val material: String?) {
        val tris = IntList(1 shl 12)      // indices into the combined vertex table
        val lines = IntList(0)
        var useNormals = true
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val size = source.file.length().toFloat()
        val px = FloatList(1 shl 16); val nx = FloatList(1 shl 12)
        val groups = ArrayList<Group>()
        var current: Group? = null
        var material: String? = null
        val mtlFiles = ArrayList<String>()
        // combined-vertex table keyed by (posIndex, normalIndex)
        val vmap = LongIntMap(1 shl 16)
        val outPos = FloatList(1 shl 16)
        val outNrm = FloatList(1 shl 12)
        var anyNormal = false
        val poly = IntList(16)
        var faceCount = 0
        FastReader(BufferedInputStream(FileInputStream(source.file), 1 shl 16)).let { r ->
            while (!r.atEnd()) {
                r.skipWhitespace()
                val c0 = r.peek(); if (c0 < 0) break
                val key = r.token()
                when (key) {
                    "v" -> { px.add(r.float(), r.float(), r.float()); r.skipLine() }
                    "vn" -> { nx.add(r.float(), r.float(), r.float()); r.skipLine() }
                    "f" -> {
                        val g = current ?: Group(defaultName(source), material).also { groups.add(it); current = it }
                        poly.clear()
                        var faceHasNormals = true
                        while (!r.endOfLine()) {
                            val tok = r.token(); if (tok.isEmpty()) break
                            var vi: Int; var ni = 0
                            val s1 = tok.indexOf('/')
                            if (s1 < 0) { vi = tok.toIntOrNull() ?: throw ImportException("Bad face index '$tok' at line ${r.line}"); faceHasNormals = false }
                            else {
                                vi = tok.substring(0, s1).toIntOrNull() ?: throw ImportException("Bad face index '$tok' at line ${r.line}")
                                val s2 = tok.indexOf('/', s1 + 1)
                                if (s2 < 0) faceHasNormals = false
                                else { val ns = tok.substring(s2 + 1); ni = ns.toIntOrNull() ?: 0; if (ni == 0) faceHasNormals = false }
                            }
                            val vc = px.size / 3
                            vi = if (vi < 0) vc + vi else vi - 1
                            if (vi < 0 || vi >= vc) throw ImportException("Face references missing vertex ${vi + 1} at line ${r.line}")
                            if (ni != 0) { val nc = nx.size / 3; ni = if (ni < 0) nc + ni else ni - 1; if (ni < 0 || ni >= nc) { ni = -1; faceHasNormals = false } } else ni = -1
                            val k = (vi.toLong() shl 32) or ((ni + 1).toLong() and 0xffffffffL)
                            var idx = vmap.getOrPut(k, outPos.size / 3)
                            if (idx < 0) {
                                idx = outPos.size / 3
                                outPos.add(px[vi * 3], px[vi * 3 + 1], px[vi * 3 + 2])
                                if (ni >= 0) { outNrm.add(nx[ni * 3], nx[ni * 3 + 1], nx[ni * 3 + 2]); anyNormal = true } else outNrm.add(0f, 0f, 0f)
                            }
                            poly.add(idx)
                        }
                        if (!faceHasNormals) g.useNormals = false
                        if (poly.size >= 3) {
                            if (poly.size == 3) g.tris.add(poly[0], poly[1], poly[2])
                            else MeshOps.triangulatePolygon(poly.toArray(), outPos.data, g.tris)
                        }
                        faceCount++
                        if ((faceCount and 0x7fff) == 0) { progress.progress(0.8f * r.bytesRead / size, "Reading faces"); progress.checkCancelled() }
                    }
                    "l" -> {
                        val g = current ?: Group(defaultName(source), material).also { groups.add(it); current = it }
                        var prev = -1
                        while (!r.endOfLine()) {
                            val tok = r.token(); if (tok.isEmpty()) break
                            var vi = tok.substringBefore('/').toIntOrNull() ?: break
                            val vc = px.size / 3
                            vi = if (vi < 0) vc + vi else vi - 1
                            if (vi < 0 || vi >= vc) break
                            val k = (vi.toLong() shl 32)
                            var idx = vmap.getOrPut(k, outPos.size / 3)
                            if (idx < 0) { idx = outPos.size / 3; outPos.add(px[vi * 3], px[vi * 3 + 1], px[vi * 3 + 2]); outNrm.add(0f, 0f, 0f) }
                            if (prev >= 0) g.lines.add(prev, idx)
                            prev = idx
                        }
                    }
                    "g", "o" -> {
                        val name = r.restOfLine().ifBlank { if (key == "g") "group" else "object" }
                        val g = current
                        if (g != null && g.tris.size == 0 && g.lines.size == 0) { groups.remove(g) }
                        current = Group(name, material).also { groups.add(it) }
                    }
                    "usemtl" -> {
                        material = r.restOfLine().trim()
                        val g = current
                        if (g != null && g.material != material) {
                            if (g.tris.size == 0 && g.lines.size == 0) groups.remove(g)
                            current = Group(g.name, material).also { groups.add(it) }
                        }
                    }
                    "mtllib" -> mtlFiles.add(r.restOfLine().trim())
                    else -> r.skipLine()
                }
            }
        }
        if (px.size == 0) throw ImportException("The OBJ file contains no vertices")
        val colors = loadMaterials(source.file, mtlFiles)
        val positions = outPos.toArray()
        val normals = if (anyNormal) outNrm.toArray() else null
        progress.progress(0.9f, "Building parts")
        val nodes = ArrayList<Node>()
        var notes = ArrayList<String>()
        if (mtlFiles.isNotEmpty() && colors.isEmpty()) notes.add("Material library not found next to the file; default colours used")
        for (g in groups) {
            if (g.tris.size == 0 && g.lines.size == 0) continue
            val idx = g.tris.toArray()
            val mesh = Mesh(positions, if (g.useNormals && normals != null) normals else null, idx)
            if (g.lines.size > 0) mesh.edgeIndices = g.lines.toArray()
            val name = if (g.material != null && groups.count { it.name == g.name } > 1) "${g.name} (${g.material})" else g.name
            nodes.add(Node(name, mesh, colors[g.material] ?: 0))
        }
        if (nodes.isEmpty()) throw ImportException("The OBJ file contains no faces")
        if (!anyNormal) notes.add("No vertex normals in file; flat shading")
        return Model(nodes, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, notes)
    }

    private fun defaultName(source: ImportSource) = source.displayName.substringBeforeLast('.')

    private fun loadMaterials(objFile: File, mtlFiles: List<String>): Map<String, Int> {
        val result = HashMap<String, Int>()
        for (name in mtlFiles) {
            val f = File(objFile.parentFile, name.substringAfterLast('/').substringAfterLast('\\'))
            if (!f.exists()) continue
            var cur: String? = null
            try {
                f.forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("newmtl ") -> cur = line.substring(7).trim()
                        line.startsWith("Kd ") && cur != null -> {
                            val p = line.substring(3).trim().split(Regex("\\s+"))
                            if (p.size >= 3) {
                                val r = (p[0].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f); val g = (p[1].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f); val b = (p[2].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f)
                                result[cur!!] = (0xff shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
                            }
                        }
                    }
                }
            } catch (e: Exception) { /* ignore broken MTL */ }
        }
        return result
    }
}
