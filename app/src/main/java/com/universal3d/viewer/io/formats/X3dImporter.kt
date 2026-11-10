package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.FileInputStream

/**
 * X3D (XML encoding) importer: Scene graph with Transform/Group nesting, Shape with IndexedFaceSet,
 * IndexedTriangleSet, IndexedTriangleStripSet/FanSet, TriangleSet, TriangleStripSet, IndexedLineSet, LineSet,
 * Box/Sphere/Cylinder/Cone, ElevationGrid and Extrusion; Material diffuseColor; DEF/USE for any node.
 */
class X3dImporter : Importer {
    override val formatName = "X3D"
    override val extensions = listOf("x3d")

    private class Ctx(val progress: ProgressSink) {
        val defs = HashMap<String, Element>()
        val coordCache = HashMap<Element, FloatArray>()
        val meshCache = HashMap<Element, Mesh?>()
        val notes = LinkedHashSet<String>()
        var depth = 0
        var count = 0
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        progress.progress(0f, "Parsing XML")
        val doc = BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { T.parseDom(it, "X3D file") }
        val root = doc.documentElement ?: throw ImportException("X3D file is empty")
        val rootName = T.localName(root)
        val scene: Element = when (rootName) {
            "X3D" -> T.child(root, "Scene") ?: throw ImportException("X3D file has no <Scene> element")
            "Scene" -> root
            else -> throw ImportException("Not an X3D file (root element is <${root.nodeName}>, expected <X3D>)")
        }
        return T.guard("X3D file") {
            val ctx = Ctx(progress)
            collectDefs(scene, ctx)
            progress.progress(0.3f, "Building scene")
            val roots = ArrayList<Node>()
            T.forEachChild(scene) { c -> buildNode(c, Mat4.identity(), ctx)?.let { roots.add(it) } }
            if (roots.isEmpty()) throw ImportException("The X3D file contains no geometry")
            progress.progress(0.95f, "Finalising")
            Model(roots, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, ctx.notes.toList())
        }
    }

    private fun collectDefs(e: Element, ctx: Ctx) {
        val def = e.getAttribute("DEF")
        if (!def.isNullOrEmpty()) ctx.defs.putIfAbsent(def, e)
        T.forEachChild(e) { collectDefs(it, ctx) }
    }

    /** Resolves USE references to the defining element. */
    private fun resolve(e: Element, ctx: Ctx): Element {
        val use = e.getAttribute("USE")
        if (use.isNullOrEmpty()) return e
        return ctx.defs[use] ?: throw ImportException("USE='$use' refers to a node that is not defined (<${e.nodeName}>)")
    }

    private fun buildNode(raw: Element, parentWorld: Mat4, ctx: Ctx): Node? {
        if (++ctx.count % 512 == 0) ctx.progress.checkCancelled()
        val e = resolve(raw, ctx)
        val name = T.localName(e)
        return when (name) {
            "Transform" -> {
                val local = VrmlGeometry.transformMatrix(vec(e, "translation"), vec(e, "rotation"), vec(e, "scale"), vec(e, "scaleOrientation"), vec(e, "center"))
                group(e, parentWorld.times(local), ctx, T.attr(e, "DEF") ?: "Transform", T.attr(e, "DEF") != null)
            }
            "Group", "StaticGroup", "Anchor", "Billboard", "Collision", "Switch", "LOD", "GeoLocation", "CADAssembly", "CADLayer", "CADPart", "HAnimHumanoid", "EspduTransform" -> {
                val explicit = T.attr(e, "name") ?: T.attr(e, "DEF")
                val label = explicit ?: name
                if (name == "Switch") switchChild(e, parentWorld, ctx, label, explicit != null)
                else if (name == "LOD") lodChild(e, parentWorld, ctx, label, explicit != null)
                else group(e, parentWorld, ctx, label, explicit != null)
            }
            "Shape", "CADFace" -> shape(e, parentWorld, ctx)
            "Inline" -> { ctx.notes.add("Inline references to external files are not loaded"); null }
            "ProtoInstance" -> { ctx.notes.add("ProtoInstance nodes are not supported"); null }
            else -> null
        }
    }

    private fun group(e: Element, world: Mat4, ctx: Ctx, label: String, explicit: Boolean): Node? {
        if (ctx.depth > 200) throw ImportException("X3D scene graph is nested too deeply")
        ctx.depth++
        val children = ArrayList<Node>()
        try { T.forEachChild(e) { c -> buildNode(c, world, ctx)?.let { children.add(it) } } } finally { ctx.depth-- }
        return wrap(label, explicit, children)
    }

    private fun switchChild(e: Element, world: Mat4, ctx: Ctx, label: String, explicit: Boolean): Node? {
        val which = T.attrInt(e, "whichChoice", -1)
        if (which < 0) return null
        val kids = T.children(e).filter { T.localName(it) != "metadata" && T.localName(it) != "MetadataSet" }
        if (which >= kids.size) return null
        val n = buildNode(kids[which], world, ctx) ?: return null
        return wrap(label, explicit, listOf(n))
    }

    private fun lodChild(e: Element, world: Mat4, ctx: Ctx, label: String, explicit: Boolean): Node? {
        // highest detail level is the first child
        for (c in T.children(e)) { val n = buildNode(c, world, ctx); if (n != null) return wrap(label, explicit, listOf(n)) }
        return null
    }

    /** A group with a single mesh child collapses into one node; an explicit DEF/name on the group wins over the shape's. */
    private fun wrap(label: String, explicit: Boolean, children: List<Node>): Node? {
        if (children.isEmpty()) return null
        if (children.size == 1 && children[0].children.isEmpty() && children[0].mesh != null) {
            val c = children[0]
            return Node(if (explicit || c.name == "Shape") label else c.name, c.mesh, c.color)
        }
        val n = Node(label)
        for (c in children) n.add(c)
        return n
    }

    private fun shape(e: Element, world: Mat4, ctx: Ctx): Node? {
        var color = 0
        var mesh: Mesh? = null
        T.forEachChild(e) { c ->
            val cr = resolve(c, ctx)
            when (T.localName(cr)) {
                "Appearance" -> color = appearanceColor(cr, ctx)
                else -> if (mesh == null) mesh = geometry(cr, ctx)
            }
        }
        val m = mesh ?: return null
        val name = T.attr(e, "DEF") ?: "Shape"
        return Node(name, m.transformed(world), color)
    }

    private fun appearanceColor(app: Element, ctx: Ctx): Int {
        var color = 0
        T.forEachChild(app) { c ->
            val cr = resolve(c, ctx)
            when (T.localName(cr)) {
                "Material", "TwoSidedMaterial" -> {
                    val v = vec(cr, "diffuseColor")
                    if (v != null && v.size >= 3) { val a = 1f - (T.attrFloat(cr, "transparency", 0f)).coerceIn(0f, 1f); color = T.argb(v[0], v[1], v[2], a) }
                }
                "PhysicalMaterial" -> { val v = vec(cr, "baseColor"); if (v != null && v.size >= 3) color = T.argb(v[0], v[1], v[2]) }
                "ImageTexture", "PixelTexture", "MovieTexture" -> ctx.notes.add("Textures ignored")
            }
        }
        return color
    }

    private fun geometry(g: Element, ctx: Ctx): Mesh? = ctx.meshCache.getOrPut(g) { buildGeometry(g, ctx) }

    private fun buildGeometry(g: Element, ctx: Ctx): Mesh? {
        val kind = T.localName(g)
        val what = "<$kind${T.attr(g, "DEF")?.let { " DEF='$it'" } ?: ""}>"
        val ccw = bool(g, "ccw", true)
        return when (kind) {
            "IndexedFaceSet" -> {
                val points = coords(g, ctx) ?: return null
                val ci = T.attr(g, "coordIndex")?.let { T.intArray(it, "coordIndex of $what") } ?: return null
                val normals = normals(g, ctx)
                val ni = T.attr(g, "normalIndex")?.let { T.intArray(it, "normalIndex of $what") }
                VrmlGeometry.indexedFaceSet(points, ci, normals, ni, bool(g, "normalPerVertex", true), ccw, what)
            }
            "IndexedTriangleSet", "IndexedQuadSet" -> {
                val points = coords(g, ctx) ?: return null
                val idx = T.attr(g, "index")?.let { T.intArray(it, "index of $what") } ?: return null
                if (kind == "IndexedQuadSet") {
                    val ci = IntArray(idx.size / 4 * 5); var o = 0
                    for (q in 0 until idx.size / 4) { ci[o++] = idx[q * 4]; ci[o++] = idx[q * 4 + 1]; ci[o++] = idx[q * 4 + 2]; ci[o++] = idx[q * 4 + 3]; ci[o++] = -1 }
                    VrmlGeometry.indexedFaceSet(points, ci, null, null, true, ccw, what)
                } else VrmlGeometry.triangleSet(points, idx, normals(g, ctx), ccw, what)
            }
            "TriangleSet", "QuadSet" -> {
                val points = coords(g, ctx) ?: return null
                if (kind == "QuadSet") {
                    val n = points.size / 3 / 4; val ci = IntArray(n * 5); var o = 0
                    for (q in 0 until n) { ci[o++] = q * 4; ci[o++] = q * 4 + 1; ci[o++] = q * 4 + 2; ci[o++] = q * 4 + 3; ci[o++] = -1 }
                    VrmlGeometry.indexedFaceSet(points, ci, null, null, true, ccw, what)
                } else VrmlGeometry.triangleSet(points, null, normals(g, ctx), ccw, what)
            }
            "IndexedTriangleStripSet", "IndexedTriangleFanSet" -> {
                val points = coords(g, ctx) ?: return null
                val idx = T.attr(g, "index")?.let { T.intArray(it, "index of $what") } ?: return null
                VrmlGeometry.stripsOrFans(points, idx, null, kind == "IndexedTriangleFanSet", ccw, what)
            }
            "TriangleStripSet", "TriangleFanSet" -> {
                val points = coords(g, ctx) ?: return null
                val counts = T.attr(g, if (kind == "TriangleStripSet") "stripCount" else "fanCount")?.let { T.intArray(it, "stripCount of $what") } ?: return null
                VrmlGeometry.stripsOrFans(points, null, counts, kind == "TriangleFanSet", ccw, what)
            }
            "IndexedLineSet" -> {
                val points = coords(g, ctx) ?: return null
                val ci = T.attr(g, "coordIndex")?.let { T.intArray(it, "coordIndex of $what") } ?: return null
                VrmlGeometry.indexedLineSet(points, ci, what)
            }
            "LineSet" -> {
                val points = coords(g, ctx) ?: return null
                VrmlGeometry.lineSet(points, T.attr(g, "vertexCount")?.let { T.intArray(it, "vertexCount of $what") }, what)
            }
            "Box" -> { val s = vec(g, "size") ?: floatArrayOf(2f, 2f, 2f); if (s.size < 3) null else T.box(s[0], s[1], s[2]) }
            "Sphere" -> T.sphere(T.attrFloat(g, "radius", 1f))
            "Cylinder" -> { val r = T.attrFloat(g, "radius", 1f); T.cylinder(r, r, T.attrFloat(g, "height", 2f), side = bool(g, "side", true), capBottom = bool(g, "bottom", true), capTop = bool(g, "top", true)) }
            "Cone" -> T.cylinder(T.attrFloat(g, "bottomRadius", 1f), 0f, T.attrFloat(g, "height", 2f), side = bool(g, "side", true), capBottom = bool(g, "bottom", true), capTop = false)
            "ElevationGrid" -> {
                val h = T.attr(g, "height")?.let { T.floatArray(it, "height of $what") } ?: FloatArray(0)
                VrmlGeometry.elevationGrid(T.attrInt(g, "xDimension", 0), T.attrInt(g, "zDimension", 0), T.attrFloat(g, "xSpacing", 1f), T.attrFloat(g, "zSpacing", 1f), h, ccw)
            }
            "Extrusion" -> {
                val cs = T.attr(g, "crossSection")?.let { T.floatArray(it, "crossSection of $what") } ?: floatArrayOf(1f, 1f, 1f, -1f, -1f, -1f, -1f, 1f, 1f, 1f)
                val spine = T.attr(g, "spine")?.let { T.floatArray(it, "spine of $what") } ?: floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f)
                VrmlGeometry.extrusion(cs, spine, T.attr(g, "scale")?.let { T.floatArray(it, "scale of $what") }, T.attr(g, "orientation")?.let { T.floatArray(it, "orientation of $what") },
                    bool(g, "beginCap", true), bool(g, "endCap", true), ccw, bool(g, "solid", true))
            }
            "Text" -> { ctx.notes.add("Text nodes are not rendered"); null }
            "PointSet" -> { ctx.notes.add("PointSet nodes are not rendered"); null }
            "NurbsPatchSurface", "NurbsCurve", "NurbsSweptSurface", "NurbsSwungSurface" -> { ctx.notes.add("NURBS geometry is not supported"); null }
            else -> null
        }
    }

    private fun coords(g: Element, ctx: Ctx): FloatArray? {
        var c: Element? = null
        T.forEachChild(g) { ch -> if (c == null) { val r = resolve(ch, ctx); val n = T.localName(r); if (n == "Coordinate" || n == "CoordinateDouble") c = r } }
        val coordEl = c ?: return null
        return ctx.coordCache.getOrPut(coordEl) { T.attr(coordEl, "point")?.let { T.floatArray(it, "Coordinate point") } ?: FloatArray(0) }
    }

    private fun normals(g: Element, ctx: Ctx): FloatArray? {
        var c: Element? = null
        T.forEachChild(g) { ch -> if (c == null) { val r = resolve(ch, ctx); if (T.localName(r) == "Normal") c = r } }
        val el = c ?: return null
        return T.attr(el, "vector")?.let { T.floatArray(it, "Normal vector") }
    }

    private fun vec(e: Element, name: String): FloatArray? = T.attr(e, name)?.let { T.floatArray(it, "$name of <${e.nodeName}>") }
    private fun bool(e: Element, name: String, default: Boolean): Boolean = when (T.attr(e, name)?.trim()?.lowercase()) { "true" -> true; "false" -> false; else -> default }
}
