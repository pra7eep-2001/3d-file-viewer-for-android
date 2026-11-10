package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * VRML 2.0 / VRML97 (.wrl) importer, also accepting the X3D classic (utf8) encoding and gzip-compressed files.
 * A small tokenizer reads the node syntax (DEF/USE, PROTO declarations are skipped, ROUTEs ignored);
 * Transform/Group hierarchy, Shape with IndexedFaceSet, IndexedLineSet, primitives, ElevationGrid and
 * Extrusion, Material diffuseColor. VRML 1.0 files are rejected with a clear message.
 */
class VrmlImporter : Importer {
    override val formatName = "VRML"
    override val extensions = listOf("wrl", "vrml")

    /** Parsed node: type name, optional DEF name and fields (FloatArray / IntArray / String / List<String> / Boolean / VNode / List<VNode>). */
    private class VNode(val type: String) {
        var defName: String? = null
        val fields = HashMap<String, Any>(8)
        fun node(name: String): VNode? = fields[name] as? VNode
        @Suppress("UNCHECKED_CAST")
        fun nodes(name: String): List<VNode> = when (val v = fields[name]) { is VNode -> listOf(v); is List<*> -> v as List<VNode>; else -> emptyList() }
        fun floats(name: String): FloatArray? = when (val v = fields[name]) { is FloatArray -> v; is IntArray -> FloatArray(v.size) { v[it].toFloat() }; else -> null }
        fun ints(name: String): IntArray? = when (val v = fields[name]) { is IntArray -> v; is FloatArray -> IntArray(v.size) { v[it].toInt() }; else -> null }
        fun bool(name: String, default: Boolean): Boolean = (fields[name] as? Boolean) ?: default
        fun float(name: String, default: Float): Float = floats(name)?.let { if (it.isNotEmpty()) it[0] else null } ?: default
        fun int(name: String, default: Int): Int = ints(name)?.let { if (it.isNotEmpty()) it[0] else null } ?: default
    }

    private class Ctx(val progress: ProgressSink) {
        val defs = HashMap<String, VNode>()
        val meshCache = HashMap<VNode, Mesh?>()
        val notes = LinkedHashSet<String>()
        var depth = 0
        var count = 0
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        progress.progress(0f, "Reading file")
        val text = readText(source)
        val header = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (header.startsWith("#VRML V1", ignoreCase = true)) throw ImportException("This is a VRML 1.0 file; only VRML 2.0 / VRML97 (\"#VRML V2.0 utf8\") is supported. Convert it with a tool such as vrml1tovrml2 or re-export it.")
        if (header.startsWith("#Inventor", ignoreCase = true)) throw ImportException("This is an Open Inventor file, not VRML 2.0; only VRML 2.0 / VRML97 is supported")
        val ctx = Ctx(progress)
        if (!header.startsWith("#VRML V2", ignoreCase = true) && !header.startsWith("#X3D", ignoreCase = true)) {
            if (header.isNotEmpty() && header.startsWith("#")) ctx.notes.add("Unexpected header '${header.take(40)}'; parsed as VRML 2.0")
            else ctx.notes.add("Missing '#VRML V2.0 utf8' header; parsed as VRML 2.0")
        }
        return T.guard("VRML file") {
            val parser = Parser(text, ctx)
            val rootNodes = parser.parseFile()
            progress.progress(0.7f, "Building scene")
            val roots = ArrayList<Node>()
            for (n in rootNodes) buildNode(n, Mat4.identity(), ctx)?.let { roots.add(it) }
            if (roots.isEmpty()) throw ImportException("The VRML file contains no geometry")
            progress.progress(0.95f, "Finalising")
            Model(roots, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN, ctx.notes.toList())
        }
    }

    private fun readText(source: ImportSource): String {
        val size = source.file.length()
        if (size > 512L * 1024 * 1024) throw ImportException("VRML file is too large ($size bytes)")
        BufferedInputStream(FileInputStream(source.file), 1 shl 16).use { raw ->
            raw.mark(4)
            val b0 = raw.read(); val b1 = raw.read()
            raw.reset()
            val input = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 1 shl 16) else raw
            val bos = ByteArrayOutputStream(minOf(size, 1L shl 26).toInt().coerceAtLeast(1024))
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; bos.write(buf, 0, n); if (bos.size() > 1 shl 30) throw ImportException("VRML file is too large") }
            val bytes = bos.toByteArray()
            // skip a UTF-8 BOM
            val off = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            return String(bytes, off, bytes.size - off, Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------------ tokenizer / parser

    private class Parser(val s: String, val ctx: Ctx) {
        var pos = 0
        val n = s.length
        private var line = 1
        private var lastProgress = 0

        private fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ','
        private fun isDelim(c: Char) = isWs(c) || c == '{' || c == '}' || c == '[' || c == ']' || c == '"' || c == '#'

        fun skipWs() {
            while (pos < n) {
                val c = s[pos]
                if (isWs(c)) { if (c == '\n') line++; pos++ }
                else if (c == '#') { while (pos < n && s[pos] != '\n') pos++ }
                else break
            }
            if (pos - lastProgress > 1 shl 20) { lastProgress = pos; ctx.progress.progress(0.7f * pos / n, "Parsing VRML"); ctx.progress.checkCancelled() }
        }

        fun peekChar(): Char { skipWs(); return if (pos < n) s[pos] else '\u0000' }
        fun atEnd(): Boolean { skipWs(); return pos >= n }

        /** Reads an identifier/number token (not a bracket or string). */
        fun word(): String {
            skipWs()
            val start = pos
            while (pos < n && !isDelim(s[pos])) pos++
            if (pos == start) {
                if (pos < n) { pos++; return s[start].toString() } // single delimiter char as a token
                throw err("Unexpected end of file")
            }
            return s.substring(start, pos)
        }

        fun expect(c: Char) { val p = peekChar(); if (p != c) throw err("Expected '$c' but found '${if (pos < n) s[pos] else "end of file"}'"); pos++ }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < n) {
                val c = s[pos++]
                if (c == '\\' && pos < n) { sb.append(s[pos++]); continue }
                if (c == '"') return sb.toString()
                if (c == '\n') line++
                sb.append(c)
            }
            throw err("Unterminated string")
        }

        fun err(msg: String) = ImportException("VRML syntax error at line $line: $msg")

        private fun isNumberStart(c: Char) = (c in '0'..'9') || c == '-' || c == '+' || c == '.'

        fun parseFile(): List<VNode> {
            val roots = ArrayList<VNode>()
            while (!atEnd()) {
                val c = peekChar()
                if (c == '[' || c == ']' || c == '{' || c == '}') { pos++; continue } // stray brackets: tolerate
                if (c == '"') { string(); continue }
                val w = word()
                val node = statement(w)
                if (node != null) roots.add(node)
            }
            return roots
        }

        /** Handles a top-level or child statement starting with word [w]; returns a node when it produced one. */
        fun statement(w: String): VNode? {
            when (w) {
                "DEF" -> { val name = word(); val type = word(); val node = parseNode(type, name); return node }
                "USE" -> { val name = word(); return ctx.defs[name] ?: throw err("USE $name refers to an undefined node") }
                "NULL" -> return null
                "PROTO" -> { word(); skipBracket('[', ']'); skipBracket('{', '}'); ctx.notes.add("PROTO definitions are not expanded"); return null }
                "EXTERNPROTO" -> { word(); skipBracket('[', ']'); if (peekChar() == '[') skipBracket('[', ']') else string(); ctx.notes.add("EXTERNPROTO definitions are not expanded"); return null }
                "ROUTE" -> { word(); val to = word(); if (to == "TO") word(); return null }
                "PROFILE", "COMPONENT", "UNIT", "IMPORT", "EXPORT" -> { word(); if (w == "UNIT") { word() }; return null }
                "META" -> { string(); string(); return null }
                else -> return parseNode(w, null)
            }
        }

        fun skipBracket(open: Char, close: Char) {
            expect(open)
            var depth = 1
            while (pos < n && depth > 0) {
                val c = peekChar()
                if (pos >= n) break
                when (c) {
                    open -> { depth++; pos++ }
                    close -> { depth--; pos++ }
                    '"' -> string()
                    '{', '[' -> { pos++; depth++ }
                    '}', ']' -> { pos++; depth-- }
                    else -> word()
                }
            }
            if (depth > 0) throw err("Unbalanced '$open'")
        }

        fun parseNode(type: String, defName: String?): VNode {
            if (ctx.depth > 500) throw err("nodes are nested too deeply")
            val node = VNode(type)
            node.defName = defName
            if (defName != null) ctx.defs[defName] = node
            if (peekChar() != '{') throw err("Expected '{' after node type '$type'")
            pos++
            ctx.depth++
            try {
                while (true) {
                    val c = peekChar()
                    if (pos >= n) throw err("Unexpected end of file inside node '$type'")
                    if (c == '}') { pos++; break }
                    if (c == '"' || c == '[' || c == ']' || c == '{') { pos++; if (c == '"') { pos--; string() }; continue } // tolerate junk
                    val fieldName = word()
                    if (fieldName == "ROUTE") { word(); val to = word(); if (to == "TO") word(); continue }
                    if (fieldName == "DEF" || fieldName == "USE" || fieldName == "PROTO" || fieldName == "EXTERNPROTO") { // node in a children-less context
                        statement(fieldName); continue
                    }
                    if (fieldName == "field" || fieldName == "eventIn" || fieldName == "eventOut" || fieldName == "exposedField" || fieldName == "inputOnly" || fieldName == "outputOnly" || fieldName == "inputOutput" || fieldName == "initializeOnly") {
                        // Script interface declarations: type name [value]
                        word(); word(); if (fieldName == "field" || fieldName == "exposedField" || fieldName == "initializeOnly" || fieldName == "inputOutput") parseValue("")
                        continue
                    }
                    val value = parseValue(fieldName)
                    if (value != null) node.fields[fieldName] = value
                }
            } finally { ctx.depth-- }
            if (++ctx.count % 1024 == 0) ctx.progress.checkCancelled()
            return node
        }

        private fun isIntField(name: String) = name.endsWith("Index") || name == "index" || name == "whichChoice" || name == "xDimension" || name == "zDimension" ||
            name == "vertexCount" || name == "stripCount" || name == "fanCount" || name == "level"

        /** Parses a field value following its name. */
        fun parseValue(fieldName: String): Any? {
            val c = peekChar()
            if (pos >= n) throw err("Unexpected end of file in field '$fieldName'")
            return when {
                c == '[' -> parseList(fieldName)
                c == '"' -> { val l = ArrayList<String>(); while (peekChar() == '"') l.add(string()); if (l.size == 1) l[0] else l }
                isNumberStart(c) -> numbers(fieldName)
                c == '{' || c == '}' || c == ']' -> null
                else -> {
                    val w = word()
                    when (w) {
                        "TRUE" -> true
                        "FALSE" -> false
                        "NULL" -> null
                        "IS" -> { word(); null }
                        "DEF", "USE" -> statement(w)
                        else -> if (peekChar() == '{') parseNode(w, null) else w // enum-like token (X3D classic)
                    }
                }
            }
        }

        /** Parses a list "[ ... ]" of numbers, strings or nodes. */
        fun parseList(fieldName: String): Any? {
            expect('[')
            val c = peekChar()
            if (c == ']') { pos++; return if (isIntField(fieldName)) IntArray(0) else FloatArray(0) }
            if (isNumberStart(c)) { val v = numbers(fieldName); expect(']'); return v }
            if (c == '"') { val l = ArrayList<String>(); while (peekChar() == '"') l.add(string()); expect(']'); return l }
            val nodes = ArrayList<VNode>()
            while (true) {
                val p = peekChar()
                if (pos >= n) throw err("Unterminated '[' in field '$fieldName'")
                if (p == ']') { pos++; break }
                if (p == '"') { string(); continue }
                if (p == '{' || p == '[' || p == '}') { pos++; continue }
                val w = word()
                val nd = statement(w)
                if (nd != null) nodes.add(nd)
            }
            return nodes
        }

        /** Reads consecutive numeric tokens. */
        fun numbers(fieldName: String): Any {
            val what = "field '$fieldName'"
            if (isIntField(fieldName)) {
                val out = IntList(256)
                while (true) { val c = peekChar(); if (pos >= n || !isNumberStart(c)) break; pos = T.parseIntAt(s, pos, n, out, what) }
                return out.toArray()
            }
            val out = FloatList(256)
            while (true) { val c = peekChar(); if (pos >= n || !isNumberStart(c)) break; pos = T.parseFloatAt(s, pos, n, out, what) }
            return out.toArray()
        }
    }

    // ------------------------------------------------------------------ scene building

    private fun buildNode(v: VNode, parentWorld: Mat4, ctx: Ctx): Node? {
        if (++ctx.count % 512 == 0) ctx.progress.checkCancelled()
        return when (v.type) {
            "Transform" -> {
                val local = VrmlGeometry.transformMatrix(v.floats("translation"), v.floats("rotation"), v.floats("scale"), v.floats("scaleOrientation"), v.floats("center"))
                group(v.nodes("children"), parentWorld.times(local), ctx, v.defName ?: "Transform", v.defName != null)
            }
            "Group", "Anchor", "Billboard", "Collision", "StaticGroup", "CADAssembly", "CADLayer", "CADPart" -> group(v.nodes("children"), parentWorld, ctx, v.defName ?: v.type, v.defName != null)
            "Switch" -> {
                val kids = v.nodes("choice").ifEmpty { v.nodes("children") }
                val which = v.int("whichChoice", -1)
                if (which < 0 || which >= kids.size) null else group(listOf(kids[which]), parentWorld, ctx, v.defName ?: "Switch", v.defName != null)
            }
            "LOD" -> { val kids = v.nodes("level").ifEmpty { v.nodes("children") }; if (kids.isEmpty()) null else group(listOf(kids[0]), parentWorld, ctx, v.defName ?: "LOD", v.defName != null) }
            "Shape", "CADFace" -> shape(v, parentWorld, ctx)
            "Inline" -> { ctx.notes.add("Inline references to external files are not loaded"); null }
            else -> null
        }
    }

    private fun group(children: List<VNode>, world: Mat4, ctx: Ctx, label: String, explicit: Boolean): Node? {
        if (ctx.depth > 200) throw ImportException("VRML scene graph is nested too deeply")
        ctx.depth++
        val out = ArrayList<Node>()
        try { for (c in children) buildNode(c, world, ctx)?.let { out.add(it) } } finally { ctx.depth-- }
        if (out.isEmpty()) return null
        if (out.size == 1 && out[0].children.isEmpty() && out[0].mesh != null) {
            val c = out[0]
            return Node(if (explicit || c.name == "Shape") label else c.name, c.mesh, c.color)
        }
        val n = Node(label)
        for (c in out) n.add(c)
        return n
    }

    private fun shape(v: VNode, world: Mat4, ctx: Ctx): Node? {
        val geo = v.node("geometry") ?: return null
        val mesh = ctx.meshCache.getOrPut(geo) { geometry(geo, ctx) } ?: return null
        var color = 0
        v.node("appearance")?.let { app ->
            app.node("material")?.let { mat ->
                val d = mat.floats("diffuseColor")
                if (d != null && d.size >= 3) color = T.argb(d[0], d[1], d[2], 1f - mat.float("transparency", 0f).coerceIn(0f, 1f))
            }
            if (app.node("texture") != null) ctx.notes.add("Textures ignored")
        }
        return Node(v.defName ?: "Shape", mesh.transformed(world), color)
    }

    private fun geometry(g: VNode, ctx: Ctx): Mesh? {
        val what = "${g.type}${g.defName?.let { " DEF $it" } ?: ""}"
        val ccw = g.bool("ccw", true)
        fun points(): FloatArray? = g.node("coord")?.floats("point")
        fun normals(): FloatArray? = g.node("normal")?.floats("vector")
        return when (g.type) {
            "IndexedFaceSet" -> {
                val p = points() ?: return null
                val ci = g.ints("coordIndex") ?: return null
                VrmlGeometry.indexedFaceSet(p, ci, normals(), g.ints("normalIndex"), g.bool("normalPerVertex", true), ccw, what)
            }
            "IndexedTriangleSet" -> { val p = points() ?: return null; VrmlGeometry.triangleSet(p, g.ints("index") ?: return null, normals(), ccw, what) }
            "TriangleSet" -> { val p = points() ?: return null; VrmlGeometry.triangleSet(p, null, normals(), ccw, what) }
            "IndexedTriangleStripSet", "IndexedTriangleFanSet" -> { val p = points() ?: return null; VrmlGeometry.stripsOrFans(p, g.ints("index") ?: return null, null, g.type == "IndexedTriangleFanSet", ccw, what) }
            "TriangleStripSet" -> { val p = points() ?: return null; VrmlGeometry.stripsOrFans(p, null, g.ints("stripCount") ?: return null, false, ccw, what) }
            "TriangleFanSet" -> { val p = points() ?: return null; VrmlGeometry.stripsOrFans(p, null, g.ints("fanCount") ?: return null, true, ccw, what) }
            "IndexedLineSet" -> { val p = points() ?: return null; VrmlGeometry.indexedLineSet(p, g.ints("coordIndex") ?: return null, what) }
            "LineSet" -> { val p = points() ?: return null; VrmlGeometry.lineSet(p, g.ints("vertexCount"), what) }
            "Box" -> { val s = g.floats("size") ?: floatArrayOf(2f, 2f, 2f); if (s.size < 3) null else T.box(s[0], s[1], s[2]) }
            "Sphere" -> T.sphere(g.float("radius", 1f))
            "Cylinder" -> { val r = g.float("radius", 1f); T.cylinder(r, r, g.float("height", 2f), side = g.bool("side", true), capBottom = g.bool("bottom", true), capTop = g.bool("top", true)) }
            "Cone" -> T.cylinder(g.float("bottomRadius", 1f), 0f, g.float("height", 2f), side = g.bool("side", true), capBottom = g.bool("bottom", true), capTop = false)
            "ElevationGrid" -> VrmlGeometry.elevationGrid(g.int("xDimension", 0), g.int("zDimension", 0), g.float("xSpacing", 1f), g.float("zSpacing", 1f), g.floats("height") ?: FloatArray(0), ccw)
            "Extrusion" -> VrmlGeometry.extrusion(
                g.floats("crossSection") ?: floatArrayOf(1f, 1f, 1f, -1f, -1f, -1f, -1f, 1f, 1f, 1f),
                g.floats("spine") ?: floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
                g.floats("scale"), g.floats("orientation"), g.bool("beginCap", true), g.bool("endCap", true), ccw, g.bool("solid", true))
            "Text" -> { ctx.notes.add("Text nodes are not rendered"); null }
            "PointSet" -> { ctx.notes.add("PointSet nodes are not rendered"); null }
            else -> null
        }
    }
}
