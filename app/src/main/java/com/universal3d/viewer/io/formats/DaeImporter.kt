package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.FileInputStream

/**
 * COLLADA 1.4 / 1.5 (.dae) importer: library_geometries meshes (triangles, polylist, polygons, tristrips,
 * trifans, lines), visual scene hierarchy with matrix/translate/rotate/scale transforms, instance_geometry /
 * instance_node, material diffuse colours from library_effects, units from <asset><unit meter>.
 * Geometry is never rotated for up_axis; it is only reported as a note.
 */
class DaeImporter : Importer {
    override val formatName = "COLLADA"
    override val extensions = listOf("dae")

    /** One primitive block of a geometry: a mesh plus the material symbol it binds. */
    private class Prim(val mesh: Mesh, val materialSymbol: String?)
    private class Geometry(val id: String, val name: String, val prims: List<Prim>)
    private class Source(val data: FloatArray, val stride: Int)

    private class Ctx(val doc: Element, val progress: ProgressSink) {
        val byId = HashMap<String, Element>()
        val geometries = HashMap<String, Geometry?>()
        val effectColors = HashMap<String, Int>()
        val materialColors = HashMap<String, Int>()
        val sources = HashMap<String, Source?>()
        val notes = LinkedHashSet<String>()
        var instanceDepth = 0
        var nodeCount = 0
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        progress.progress(0f, "Parsing XML")
        val doc = BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { T.parseDom(it, "COLLADA file") }
        val root = doc.documentElement ?: throw ImportException("COLLADA file is empty")
        if (T.localName(root) != "COLLADA") throw ImportException("Not a COLLADA file (root element is <${root.nodeName}>, expected <COLLADA>)")
        return T.guard("COLLADA file") {
            val ctx = Ctx(root, progress)
            indexIds(root, ctx)
            progress.progress(0.3f, "Reading materials")
            readMaterials(root, ctx)
            var units: ModelUnits = ModelUnits.UNKNOWN
            T.child(root, "asset")?.let { asset ->
                T.child(asset, "unit")?.let { u ->
                    val meter = T.attr(u, "meter")?.trim()?.toDoubleOrNull()
                    if (meter != null) {
                        val mu = T.unitsByMeters(meter)
                        if (mu != null) units = mu else ctx.notes.add("Unit '${T.attr(u, "name") ?: meter.toString()}' ($meter m) is not a standard unit; coordinates shown as-is")
                    }
                }
                T.child(asset, "up_axis")?.let { ua -> val v = T.text(ua).trim(); if (v.isNotEmpty() && !v.equals("Y_UP", true)) ctx.notes.add("File declares ${v.uppercase()} (geometry shown in file coordinates)") }
            }
            progress.progress(0.4f, "Building scene")
            val roots = ArrayList<Node>()
            val scene = findVisualScene(root, ctx)
            if (scene != null) {
                T.forEachChild(scene) { n -> if (T.localName(n) == "node") buildNode(n, Mat4.identity(), ctx)?.let { roots.add(it) } }
            }
            if (roots.isEmpty()) {
                // no visual scene (or nothing instanced): import every geometry as a root node
                if (scene != null) ctx.notes.add("Visual scene instanced no geometry; all geometries are shown")
                val lib = T.child(root, "library_geometries")
                if (lib != null) T.forEachChild(lib) { g ->
                    if (T.localName(g) == "geometry") geometryNode(T.attr(g, "id") ?: "", g, Mat4.identity(), null, ctx)?.let { roots.add(it) }
                }
            }
            if (roots.isEmpty()) throw ImportException("The COLLADA file contains no geometry")
            if (T.child(root, "library_animations") != null) ctx.notes.add("Animations ignored")
            if (T.child(root, "library_controllers") != null) ctx.notes.add("Skin/morph controllers ignored (bind pose geometry shown)")
            if (T.child(root, "library_images") != null) ctx.notes.add("Textures ignored")
            progress.progress(0.95f, "Finalising")
            Model(roots, formatName, source.displayName, source.sizeBytes, units, ctx.notes.toList())
        }
    }

    private fun indexIds(e: Element, ctx: Ctx) {
        val id = e.getAttribute("id")
        if (!id.isNullOrEmpty()) ctx.byId.putIfAbsent(id, e)
        T.forEachChild(e) { indexIds(it, ctx) }
    }

    private fun ref(url: String?): String? = url?.trim()?.removePrefix("#")?.ifEmpty { null }

    private fun findVisualScene(root: Element, ctx: Ctx): Element? {
        val scene = T.child(root, "scene")
        val ivs = scene?.let { T.child(it, "instance_visual_scene") }
        val url = ref(ivs?.getAttribute("url"))
        if (url != null) { val e = ctx.byId[url]; if (e != null && T.localName(e) == "visual_scene") return e }
        val lib = T.child(root, "library_visual_scenes") ?: return null
        return T.child(lib, "visual_scene")
    }

    // ------------------------------------------------------------------ materials

    private fun readMaterials(root: Element, ctx: Ctx) {
        T.child(root, "library_effects")?.let { lib ->
            T.forEachChild(lib) { eff ->
                if (T.localName(eff) != "effect") return@forEachChild
                val id = T.attr(eff, "id") ?: return@forEachChild
                val color = effectDiffuse(eff)
                if (color != 0) ctx.effectColors[id] = color
            }
        }
        T.child(root, "library_materials")?.let { lib ->
            T.forEachChild(lib) { mat ->
                if (T.localName(mat) != "material") return@forEachChild
                val id = T.attr(mat, "id") ?: return@forEachChild
                val eff = ref(T.child(mat, "instance_effect")?.getAttribute("url")) ?: return@forEachChild
                ctx.effectColors[eff]?.let { ctx.materialColors[id] = it }
            }
        }
    }

    private fun effectDiffuse(eff: Element): Int {
        val profile = T.child(eff, "profile_COMMON") ?: return 0
        val tech = T.child(profile, "technique") ?: return 0
        val shader = T.child(tech, "phong") ?: T.child(tech, "lambert") ?: T.child(tech, "blinn") ?: T.child(tech, "constant") ?: return 0
        val diffuse = T.child(shader, "diffuse") ?: T.child(shader, "emission") ?: return 0
        val colorEl = T.child(diffuse, "color")
        if (colorEl != null) {
            val v = try { T.floatArray(T.text(colorEl), "diffuse colour") } catch (e: ImportException) { return 0 }
            if (v.size >= 3) return T.argb(v[0], v[1], v[2], if (v.size >= 4) v[3] else 1f)
        }
        return 0
    }

    // ------------------------------------------------------------------ scene nodes

    private fun buildNode(n: Element, parentWorld: Mat4, ctx: Ctx): Node? {
        if (++ctx.nodeCount % 256 == 0) ctx.progress.checkCancelled()
        val local = localTransform(n, ctx)
        val world = parentWorld.times(local)
        val name = T.attr(n, "name") ?: T.attr(n, "id") ?: T.attr(n, "sid") ?: "node"
        val children = ArrayList<Node>()
        T.forEachChild(n) { c ->
            when (T.localName(c)) {
                "node" -> buildNode(c, world, ctx)?.let { children.add(it) }
                "instance_geometry" -> {
                    val gid = ref(c.getAttribute("url"))
                    val g = gid?.let { ctx.byId[it] }
                    if (g == null) ctx.notes.add("Geometry '${gid ?: "?"}' referenced by the scene is missing")
                    else geometryNode(gid, g, world, bindMaterial(c), ctx)?.let { children.add(it) }
                }
                "instance_node" -> {
                    val nid = ref(c.getAttribute("url"))
                    val target = nid?.let { ctx.byId[it] }
                    if (target != null && T.localName(target) == "node") {
                        if (ctx.instanceDepth > 24) throw ImportException("COLLADA instance_node references are nested too deeply (cycle?)")
                        ctx.instanceDepth++
                        try { buildNode(target, world, ctx)?.let { children.add(it) } } finally { ctx.instanceDepth-- }
                    }
                }
                "instance_controller" -> {
                    // show the bind-pose geometry of a skinned mesh
                    val cid = ref(c.getAttribute("url"))
                    val ctrl = cid?.let { ctx.byId[it] }
                    val skin = ctrl?.let { T.child(it, "skin") ?: T.child(it, "morph") }
                    val gid = ref(skin?.getAttribute("source"))
                    val g = gid?.let { ctx.byId[it] }
                    if (g != null && T.localName(g) == "geometry") geometryNode(gid!!, g, world, bindMaterial(c), ctx)?.let { children.add(it) }
                }
            }
        }
        if (children.isEmpty()) return null
        if (children.size == 1 && children[0].children.isEmpty() && children[0].mesh != null) {
            // collapse "node -> single mesh" into one node carrying the scene node's name
            val m = children[0]
            return Node(name, m.mesh, m.color)
        }
        val node = Node(name)
        for (c in children) node.add(c)
        return node
    }

    private fun bindMaterial(instance: Element): Map<String, String>? {
        val bm = T.child(instance, "bind_material") ?: return null
        val tc = T.child(bm, "technique_common") ?: return null
        val map = HashMap<String, String>()
        T.forEachChild(tc) { im ->
            if (T.localName(im) == "instance_material") {
                val sym = T.attr(im, "symbol"); val target = ref(im.getAttribute("target"))
                if (sym != null && target != null) map[sym] = target
            }
        }
        return map
    }

    private fun localTransform(n: Element, ctx: Ctx): Mat4 {
        var m = Mat4.identity()
        T.forEachChild(n) { c ->
            val v: FloatArray
            when (T.localName(c)) {
                "matrix" -> {
                    v = T.floatArray(T.text(c), "<matrix>")
                    if (v.size != 16) throw ImportException("COLLADA <matrix> must contain 16 numbers but has ${v.size}")
                    m = m.times(Mat4.fromRowMajor(v))
                }
                "translate" -> { v = T.floatArray(T.text(c), "<translate>"); if (v.size >= 3) m = m.times(Mat4.translation(v[0], v[1], v[2])) }
                "rotate" -> { v = T.floatArray(T.text(c), "<rotate>"); if (v.size >= 4) m = m.times(Mat4.rotationAxis(Math.toRadians(v[3].toDouble()).toFloat(), v[0], v[1], v[2])) }
                "scale" -> { v = T.floatArray(T.text(c), "<scale>"); if (v.size >= 3) m = m.times(Mat4.scale(v[0], v[1], v[2])) }
                "lookat", "skew" -> ctx.notes.add("<${T.localName(c)}> transforms are not supported and were ignored")
            }
        }
        return m
    }

    // ------------------------------------------------------------------ geometry

    private fun geometryNode(gid: String, g: Element, world: Mat4, materials: Map<String, String>?, ctx: Ctx): Node? {
        val geo = ctx.geometries.getOrPut(gid) { parseGeometry(gid, g, ctx) } ?: return null
        fun color(p: Prim): Int {
            val sym = p.materialSymbol ?: return 0
            val target = materials?.get(sym) ?: sym
            return ctx.materialColors[target] ?: 0
        }
        if (geo.prims.size == 1) return Node(geo.name, geo.prims[0].mesh.transformed(world), color(geo.prims[0]))
        val n = Node(geo.name)
        for ((i, p) in geo.prims.withIndex()) n.add(Node(p.materialSymbol ?: "${geo.name} ${i + 1}", p.mesh.transformed(world), color(p)))
        return n
    }

    private fun parseGeometry(gid: String, g: Element, ctx: Ctx): Geometry? {
        val mesh = T.child(g, "mesh")
        if (mesh == null) {
            if (T.child(g, "spline") != null || T.child(g, "brep") != null) ctx.notes.add("Spline / B-rep geometry is not supported")
            if (T.child(g, "convex_mesh") != null) ctx.notes.add("convex_mesh geometry is not supported")
            return null
        }
        val name = T.attr(g, "name") ?: gid
        ctx.progress.progress(-1f, "Reading geometry $name")
        // <vertices>: semantic POSITION (and possibly NORMAL) sources
        var vertexPositions: Source? = null
        var vertexNormals: Source? = null
        T.child(mesh, "vertices")?.let { vs ->
            T.forEachChild(vs) { inp ->
                if (T.localName(inp) == "input") {
                    val sem = T.attr(inp, "semantic")?.uppercase(); val src = ref(inp.getAttribute("source"))
                    if (src != null) when (sem) {
                        "POSITION" -> vertexPositions = source(src, ctx)
                        "NORMAL" -> vertexNormals = source(src, ctx)
                    }
                }
            }
        }
        val prims = ArrayList<Prim>()
        T.forEachChild(mesh) { p ->
            val kind = T.localName(p)
            if (kind == "triangles" || kind == "polylist" || kind == "polygons" || kind == "tristrips" || kind == "trifans" || kind == "lines" || kind == "linestrips") {
                try { parsePrimitive(kind, p, vertexPositions, vertexNormals, name, ctx)?.let { prims.add(it) } }
                catch (e: ImportException) { throw ImportException("Geometry '$name': ${e.message}", e) }
            }
        }
        if (prims.isEmpty()) {
            // Points-only or unsupported primitive types: nothing to show
            return null
        }
        return Geometry(gid, name, prims)
    }

    private fun source(id: String, ctx: Ctx): Source? = ctx.sources.getOrPut(id) {
        val e = ctx.byId[id] ?: return@getOrPut null
        val fa = T.child(e, "float_array") ?: return@getOrPut null
        val data = T.floatArray(T.text(fa), "float_array '$id'")
        var stride = 3
        T.child(e, "technique_common")?.let { tc -> T.child(tc, "accessor")?.let { acc -> stride = T.attrInt(acc, "stride", 1).coerceAtLeast(1) } }
        Source(data, stride)
    }

    private class Input(val semantic: String, val source: Source, val offset: Int)

    private fun parsePrimitive(kind: String, p: Element, vertexPositions: Source?, vertexNormals: Source?, geoName: String, ctx: Ctx): Prim? {
        // gather inputs
        var posSrc: Source? = null; var posOffset = 0
        var nrmSrc: Source? = null; var nrmOffset = -1
        var maxOffset = 0
        var vertexInputSeen = false
        T.forEachChild(p) { inp ->
            if (T.localName(inp) != "input") return@forEachChild
            val sem = T.attr(inp, "semantic")?.uppercase() ?: return@forEachChild
            val off = T.attrInt(inp, "offset", 0)
            if (off > maxOffset) maxOffset = off
            val srcId = ref(inp.getAttribute("source"))
            when (sem) {
                "VERTEX" -> {
                    vertexInputSeen = true
                    posSrc = vertexPositions ?: (srcId?.let { source(it, ctx) }); posOffset = off
                    if (vertexNormals != null && nrmSrc == null) { nrmSrc = vertexNormals; nrmOffset = off }
                }
                "POSITION" -> { posSrc = srcId?.let { source(it, ctx) }; posOffset = off }
                "NORMAL" -> { if (srcId != null) { val s = source(srcId, ctx); if (s != null) { nrmSrc = s; nrmOffset = off } } }
            }
        }
        val ps = posSrc ?: run {
            if (!vertexInputSeen) throw ImportException("<$kind> has no VERTEX input")
            throw ImportException("<$kind> references a missing position source")
        }
        if (maxOffset > 64) throw ImportException("<$kind> input offset $maxOffset is out of range")
        val stride = maxOffset + 1
        val vcount = ps.data.size / ps.stride
        val material = T.attr(p, "material")

        // read index streams
        val pLists = ArrayList<IntArray>()
        T.forEachChild(p) { c ->
            when (T.localName(c)) {
                "p" -> pLists.add(T.intArray(T.text(c), "<p> of <$kind>"))
                "ph" -> T.child(c, "p")?.let { pLists.add(T.intArray(T.text(it), "<p> of <$kind>")) } // holes ignored
            }
        }
        if (pLists.isEmpty()) return null
        val useNormals = nrmSrc != null && nrmOffset >= 0
        // Combined vertex table keyed by (position index, normal index) when normals are present; otherwise
        // the mesh indexes the positions directly (shared vertices).
        val outPos = FloatList(if (useNormals) 1 shl 12 else 0)
        val outNrm = FloatList(if (useNormals) 1 shl 12 else 0)
        val vmap = if (useNormals) LongIntMap(1 shl 12) else null
        val positions: FloatArray = if (useNormals) FloatArray(0) else extract(ps, vcount)
        val ns = nrmSrc
        val ncount = if (ns != null) ns.data.size / ns.stride else 0

        fun vertex(ints: IntArray, at: Int, what: String): Int {
            val pi = ints[at + posOffset]
            if (pi < 0 || pi >= vcount) throw ImportException("<$kind> $what references vertex $pi but only $vcount positions exist")
            if (!useNormals) return pi
            var ni = ints[at + nrmOffset]
            if (ni < 0 || ni >= ncount) ni = -1
            val key = (pi.toLong() shl 32) or ((ni + 1).toLong() and 0xffffffffL)
            var idx = vmap!!.getOrPut(key, outPos.size / 3)
            if (idx < 0) {
                idx = outPos.size / 3
                val po = pi * ps.stride; outPos.add(ps.data[po], ps.data[po + 1], ps.data[po + 2])
                if (ni >= 0) { val no = ni * ns!!.stride; outNrm.add(ns.data[no], ns.data[no + 1], ns.data[no + 2]) } else outNrm.add(0f, 0f, 1f)
            }
            return idx
        }

        val tris = IntList(1 shl 12)
        val edges = IntList(0)
        val poly = IntList(16)
        var processed = 0
        fun emitPolygon(posForTri: FloatArray) {
            if (poly.size == 3) tris.add(poly[0], poly[1], poly[2])
            else if (poly.size > 3) MeshOps.triangulatePolygon(poly.toArray(), posForTri, tris)
            poly.clear()
        }
        when (kind) {
            "triangles" -> {
                val ints = pLists[0]
                val per = 3 * stride
                val n = ints.size / per
                for (t in 0 until n) {
                    val b = t * per
                    tris.add(vertex(ints, b, "triangle $t"), vertex(ints, b + stride, "triangle $t"), vertex(ints, b + 2 * stride, "triangle $t"))
                    if ((++processed and 0x3fff) == 0) ctx.progress.checkCancelled()
                }
            }
            "polylist" -> {
                val vc = T.child(p, "vcount")?.let { T.intArray(T.text(it), "<vcount>") } ?: IntArray(0)
                val ints = pLists[0]
                var at = 0
                for ((pi, count) in vc.withIndex()) {
                    if (count < 0 || at + count.toLong() * stride > ints.size) throw ImportException("<polylist> polygon $pi exceeds the index list")
                    for (k in 0 until count) { poly.add(vertex(ints, at, "polygon $pi")); at += stride }
                    emitPolygon(if (useNormals) outPos.data else positions)
                    if ((++processed and 0x3fff) == 0) ctx.progress.checkCancelled()
                }
            }
            "polygons" -> {
                for ((pi, ints) in pLists.withIndex()) {
                    var at = 0
                    while (at + stride <= ints.size) { poly.add(vertex(ints, at, "polygon $pi")); at += stride }
                    emitPolygon(if (useNormals) outPos.data else positions)
                    if ((++processed and 0x3fff) == 0) ctx.progress.checkCancelled()
                }
            }
            "tristrips", "trifans" -> {
                for (ints in pLists) {
                    val n = ints.size / stride
                    val v = IntArray(n) { vertex(ints, it * stride, kind) }
                    for (k in 2 until n) {
                        if (kind == "tristrips") { if (k % 2 == 0) tris.add(v[k - 2], v[k - 1], v[k]) else tris.add(v[k - 1], v[k - 2], v[k]) }
                        else tris.add(v[0], v[k - 1], v[k])
                    }
                }
            }
            "lines" -> {
                for (ints in pLists) { var at = 0; while (at + 2 * stride <= ints.size) { edges.add(vertex(ints, at, "line"), vertex(ints, at + stride, "line")); at += 2 * stride } }
            }
            "linestrips" -> {
                for (ints in pLists) { val n = ints.size / stride; var prev = -1; for (k in 0 until n) { val v = vertex(ints, k * stride, "linestrip"); if (prev >= 0) edges.add(prev, v); prev = v } }
            }
        }
        if (tris.size == 0 && edges.size == 0) return null
        val mesh = if (useNormals) Mesh(outPos.toArray(), outNrm.toArray(), tris.toArray()) else Mesh(positions, null, tris.toArray())
        if (edges.size > 0) mesh.edgeIndices = edges.toArray()
        return Prim(mesh, material)
    }

    /** Extracts xyz from a source with arbitrary stride. */
    private fun extract(s: Source, count: Int): FloatArray {
        if (s.stride == 3) return if (s.data.size == count * 3) s.data else s.data.copyOf(count * 3)
        if (s.stride < 3) throw ImportException("Position source has stride ${s.stride}; expected 3 components")
        val out = FloatArray(count * 3)
        for (i in 0 until count) { out[i * 3] = s.data[i * s.stride]; out[i * 3 + 1] = s.data[i * s.stride + 1]; out[i * 3 + 2] = s.data[i * s.stride + 2] }
        return out
    }
}
