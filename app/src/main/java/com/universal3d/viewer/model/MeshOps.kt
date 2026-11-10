package com.universal3d.viewer.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Geometry utilities shared by importers, renderer and tools. All operations preserve coordinates (no scaling). */
object MeshOps {

    /**
     * Weld vertices that share the same position (exact float match after quantisation to [tolerance]).
     * Used for formats without shared vertices (STL, unindexed PLY/OBJ) so adjacency and edges can be computed.
     * Normals are discarded (the renderer flat-shades welded meshes unless [keepNormals]).
     */
    fun weld(positions: FloatArray, tolerance: Float = 0f): Pair<FloatArray, IntArray> {
        val n = positions.size / 3
        val map = LongIntMap(n)
        val out = FloatList(n * 3 / 2 + 3)
        val remap = IntArray(n)
        var unique = 0
        val inv = if (tolerance > 0f) 1f / tolerance else 0f
        for (i in 0 until n) {
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            val key: Long = if (inv > 0f) {
                val qx = Math.round(x * inv).toLong(); val qy = Math.round(y * inv).toLong(); val qz = Math.round(z * inv).toLong()
                (qx * 73856093L) xor (qy * 19349663L) xor (qz * 83492791L) xor (qx shl 40) xor (qy shl 20)
            } else {
                val bx = java.lang.Float.floatToIntBits(x + 0f).toLong(); val by = java.lang.Float.floatToIntBits(y + 0f).toLong(); val bz = java.lang.Float.floatToIntBits(z + 0f).toLong()
                (bx shl 32) xor (by shl 11) xor bz xor (by * 0x9E3779B97F4A7C15uL.toLong())
            }
            val existing = map.getOrPut(key, unique)
            if (existing >= 0 && out[existing * 3] == x && out[existing * 3 + 1] == y && out[existing * 3 + 2] == z) {
                remap[i] = existing
            } else if (existing >= 0) {
                // hash collision with a different position: keep as a new vertex (rare)
                remap[i] = unique; out.add(x, y, z); unique++
            } else {
                remap[i] = unique; out.add(x, y, z); unique++
            }
        }
        return Pair(out.toArray(), remap)
    }

    /** Builds an indexed mesh from a triangle soup (3 vertices per triangle). */
    fun fromSoup(positions: FloatArray): Mesh {
        val (p, remap) = weld(positions)
        return Mesh(p, null, remap)
    }

    /** Area-weighted smooth normals (used when a format promises smooth surfaces but omits normals). */
    fun computeSmoothNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val n = FloatArray(positions.size)
        var i = 0
        while (i < indices.size) {
            val a = indices[i] * 3; val b = indices[i + 1] * 3; val c = indices[i + 2] * 3
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            n[a] += nx; n[a + 1] += ny; n[a + 2] += nz
            n[b] += nx; n[b + 1] += ny; n[b + 2] += nz
            n[c] += nx; n[c + 1] += ny; n[c + 2] += nz
            i += 3
        }
        normalize(n)
        return n
    }

    fun normalize(v: FloatArray) {
        var i = 0
        while (i < v.size) {
            val l = sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2])
            if (l > 1e-20f) { v[i] /= l; v[i + 1] /= l; v[i + 2] /= l } else { v[i] = 0f; v[i + 1] = 0f; v[i + 2] = 1f }
            i += 3
        }
    }

    /**
     * Feature edges of an indexed mesh: boundary edges and edges whose adjacent faces meet at an angle
     * larger than [creaseAngleDeg]. Returns a line list into the mesh's positions, or null when the
     * mesh is too large for the adjacency pass (above [maxTriangles]).
     */
    fun featureEdges(positions: FloatArray, indices: IntArray, creaseAngleDeg: Float = 30f, maxTriangles: Int = 3_000_000): IntArray? {
        val triCount = indices.size / 3
        if (triCount == 0 || triCount > maxTriangles) return null
        // Face normals
        val fn = FloatArray(triCount * 3)
        for (t in 0 until triCount) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz); if (l > 1e-20f) { nx /= l; ny /= l; nz /= l }
            fn[t * 3] = nx; fn[t * 3 + 1] = ny; fn[t * 3 + 2] = nz
        }
        // Edge map: key = (min<<32)|max -> first face index (+1), second face encoded via separate map
        val first = LongIntMap(triCount * 3)
        val count = IntList(triCount * 3)
        val cosThresh = kotlin.math.cos(Math.toRadians(creaseAngleDeg.toDouble())).toFloat()
        val out = IntList(triCount)
        // Pass 1: record first face of each edge
        val edgeFirstFace = IntList(triCount * 3)
        for (t in 0 until triCount) {
            for (e in 0 until 3) {
                val i0 = indices[t * 3 + e]; val i1 = indices[t * 3 + (e + 1) % 3]
                if (i0 == i1) continue
                val lo = minOf(i0, i1); val hi = maxOf(i0, i1)
                val key = (lo.toLong() shl 32) or (hi.toLong() and 0xffffffffL)
                val id = first.getOrPut(key, edgeFirstFace.size)
                if (id < 0) { edgeFirstFace.add(t); count.add(1) }
                else {
                    val c = count[id]
                    if (c == 1) {
                        val f0 = edgeFirstFace[id]
                        val d = fn[f0 * 3] * fn[t * 3] + fn[f0 * 3 + 1] * fn[t * 3 + 1] + fn[f0 * 3 + 2] * fn[t * 3 + 2]
                        if (d < cosThresh) out.add(lo, hi)
                    }
                    count.data[id] = c + 1
                }
            }
        }
        // Pass 2: boundary edges (seen exactly once)
        for (t in 0 until triCount) {
            for (e in 0 until 3) {
                val i0 = indices[t * 3 + e]; val i1 = indices[t * 3 + (e + 1) % 3]
                if (i0 == i1) continue
                val lo = minOf(i0, i1); val hi = maxOf(i0, i1)
                val key = (lo.toLong() shl 32) or (hi.toLong() and 0xffffffffL)
                val id = first.get(key)
                if (id >= 0 && count[id] == 1) out.add(lo, hi)
            }
        }
        return out.toArray()
    }

    /** Triangulate a polygon given as vertex indices (fan for convex, ear clipping for concave planar polygons). */
    fun triangulatePolygon(poly: IntArray, positions: FloatArray, out: IntList) {
        val n = poly.size
        if (n < 3) return
        if (n == 3) { out.add(poly[0], poly[1], poly[2]); return }
        if (n == 4) {
            // split quad along the shorter diagonal
            val d02 = dist2(positions, poly[0], poly[2]); val d13 = dist2(positions, poly[1], poly[3])
            if (d02 <= d13) { out.add(poly[0], poly[1], poly[2]); out.add(poly[0], poly[2], poly[3]) }
            else { out.add(poly[1], poly[2], poly[3]); out.add(poly[1], poly[3], poly[0]) }
            return
        }
        // Polygon normal (Newell)
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val a = poly[i] * 3; val b = poly[(i + 1) % n] * 3
            nx += (positions[a + 1] - positions[b + 1]) * (positions[a + 2] + positions[b + 2])
            ny += (positions[a + 2] - positions[b + 2]) * (positions[a] + positions[b])
            nz += (positions[a] - positions[b]) * (positions[a + 1] + positions[b + 1])
        }
        val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
        // project to 2D
        val u = FloatArray(n); val v = FloatArray(n)
        for (i in 0 until n) {
            val p = poly[i] * 3
            when {
                ax >= ay && ax >= az -> { u[i] = positions[p + 1]; v[i] = positions[p + 2] }
                ay >= ax && ay >= az -> { u[i] = positions[p + 2]; v[i] = positions[p] }
                else -> { u[i] = positions[p]; v[i] = positions[p + 1] }
            }
        }
        val sign = if ((ax >= ay && ax >= az && nx < 0) || (ay >= ax && ay >= az && ny < 0) || (az > ax && az > ay && nz < 0)) -1f else 1f
        val idx = IntArray(n) { it }
        var remaining = n
        var guard = 0
        var i = 0
        while (remaining > 3 && guard < n * n) {
            guard++
            val ia = idx[(i + remaining - 1) % remaining]; val ib = idx[i % remaining]; val ic = idx[(i + 1) % remaining]
            val cross = ((u[ib] - u[ia]) * (v[ic] - v[ia]) - (v[ib] - v[ia]) * (u[ic] - u[ia])) * sign
            var ear = cross > 1e-12f
            if (ear) {
                for (k in 0 until remaining) {
                    val ik = idx[k]; if (ik == ia || ik == ib || ik == ic) continue
                    if (pointInTri(u[ik], v[ik], u[ia], v[ia], u[ib], v[ib], u[ic], v[ic], sign)) { ear = false; break }
                }
            }
            if (ear) {
                out.add(poly[ia], poly[ib], poly[ic])
                // remove ib
                val pos = i % remaining
                System.arraycopy(idx, pos + 1, idx, pos, remaining - pos - 1)
                remaining--
                i = 0
            } else i++
        }
        if (remaining == 3) out.add(poly[idx[0]], poly[idx[1]], poly[idx[2]])
        else if (remaining > 3) { // degenerate: fall back to fan
            for (k in 1 until remaining - 1) out.add(poly[idx[0]], poly[idx[k]], poly[idx[k + 1]])
        }
    }

    private fun pointInTri(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, sign: Float): Boolean {
        val d1 = ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) * sign
        val d2 = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * sign
        val d3 = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * sign
        return d1 >= 0 && d2 >= 0 && d3 >= 0
    }

    private fun dist2(p: FloatArray, a: Int, b: Int): Float {
        val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]; val dz = p[a * 3 + 2] - p[b * 3 + 2]
        return dx * dx + dy * dy + dz * dz
    }

    /** Angle in degrees between two unit vectors. */
    fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float =
        Math.toDegrees(acos((ax * bx + ay * by + az * bz).coerceIn(-1f, 1f).toDouble())).toFloat()
}
