package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 3MF importer. Reads the 3D model part of the OPC package (located through _rels/.rels, falling back to
 * 3D/3dmodel.model); mesh objects, components with transforms, build items, base materials / colour groups
 * and units. The Production extension `path` attribute (objects in other model parts) is ignored.
 */
class ThreeMfImporter : Importer {
    override val formatName = "3MF"
    override val extensions = listOf("3mf")

    private class Component(val objectId: String, val transform: Mat4)
    private class Obj(val id: String, val name: String?, val type: String?) {
        val pos = FloatList(1 shl 12); val idx = IntList(1 shl 12)
        var color = 0
        var pid: String? = null; var pindex = -1
        var firstTriPid: String? = null; var firstTriP1 = -1
        val components = ArrayList<Component>()
    }
    private class Item(val objectId: String, val transform: Mat4)

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val zip = try { ZipFile(source.file) } catch (e: Exception) { throw ImportException("Not a 3MF package (expected a zip archive): ${e.message ?: ""}".trimEnd(' ', ':'), e) }
        zip.use { z ->
            progress.progress(0f, "Locating model")
            val partName = locateModelPart(z)
            val entry = z.getEntry(partName) ?: z.getEntry(partName.removePrefix("/"))
                ?: z.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".model") }
                ?: throw ImportException("The 3MF package contains no 3D model part ($partName)")
            val h = Handler(progress, entry.size)
            T.guard("3MF model part") {
                val counting = T.CountingInputStream(BufferedInputStream(z.getInputStream(entry), 1 shl 16))
                h.counting = counting
                try { T.saxParser().parse(InputSource(counting), h) }
                catch (e: org.xml.sax.SAXParseException) { throw ImportException("3MF model part is not well-formed XML (line ${e.lineNumber}): ${e.message}", e) }
                catch (e: SAXException) { val c = e.cause ?: e.exception; if (c is ImportException) throw c; throw ImportException("3MF model part could not be parsed: ${e.message}", e) }
                finally { counting.close() }
            }
            if (!h.sawModel) throw ImportException("The 3MF model part has no <model> root element")
            return build(h, source)
        }
    }

    private fun locateModelPart(z: ZipFile): String {
        val rels: ZipEntry? = z.getEntry("_rels/.rels")
        if (rels != null) {
            try {
                var target: String? = null
                val handler = object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                        if (qName.substringAfter(':') == "Relationship") {
                            val type = a.getValue("Type") ?: ""
                            if (target == null && (type.endsWith("/3dmodel") || type.contains("3dmodel"))) target = a.getValue("Target")
                        }
                    }
                }
                z.getInputStream(rels).use { T.saxParser().parse(InputSource(it), handler) }
                val t = target
                if (t != null) return t.removePrefix("/")
            } catch (e: Exception) { /* fall back below */ }
        }
        return "3D/3dmodel.model"
    }

    private fun build(h: Handler, source: ImportSource): Model {
        val notes = ArrayList<String>()
        if (h.unitName != null && h.units == null) notes.add("Unit '${h.unitName}' is not supported; coordinates shown as-is")
        if (h.externalPaths) notes.add("Components stored in other model parts (Production extension) were ignored")
        if (h.sawTexture) notes.add("Textures ignored")
        if (h.objects.isEmpty()) throw ImportException("The 3MF model contains no objects")
        val meshCache = HashMap<String, Mesh>()

        fun colorOf(o: Obj): Int {
            if (o.color != 0) return o.color
            val pid = o.pid
            if (pid != null) { h.colorOf(pid, o.pindex)?.let { return it } }
            val tp = o.firstTriPid
            if (tp != null) { h.colorOf(tp, o.firstTriP1)?.let { return it } }
            return 0
        }

        fun instantiate(o: Obj, m: Mat4, depth: Int, nameOverride: String?): Node? {
            if (depth > 32) throw ImportException("3MF components are nested too deeply (object ${o.id})")
            val name = nameOverride ?: o.name ?: "Object ${o.id}"
            if (o.idx.size > 0) {
                val base = meshCache.getOrPut(o.id) { Mesh(o.pos.toArray(), null, o.idx.toArray()) }
                return Node(name, base.transformed(m), colorOf(o))
            }
            if (o.components.isEmpty()) return null
            val n = Node(name, null, colorOf(o))
            for (c in o.components) {
                val child = h.objects[c.objectId] ?: continue
                instantiate(child, m.times(c.transform), depth + 1, null)?.let { n.add(it) }
            }
            return if (n.children.isEmpty()) null else n
        }

        val roots = ArrayList<Node>()
        if (h.items.isNotEmpty()) {
            for (it in h.items) {
                val o = h.objects[it.objectId] ?: continue
                instantiate(o, it.transform, 0, null)?.let { roots.add(it) }
            }
        }
        if (roots.isEmpty()) {
            // No (usable) build section: show every object that is not referenced as a component
            val referenced = HashSet<String>(); for (o in h.objects.values) for (c in o.components) referenced.add(c.objectId)
            for (o in h.objectOrder) if (o.id !in referenced) instantiate(o, Mat4.identity(), 0, null)?.let { roots.add(it) }
        }
        if (roots.isEmpty()) throw ImportException("The 3MF model contains no geometry")
        return Model(roots, formatName, source.displayName, source.sizeBytes, h.units ?: ModelUnits.MILLIMETER, notes)
    }

    private class Handler(val progress: ProgressSink, val size: Long) : DefaultHandler() {
        var counting: T.CountingInputStream? = null
        var sawModel = false
        var unitName: String? = null
        var units: ModelUnits? = null
        var externalPaths = false
        var sawTexture = false
        val objects = HashMap<String, Obj>()
        val objectOrder = ArrayList<Obj>()
        val items = ArrayList<Item>()
        /** material group id -> list of colours (base materials or colour groups) */
        private val groups = HashMap<String, IntList>()
        private var group: IntList? = null
        private var obj: Obj? = null
        private var elements = 0

        fun colorOf(pid: String, index: Int): Int? {
            val g = groups[pid] ?: return null
            if (g.size == 0) return null
            val i = if (index in 0 until g.size) index else 0
            val c = g[i]
            return if (c == 0) null else c
        }

        private fun err(msg: String): SAXException = SAXException(ImportException(msg))

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            val name = qName.substringAfter(':')
            when (name) {
                "model" -> { sawModel = true; unitName = a.getValue("unit"); units = if (unitName == null) ModelUnits.MILLIMETER else T.unitsByName(unitName) }
                "object" -> {
                    val id = a.getValue("id") ?: throw err("3MF object without id")
                    val o = Obj(id, a.getValue("name"), a.getValue("type"))
                    o.pid = a.getValue("pid"); o.pindex = a.getValue("pindex")?.toIntOrNull() ?: -1
                    objects[id] = o; objectOrder.add(o); obj = o
                }
                "vertex" -> {
                    val o = obj ?: return
                    o.pos.add(f(a, "x", o), f(a, "y", o), f(a, "z", o))
                }
                "triangle" -> {
                    val o = obj ?: return
                    val vc = o.pos.size / 3
                    val v1 = i(a, "v1", o); val v2 = i(a, "v2", o); val v3 = i(a, "v3", o)
                    if (v1 >= vc || v2 >= vc || v3 >= vc || v1 < 0 || v2 < 0 || v3 < 0) throw err("Triangle in 3MF object ${o.id} references vertex ${maxOf(v1, v2, v3)} but only $vc vertices are defined")
                    o.idx.add(v1, v2, v3)
                    if (o.firstTriPid == null) { val pid = a.getValue("pid"); if (pid != null) { o.firstTriPid = pid; o.firstTriP1 = a.getValue("p1")?.toIntOrNull() ?: 0 } }
                }
                "component" -> {
                    val o = obj ?: return
                    if (a.getValue("path") != null || a.getValue("p:path") != null) { externalPaths = true; return }
                    val oid = a.getValue("objectid") ?: return
                    o.components.add(Component(oid, parseTransform(a.getValue("transform"), "component")))
                }
                "item" -> {
                    if (a.getValue("path") != null || a.getValue("p:path") != null) externalPaths = true
                    val oid = a.getValue("objectid") ?: return
                    items.add(Item(oid, parseTransform(a.getValue("transform"), "build item")))
                }
                "basematerials", "colorgroup" -> { val id = a.getValue("id") ?: return; group = IntList(16).also { groups[id] = it } }
                "base" -> group?.add(T.hexColor(a.getValue("displaycolor")))
                "color" -> group?.add(T.hexColor(a.getValue("color")))
                "texture2d", "texture2dgroup" -> sawTexture = true
            }
            if (++elements % 4096 == 0) {
                val c = counting?.count ?: 0L
                progress.progress(if (size > 0) (0.9f * c / size).coerceAtMost(0.9f) else -1f, "Reading 3MF")
                progress.checkCancelled()
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = qName.substringAfter(':')
            when (name) { "object" -> obj = null; "basematerials", "colorgroup" -> group = null }
        }

        private fun f(a: Attributes, n: String, o: Obj): Float {
            val s = a.getValue(n) ?: throw err("Vertex without '$n' in 3MF object ${o.id}")
            return s.toFloatOrNull() ?: throw err("Invalid coordinate '$s' in 3MF object ${o.id}")
        }
        private fun i(a: Attributes, n: String, o: Obj): Int {
            val s = a.getValue(n) ?: throw err("Triangle without '$n' in 3MF object ${o.id}")
            return s.toIntOrNull() ?: throw err("Invalid vertex index '$s' in 3MF object ${o.id}")
        }

        private fun parseTransform(s: String?, what: String): Mat4 {
            if (s == null) return Mat4.identity()
            val v = try { T.floatArray(s, "$what transform") } catch (e: ImportException) { throw err(e.message!!) }
            if (v.size != 12) throw err("3MF $what transform must have 12 numbers but has ${v.size}")
            // 3MF matrices are row vectors (translation in the last row): column c of our matrix is row c of the file.
            val m = Mat4()
            for (c in 0 until 4) { m.m[c * 4] = v[c * 3]; m.m[c * 4 + 1] = v[c * 3 + 1]; m.m[c * 4 + 2] = v[c * 3 + 2] }
            m.m[15] = 1f
            return m
        }
    }
}
