package com.universal3d.viewer.io

import java.io.EOFException
import java.io.InputStream

/**
 * Allocation-free tokenizer over a byte stream for large ASCII formats (OBJ, STL, PLY, OFF, VTK ...).
 * Parses floats and ints directly from bytes; strings are only materialised on demand.
 */
class FastReader(private val input: InputStream, bufferSize: Int = 1 shl 16) {
    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private var lim = 0
    private var eof = false
    /** Total bytes consumed so far (for progress reporting). */
    var bytesRead = 0L
        private set
    var line = 1
        private set

    private fun fill(): Boolean {
        if (eof) return false
        val n = input.read(buf, 0, buf.size)
        if (n <= 0) { eof = true; lim = 0; pos = 0; return false }
        lim = n; pos = 0; return true
    }

    /** Current byte or -1 at end of stream. */
    fun peek(): Int { if (pos >= lim && !fill()) return -1; return buf[pos].toInt() and 0xff }

    fun read(): Int { val c = peek(); if (c >= 0) { pos++; bytesRead++; if (c == '\n'.code) line++ }; return c }

    fun atEnd(): Boolean = peek() < 0

    /** Skips spaces, tabs and carriage returns (not newlines). */
    fun skipSpaces() { while (true) { val c = peek(); if (c == ' '.code || c == '\t'.code || c == '\r'.code) read() else return } }

    /** Skips all whitespace including newlines. */
    fun skipWhitespace() { while (true) { val c = peek(); if (c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code) read() else return } }

    fun skipLine() { while (true) { val c = read(); if (c < 0 || c == '\n'.code) return } }

    /** True when the next non-space char is a newline or EOF (i.e. no more tokens on this line). */
    fun endOfLine(): Boolean { skipSpaces(); val c = peek(); return c < 0 || c == '\n'.code }

    private val sb = StringBuilder(64)

    /** Reads a whitespace-delimited token on the current line; empty string if none. */
    fun token(): String {
        skipSpaces(); sb.setLength(0)
        while (true) {
            val c = peek()
            if (c < 0 || c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code) break
            sb.append(c.toChar()); read()
        }
        return sb.toString()
    }

    /** Reads a whitespace-delimited token across lines; empty string at EOF. */
    fun tokenAnyLine(): String { skipWhitespace(); return token() }

    /** Reads the rest of the line (trimmed). */
    fun restOfLine(): String {
        skipSpaces(); sb.setLength(0)
        while (true) { val c = peek(); if (c < 0 || c == '\n'.code) break; sb.append(c.toChar()); read() }
        if (peek() == '\n'.code) read()
        return sb.toString().trimEnd()
    }

    /** Reads a line (without terminator); null at EOF. */
    fun readLine(): String? { if (atEnd()) return null; return restOfLine() }

    /** Parses a float token on the current line. Throws [ImportException] on malformed input. */
    fun float(): Float {
        skipSpaces()
        var c = peek()
        var neg = false
        if (c == '-'.code || c == '+'.code) { neg = c == '-'.code; read(); c = peek() }
        var mant = 0L; var digits = 0; var exp = 0; var any = false
        while (c >= '0'.code && c <= '9'.code) { if (digits < 18) { mant = mant * 10 + (c - '0'.code); digits++ } else exp++; any = true; read(); c = peek() }
        if (c == '.'.code) {
            read(); c = peek()
            while (c >= '0'.code && c <= '9'.code) { if (digits < 18) { mant = mant * 10 + (c - '0'.code); digits++; exp-- }; any = true; read(); c = peek() }
        }
        if (!any) {
            // nan / inf or garbage
            val t = token()
            if (t.equals("nan", true)) return Float.NaN
            if (t.equals("inf", true) || t.equals("infinity", true)) return if (neg) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            throw ImportException("Expected a number at line $line but found '${if (t.isEmpty()) "end of line" else t}'")
        }
        if (c == 'e'.code || c == 'E'.code) {
            read(); c = peek()
            var eneg = false
            if (c == '-'.code || c == '+'.code) { eneg = c == '-'.code; read(); c = peek() }
            var e = 0
            while (c >= '0'.code && c <= '9'.code) { if (e < 10000) e = e * 10 + (c - '0'.code); read(); c = peek() }
            exp += if (eneg) -e else e
        }
        var v = if (exp == 0) mant.toDouble() else mant.toDouble() * pow10(exp)
        if (neg) v = -v
        return v.toFloat()
    }

    fun int(): Int {
        skipSpaces()
        var c = peek(); var neg = false
        if (c == '-'.code || c == '+'.code) { neg = c == '-'.code; read(); c = peek() }
        var v = 0L; var any = false
        while (c >= '0'.code && c <= '9'.code) { v = v * 10 + (c - '0'.code); any = true; read(); c = peek(); if (v > Int.MAX_VALUE) v = Int.MAX_VALUE.toLong() }
        if (!any) {
            // allow "12.0" style ints by parsing float
            if (c == '.'.code) return float().toInt()
            val t = token(); throw ImportException("Expected an integer at line $line but found '${if (t.isEmpty()) "end of line" else t}'")
        }
        if (c == '.'.code) { read(); c = peek(); while (c >= '0'.code && c <= '9'.code) { read(); c = peek() } }
        return (if (neg) -v else v).toInt()
    }

    /** Reads raw bytes (for binary sections following an ASCII header, e.g. PLY). */
    fun readFully(dst: ByteArray, off: Int, len: Int) {
        var o = off; var remaining = len
        while (remaining > 0) {
            if (pos >= lim && !fill()) throw EOFException("Unexpected end of file")
            val n = minOf(remaining, lim - pos)
            System.arraycopy(buf, pos, dst, o, n); pos += n; o += n; remaining -= n; bytesRead += n
        }
    }

    companion object {
        private val POW10 = DoubleArray(617) { Math.pow(10.0, (it - 308).toDouble()) }
        fun pow10(e: Int): Double = if (e < -308) 0.0 else if (e > 308) Double.POSITIVE_INFINITY else POW10[e + 308]
    }
}
