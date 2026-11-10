package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the U3DM intermediate format produced by the bundled Open CASCADE converter
 * (see native/converter/README.md). Little-endian; nodes in parent-before-child order.
 */
class U3dmImporter : Importer {
    override val formatName = "U3DM"
    override val extensions = listOf("u3dm")

    override fun load(source: ImportSource, progress: ProgressSink): Model = load(source, progress, formatName, emptyList())

    fun load(source: ImportSource, progress: ProgressSink, reportedFormat: String, extraNotes: List<String>): Model {
        val total = source.file.length().toFloat()
        var consumed = 0L
        DataInputStream(BufferedInputStream(FileInputStream(source.file), 1 shl 16)).use { ins ->
            fun u8(): Int { consumed++; return ins.readUnsignedByte() }
            fun u32(): Int { consumed += 4; return Integer.reverseBytes(ins.readInt()) }
            fun f64(): Double { consumed += 8; return java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(ins.readLong())) }
            fun floats(n: Int): FloatArray {
                val bytes = ByteArray(n * 4); ins.readFully(bytes); consumed += bytes.size
                val out = FloatArray(n); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out); return out
            }
            fun ints(n: Int): IntArray {
                val bytes = ByteArray(n * 4); ins.readFully(bytes); consumed += bytes.size
                val out = IntArray(n); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out); return out
            }
            val magic = ByteArray(4); ins.readFully(magic); consumed += 4
            if (String(magic, Charsets.US_ASCII) != "U3DM") throw ImportException("Not a U3DM file")
            val version = u32(); if (version != 1) throw ImportException("Unsupported U3DM version $version")
            val flags = u32()
            val unitToMm = f64()
            val nodeCount = u32()
            if (nodeCount < 0 || nodeCount > 10_000_000) throw ImportException("Corrupt U3DM node count")
            val nodes = ArrayList<Node>(nodeCount)
            val roots = ArrayList<Node>()
            for (i in 0 until nodeCount) {
                val id = u32(); val parent = u32()
                val nameLen = u32(); if (nameLen < 0 || nameLen > 1_000_000) throw ImportException("Corrupt U3DM name")
                val nb = ByteArray(nameLen); ins.readFully(nb); consumed += nameLen
                val name = String(nb, Charsets.UTF_8).ifBlank { "Part ${i + 1}" }
                val r = u8(); val g = u8(); val b = u8(); val a = u8()
                val color = if (a == 0) 0 else (0xff shl 24) or (r shl 16) or (g shl 8) or b
                val vc = u32(); if (vc < 0) throw ImportException("Corrupt U3DM vertex count")
                val pos = floats(vc * 3)
                val hasN = u8()
                val nrm = if (hasN == 1) floats(vc * 3) else null
                val ic = u32(); if (ic < 0 || ic % 3 != 0) throw ImportException("Corrupt U3DM index count")
                val idx = ints(ic)
                val evc = u32(); val epos = floats(evc * 3)
                val eic = u32(); if (eic < 0 || eic % 2 != 0) throw ImportException("Corrupt U3DM edge index count")
                val eidx = ints(eic)
                val mesh = if (vc > 0 || evc > 0) Mesh(pos, nrm, idx, if (evc > 0) epos else null, if (eic > 0) eidx else null) else null
                val node = Node(name, mesh, color)
                if (parent >= 0 && parent < nodes.size) nodes[parent].add(node) else roots.add(node)
                nodes.add(node)
                if ((i and 63) == 0) { progress.progress(0.1f + 0.8f * consumed / total, "Reading geometry"); progress.checkCancelled() }
            }
            val units = when (unitToMm) { 1.0 -> ModelUnits.MILLIMETER; 10.0 -> ModelUnits.CENTIMETER; 1000.0 -> ModelUnits.METER; 25.4 -> ModelUnits.INCH; else -> ModelUnits.MILLIMETER }
            val notes = ArrayList(extraNotes); if (flags and 1 != 0) notes.add("Colours from file")
            return Model(roots, reportedFormat, source.displayName, source.sizeBytes, units, notes)
        }
    }
}
