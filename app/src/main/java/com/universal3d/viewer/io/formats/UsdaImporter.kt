package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import com.universal3d.viewer.io.formats.TextFormatSupport as T
import java.io.FileInputStream

/**
 * USD ASCII (.usda) importer with a tolerant hand-written parser: Xform/Scope hierarchy, Mesh prims
 * (faceVertexCounts/faceVertexIndices/points/normals/displayColor), gprim primitives (Sphere, Cube, Cylinder,
 * Cone, Capsule), xformOp transforms (translate, rotate*, scale, orient, transform in xformOpOrder), metersPerUnit
 * and upAxis layer metadata, UsdPreviewSurface diffuseColor through material bindings. References, payloads,
 * variants and sublayers are not composed. Binary (.usdc) and .usdz files are rejected with a clear message.
 */
class UsdaImporter : Importer {
    override val formatName = "USD"
    override val extensions = listOf("usda", "usd")

    private class Prim(val spec: String, val type: String?, val name: String, val parent: Prim?) {
        val children = ArrayList<Prim>()
        val attrs = HashMap<String, Any>(8)
        val path: String = if (parent == null) "/$name" else "${parent.path}/$name"
        fun floats(n: String): FloatArray? = attrs[n] as? FloatArray
        fun ints(n: String): IntArray? = attrs[n] as? IntArray
        fun str(n: String): String? = attrs[n] as? String
        fun float(n: String, d: Float): Float = floats(n)?.let { if (it.isNotEmpty()) it[0] else null } ?: d
    }

    private class Ctx(val progress: ProgressSink) {
        val notes = LinkedHashSet<String>()
        var metersPerUnit: Double? = null
        var upAxis: String? = null
        val materialColors = HashMap<String, Int>()
        var count = 0
    }

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val size = source.file.length()
        val head = ByteArray(16)
        val n = FileInputStream(source.file).use { it.read(head) }
        val hs = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1) else ""
        if (hs.startsWith("PXR-USDC")) throw ImportException("Binary USD (.usdc) is not supported; export as .usda")
        if (hs.startsWith("PK")) throw ImportException("USDZ packages are not supported; extract the .usda file from the archive or export as .usda")
        if (!hs.startsWith("#usda")) throw ImportException("Not a USD ASCII file (expected the file to start with '#usda 1.0')")
        if (size > 1L shl 30) throw ImportException("USD file is too large to load ($size bytes)")
        progress.progress(0f, "Reading file")
        val text = String(source.file.readBytes(), Charsets.UTF_8)
        return T.guard("USD file") {
            val ctx = Ctx(progress)
            val parser = Parser(text, ctx)
            val roots = parser.parseLayer()
            progress.progress(0.7f, "Building scene")
            collectMaterials(roots, ctx)
            val nodes = ArrayList<Node>()
            for (p in roots) buildNode(p, Mat4.identity(), ctx)?.let { nodes.add(it) }
            if (nodes.isEmpty()) throw ImportException("The USD file contains no geometry (no Mesh or primitive prims)")
            var units = ModelUnits.UNKNOWN
            ctx.metersPerUnit?.let { mpu ->
                val u = T.unitsByMeters(mpu)
                if (u != null) units = u else ctx.notes.add("metersPerUnit = $mpu is not a standard unit; coordinates shown as-is")
            }
            ctx.upAxis?.let { if (!it.equals("Y", true)) ctx.notes.add("File declares upAxis = $it (geometry shown in file coordinates)") }
            progress.progress(0.95f, "Finalising")
            Model(nodes, formatName, source.displayName, source.sizeBytes, units, ctx.notes.toList())
        }
    }

    // ------------------------------------------------------------------ parser

    private class Parser(val s: String, val ctx: Ctx) {
        var pos = 0
        val n = s.length
        private var line = 1
        private var lastProgress = 0

        fun err(msg: String) = ImportException("USD syntax error at line $line: $msg")

        private fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
        private fun isDelim(c: Char) = isWs(c) || c == '{' || c == '}' || c == '(' || c == ')' || c == '[' || c == ']' || c == '=' || c == ',' || c == '"' || c == '\'' || c == '#' || c == '<' || c == '>' || c == '@' || c == ';'

        fun skipWs() {
            while (pos < n) {
                val c = s[pos]
                if (isWs(c)) { if (c == '\n') line++; pos++ }
                else if (c == '#') { while (pos < n && s[pos] != '\n') pos++ }
                else break
            }
            if (pos - lastProgress > 1 shl 20) { lastProgress = pos; ctx.progress.progress(0.7f * pos / n, "Parsing USD"); ctx.progress.checkCancelled() }
        }

        fun peekChar(): Char { skipWs(); return if (pos < n) s[pos] else '\u0000' }
        fun atEnd(): Boolean { skipWs(); return pos >= n }

        /** Identifier / number / keyword token; single-char token for delimiters. */
        fun word(): String {
            skipWs()
            if (pos >= n) throw err("unexpected end of file")
            val start = pos
            while (pos < n && !isDelim(s[pos])) pos++
            if (pos == start) { pos++; return s[start].toString() }
            return s.substring(start, pos)
        }

        fun peekWord(): String { val p = pos; val l = line; val w = if (atEnd()) "" else word(); pos = p; line = l; return w }

        fun expect(c: Char) { val p = peekChar(); if (p != c) throw err("expected '$c' but found '${if (pos < n) s[pos] else "end of file"}'"); pos++ }

        fun string(): String {
            skipWs()
            if (pos >= n) throw err("unexpected end of file")
            val q = s[pos]
            if (q != '"' && q != '\'') throw err("expected a string")
            if (pos + 2 < n && s[pos + 1] == q && s[pos + 2] == q) {
                pos += 3
                val end = s.indexOf("$q$q$q", pos)
                if (end < 0) throw err("unterminated string")
                val r = s.substring(pos, end); pos = end + 3
                for (ch in r) if (ch == '\n') line++
                return r
            }
            pos++
            val sb = StringBuilder()
            while (pos < n) {
                val c = s[pos++]
                if (c == '\\' && pos < n) { sb.append(s[pos++]); continue }
                if (c == q) return sb.toString()
                if (c == '\n') line++
                sb.append(c)
            }
            throw err("unterminated string")
        }

        /** Skips a balanced bracket block starting at the current position. */
        fun skipBlock(open: Char, close: Char) {
            expect(open)
            var depth = 1
            while (pos < n && depth > 0) {
                val c = peekChar()
                if (pos >= n) break
                when (c) {
                    open -> { depth++; pos++ }
                    close -> { depth--; pos++ }
                    '"', '\'' -> string()
                    '{', '(', '[' -> { depth++; pos++ }
                    '}', ')', ']' -> { depth--; pos++ }
                    '<' -> path()
                    '@' -> asset()
                    else -> word()
                }
            }
            if (depth > 0) throw err("unbalanced '$open'")
        }

        fun path(): String { expect('<'); val e = s.indexOf('>', pos); if (e < 0) throw err("unterminated path"); val r = s.substring(pos, e); pos = e + 1; return r }
        fun asset(): String {
            expect('@')
            val triple = pos + 1 < n && s[pos] == '@' && s[pos + 1] == '@'
            if (triple) pos += 2
            val e = s.indexOf(if (triple) "@@@" else "@", pos); if (e < 0) throw err("unterminated asset path")
            val r = s.substring(pos, e); pos = e + (if (triple) 3 else 1); return r
        }

        fun parseLayer(): List<Prim> {
            // header line "#usda 1.0" is a comment for the tokenizer
            if (peekChar() == '(') layerMetadata()
            val roots = ArrayList<Prim>()
            while (!atEnd()) {
                val w = word()
                when (w) {
                    "def", "over", "class" -> roots.add(prim(w, null))
                    "{", "}", "(", ")", "[", "]", "=", ",", ";" -> {}
                    else -> {}
                }
            }
            return roots
        }

        private fun layerMetadata() {
            expect('(')
            while (true) {
                val c = peekChar()
                if (pos >= n) throw err("unterminated layer metadata")
                if (c == ')') { pos++; break }
                if (c == '"' || c == '\'') { string(); continue } // doc string
                val name = word()
                if (peekChar() == '=') {
                    pos++
                    val v = value(false)
                    when (name) {
                        "metersPerUnit" -> (v as? FloatArray)?.let { if (it.isNotEmpty()) ctx.metersPerUnit = it[0].toDouble() }
                        "upAxis" -> ctx.upAxis = v as? String
                        "subLayers" -> ctx.notes.add("subLayers are not loaded")
                    }
                }
            }
        }

        fun prim(spec: String, parent: Prim?): Prim {
            var type: String? = null
            val c = peekChar()
            if (c != '"' && c != '\'') type = word()
            val name = string()
            val p = Prim(spec, type, name, parent)
            if (peekChar() == '(') primMetadata(p)
            if (peekChar() != '{') throw err("expected '{' after prim \"$name\"")
            pos++
            body(p)
            if (++ctx.count % 1024 == 0) ctx.progress.checkCancelled()
            return p
        }

        private fun primMetadata(p: Prim) {
            // ( kind = "component"  references = @file@  ... ) : scan for composition arcs, skip the rest
            val start = pos
            skipBlock('(', ')')
            val meta = s.substring(start, pos)
            if (Regex("\\breferences\\b").containsMatchIn(meta)) ctx.notes.add("References to other layers are not composed")
            if (Regex("\\bpayload\\b").containsMatchIn(meta)) ctx.notes.add("Payloads are not loaded")
            if (Regex("\\binherits\\b|\\bspecializes\\b").containsMatchIn(meta)) ctx.notes.add("inherits/specializes arcs are not composed")
            if (Regex("\\bactive\\s*=\\s*false\\b").containsMatchIn(meta)) p.attrs["__inactive"] = "true"
        }

        private val keywords = setOf("def", "over", "class", "variantSet", "rel", "reorder", "custom", "uniform", "varying", "config", "add", "append", "prepend", "delete")

        private fun body(p: Prim) {
            val ids = ArrayList<String>(4)
            while (true) {
                val c = peekChar()
                if (pos >= n) throw err("unterminated prim \"${p.name}\"")
                when (c) {
                    '}' -> { pos++; return }
                    '(' -> { skipBlock('(', ')'); ids.clear() }         // metadata of a value-less attribute
                    '{', '[' -> { skipBlock(c, if (c == '{') '}' else ']'); ids.clear() }
                    '"', '\'' -> { string(); ids.clear() }
                    '=' -> {
                        pos++
                        val name = ids.lastOrNull() ?: ""
                        ids.clear()
                        val v = value(isIntAttr(name))
                        if (v != null && name.isNotEmpty() && !name.contains('.')) p.attrs[name] = v
                        if (peekChar() == '(') skipBlock('(', ')')
                    }
                    '<' -> { path(); ids.clear() }
                    '@' -> { asset(); ids.clear() }
                    ')', ']', ',', ';' -> pos++
                    else -> {
                        val w = word()
                        when (w) {
                            "def", "over", "class" -> { ids.clear(); p.children.add(prim(w, p)) }
                            "variantSet" -> { ids.clear(); string(); if (peekChar() == '=') pos++; if (peekChar() == '{') skipBlock('{', '}'); ctx.notes.add("Variant sets are not composed (variants ignored)") }
                            "rel" -> {
                                ids.clear()
                                val rn = word()
                                if (peekChar() == '=') {
                                    pos++
                                    val cc = peekChar()
                                    if (cc == '<') p.attrs[rn] = "<" + path() + ">"
                                    else if (cc == '[') { // list of targets: keep the first
                                        pos++
                                        while (peekChar() != ']' && pos < n) { val c2 = peekChar(); if (c2 == '<') { val t = path(); if (!p.attrs.containsKey(rn)) p.attrs[rn] = "<$t>" } else if (c2 == ',') pos++ else word() }
                                        if (pos < n) pos++
                                    } else value(false)
                                }
                                if (peekChar() == '(') skipBlock('(', ')')
                            }
                            "reorder" -> { ids.clear(); word(); if (peekChar() == '=') { pos++; value(false) } }
                            else -> if (w in keywords) { /* qualifier */ } else ids.add(w)
                        }
                    }
                }
            }
        }

        private fun isIntAttr(name: String) = name == "faceVertexCounts" || name == "faceVertexIndices" || name == "holeIndices" || name == "cornerIndices" ||
            name == "creaseIndices" || name == "creaseLengths" || name.endsWith(":indices") || name == "curveVertexCounts"

        /**
         * Parses an attribute value. Returns FloatArray / IntArray for numeric scalars, tuples, arrays of tuples
         * and matrices (flattened), String for strings/tokens/paths, List<String> for string arrays, Boolean, or
         * null for dictionaries and other skipped values.
         */
        fun value(asInt: Boolean): Any? {
            val c = peekChar()
            if (pos >= n) throw err("unexpected end of file in value")
            return when (c) {
                '(' , '[' -> {
                    // numeric (possibly nested) or string list
                    val save = pos; val saveLine = line
                    pos++
                    val inner = peekChar()
                    if (inner == '"' || inner == '\'') {
                        val l = ArrayList<String>()
                        while (pos < n) { val c2 = peekChar(); if (c2 == ']' || c2 == ')') { pos++; break }; if (c2 == ',') { pos++; continue }; if (c2 == '"' || c2 == '\'') l.add(string()) else word() }
                        l
                    } else if (inner == '<' || inner == '@' || inner == '{') {
                        pos = save; line = saveLine; skipBlock(c, if (c == '(') ')' else ']'); null
                    } else { pos = save; line = saveLine; numbers(asInt, c, if (c == '(') ')' else ']') }
                }
                '{' -> { skipBlock('{', '}'); null }
                '"', '\'' -> string()
                '<' -> "<" + path() + ">"
                '@' -> { asset(); null }
                else -> {
                    val w = word()
                    when (w) {
                        "true", "True" -> true
                        "false", "False" -> false
                        "None" -> null
                        else -> {
                            val ch = w[0]
                            if (ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || w == "inf" || w == "nan") {
                                if (asInt) { val l = IntList(1); T.parseIntAt(w, 0, w.length, l, "value"); l.toArray() }
                                else { val l = FloatList(1); T.parseFloatAt(w, 0, w.length, l, "value"); l.toArray() }
                            } else w // token / enum
                        }
                    }
                }
            }
        }

        /** Reads all numbers inside a balanced bracket expression (tuples, arrays of tuples, matrices). */
        private fun numbers(asInt: Boolean, open: Char, close: Char): Any {
            expect(open)
            var depth = 1
            val fl = if (asInt) null else FloatList(256)
            val il = if (asInt) IntList(256) else null
            while (pos < n) {
                skipWs()
                if (pos >= n) break
                val c = s[pos]
                when {
                    c == '(' || c == '[' -> { depth++; pos++ }
                    c == ')' || c == ']' -> { depth--; pos++; if (depth == 0) break }
                    c == ',' -> pos++
                    c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'i' || c == 'n' -> {
                        pos = if (asInt) T.parseIntAt(s, pos, n, il!!, "array at line $line") else T.parseFloatAt(s, pos, n, fl!!, "array at line $line")
                    }
                    else -> throw err("unexpected '${c}' in numeric array")
                }
            }
            if (depth != 0) throw err("unbalanced '$open' in array")
            return if (asInt) il!!.toArray() else fl!!.toArray()
        }
    }

    // ------------------------------------------------------------------ scene building

    private fun collectMaterials(roots: List<Prim>, ctx: Ctx) {
        fun walk(p: Prim) {
            if (p.type == "Material") {
                var color = 0
                fun shaders(q: Prim) {
                    if (q.type == "Shader") {
                        val d = q.floats("inputs:diffuseColor")
                        if (d != null && d.size >= 3 && color == 0) color = T.argb(d[0], d[1], d[2])
                    }
                    for (c in q.children) shaders(c)
                }
                shaders(p)
                if (color != 0) ctx.materialColors[p.path] = color
            }
            for (c in p.children) walk(c)
        }
        for (r in roots) walk(r)
    }

    private fun buildNode(p: Prim, parentWorld: Mat4, ctx: Ctx): Node? {
        if (p.attrs.containsKey("__inactive")) return null
        if (p.type == "Material" || p.type == "Shader" || p.type == "Camera" || p.type == "Scope" && p.name == "Looks") {
            if (p.type != "Scope") return null
        }
        val purpose = p.str("purpose")
        if (purpose == "guide") return null
        val world = localTransform(p, ctx)?.let { (m, reset) -> if (reset) m else parentWorld.times(m) } ?: parentWorld
        val children = ArrayList<Node>()
        for (c in p.children) buildNode(c, world, ctx)?.let { children.add(it) }
        val mesh = try { geometry(p, ctx) } catch (e: ImportException) { throw ImportException("Prim ${p.path}: ${e.message}", e) }
        if (mesh == null && children.isEmpty()) return null
        if (mesh != null) {
            val node = Node(p.name, mesh.transformed(world), color(p, ctx))
            for (c in children) node.add(c)
            return node
        }
        if (children.size == 1 && children[0].mesh != null && children[0].children.isEmpty() && (p.type == "Xform" || p.type == null) && children[0].name == p.name) return children[0]
        val node = Node(p.name)
        for (c in children) node.add(c)
        return node
    }

    private fun color(p: Prim, ctx: Ctx): Int {
        val dc = p.floats("primvars:displayColor")
        if (dc != null && dc.size >= 3) return T.argb(dc[0], dc[1], dc[2])
        val binding = p.str("material:binding")
        if (binding != null) {
            val path = binding.removePrefix("<").removeSuffix(">")
            ctx.materialColors[path]?.let { return it }
        }
        // inherit a binding from ancestors
        var a = p.parent
        while (a != null) { val b = a.str("material:binding"); if (b != null) { ctx.materialColors[b.removePrefix("<").removeSuffix(">")]?.let { return it }; break }; a = a.parent }
        return 0
    }

    /** Local transform from xformOp attributes; second value is true when !resetXformStack! was present. */
    private fun localTransform(p: Prim, ctx: Ctx): Pair<Mat4, Boolean>? {
        @Suppress("UNCHECKED_CAST")
        var order: List<String>? = (p.attrs["xformOpOrder"] as? List<String>) ?: (p.attrs["xformOpOrder"] as? String)?.let { listOf(it) }
        if (order == null) {
            // lenient: ops present but no order -> standard translate / rotate / scale order
            val ops = p.attrs.keys.filter { it.startsWith("xformOp:") }
            if (ops.isEmpty()) return null
            order = listOf("xformOp:translate", "xformOp:orient", "xformOp:rotateXYZ", "xformOp:rotateZYX", "xformOp:rotateXZY", "xformOp:rotateYXZ", "xformOp:rotateYZX", "xformOp:rotateZXY",
                "xformOp:rotateX", "xformOp:rotateY", "xformOp:rotateZ", "xformOp:scale", "xformOp:transform").filter { it in ops }
        }
        var m = Mat4.identity()
        var reset = false
        for (opRaw in order) {
            if (opRaw == "!resetXformStack!") { reset = true; continue }
            var op = opRaw
            var invert = false
            if (op.startsWith("!invert!")) { invert = true; op = op.removePrefix("!invert!") }
            val v = p.floats(op)
            if (v == null) { if (op.startsWith("xformOp:")) ctx.notes.add("Transform op '$op' has no value and was ignored"); continue }
            val base = op.removePrefix("xformOp:").substringBefore(':')
            var om: Mat4 = when {
                base == "translate" -> if (v.size >= 3) Mat4.translation(v[0], v[1], v[2]) else Mat4.identity()
                base == "scale" -> if (v.size >= 3) Mat4.scale(v[0], v[1], v[2]) else Mat4.identity()
                base == "orient" -> if (v.size >= 4) Mat4.fromQuaternion(v[1], v[2], v[3], v[0]) else Mat4.identity()
                base == "transform" -> if (v.size >= 16) Mat4.fromColumnMajor(v) else Mat4.identity()
                base.startsWith("rotate") -> rotation(base.removePrefix("rotate"), v)
                else -> { ctx.notes.add("Transform op '$op' is not supported"); Mat4.identity() }
            }
            if (invert) om = om.inverse() ?: Mat4.identity()
            m = m.times(om)
        }
        return Pair(m, reset)
    }

    /** rotateXYZ etc.: the first axis is applied first to the point, so M = R_last * ... * R_first. */
    private fun rotation(axes: String, v: FloatArray): Mat4 {
        var m = Mat4.identity()
        for ((i, ax) in axes.withIndex()) {
            if (i >= v.size) break
            val rad = Math.toRadians(v[i].toDouble()).toFloat()
            val r = when (ax) { 'X' -> Mat4.rotationAxis(rad, 1f, 0f, 0f); 'Y' -> Mat4.rotationAxis(rad, 0f, 1f, 0f); 'Z' -> Mat4.rotationAxis(rad, 0f, 0f, 1f); else -> Mat4.identity() }
            m = r.times(m)
        }
        return m
    }

    private fun geometry(p: Prim, ctx: Ctx): Mesh? {
        return when (p.type) {
            "Mesh" -> meshPrim(p, ctx)
            "Sphere" -> T.sphere(p.float("radius", 1f))
            "Cube" -> { val s = p.float("size", 2f); T.box(s, s, s) }
            "Cylinder", "Cone", "Capsule" -> {
                val r = p.float("radius", if (p.type == "Capsule") 0.5f else 1f)
                val h = p.float("height", if (p.type == "Capsule") 1f else 2f)
                val axis = p.str("axis") ?: "Z"
                val m = when (p.type) {
                    "Cone" -> T.cylinder(r, 0f, h, capTop = false)
                    "Capsule" -> { ctx.notes.add("Capsule prims are shown as cylinders"); T.cylinder(r, r, h + 2 * r) }
                    else -> T.cylinder(r, r, h)
                }
                // primitives are built around Y; USD's default axis is Z
                when (axis) {
                    "Z" -> m.transformed(Mat4.rotationAxis((Math.PI / 2).toFloat(), 1f, 0f, 0f))
                    "X" -> m.transformed(Mat4.rotationAxis((-Math.PI / 2).toFloat(), 0f, 0f, 1f))
                    else -> m
                }
            }
            "BasisCurves", "NurbsCurves", "NurbsPatch", "Points", "PointInstancer" -> { ctx.notes.add("${p.type} prims are not supported"); null }
            else -> null
        }
    }

    private fun meshPrim(p: Prim, ctx: Ctx): Mesh? {
        val points = p.floats("points") ?: return null
        if (points.size % 3 != 0) throw ImportException("points has ${points.size} values, not a multiple of 3")
        val counts = p.ints("faceVertexCounts") ?: return null
        val indices = p.ints("faceVertexIndices") ?: return null
        val vc = points.size / 3
        val tris = IntList(indices.size * 2)
        val flip = p.str("orientation") == "leftHanded"
        val poly = IntList(16)
        var at = 0
        for ((fi, c) in counts.withIndex()) {
            if (c < 0 || at + c > indices.size) throw ImportException("faceVertexIndices is shorter than faceVertexCounts requires (face $fi)")
            poly.clear()
            for (k in 0 until c) { val v = indices[at + k]; if (v < 0 || v >= vc) throw ImportException("face $fi references point $v but only $vc points are defined"); poly.add(v) }
            at += c
            if (flip) { var a = 0; var b = poly.size - 1; while (a < b) { val t = poly.data[a]; poly.data[a] = poly.data[b]; poly.data[b] = t; a++; b-- } }
            if (poly.size == 3) tris.add(poly[0], poly[1], poly[2])
            else if (poly.size > 3) MeshOps.triangulatePolygon(poly.toArray(), points, tris)
            if ((fi and 0x3fff) == 0) ctx.progress.checkCancelled()
        }
        if (tris.size == 0) return null
        var normals = p.floats("normals") ?: p.floats("primvars:normals")
        if (normals != null && (normals.size != points.size || p.attrs.containsKey("primvars:normals:indices"))) normals = null
        return Mesh(points, normals, tris.toArray())
    }
}
