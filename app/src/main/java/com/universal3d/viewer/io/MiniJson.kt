package com.universal3d.viewer.io

/** Minimal JSON parser (objects -> Map<String, Any?>, arrays -> List<Any?>, numbers -> Double, strings, booleans, null). */
object MiniJson {
    fun parse(text: String): Any? { val p = Parser(text); p.ws(); val v = p.value(); p.ws(); if (p.i != text.length) throw ImportException("Trailing characters in JSON at ${p.i}"); return v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw ImportException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { expect("true"); true }; 'f' -> { expect("false"); false }; 'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw ImportException("Unexpected JSON character '$c' at $i")
            }
        }
        fun expect(w: String) { if (!s.startsWith(w, i)) throw ImportException("Bad JSON literal at $i"); i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw ImportException("Expected JSON key at $i")
                val k = str(); ws(); if (i >= s.length || s[i] != ':') throw ImportException("Expected ':' at $i"); i++; ws()
                m[k] = value(); ws()
                if (i >= s.length) throw ImportException("Unterminated JSON object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw ImportException("Expected ',' or '}' at $i")
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (i >= s.length) throw ImportException("Unterminated JSON array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw ImportException("Expected ',' or ']' at $i")
            }
        }
        fun str(): String {
            i++; val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw ImportException("Unterminated JSON string")
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw ImportException("Bad JSON number at $st")
        }
    }

    // Convenience accessors
    fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
    fun Any?.asList(): List<Any?>? = this as? List<Any?>
    fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
    fun Any?.asInt(): Int? = (this as? Number)?.toInt()
    fun Any?.asString(): String? = this as? String
}
