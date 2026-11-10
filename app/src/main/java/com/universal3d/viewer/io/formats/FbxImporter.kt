package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Autodesk FBX importer for binary (Kaydara FBX Binary, versions 7100-7700) and ASCII FBX 7.x files; FBX 6
 * ASCII files are read on a best-effort basis.
 *
 * Supported: Objects/Geometry meshes (polygons triangulated, normals kept where they can be attached to
 * vertices, per-polygon material groups), Objects/Model hierarchy with the full FBX transform chain
 * (Lcl Translation/Rotation/Scaling, rotation order, pre/post rotation, pivots and offsets, geometric
 * transforms) baked into the vertices, material diffuse colours, GlobalSettings units and up axis.
 * Animation, skinning, blend shapes, textures, cameras, lights and NURBS are ignored.
 */
class FbxImporter : Importer {
    override val formatName = "FBX"
    override val extensions = listOf("fbx")

    /** Generic FBX node: name, properties (Long/Double/String/ByteArray/DoubleArray/IntArray/LongArray) and children. */
    private class FNode(val name: String) {
        val props = ArrayList<Any>(4)
        val children = ArrayList<FNode>(0)
        fun child(n: String): FNode? { for (c in children) if (c.name == n) return c; return null }
        fun childrenNamed(n: String): List<FNode> = children.filter { it.name == n }
        fun str(i: Int): String? = props.getOrNull(i) as? String
        fun num(i: Int): Double? = (props.getOrNull(i) as? Number)?.toDouble()
        /** First array property, or the numeric scalar properties as one array (FBX 6 style). */
        fun array(): Any? {
            for (p in props) if (p is DoubleArray || p is IntArray || p is LongArray) return p
            if (props.isNotEmpty() && props.all { it is Number }) return DoubleArray(props.size) { (props[it] as Number).toDouble() }
            return null
        }
    }

    private class Parsed(val version: Int, val roots: List<FNode>, val binary: Boolean)

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val size = source.file.length()
        if (size < 32) throw ImportException("File is too small to be an FBX model")
        if (size > 1024L * 1024 * 1024) throw ImportException("FBX file is too large to load")
        val bytes = source.file.readBytes()
        val binary = bytes.size > 23 && String(bytes, 0, 18, Charsets.ISO_8859_1) == "Kaydara FBX Binary"
        val parsed: Parsed
        try {
            parsed = if (binary) parseBinary(bytes, progress) else parseAscii(bytes, progress)
        } catch (e: ImportException) { throw e
        } catch (e: OutOfMemoryError) { throw ImportException("Not enough memory to load this FBX file")
        } catch (e: Exception) {
            val v = if (binary && bytes.size >= 27) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(23) else asciiVersion(bytes)
            throw ImportException("The FBX file (version ${versionString(v)}) could not be parsed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            return convert(parsed, source, progress)
        } catch (e: ImportException) { throw e
        } catch (e: OutOfMemoryError) { throw ImportException("Not enough memory to load this FBX file")
        } catch (e: Exception) { throw ImportException("The FBX file (version ${versionString(parsed.version)}) is corrupt: ${e.message ?: e.javaClass.simpleName}", e) }
    }

    private fun versionString(v: Int): String = if (v <= 0) "unknown" else "${v / 1000}.${(v / 100) % 10}.${v % 100}"

    // ---- binary parser ------------------------------------------------------------------------------------

    private fun parseBinary(bytes: ByteArray, progress: ProgressSink): Parsed {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        if (version < 7000) throw ImportException("Binary FBX version ${versionString(version)} is not supported (FBX 7.1 or newer required)")
        val big = version >= 7500
        val roots = ArrayList<FNode>()
        var p = 27
        var guard = 0
        while (p < bytes.size) {
            val r = readNode(bb, p, big, 0) ?: break
            roots.add(r.first); p = r.second
            progress.progress(0.4f * p / bytes.size, "Parsing FBX"); progress.checkCancelled()
            if (++guard > 1_000_000) break
        }
        return Parsed(version, roots, true)
    }

    private fun readNode(bb: ByteBuffer, start: Int, big: Boolean, depth: Int): Pair<FNode, Int>? {
        val hdr = if (big) 25 else 13
        if (start + hdr > bb.limit()) return null
        val end: Long; val numProps: Long; val nameLen: Int
        if (big) { end = bb.getLong(start); numProps = bb.getLong(start + 8); nameLen = bb.get(start + 24).toInt() and 0xff }
        else { end = bb.getInt(start).toLong() and 0xffffffffL; numProps = bb.getInt(start + 4).toLong() and 0xffffffffL; nameLen = bb.get(start + 12).toInt() and 0xff }
        if (end == 0L) return null // null record
        if (end < start + hdr || end > bb.limit()) throw ImportException("FBX node at offset $start extends beyond the end of the file (file truncated?)")
        if (depth > 64) throw ImportException("FBX node nesting is too deep")
        var p = start + hdr
        val name = String(bb.array(), p, nameLen, Charsets.ISO_8859_1); p += nameLen
        val node = FNode(name)
        if (numProps > 100_000_000L) throw ImportException("FBX node '$name' declares $numProps properties")
        for (i in 0 until numProps.toInt()) {
            if (p >= end) throw ImportException("FBX node '$name' properties run past the node end")
            val r = readProp(bb, p, end.toInt()); node.props.add(r.first); p = r.second
        }
        while (p < end) {
            val c = readNode(bb, p, big, depth + 1) ?: break
            node.children.add(c.first); p = c.second
        }
        return Pair(node, end.toInt())
    }

    private fun readProp(bb: ByteBuffer, p0: Int, end: Int): Pair<Any, Int> {
        var p = p0
        val t = bb.get(p).toInt().toChar(); p++
        fun need(n: Int) { if (p + n > end) throw ImportException("FBX property '$t' runs past the end of its node") }
        return when (t) {
            'Y' -> { need(2); Pair(bb.getShort(p).toLong(), p + 2) }
            'C' -> { need(1); Pair((bb.get(p).toInt() and 1).toLong(), p + 1) }
            'I' -> { need(4); Pair(bb.getInt(p).toLong(), p + 4) }
            'F' -> { need(4); Pair(bb.getFloat(p).toDouble(), p + 4) }
            'D' -> { need(8); Pair(bb.getDouble(p), p + 8) }
            'L' -> { need(8); Pair(bb.getLong(p), p + 8) }
            'S', 'R' -> {
                need(4); val len = bb.getInt(p); p += 4
                if (len < 0 || p + len > end) throw ImportException("FBX string property has an invalid length $len")
                val v: Any = if (t == 'S') fbxString(bb.array(), p, len) else bb.array().copyOfRange(p, p + len)
                Pair(v, p + len)
            }
            'f', 'd', 'l', 'i', 'b', 'c' -> {
                need(12)
                val n = bb.getInt(p); val enc = bb.getInt(p + 4); val clen = bb.getInt(p + 8); p += 12
                val es = when (t) { 'f', 'i' -> 4; 'd', 'l' -> 8; else -> 1 }
                if (n < 0 || n.toLong() * es > 2_000_000_000L) throw ImportException("FBX array property has an invalid length $n")
                val raw: ByteArray; val rawOff: Int
                if (enc == 0) {
                    if (p + n.toLong() * es > end) throw ImportException("FBX array property runs past the end of its node (file truncated?)")
                    raw = bb.array(); rawOff = p; p += n * es
                } else if (enc == 1) {
                    if (clen < 0 || p + clen > end) throw ImportException("FBX compressed array runs past the end of its node (file truncated?)")
                    raw = inflate(bb.array(), p, clen, n * es); rawOff = 0; p += clen
                } else throw ImportException("FBX array uses unknown encoding $enc")
                val ab = ByteBuffer.wrap(raw, rawOff, n * es).order(ByteOrder.LITTLE_ENDIAN).slice().order(ByteOrder.LITTLE_ENDIAN)
                val v: Any = when (t) {
                    'f' -> { val fb = ab.asFloatBuffer(); DoubleArray(n) { fb.get(it).toDouble() } }
                    'd' -> { val a = DoubleArray(n); ab.asDoubleBuffer().get(a); a }
                    'i' -> { val a = IntArray(n); ab.asIntBuffer().get(a); a }
                    'l' -> { val a = LongArray(n); ab.asLongBuffer().get(a); a }
                    else -> IntArray(n) { ab.get(it).toInt() and 0xff }
                }
                Pair(v, p)
            }
            else -> throw ImportException("FBX property has unknown type code '${t}' (0x${Integer.toHexString(t.code)}) at offset ${p0}")
        }
    }

    private fun inflate(src: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(src, off, len)
            val out = ByteArray(expected)
            var o = 0
            while (o < expected && !inf.finished()) {
                val n = inf.inflate(out, o, expected - o)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o += n
            }
            if (o < expected) throw ImportException("FBX compressed array is truncated ($o of $expected bytes)")
            return out
        } catch (e: java.util.zip.DataFormatException) { throw ImportException("FBX compressed array is corrupt: ${e.message}")
        } finally { inf.end() }
    }

    /** Binary FBX stores "Name\u0000\u0001Class"; normalise to the ASCII "Class::Name" form. */
    private fun fbxString(b: ByteArray, off: Int, len: Int): String {
        val s = String(b, off, len, Charsets.UTF_8)
        val sep = s.indexOf("\u0000\u0001")
        if (sep >= 0) return s.substring(sep + 2) + "::" + s.substring(0, sep)
        return s
    }

    // ---- ASCII parser -------------------------------------------------------------------------------------

    private fun asciiVersion(bytes: ByteArray): Int {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        Regex("FBX\\s+(\\d+)\\.(\\d+)\\.(\\d+)").find(head)?.let { m -> return m.groupValues[1].toInt() * 1000 + m.groupValues[2].toInt() * 100 + m.groupValues[3].toInt() }
        Regex("FBXVersion:\\s*(\\d+)").find(head)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    private class AsciiParser(val s: String, val progress: ProgressSink) {
        var i = 0
        val n = s.length
        private val sb = StringBuilder()

        fun ws() {
            while (i < n) {
                val c = s[i]
                if (c == ';') { while (i < n && s[i] != '\n') i++ }
                else if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++
                else return
            }
        }

        fun parseList(out: ArrayList<FNode>, depth: Int) {
            while (true) {
                ws()
                if (i >= n || s[i] == '}') return
                out.add(parseNode(depth))
                if ((out.size and 1023) == 0) { progress.progress(0.4f * i / n, "Parsing FBX"); progress.checkCancelled() }
            }
        }

        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '+'

        fun parseNode(depth: Int): FNode {
            if (depth > 64) throw ImportException("FBX node nesting is too deep")
            val st = i
            while (i < n && isIdent(s[i])) i++
            if (i == st) throw ImportException("Unexpected character '${s[i]}' at offset $i in ASCII FBX")
            val name = s.substring(st, i)
            ws()
            if (i >= n || s[i] != ':') throw ImportException("Expected ':' after '$name' at offset $i in ASCII FBX")
            i++
            val node = FNode(name)
            // properties
            while (true) {
                ws()
                if (i >= n) break
                val c = s[i]
                if (c == '{' || c == '}') break
                if (c == ',') { i++; continue }
                if (c == '"') node.props.add(parseString())
                else if (c == '*') { i++; node.props.add(parseArray()) }
                else if (c == '-' || c == '+' || c.isDigit() || c == '.') node.props.add(parseNumber())
                else if (c.isLetter() || c == '_') {
                    // bare word: either an identifier value (Y, T, W...) or the start of the next node
                    val st2 = i
                    while (i < n && isIdent(s[i])) i++
                    val word = s.substring(st2, i)
                    val save = i; ws()
                    if (i < n && s[i] == ':') { i = st2; break } // next node
                    i = save
                    node.props.add(word)
                } else throw ImportException("Unexpected character '$c' at offset $i in ASCII FBX")
                // continue only if a comma follows
                val save = i; ws()
                if (i < n && s[i] == ',') { i++; continue }
                i = save
                break
            }
            ws()
            if (i < n && s[i] == '{') {
                i++
                parseList(node.children, depth + 1)
                ws()
                if (i >= n || s[i] != '}') throw ImportException("Missing '}' for node '$name' in ASCII FBX")
                i++
            }
            return node
        }

        fun parseString(): String {
            i++ // opening quote
            sb.setLength(0)
            while (i < n && s[i] != '"') { sb.append(s[i]); i++ }
            if (i >= n) throw ImportException("Unterminated string in ASCII FBX")
            i++
            return sb.toString()
        }

        fun parseNumber(): Any {
            val st = i
            var isInt = true
            if (s[i] == '-' || s[i] == '+') i++
            while (i < n) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' || c == 'e' || c == 'E' || ((c == '-' || c == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E'))) { isInt = false; i++ }
                else break
            }
            val t = s.substring(st, i)
            if (isInt) { t.toLongOrNull()?.let { return it } }
            return t.toDoubleOrNull() ?: throw ImportException("Bad number '$t' at offset $st in ASCII FBX")
        }

        /** Parses "*N { a: v,v,v }" after the '*' has been consumed. */
        fun parseArray(): Any {
            val st = i
            while (i < n && s[i].isDigit()) i++
            val declared = s.substring(st, i).toIntOrNull() ?: 0
            ws()
            if (i >= n || s[i] != '{') throw ImportException("Expected '{' after array size at offset $i in ASCII FBX")
            i++; ws()
            // "a:" prefix
            while (i < n && (s[i].isLetter() || s[i] == '_')) i++
            ws(); if (i < n && s[i] == ':') i++
            if (declared < 0 || declared.toLong() > n) throw ImportException("ASCII FBX array declares $declared values")
            var dbl = DoubleArray(maxOf(16, declared)); var lng: LongArray? = LongArray(maxOf(16, declared))
            var cnt = 0
            while (true) {
                ws()
                if (i >= n) throw ImportException("Unterminated array in ASCII FBX")
                val c = s[i]
                if (c == '}') { i++; break }
                if (c == ',') { i++; continue }
                if (cnt == dbl.size) { dbl = dbl.copyOf(dbl.size * 2); lng = lng?.copyOf(dbl.size) }
                val v = fastNumber()
                if (v.isNaN() && lastWasNonNumeric) throw ImportException("Unexpected character '$c' in array at offset $i in ASCII FBX")
                dbl[cnt] = v
                if (lng != null) { if (lastWasInt) lng[cnt] = lastLong else lng = null }
                cnt++
            }
            return if (lng != null) lng.copyOf(cnt) else dbl.copyOf(cnt)
        }

        private var lastWasInt = false
        private var lastLong = 0L
        private var lastWasNonNumeric = false

        /** Allocation-free number parse for array contents. */
        private fun fastNumber(): Double {
            lastWasNonNumeric = false
            var c = s[i]
            var neg = false
            if (c == '-' || c == '+') { neg = c == '-'; i++; if (i >= n) { lastWasNonNumeric = true; return Double.NaN }; c = s[i] }
            var mant = 0L; var digits = 0; var exp = 0; var any = false
            while (c in '0'..'9') { if (digits < 18) { mant = mant * 10 + (c - '0'); digits++ } else exp++; any = true; i++; if (i >= n) break; c = s[i] }
            var isInt = true
            if (i < n && c == '.') {
                isInt = false; i++
                while (i < n) { c = s[i]; if (c !in '0'..'9') break; if (digits < 18) { mant = mant * 10 + (c - '0'); digits++; exp-- }; any = true; i++ }
            }
            if (!any) {
                // nan / inf tokens (e.g. "1.#QNAN", "nan", "inf") - skip the token
                val st = i; while (i < n && isIdent(s[i]) || (i < n && s[i] == '#')) i++
                if (i == st) { lastWasNonNumeric = true; return Double.NaN }
                lastWasInt = false; return 0.0
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false; i++
                var eneg = false
                if (i < n && (s[i] == '-' || s[i] == '+')) { eneg = s[i] == '-'; i++ }
                var e = 0
                while (i < n && s[i] in '0'..'9') { if (e < 10000) e = e * 10 + (s[i] - '0'); i++ }
                exp += if (eneg) -e else e
            }
            if (i < n && s[i] == '#') { while (i < n && isIdent(s[i]) || (i < n && s[i] == '#')) i++; isInt = false } // "1.#QNAN0"
            var v = if (exp == 0) mant.toDouble() else mant.toDouble() * FastReader.pow10(exp)
            if (neg) v = -v
            lastWasInt = isInt && exp == 0 && digits <= 18
            lastLong = if (neg) -mant else mant
            return v
        }
    }

    private fun parseAscii(bytes: ByteArray, progress: ProgressSink): Parsed {
        val text = String(bytes, Charsets.UTF_8)
        val version = asciiVersion(bytes)
        val head = text.substring(0, minOf(text.length, 2048))
        if (!head.contains("FBX") && !head.contains("FBXHeaderExtension")) throw ImportException("Not an FBX file: no FBX header found")
        val p = AsciiParser(text, progress)
        val roots = ArrayList<FNode>()
        p.parseList(roots, 0)
        p.ws()
        if (p.i < p.n) throw ImportException("Unexpected '}' at offset ${p.i} in ASCII FBX")
        return Parsed(version, roots, false)
    }

    // ---- scene conversion ---------------------------------------------------------------------------------

    private class ModelInfo(val key: String, val node: FNode) {
        val children = ArrayList<ModelInfo>()
        var parentKey: String? = null
        val geometry = ArrayList<FNode>()
        val materialColors = ArrayList<Int>()
    }

    private class MeshGroup(val matIndex: Int, val mesh: Mesh)

    private fun convert(parsed: Parsed, source: ImportSource, progress: ProgressSink): Model {
        val root = FNode("ROOT"); root.children.addAll(parsed.roots)
        val objects = root.child("Objects") ?: throw ImportException("The FBX file has no Objects section")
        val notes = ArrayList<String>()
        if (parsed.version in 1 until 7000) notes.add("FBX ${versionString(parsed.version)} read with the FBX 7 parser (best effort)")
        notes.add(if (parsed.binary) "Binary FBX ${versionString(parsed.version)}" else "ASCII FBX ${versionString(parsed.version)}")

        // Collect objects by key (numeric id for 7.x, name for 6.x)
        val models = LinkedHashMap<String, ModelInfo>()
        val geometries = HashMap<String, FNode>()
        val materials = HashMap<String, Int>()
        var anyDeformer = false; var anyTexture = false; var anyNurbs = false; var anyAnim = false
        for (o in objects.children) {
            val key = objectKey(o) ?: continue
            when (o.name) {
                "Model" -> {
                    val mi = ModelInfo(key, o); models[key] = mi
                    if (o.child("Vertices") != null) mi.geometry.add(o) // FBX 6: geometry inside the model
                }
                "Geometry" -> {
                    val type = o.props.lastOrNull { it is String } as? String
                    if (o.child("Vertices") != null && o.child("PolygonVertexIndex") != null) geometries[key] = o
                    else if (type != null && type.contains("Nurb", true)) anyNurbs = true
                }
                "Material" -> materials[key] = materialColor(o)
                "Deformer" -> anyDeformer = true
                "Texture", "Video" -> anyTexture = true
                "AnimationStack", "AnimationCurve", "AnimationLayer" -> anyAnim = true
                "NodeAttribute" -> if ((o.props.lastOrNull { it is String } as? String)?.contains("Nurb", true) == true) anyNurbs = true
            }
        }
        if (root.child("Takes")?.children?.any { it.name == "Take" } == true) anyAnim = true
        // Connections
        val conns = root.child("Connections")
        if (conns != null) for (c in conns.children) {
            if (c.name != "C" && c.name != "Connect") continue
            val kind = c.str(0) ?: continue
            if (kind != "OO") continue
            val child = keyOf(c.props.getOrNull(1)) ?: continue
            val parent = keyOf(c.props.getOrNull(2)) ?: continue
            val pm = models[parent]
            models[child]?.let { cm -> if (cm.parentKey == null && pm != null && pm !== cm) { cm.parentKey = parent; pm.children.add(cm) } else if (cm.parentKey == null && (parent == "0" || parent == "Model::Scene")) cm.parentKey = parent }
            geometries[child]?.let { g -> pm?.geometry?.add(g) }
            materials[child]?.let { col -> pm?.materialColors?.add(col) }
        }
        val roots = models.values.filter { it.parentKey == null || models[it.parentKey!!] == null || it.parentKey == "0" }
        // Units / axes
        var units = ModelUnits.UNKNOWN
        root.child("GlobalSettings")?.let { gs ->
            val usf = propValues(gs, "UnitScaleFactor")?.getOrNull(0)
            if (usf != null) {
                units = when {
                    near(usf, 1.0) -> ModelUnits.CENTIMETER; near(usf, 0.1) -> ModelUnits.MILLIMETER; near(usf, 100.0) -> ModelUnits.METER
                    near(usf, 2.54) -> ModelUnits.INCH; near(usf, 30.48) -> ModelUnits.FOOT
                    else -> { notes.add("Unit scale factor $usf cm per unit (not mapped; geometry not scaled)"); ModelUnits.UNKNOWN }
                }
            }
            val up = propValues(gs, "UpAxis")?.getOrNull(0)?.toInt()
            val sign = propValues(gs, "UpAxisSign")?.getOrNull(0)?.toInt() ?: 1
            if (up != null) notes.add("Up axis: ${if (sign < 0) "-" else ""}${when (up) { 0 -> "X"; 1 -> "Y"; 2 -> "Z"; else -> "?" }}")
        }
        val meshCache = HashMap<FNode, List<MeshGroup>>()
        val out = ArrayList<Node>()
        val total = maxOf(1, models.size)
        var done = 0
        val visiting = HashSet<String>()
        for (r in roots) {
            val n = buildModel(r, Mat4.identity(), meshCache, materials, visiting, 0) { done++; progress.progress(0.4f + 0.55f * done / total, "Building meshes"); progress.checkCancelled() }
            if (n != null) out.add(n)
        }
        if (out.isEmpty() && models.isEmpty() && geometries.isNotEmpty()) {
            // geometry without any models: show it at the origin
            for ((k, g) in geometries) {
                val groups = meshCache.getOrPut(g) { convertGeometry(g) }
                nodeForGroups(nameOf(g, "Geometry $k"), groups, Mat4.identity(), emptyList())?.let { out.add(it) }
            }
        }
        if (anyDeformer) notes.add("Skinning and blend shapes ignored (bind pose shown)")
        if (anyAnim) notes.add("Animation ignored")
        if (anyTexture) notes.add("Textures ignored; diffuse colours only")
        if (anyNurbs) notes.add("NURBS geometry skipped")
        val model = Model(out, formatName, source.displayName, source.sizeBytes, units, notes)
        if (model.isEmpty) throw ImportException("The FBX file contains no mesh geometry" + (if (anyNurbs) " (NURBS surfaces are not supported)" else ""))
        progress.progress(0.98f, "Finalising")
        return model
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-4 * maxOf(1.0, abs(b))

    private fun objectKey(o: FNode): String? = keyOf(o.props.getOrNull(0))
    private fun keyOf(p: Any?): String? = when (p) { is Number -> p.toLong().toString(); is String -> p; else -> null }

    private fun nameOf(o: FNode, fallback: String): String {
        val s = (o.props.firstOrNull { it is String } as? String) ?: return fallback
        val n = s.substringAfter("::", s).trim()
        return n.ifEmpty { fallback }
    }

    /** Numeric values of a Properties70 "P" / Properties60 "Property" entry with the given name. */
    private fun propValues(owner: FNode, name: String): DoubleArray? {
        val container = owner.child("Properties70") ?: owner.child("Properties60") ?: return null
        for (p in container.children) {
            if (p.name != "P" && p.name != "Property") continue
            if (p.str(0) != name) continue
            val vals = ArrayList<Double>(4)
            for (k in 1 until p.props.size) { val v = p.props[k]; if (v is Number) vals.add(v.toDouble()) }
            return DoubleArray(vals.size) { vals[it] }
        }
        return null
    }

    private fun materialColor(m: FNode): Int {
        val c = propValues(m, "DiffuseColor") ?: propValues(m, "Diffuse") ?: return 0
        if (c.size < 3) return 0
        val a = propValues(m, "Opacity")?.getOrNull(0) ?: 1.0
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (q(a) shl 24) or (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
    }

    private fun buildModel(mi: ModelInfo, parentWorld: Mat4, cache: HashMap<FNode, List<MeshGroup>>, materials: HashMap<String, Int>, visiting: HashSet<String>, depth: Int, tick: () -> Unit): Node? {
        if (!visiting.add(mi.key) || depth > 256) throw ImportException("FBX model hierarchy contains a cycle at '${nameOf(mi.node, mi.key)}'")
        try {
            tick()
            val name = nameOf(mi.node, "Model ${mi.key}")
            val world = parentWorld.times(localTransform(mi.node))
            val geoWorld = world.times(geometricTransform(mi.node))
            var node: Node? = null
            if (mi.geometry.isNotEmpty()) {
                val groups = ArrayList<MeshGroup>()
                for (g in mi.geometry) groups.addAll(cache.getOrPut(g) { convertGeometry(g) })
                node = nodeForGroups(name, groups, geoWorld, mi.materialColors)
            }
            val n = node ?: Node(name)
            for (c in mi.children) buildModel(c, world, cache, materials, visiting, depth + 1, tick)?.let { n.add(it) }
            return if (n.hasGeometryInSubtree) n else null
        } finally { visiting.remove(mi.key) }
    }

    private fun nodeForGroups(name: String, groups: List<MeshGroup>, world: Mat4, colors: List<Int>): Node? {
        if (groups.isEmpty()) return null
        if (groups.size == 1) return Node(name, groups[0].mesh.transformed(world), colors.getOrNull(groups[0].matIndex) ?: colors.firstOrNull() ?: 0)
        val parent = Node(name)
        for (g in groups) parent.add(Node("$name (material ${g.matIndex})", g.mesh.transformed(world), colors.getOrNull(g.matIndex) ?: 0))
        return parent
    }

    // ---- transforms ---------------------------------------------------------------------------------------

    private fun vec3(m: FNode, name: String): FloatArray? { val v = propValues(m, name) ?: return null; if (v.size < 3) return null; return floatArrayOf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat()) }

    private fun rotation(deg: FloatArray?, order: Int): Mat4 {
        if (deg == null) return Mat4.identity()
        val rx = Mat4.rotationAxis(Math.toRadians(deg[0].toDouble()).toFloat(), 1f, 0f, 0f)
        val ry = Mat4.rotationAxis(Math.toRadians(deg[1].toDouble()).toFloat(), 0f, 1f, 0f)
        val rz = Mat4.rotationAxis(Math.toRadians(deg[2].toDouble()).toFloat(), 0f, 0f, 1f)
        // FBX applies the first axis of the order first: for XYZ the column-vector matrix is Rz * Ry * Rx.
        return when (order) {
            1 -> ry.times(rz).times(rx)      // XZY
            2 -> rx.times(rz).times(ry)      // YZX
            3 -> rz.times(rx).times(ry)      // YXZ
            4 -> ry.times(rx).times(rz)      // ZXY
            5 -> rx.times(ry).times(rz)      // ZYX
            else -> rz.times(ry).times(rx)   // XYZ (and spheric)
        }
    }

    private fun translation(v: FloatArray?): Mat4 = if (v == null) Mat4.identity() else Mat4.translation(v[0], v[1], v[2])
    private fun scaling(v: FloatArray?): Mat4 = if (v == null) Mat4.identity() else Mat4.scale(if (v[0] == 0f) 1e-6f else v[0], if (v[1] == 0f) 1e-6f else v[1], if (v[2] == 0f) 1e-6f else v[2])
    private fun negated(v: FloatArray?): FloatArray? = v?.let { floatArrayOf(-it[0], -it[1], -it[2]) }

    /** World = Parent * T * Roff * Rp * Rpre * R * Rpost^-1 * Rp^-1 * Soff * Sp * S * Sp^-1 */
    private fun localTransform(m: FNode): Mat4 {
        val order = propValues(m, "RotationOrder")?.getOrNull(0)?.toInt() ?: 0
        val t = translation(vec3(m, "Lcl Translation"))
        val roff = translation(vec3(m, "RotationOffset"))
        val rp = vec3(m, "RotationPivot")
        val rpre = rotation(vec3(m, "PreRotation"), 0)
        val r = rotation(vec3(m, "Lcl Rotation"), order)
        val rpostInv = rotation(vec3(m, "PostRotation"), 0).inverse() ?: Mat4.identity()
        val soff = translation(vec3(m, "ScalingOffset"))
        val sp = vec3(m, "ScalingPivot")
        val s = scaling(vec3(m, "Lcl Scaling"))
        var out = t.times(roff).times(translation(rp)).times(rpre).times(r).times(rpostInv).times(translation(negated(rp)))
        out = out.times(soff).times(translation(sp)).times(s).times(translation(negated(sp)))
        return out
    }

    private fun geometricTransform(m: FNode): Mat4 =
        translation(vec3(m, "GeometricTranslation")).times(rotation(vec3(m, "GeometricRotation"), 0)).times(scaling(vec3(m, "GeometricScaling")))

    // ---- geometry -----------------------------------------------------------------------------------------

    private fun doubles(a: Any?): DoubleArray? = when (a) {
        is DoubleArray -> a; is LongArray -> DoubleArray(a.size) { a[it].toDouble() }; is IntArray -> DoubleArray(a.size) { a[it].toDouble() }; else -> null
    }
    private fun ints(a: Any?): IntArray? = when (a) {
        is IntArray -> a; is LongArray -> IntArray(a.size) { a[it].toInt() }; is DoubleArray -> IntArray(a.size) { a[it].toInt() }; else -> null
    }

    private fun convertGeometry(g: FNode): List<MeshGroup> {
        val verts = doubles(g.child("Vertices")?.array()) ?: return emptyList()
        val pvi = ints(g.child("PolygonVertexIndex")?.array()) ?: return emptyList()
        val nv = verts.size / 3
        if (nv == 0 || pvi.isEmpty()) return emptyList()
        // Normals
        var normals: DoubleArray? = null; var normalIdx: IntArray? = null; var nMapping = ""; var nRef = ""
        g.child("LayerElementNormal")?.let { ln ->
            normals = doubles(ln.child("Normals")?.array())
            normalIdx = ints(ln.child("NormalsIndex")?.array())
            nMapping = ln.child("MappingInformationType")?.str(0) ?: ""
            nRef = ln.child("ReferenceInformationType")?.str(0) ?: "Direct"
        }
        val nrm = normals
        val useNormals = nrm != null && nrm.size >= 3 && (nMapping == "ByVertice" || nMapping == "ByVertex" || nMapping == "ByPolygonVertex" || nMapping == "ByPolygon")
        // Materials
        var matMapping = "AllSame"; var matIds: IntArray? = null
        g.child("LayerElementMaterial")?.let { lm ->
            matMapping = lm.child("MappingInformationType")?.str(0) ?: "AllSame"
            matIds = ints(lm.child("Materials")?.array())
        }
        val mids = matIds
        val baseMat = if (mids != null && mids.isNotEmpty()) mids[0] else 0

        // Split vertices by (vertex, normal) so hard edges survive while shared vertices stay shared.
        val vmap = LongIntMap(nv * 2)
        val outPos = FloatList(nv * 3 + 3)
        val outNrm = FloatList(if (useNormals) nv * 3 + 3 else 16)
        val groups = LinkedHashMap<Int, IntList>()
        val poly = IntList(16)
        var corner = 0; var polyIndex = 0
        var cornerStart = 0
        val tmpN = FloatArray(3)
        var normalsOk = useNormals
        var i = 0
        while (i < pvi.size) {
            var raw = pvi[i]
            val last = raw < 0
            if (last) raw = raw.inv()
            if (raw < 0 || raw >= nv) throw ImportException("FBX polygon references vertex $raw but the geometry has only $nv vertices")
            var key = raw.toLong() shl 32
            if (normalsOk) {
                val ok = cornerNormal(nrm!!, normalIdx, nMapping, nRef, raw, corner, polyIndex, tmpN)
                if (!ok) normalsOk = false
                else {
                    val qx = ((tmpN[0].coerceIn(-1f, 1f) * 511f).toInt() + 512).toLong()
                    val qy = ((tmpN[1].coerceIn(-1f, 1f) * 511f).toInt() + 512).toLong()
                    val qz = ((tmpN[2].coerceIn(-1f, 1f) * 511f).toInt() + 512).toLong()
                    key = key or (qx shl 20) or (qy shl 10) or qz
                }
            }
            var idx = vmap.getOrPut(key, outPos.size / 3)
            if (idx < 0) {
                idx = outPos.size / 3
                outPos.add(verts[raw * 3].toFloat(), verts[raw * 3 + 1].toFloat(), verts[raw * 3 + 2].toFloat())
                if (normalsOk) outNrm.add(tmpN[0], tmpN[1], tmpN[2])
            }
            poly.add(idx)
            corner++
            if (last) {
                val mat = if (matMapping == "ByPolygon" && mids != null) (mids.getOrElse(polyIndex) { baseMat }) else baseMat
                val tris = groups.getOrPut(mat) { IntList(1024) }
                if (poly.size == 3) tris.add(poly[0], poly[1], poly[2])
                else if (poly.size > 3) MeshOps.triangulatePolygon(poly.toArray(), outPos.data, tris)
                poly.clear(); polyIndex++; cornerStart = corner
            }
            i++
        }
        if (poly.size >= 3) { // unterminated last polygon
            val tris = groups.getOrPut(baseMat) { IntList(1024) }
            if (poly.size == 3) tris.add(poly[0], poly[1], poly[2]) else MeshOps.triangulatePolygon(poly.toArray(), outPos.data, tris)
        }
        val positions = outPos.toArray()
        val nArr = if (normalsOk && outNrm.size == positions.size) outNrm.toArray() else null
        val result = ArrayList<MeshGroup>()
        for ((mat, tris) in groups) if (tris.size > 0) result.add(MeshGroup(mat, Mesh(positions, nArr, tris.toArray())))
        return result
    }

    /** Normal of a polygon corner; false when the layer cannot be mapped to this corner. */
    private fun cornerNormal(nrm: DoubleArray, nIdx: IntArray?, mapping: String, ref: String, vertex: Int, corner: Int, polygon: Int, out: FloatArray): Boolean {
        val slot = when (mapping) { "ByVertice", "ByVertex" -> vertex; "ByPolygonVertex" -> corner; "ByPolygon" -> polygon; else -> return false }
        val ni = if (ref == "IndexToDirect" || ref == "Index") { if (nIdx == null || slot >= nIdx.size) return false; nIdx[slot] } else slot
        if (ni < 0 || ni * 3 + 2 >= nrm.size) return false
        out[0] = nrm[ni * 3].toFloat(); out[1] = nrm[ni * 3 + 1].toFloat(); out[2] = nrm[ni * 3 + 2].toFloat()
        return true
    }
}
