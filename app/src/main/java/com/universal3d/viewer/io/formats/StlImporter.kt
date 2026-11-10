package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** STL importer (binary and ASCII). Vertices are welded by exact position so feature edges can be derived. */
class StlImporter : Importer {
    override val formatName = "STL"
    override val extensions = listOf("stl")

    override fun load(source: ImportSource, progress: ProgressSink): Model {
        val file = source.file
        val size = file.length()
        if (size < 15) throw ImportException("File is too small to be an STL model")
        val binary = isBinary(file, size)
        val soup = if (binary) readBinary(source, progress) else readAscii(source, progress)
        progress.progress(0.85f, "Welding vertices")
        if (soup.size < 9) throw ImportException("The STL file contains no triangles")
        val mesh = MeshOps.fromSoup(soup)
        progress.progress(0.95f, "Finalising")
        val name = source.displayName.substringBeforeLast('.')
        return Model.single(mesh, name, formatName, source.displayName, source.sizeBytes, ModelUnits.UNKNOWN,
            listOf(if (binary) "Binary STL" else "ASCII STL"))
    }

    private fun isBinary(file: java.io.File, size: Long): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(size, 1024L).toInt())
            raf.readFully(head)
            // Binary check by size: 84 + 50 * n
            if (size >= 84) {
                raf.seek(80)
                val b = ByteArray(4); raf.readFully(b)
                val n = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                if (84 + 50 * n == size) return true
            }
            val s = String(head, Charsets.ISO_8859_1)
            if (!s.trimStart().startsWith("solid")) return true
            // "solid" header can also start a binary file: look for non-printable bytes
            for (b in head) { val c = b.toInt() and 0xff; if (c == 0 || (c < 32 && c != 9 && c != 10 && c != 13) || c > 126) return true }
            return !s.contains("facet") && size > 1024 && !hasFacetLater(file)
        }
    }

    private fun hasFacetLater(file: java.io.File): Boolean {
        BufferedInputStream(FileInputStream(file)).use { ins ->
            val buf = ByteArray(65536); val n = ins.read(buf)
            return n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).contains("facet")
        }
    }

    private fun readBinary(source: ImportSource, progress: ProgressSink): FloatArray {
        val size = source.file.length()
        DataInputStream(BufferedInputStream(FileInputStream(source.file), 1 shl 16)).use { ins ->
            val header = ByteArray(80); ins.readFully(header)
            val cnt = ByteArray(4); ins.readFully(cnt)
            var n = ByteBuffer.wrap(cnt).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val maxByFile = (size - 84) / 50
            if (n > maxByFile) n = maxByFile // truncated file: read what is there
            if (n <= 0) throw ImportException("Binary STL declares no triangles")
            if (n > 200_000_000L) throw ImportException("STL has $n triangles which exceeds what this device can display")
            val out = FloatArray((n * 9).toInt())
            val rec = ByteArray(50)
            val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
            var o = 0
            val step = maxOf(1L, n / 100)
            for (i in 0 until n) {
                try { ins.readFully(rec) } catch (e: EOFException) { break }
                // skip normal (12 bytes)
                out[o++] = bb.getFloat(12); out[o++] = bb.getFloat(16); out[o++] = bb.getFloat(20)
                out[o++] = bb.getFloat(24); out[o++] = bb.getFloat(28); out[o++] = bb.getFloat(32)
                out[o++] = bb.getFloat(36); out[o++] = bb.getFloat(40); out[o++] = bb.getFloat(44)
                if (i % step == 0L) { progress.progress(0.8f * i / n, "Reading triangles"); progress.checkCancelled() }
            }
            return if (o == out.size) out else out.copyOf(o)
        }
    }

    private fun readAscii(source: ImportSource, progress: ProgressSink): FloatArray {
        val size = source.file.length().toFloat()
        val out = FloatList(1 shl 16)
        FastReader(BufferedInputStream(FileInputStream(source.file), 1 shl 16)).let { r ->
            var count = 0
            while (!r.atEnd()) {
                val t = r.tokenAnyLine()
                when (t) {
                    "vertex" -> { out.add(r.float(), r.float(), r.float()); count++ }
                    "solid", "endsolid" -> r.skipLine()
                    "" -> {}
                    else -> r.skipLine()
                }
                if ((count and 0x3fff) == 0 && count > 0) { progress.progress(0.8f * r.bytesRead / size, "Reading triangles"); progress.checkCancelled() }
            }
        }
        if (out.size % 9 != 0) throw ImportException("ASCII STL is corrupt: vertex count is not a multiple of 3")
        return out.toArray()
    }
}
