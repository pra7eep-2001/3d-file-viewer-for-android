package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * AMF (Additive Manufacturing File Format) importer. Plain XML or a zip archive containing the .amf.
 * Objects become nodes, volumes become child nodes; colours from volume/material/object; units from the
 * `unit` attribute. Constellations (instances with translation/rotation) are honoured.
 * Parsed with SAX so that large files stream.
 */
class AmfImporter : Importer {
    override val formatName = "AMF"
    override val extensions = listOf("amf")

    private class Volume(val name: String?, val materialId: String?, val color: Int) { val tris = IntList(1 shl 12) }
    private class AmfObject(val id: String) {
        var name: String? = null
        var color = 0
        val pos = FloatList(1 shl 12); val nrm = FloatList(16); var normalCount = 0
        val volumes = ArrayList<Volume>()
    }
    private class Instance(val objectId: String) { var dx = 0f; var dy = 0f; var dz = 0f; var rx = 0f; var ry = 0f; var rz = 0f }
    private class Constellation(val id: String) { val instances = ArrayList<Instance>() }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val file = source.file
        if (file.length() < 10) throw ImportException("File is too small to be an AMF model")
        val zipped = FileInputStream(file).use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        val handler = Handler(progress, file.length())
        progress.progress(0f, if (zipped) "Decompressing" else "Reading AMF")
        T.guard("AMF file") {
            val raw = BufferedInputStream(FileInputStream(file), 1 shl 16)
            val input: InputStream = if (zipped) openZipEntry(raw) else raw
            val counting = T.CountingInputStream(input)
            handler.counting = counting
            try {
                T.saxParser().parse(InputSource(counting), handler)
            } catch (e: org.xml.sax.SAXParseException) {
                throw ImportException("AMF file is not well-formed XML (line ${e.lineNumber}): ${e.message}", e)
            } catch (e: SAXException) {
                val c = e.cause ?: e.exception
                if (c is ImportException) throw c
                throw ImportException("AMF file could not be parsed: ${e.message}", e)
            } finally { counting.close() }
        }
        if (!handler.sawRoot) throw ImportException("Not an AMF file (missing <amf> root element)")
        return build(handler, source)
    }

    private fun openZipEntry(raw: InputStream): InputStream {
        val zin = ZipInputStream(raw)
        while (true) {
            val e = zin.nextEntry ?: throw ImportException("The zip archive contains no .amf file")
            if (e.isDirectory) continue
            val n = e.name.lowercase()
            if (n.endsWith(".amf") || n.endsWith(".xml")) return zin
        }
    }

    private fun build(h: Handler, source: ImportSource): Model {
        val notes = ArrayList<String>()
        if (h.unitName != null && h.units == null) notes.add("Unit '${h.unitName}' is not supported; coordinates shown as-is")
        if (h.vertexColors) notes.add("Per-vertex and per-triangle colours ignored")
        if (h.textures) notes.add("Textures ignored")
        val objects = h.objects
        if (objects.isEmpty()) throw ImportException("The AMF file contains no objects")

        fun nodeFor(o: AmfObject, m: Mat4): Node? {
            val positions = o.pos.toArray()
            val normals = if (o.normalCount > 0 && o.normalCount == positions.size / 3) o.nrm.toArray() else null
            val vols = o.volumes.filter { it.tris.size > 0 }
            if (vols.isEmpty()) return null
            val baseName = o.name ?: "Object ${o.id}"
            fun volMesh(v: Volume) = Mesh(positions, normals, v.tris.toArray()).transformed(m)
            fun volColor(v: Volume): Int = when {
                v.color != 0 -> v.color
                v.materialId != null && (h.materialColors[v.materialId] ?: 0) != 0 -> h.materialColors[v.materialId]!!
                else -> o.color
            }
            if (vols.size == 1) {
                val v = vols[0]
                val name = o.name ?: v.name ?: (if (v.materialId != null) h.materialNames[v.materialId] else null) ?: baseName
                return Node(name, volMesh(v), volColor(v))
            }
            val n = Node(baseName, null, o.color)
            for ((i, v) in vols.withIndex()) {
                val name = v.name ?: (if (v.materialId != null) h.materialNames[v.materialId] else null) ?: "Volume ${i + 1}"
                n.add(Node(name, volMesh(v), volColor(v)))
            }
            return n
        }

        val roots = ArrayList<Node>()
        val instanced = HashSet<String>()
        val byId = HashMap<String, AmfObject>(); for (o in objects) byId[o.id] = o
        for (c in h.constellations) {
            if (c.instances.isEmpty()) continue
            val cn = Node("Constellation ${c.id}")
            for (inst in c.instances) {
                val o = byId[inst.objectId] ?: continue
                instanced.add(inst.objectId)
                var m = Mat4.translation(inst.dx, inst.dy, inst.dz)
                m = m.times(Mat4.rotationAxis(Math.toRadians(inst.rz.toDouble()).toFloat(), 0f, 0f, 1f))
                m = m.times(Mat4.rotationAxis(Math.toRadians(inst.ry.toDouble()).toFloat(), 0f, 1f, 0f))
                m = m.times(Mat4.rotationAxis(Math.toRadians(inst.rx.toDouble()).toFloat(), 1f, 0f, 0f))
                nodeFor(o, m)?.let { cn.add(it) }
            }
            if (cn.children.isNotEmpty()) roots.add(cn)
        }
        for (o in objects) if (o.id !in instanced) nodeFor(o, Mat4.identity())?.let { roots.add(it) }
        if (roots.isEmpty()) throw ImportException("The AMF file contains no geometry")
        return Model(roots, formatName, source.displayName, source.sizeBytes, h.units ?: ModelUnits.UNKNOWN, notes)
    }

    /** SAX handler collecting objects, materials and constellations. */
    private class Handler(val progress: ProgressSink, val fileSize: Long) : DefaultHandler() {
        var counting: T.CountingInputStream? = null
        var sawRoot = false
        var unitName: String? = null
        var units: ModelUnits? = null
        val objects = ArrayList<AmfObject>()
        val materialColors = HashMap<String, Int>()
        val materialNames = HashMap<String, String>()
        val constellations = ArrayList<Constellation>()
        var vertexColors = false
        var textures = false

        private var obj: AmfObject? = null
        private var vol: Volume? = null
        private var materialId: String? = null
        private var constellation: Constellation? = null
        private var instance: Instance? = null
        private var depth = 0
        private val stack = arrayOfNulls<String>(64)
        private val text = StringBuilder(32)
        private var capture = false
        // scratch for coordinates / triangles / colours
        private var x = 0f; private var y = 0f; private var z = 0f
        private var v1 = -1; private var v2 = -1; private var v3 = -1
        private var cr = -1f; private var cg = -1f; private var cb = -1f; private var ca = 1f
        private var colorOwner = ""   // "object" | "volume" | "material" | "other"
        private var metadataType: String? = null
        private var inNormal = false
        private var elements = 0
        private var volName: String? = null; private var volMaterial: String? = null; private var volColor = 0; private var volTris: IntList? = null
        private var pendingVolumeTris = IntList(0)

        private fun parent(): String = if (depth >= 2) stack[depth - 2] ?: "" else ""

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val name = qName.substringAfter(':')
            if (depth >= stack.size) throw SAXException(ImportException("AMF file is nested too deeply at <$name>"))
            stack[depth++] = name
            text.setLength(0); capture = false
            when (name) {
                "amf" -> { sawRoot = true; unitName = attributes.getValue("unit"); units = T.unitsByName(unitName); if (unitName == null) units = ModelUnits.MILLIMETER }
                "object" -> { obj = AmfObject(attributes.getValue("id") ?: objects.size.toString()); objects.add(obj!!) }
                "volume" -> { volName = null; volMaterial = attributes.getValue("materialid"); volColor = 0; pendingVolumeTris = IntList(1 shl 12)
                    vol = Volume(null, volMaterial, 0) }
                "material" -> { materialId = attributes.getValue("id"); cr = -1f; cg = -1f; cb = -1f; ca = 1f }
                "constellation" -> { constellation = Constellation(attributes.getValue("id") ?: constellations.size.toString()); constellations.add(constellation!!) }
                "instance" -> { instance = Instance(attributes.getValue("objectid") ?: ""); constellation?.instances?.add(instance!!) }
                "coordinates" -> { x = 0f; y = 0f; z = 0f; inNormal = false }
                "normal" -> { if (parent() == "vertex") { x = 0f; y = 0f; z = 0f; inNormal = true } }
                "triangle" -> { v1 = -1; v2 = -1; v3 = -1 }
                "color" -> { cr = -1f; cg = -1f; cb = -1f; ca = 1f; colorOwner = parent(); if (colorOwner == "vertex" || colorOwner == "triangle") vertexColors = true }
                "texture", "texmap", "map" -> textures = true
                "metadata" -> { metadataType = attributes.getValue("type"); capture = true }
                "x", "y", "z", "nx", "ny", "nz", "v1", "v2", "v3", "r", "g", "b", "a",
                "deltax", "deltay", "deltaz", "rx", "ry", "rz" -> capture = true
            }
            if (++elements % 4096 == 0) {
                val c = counting?.count ?: 0L
                progress.progress(if (fileSize > 0) (0.9f * c / fileSize).coerceAtMost(0.9f) else -1f, "Reading AMF")
                progress.checkCancelled()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { if (capture) text.append(ch, start, length) }

        private fun num(name: String): Float {
            val s = text.toString().trim()
            return s.toFloatOrNull() ?: throw SAXException(ImportException("Invalid number '${s.take(32)}' in <$name> of AMF object ${obj?.id ?: ""}"))
        }
        private fun index(name: String): Int {
            val s = text.toString().trim()
            return s.toIntOrNull() ?: throw SAXException(ImportException("Invalid vertex index '${s.take(32)}' in <$name> of AMF object ${obj?.id ?: ""}"))
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = qName.substringAfter(':')
            val p = parent()
            when (name) {
                "x" -> if (p == "coordinates" || p == "normal") x = num(name)
                "y" -> if (p == "coordinates" || p == "normal") y = num(name)
                "z" -> if (p == "coordinates" || p == "normal") z = num(name)
                "nx" -> x = num(name)
                "ny" -> y = num(name)
                "nz" -> z = num(name)
                "coordinates" -> obj?.pos?.add(x, y, z)
                "normal" -> if (inNormal) { val o = obj; if (o != null) { o.nrm.add(x, y, z); o.normalCount++ }; inNormal = false }
                "v1" -> v1 = index(name)
                "v2" -> v2 = index(name)
                "v3" -> v3 = index(name)
                "triangle" -> {
                    val o = obj ?: return
                    val vc = o.pos.size / 3
                    if (v1 < 0 || v2 < 0 || v3 < 0) throw SAXException(ImportException("Triangle without v1/v2/v3 in AMF object ${o.id}"))
                    if (v1 >= vc || v2 >= vc || v3 >= vc) throw SAXException(ImportException("Triangle references vertex ${maxOf(v1, v2, v3)} but object ${o.id} has only $vc vertices"))
                    pendingVolumeTris.add(v1, v2, v3)
                }
                "r" -> cr = num(name)
                "g" -> cg = num(name)
                "b" -> cb = num(name)
                "a" -> ca = num(name)
                "color" -> {
                    if (cr >= 0f && cg >= 0f && cb >= 0f) {
                        val c = T.argb(cr, cg, cb, ca)
                        when (colorOwner) {
                            "object" -> obj?.color = c
                            "volume" -> volColor = c
                            "material" -> materialId?.let { materialColors[it] = c }
                        }
                    }
                }
                "metadata" -> {
                    val v = text.toString().trim()
                    if (metadataType.equals("name", true) && v.isNotEmpty()) when (p) {
                        "object" -> obj?.name = v
                        "volume" -> volName = v
                        "material" -> materialId?.let { materialNames[it] = v }
                    }
                }
                "volume" -> {
                    val o = obj
                    if (o != null) { val v = Volume(volName, volMaterial, volColor); v.tris.data = pendingVolumeTris.data; v.tris.size = pendingVolumeTris.size; o.volumes.add(v) }
                    vol = null
                }
                "object" -> obj = null
                "material" -> materialId = null
                "deltax" -> instance?.dx = num(name)
                "deltay" -> instance?.dy = num(name)
                "deltaz" -> instance?.dz = num(name)
                "rx" -> instance?.rx = num(name)
                "ry" -> instance?.ry = num(name)
                "rz" -> instance?.rz = num(name)
                "instance" -> instance = null
                "constellation" -> constellation = null
            }
            capture = false
            depth--
        }
    }
}
