package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.FastReader
import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.FloatList
import com.universal3d.viewer.model.IntList
import com.universal3d.viewer.model.Mesh
import com.universal3d.viewer.model.MeshOps
import com.universal3d.viewer.model.ModelUnits
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import org.xml.sax.InputSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared helpers for the text / XML based importers (AMF, 3MF, COLLADA, X3D, VRML, VTK, USDA, DXF).
 * Pure JVM: only Kotlin stdlib, java.* and javax.xml.
 */
internal object TextFormatSupport {

    // ---------------------------------------------------------------- number lists

    private fun isSeparator(c: Char): Boolean =
        c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'

    /** Parses all whitespace/comma separated floats in [s] (from [start] to [end]) into [out]. */
    fun floats(s: CharSequence, out: FloatList, start: Int = 0, end: Int = s.length, what: String = "number list") {
        var i = start
        while (i < end) {
            val c = s[i]
            if (isSeparator(c)) { i++; continue }
            i = parseFloatAt(s, i, end, out, what)
        }
    }

    fun ints(s: CharSequence, out: IntList, start: Int = 0, end: Int = s.length, what: String = "index list") {
        var i = start
        while (i < end) {
            val c = s[i]
            if (isSeparator(c)) { i++; continue }
            i = parseIntAt(s, i, end, out, what)
        }
    }

    fun floatArray(s: CharSequence, what: String = "number list"): FloatArray { val l = FloatList(maxOf(16, s.length / 6)); floats(s, l, what = what); return l.toArray() }
    fun intArray(s: CharSequence, what: String = "index list"): IntArray { val l = IntList(maxOf(16, s.length / 3)); ints(s, l, what = what); return l.toArray() }

    /** Parses one float starting at [i]; appends it to [out] and returns the index after the token. */
    fun parseFloatAt(s: CharSequence, i0: Int, end: Int, out: FloatList, what: String): Int {
        var i = i0
        var c = s[i]
        var neg = false
        if (c == '-' || c == '+') { neg = c == '-'; i++; c = if (i < end) s[i] else ' ' }
        var mant = 0L; var digits = 0; var exp = 0; var any = false
        while (i < end && c in '0'..'9') { if (digits < 18) { mant = mant * 10 + (c - '0'); digits++ } else exp++; any = true; i++; c = if (i < end) s[i] else ' ' }
        if (i < end && c == '.') {
            i++; c = if (i < end) s[i] else ' '
            while (i < end && c in '0'..'9') { if (digits < 18) { mant = mant * 10 + (c - '0'); digits++; exp-- }; any = true; i++; c = if (i < end) s[i] else ' ' }
        }
        if (!any) {
            // nan / inf / garbage: take the token
            var j = i0; while (j < end && !isSeparator(s[j])) j++
            val t = s.subSequence(i0, j).toString()
            if (t.equals("nan", true) || t.equals("-nan", true)) { out.add(Float.NaN); return j }
            if (t.equals("inf", true) || t.equals("infinity", true) || t.equals("+inf", true)) { out.add(Float.POSITIVE_INFINITY); return j }
            if (t.equals("-inf", true) || t.equals("-infinity", true)) { out.add(Float.NEGATIVE_INFINITY); return j }
            throw ImportException("Expected a number in $what but found '${t.take(32)}'")
        }
        if (i < end && (c == 'e' || c == 'E')) {
            i++; c = if (i < end) s[i] else ' '
            var eneg = false
            if (c == '-' || c == '+') { eneg = c == '-'; i++; c = if (i < end) s[i] else ' ' }
            var e = 0
            while (i < end && c in '0'..'9') { if (e < 10000) e = e * 10 + (c - '0'); i++; c = if (i < end) s[i] else ' ' }
            exp += if (eneg) -e else e
        }
        var v = if (exp == 0) mant.toDouble() else mant.toDouble() * FastReader.pow10(exp)
        if (neg) v = -v
        out.add(v.toFloat())
        // trailing garbage glued to the number (e.g. "1.0f") is skipped
        while (i < end && !isSeparator(s[i])) i++
        return i
    }

    fun parseIntAt(s: CharSequence, i0: Int, end: Int, out: IntList, what: String): Int {
        var i = i0
        var c = s[i]
        var neg = false
        if (c == '-' || c == '+') { neg = c == '-'; i++; c = if (i < end) s[i] else ' ' }
        var v = 0L; var any = false
        while (i < end && c in '0'..'9') { v = v * 10 + (c - '0'); if (v > Int.MAX_VALUE) v = Int.MAX_VALUE.toLong(); any = true; i++; c = if (i < end) s[i] else ' ' }
        if (!any) {
            var j = i0; while (j < end && !isSeparator(s[j])) j++
            throw ImportException("Expected an integer in $what but found '${s.subSequence(i0, j).toString().take(32)}'")
        }
        if (i < end && (c == '.' || c == 'e' || c == 'E')) {
            // "3.0" style: parse as float and truncate
            val fl = FloatList(1); val j = parseFloatAt(s, i0, end, fl, what); out.add(fl[0].toInt()); return j
        }
        out.add((if (neg) -v else v).toInt())
        while (i < end && !isSeparator(s[i])) i++
        return i
    }

    // ---------------------------------------------------------------- colours / units

    fun argb(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Parses "#RRGGBB" / "#RRGGBBAA" (3MF, HTML style). Returns 0 when malformed. */
    fun hexColor(s: String?): Int {
        if (s == null) return 0
        val t = s.trim().removePrefix("#")
        if (t.length != 6 && t.length != 8) return 0
        return try {
            val r = t.substring(0, 2).toInt(16); val g = t.substring(2, 4).toInt(16); val b = t.substring(4, 6).toInt(16)
            val a = if (t.length == 8) t.substring(6, 8).toInt(16) else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        } catch (e: NumberFormatException) { 0 }
    }

    /** Units from a textual unit name as used by AMF / 3MF / VRML metadata. Null when unknown. */
    fun unitsByName(name: String?): ModelUnits? = when (name?.trim()?.lowercase()) {
        "millimeter", "millimetre", "mm" -> ModelUnits.MILLIMETER
        "centimeter", "centimetre", "cm" -> ModelUnits.CENTIMETER
        "meter", "metre", "m" -> ModelUnits.METER
        "inch", "in" -> ModelUnits.INCH
        "foot", "feet", "ft" -> ModelUnits.FOOT
        else -> null
    }

    /** Units from a "meters per unit" scale (COLLADA <unit meter>, USD metersPerUnit). Null when not a standard unit. */
    fun unitsByMeters(metersPerUnit: Double): ModelUnits? {
        fun near(a: Double, b: Double) = kotlin.math.abs(a - b) <= b * 1e-3
        return when {
            near(metersPerUnit, 0.001) -> ModelUnits.MILLIMETER
            near(metersPerUnit, 0.01) -> ModelUnits.CENTIMETER
            near(metersPerUnit, 1.0) -> ModelUnits.METER
            near(metersPerUnit, 0.0254) -> ModelUnits.INCH
            near(metersPerUnit, 0.3048) -> ModelUnits.FOOT
            else -> null
        }
    }

    // ---------------------------------------------------------------- XML

    private val NO_ENTITIES = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }

    private fun <T> quietly(block: () -> T) { try { block() } catch (e: Throwable) { /* feature not supported by this parser */ } }

    fun documentBuilder(): DocumentBuilder {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        quietly { f.isExpandEntityReferences = false }
        quietly { f.isXIncludeAware = false }
        quietly { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        quietly { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        quietly { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val b = f.newDocumentBuilder()
        b.setEntityResolver(NO_ENTITIES)
        b.setErrorHandler(null)
        return b
    }

    fun saxParser(): SAXParser {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        quietly { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        quietly { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        quietly { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newSAXParser()
    }

    /** Parses a DOM document, converting parser errors to [ImportException]. */
    fun parseDom(input: InputStream, what: String): Document {
        return try {
            documentBuilder().parse(InputSource(input))
        } catch (e: org.xml.sax.SAXParseException) {
            throw ImportException("$what is not well-formed XML (line ${e.lineNumber}): ${e.message}", e)
        } catch (e: org.xml.sax.SAXException) {
            throw ImportException("$what could not be parsed: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw ImportException("$what could not be read: ${e.message}", e)
        }
    }

    /** Local (prefix-less, lower-cased) tag name. */
    fun localName(e: DomNode): String { val n = e.nodeName; val i = n.indexOf(':'); return if (i >= 0) n.substring(i + 1) else n }

    inline fun forEachChild(e: Element, action: (Element) -> Unit) {
        var c = e.firstChild
        while (c != null) { if (c.nodeType == DomNode.ELEMENT_NODE) action(c as Element); c = c.nextSibling }
    }

    fun children(e: Element): List<Element> { val l = ArrayList<Element>(); forEachChild(e) { l.add(it) }; return l }
    fun children(e: Element, name: String): List<Element> { val l = ArrayList<Element>(); forEachChild(e) { if (localName(it) == name) l.add(it) }; return l }
    fun child(e: Element, name: String): Element? { var r: Element? = null; forEachChild(e) { if (r == null && localName(it) == name) r = it }; return r }

    /** Finds the first descendant with the given local name (depth-first). */
    fun descendant(e: Element, name: String): Element? {
        var c = e.firstChild
        while (c != null) {
            if (c.nodeType == DomNode.ELEMENT_NODE) {
                val el = c as Element
                if (localName(el) == name) return el
                val d = descendant(el, name); if (d != null) return d
            }
            c = c.nextSibling
        }
        return null
    }

    /** Direct text content (text nodes only, no descendants' text). */
    fun text(e: Element): String {
        var c = e.firstChild
        if (c != null && c.nextSibling == null && (c.nodeType == DomNode.TEXT_NODE || c.nodeType == DomNode.CDATA_SECTION_NODE)) return c.nodeValue
        val sb = StringBuilder()
        while (c != null) { if (c.nodeType == DomNode.TEXT_NODE || c.nodeType == DomNode.CDATA_SECTION_NODE) sb.append(c.nodeValue); c = c.nextSibling }
        return sb.toString()
    }

    fun attr(e: Element, name: String): String? { val v = e.getAttribute(name); return if (v.isNullOrEmpty()) null else v }
    fun attrFloat(e: Element, name: String, default: Float): Float = attr(e, name)?.trim()?.toFloatOrNull() ?: default
    fun attrInt(e: Element, name: String, default: Int): Int = attr(e, name)?.trim()?.toIntOrNull() ?: default

    // ---------------------------------------------------------------- misc

    /** Wraps unexpected runtime failures into a user-readable [ImportException]. */
    inline fun <T> guard(what: String, block: () -> T): T {
        try { return block() }
        catch (e: ImportException) { throw e }
        catch (e: OutOfMemoryError) { throw ImportException("Out of memory while reading $what; the file is too large for this device") }
        catch (e: StackOverflowError) { throw ImportException("$what is nested too deeply to be read") }
        catch (e: Exception) { throw ImportException("$what could not be read: ${e.message ?: e.javaClass.simpleName}", e) }
    }

    fun checkCount(n: Long, what: String, max: Long = 100_000_000L) {
        if (n < 0) throw ImportException("Negative $what count ($n)")
        if (n > max) throw ImportException("The file declares $n $what which exceeds what this device can display")
    }

    /** Input stream wrapper that counts consumed bytes for progress reporting. */
    class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L; private set
        override fun read(): Int { val c = super.read(); if (c >= 0) count++; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) count += n; return n }
        override fun skip(n: Long): Long { val s = super.skip(n); count += s; return s }
    }

    // ---------------------------------------------------------------- primitives (X3D / VRML)

    /** Axis-aligned box centred at the origin. */
    fun box(sx: Float, sy: Float, sz: Float): Mesh {
        val x = sx / 2; val y = sy / 2; val z = sz / 2
        val p = floatArrayOf(-x, -y, -z, x, -y, -z, x, y, -z, -x, y, -z, -x, -y, z, x, -y, z, x, y, z, -x, y, z)
        val i = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return Mesh(p, null, i)
    }

    /** UV sphere (Y up) with smooth normals. */
    fun sphere(radius: Float, segments: Int = 32, rings: Int = 16): Mesh {
        val pos = FloatList((rings + 1) * (segments + 1) * 3); val nrm = FloatList(pos.data.size)
        for (r in 0..rings) {
            val phi = Math.PI * r / rings; val y = cos(phi).toFloat(); val sr = sin(phi).toFloat()
            for (s in 0..segments) {
                val th = 2 * Math.PI * s / segments; val x = (sr * cos(th)).toFloat(); val z = (sr * sin(th)).toFloat()
                pos.add(x * radius, y * radius, z * radius); nrm.add(x, y, z)
            }
        }
        val idx = IntList(rings * segments * 6)
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * (segments + 1) + s; val b = a + segments + 1
            if (r > 0) idx.add(a, a + 1, b)
            if (r < rings - 1) idx.add(a + 1, b + 1, b)
        }
        return Mesh(pos.toArray(), nrm.toArray(), idx.toArray())
    }

    /** Cylinder / cone around the Y axis centred at the origin ([bottom] radius at -h/2, [top] radius at +h/2). */
    fun cylinder(bottom: Float, top: Float, height: Float, segments: Int = 32, side: Boolean = true, capBottom: Boolean = true, capTop: Boolean = true): Mesh {
        val pos = FloatList(); val idx = IntList()
        val h = height / 2
        if (side) {
            val base = 0
            for (s in 0..segments) {
                val th = 2 * Math.PI * s / segments; val c = cos(th).toFloat(); val sn = sin(th).toFloat()
                pos.add(bottom * c, -h, bottom * sn); pos.add(top * c, h, top * sn)
            }
            for (s in 0 until segments) { val a = base + s * 2; idx.add(a, a + 1, a + 2); idx.add(a + 1, a + 3, a + 2) }
        }
        if (capBottom && bottom > 0f) {
            val c0 = pos.size / 3; pos.add(0f, -h, 0f)
            for (s in 0 until segments) { val th = 2 * Math.PI * s / segments; pos.add((bottom * cos(th)).toFloat(), -h, (bottom * sin(th)).toFloat()) }
            for (s in 0 until segments) idx.add(c0, c0 + 1 + (s + 1) % segments, c0 + 1 + s)
        }
        if (capTop && top > 0f) {
            val c0 = pos.size / 3; pos.add(0f, h, 0f)
            for (s in 0 until segments) { val th = 2 * Math.PI * s / segments; pos.add((top * cos(th)).toFloat(), h, (top * sin(th)).toFloat()) }
            for (s in 0 until segments) idx.add(c0, c0 + 1 + s, c0 + 1 + (s + 1) % segments)
        }
        return Mesh(pos.toArray(), null, idx.toArray())
    }

    /** Builds triangles from a list of polygons given as a -1 terminated index stream (X3D/VRML coordIndex). */
    fun triangulateIndexStream(idx: IntArray, positions: FloatArray, out: IntList, flip: Boolean, what: String) {
        val vc = positions.size / 3
        val poly = IntList(16)
        var i = 0
        while (i <= idx.size) {
            val v = if (i < idx.size) idx[i] else -1
            if (v < 0) {
                if (poly.size >= 3) {
                    if (flip) { var a = 0; var b = poly.size - 1; while (a < b) { val t = poly.data[a]; poly.data[a] = poly.data[b]; poly.data[b] = t; a++; b-- } }
                    if (poly.size == 3) out.add(poly[0], poly[1], poly[2])
                    else MeshOps.triangulatePolygon(poly.toArray(), positions, out)
                }
                poly.clear()
            } else {
                if (v >= vc) throw ImportException("$what references vertex $v but only $vc coordinates are defined")
                poly.add(v)
            }
            i++
        }
    }

    /** Builds edges from a -1 terminated index stream (IndexedLineSet). */
    fun edgesFromIndexStream(idx: IntArray, vertexCount: Int, out: IntList, what: String) {
        var prev = -1
        for (v in idx) {
            if (v < 0) { prev = -1; continue }
            if (v >= vertexCount) throw ImportException("$what references vertex $v but only $vertexCount coordinates are defined")
            if (prev >= 0) out.add(prev, v)
            prev = v
        }
    }
}
