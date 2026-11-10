package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.model.FloatList
import com.universal3d.viewer.model.IntList
import com.universal3d.viewer.model.Mat4
import com.universal3d.viewer.model.Mesh
import com.universal3d.viewer.model.MeshOps
import kotlin.math.sqrt

/** Geometry node builders shared by the VRML 97 and X3D importers (both use the same node semantics). */
internal object VrmlGeometry {

    /**
     * IndexedFaceSet: [coordIndex] is -1 separated polygons. Normals are used only when they are per vertex and
     * either indexed exactly like the coordinates (no normalIndex, same count) or resolvable through [normalIndex]
     * (then vertices are split per corner).
     */
    fun indexedFaceSet(points: FloatArray, coordIndex: IntArray, normals: FloatArray?, normalIndex: IntArray?, normalPerVertex: Boolean, ccw: Boolean, what: String): Mesh? {
        if (points.isEmpty() || coordIndex.isEmpty()) return null
        val vc = points.size / 3
        val tris = IntList(coordIndex.size * 2)
        if (normals != null && normalPerVertex && normalIndex != null && normalIndex.size == coordIndex.size) {
            // split vertices: each polygon corner gets (coord, normal) pair
            val outPos = FloatList(coordIndex.size * 3); val outNrm = FloatList(coordIndex.size * 3)
            val map = com.universal3d.viewer.model.LongIntMap(coordIndex.size)
            val nc = normals.size / 3
            val remapped = IntArray(coordIndex.size)
            for (i in coordIndex.indices) {
                val ci = coordIndex[i]
                if (ci < 0) { remapped[i] = -1; continue }
                if (ci >= vc) throw ImportException("$what references vertex $ci but only $vc coordinates are defined")
                var ni = normalIndex[i]; if (ni < 0 || ni >= nc) ni = -1
                val key = (ci.toLong() shl 32) or ((ni + 1).toLong() and 0xffffffffL)
                var idx = map.getOrPut(key, outPos.size / 3)
                if (idx < 0) {
                    idx = outPos.size / 3
                    outPos.add(points[ci * 3], points[ci * 3 + 1], points[ci * 3 + 2])
                    if (ni >= 0) outNrm.add(normals[ni * 3], normals[ni * 3 + 1], normals[ni * 3 + 2]) else outNrm.add(0f, 0f, 1f)
                }
                remapped[i] = idx
            }
            val p = outPos.toArray()
            TextFormatSupport.triangulateIndexStream(remapped, p, tris, !ccw, what)
            return Mesh(p, outNrm.toArray(), tris.toArray())
        }
        TextFormatSupport.triangulateIndexStream(coordIndex, points, tris, !ccw, what)
        val n = if (normals != null && normalPerVertex && normalIndex == null && normals.size == points.size) normals else null
        return Mesh(points, n, tris.toArray())
    }

    /** IndexedTriangleSet / TriangleSet style: flat triangle index list (or implicit 0..n-1). */
    fun triangleSet(points: FloatArray, index: IntArray?, normals: FloatArray?, ccw: Boolean, what: String): Mesh? {
        val vc = points.size / 3
        val idx = index ?: IntArray(vc) { it }
        val n = idx.size / 3 * 3
        val out = IntArray(n)
        var i = 0
        while (i < n) {
            val a = idx[i]; val b = idx[i + 1]; val c = idx[i + 2]
            if (a >= vc || b >= vc || c >= vc || a < 0 || b < 0 || c < 0) throw ImportException("$what references vertex ${maxOf(a, b, c)} but only $vc coordinates are defined")
            if (ccw) { out[i] = a; out[i + 1] = b; out[i + 2] = c } else { out[i] = a; out[i + 1] = c; out[i + 2] = b }
            i += 3
        }
        if (n == 0) return null
        return Mesh(points, if (normals != null && normals.size == points.size) normals else null, out)
    }

    /** Strips (-1 separated for IndexedTriangleStripSet, or counts for TriangleStripSet) and fans. */
    fun stripsOrFans(points: FloatArray, index: IntArray?, counts: IntArray?, fan: Boolean, ccw: Boolean, what: String): Mesh? {
        val vc = points.size / 3
        val out = IntList(256)
        val run = IntList(64)
        fun flush() {
            for (k in 2 until run.size) {
                val a: Int; val b: Int; val c: Int
                if (fan) { a = run[0]; b = run[k - 1]; c = run[k] }
                else if (k % 2 == 0) { a = run[k - 2]; b = run[k - 1]; c = run[k] } else { a = run[k - 1]; b = run[k - 2]; c = run[k] }
                if (ccw) out.add(a, b, c) else out.add(a, c, b)
            }
            run.clear()
        }
        if (index != null) {
            for (v in index) { if (v < 0) flush() else { if (v >= vc) throw ImportException("$what references vertex $v but only $vc coordinates are defined"); run.add(v) } }
            flush()
        } else if (counts != null) {
            var at = 0
            for (c in counts) { for (k in 0 until c) { if (at >= vc) break; run.add(at++) }; flush() }
        }
        if (out.size == 0) return null
        return Mesh(points, null, out.toArray())
    }

    fun indexedLineSet(points: FloatArray, coordIndex: IntArray, what: String): Mesh? {
        if (points.isEmpty()) return null
        val edges = IntList(coordIndex.size * 2)
        TextFormatSupport.edgesFromIndexStream(coordIndex, points.size / 3, edges, what)
        if (edges.size == 0) return null
        return Mesh(FloatArray(0), null, IntArray(0), points, edges.toArray())
    }

    /** LineSet: vertexCount gives consecutive polylines. */
    fun lineSet(points: FloatArray, vertexCount: IntArray?, what: String): Mesh? {
        val vc = points.size / 3
        if (vc < 2) return null
        val edges = IntList(vc * 2)
        if (vertexCount == null || vertexCount.isEmpty()) { for (i in 1 until vc) edges.add(i - 1, i) }
        else { var at = 0; for (c in vertexCount) { for (k in 1 until c) { if (at + k >= vc) break; edges.add(at + k - 1, at + k) }; at += c } }
        if (edges.size == 0) return null
        return Mesh(FloatArray(0), null, IntArray(0), points, edges.toArray())
    }

    /** ElevationGrid: heights on an xDimension * zDimension grid. */
    fun elevationGrid(xDim: Int, zDim: Int, xSpacing: Float, zSpacing: Float, height: FloatArray, ccw: Boolean): Mesh? {
        if (xDim < 2 || zDim < 2) return null
        if (xDim.toLong() * zDim > 50_000_000L) throw ImportException("ElevationGrid of $xDim x $zDim is too large")
        val n = xDim * zDim
        val p = FloatArray(n * 3)
        for (z in 0 until zDim) for (x in 0 until xDim) {
            val i = z * xDim + x
            p[i * 3] = x * xSpacing; p[i * 3 + 1] = if (i < height.size) height[i] else 0f; p[i * 3 + 2] = z * zSpacing
        }
        val idx = IntArray((xDim - 1) * (zDim - 1) * 6)
        var o = 0
        for (z in 0 until zDim - 1) for (x in 0 until xDim - 1) {
            val a = z * xDim + x; val b = a + 1; val c = a + xDim; val d = c + 1
            if (ccw) { idx[o++] = a; idx[o++] = c; idx[o++] = b; idx[o++] = b; idx[o++] = c; idx[o++] = d }
            else { idx[o++] = a; idx[o++] = b; idx[o++] = c; idx[o++] = b; idx[o++] = d; idx[o++] = c }
        }
        return Mesh(p, null, idx)
    }

    /**
     * Extrusion: 2D [crossSection] (x,z pairs) swept along [spine] (xyz) with optional per-spine [scale] (x,z pairs)
     * and [orientation] (axis-angle quadruples). Uses the spine-aligned cross-section plane of the VRML spec.
     */
    fun extrusion(crossSection: FloatArray, spine: FloatArray, scale: FloatArray?, orientation: FloatArray?, beginCap: Boolean, endCap: Boolean, ccw: Boolean, solid: Boolean): Mesh? {
        val csN = crossSection.size / 2; val spN = spine.size / 3
        if (csN < 2 || spN < 2) return null
        if (csN.toLong() * spN > 20_000_000L) throw ImportException("Extrusion is too large ($csN x $spN points)")
        val closedSpine = spine[0] == spine[(spN - 1) * 3] && spine[1] == spine[(spN - 1) * 3 + 1] && spine[2] == spine[(spN - 1) * 3 + 2]
        // spine-aligned coordinate frames
        val yAx = FloatArray(spN * 3); val zAx = FloatArray(spN * 3); val xAx = FloatArray(spN * 3)
        fun sp(i: Int, c: Int) = spine[((i % spN + spN) % spN) * 3 + c]
        for (i in 0 until spN) {
            var yx: Float; var yy: Float; var yz: Float
            if (closedSpine) { val n = spN - 1; val ip = ((i + 1) % n); val im = ((i - 1 + n) % n); yx = sp(ip, 0) - sp(im, 0); yy = sp(ip, 1) - sp(im, 1); yz = sp(ip, 2) - sp(im, 2) }
            else if (i == 0) { yx = sp(1, 0) - sp(0, 0); yy = sp(1, 1) - sp(0, 1); yz = sp(1, 2) - sp(0, 2) }
            else if (i == spN - 1) { yx = sp(i, 0) - sp(i - 1, 0); yy = sp(i, 1) - sp(i - 1, 1); yz = sp(i, 2) - sp(i - 1, 2) }
            else { yx = sp(i + 1, 0) - sp(i - 1, 0); yy = sp(i + 1, 1) - sp(i - 1, 1); yz = sp(i + 1, 2) - sp(i - 1, 2) }
            yAx[i * 3] = yx; yAx[i * 3 + 1] = yy; yAx[i * 3 + 2] = yz
            // Z = (next - this) x (prev - this)
            val a0: Int; val a1: Int
            if (closedSpine) { val n = spN - 1; a0 = (i + 1) % n; a1 = (i - 1 + n) % n }
            else { a0 = if (i == spN - 1) i else i + 1; a1 = if (i == 0) 0 else i - 1 }
            val ux = sp(a0, 0) - sp(i, 0); val uy = sp(a0, 1) - sp(i, 1); val uz = sp(a0, 2) - sp(i, 2)
            val vx = sp(a1, 0) - sp(i, 0); val vy = sp(a1, 1) - sp(i, 1); val vz = sp(a1, 2) - sp(i, 2)
            zAx[i * 3] = uy * vz - uz * vy; zAx[i * 3 + 1] = uz * vx - ux * vz; zAx[i * 3 + 2] = ux * vy - uy * vx
        }
        // propagate Z where undefined (collinear sections); flip to keep continuity
        var lastZ = -1
        for (i in 0 until spN) { if (len(zAx, i) > 1e-12f) { if (lastZ >= 0 && dot(zAx, i, zAx, lastZ) < 0) neg(zAx, i); lastZ = i } else if (lastZ >= 0) copy(zAx, lastZ, zAx, i) }
        if (lastZ < 0) {
            // whole spine collinear: pick any perpendicular to Y
            for (i in 0 until spN) {
                val yx = yAx[i * 3]; val yy = yAx[i * 3 + 1]; val yz = yAx[i * 3 + 2]
                if (kotlin.math.abs(yy) < 0.9f * sqrt(yx * yx + yy * yy + yz * yz)) {
                    // Z = (0,1,0) x Y -> perpendicular to the spine
                    zAx[i * 3] = yz; zAx[i * 3 + 1] = 0f; zAx[i * 3 + 2] = -yx
                } else { zAx[i * 3] = 0f; zAx[i * 3 + 1] = 0f; zAx[i * 3 + 2] = 1f }
            }
        } else { var first = -1; for (i in 0 until spN) if (len(zAx, i) > 1e-12f) { first = i; break }; for (i in 0 until first) copy(zAx, first, zAx, i) }
        for (i in 0 until spN) {
            normalize(yAx, i); normalize(zAx, i)
            // X = Y x Z
            xAx[i * 3] = yAx[i * 3 + 1] * zAx[i * 3 + 2] - yAx[i * 3 + 2] * zAx[i * 3 + 1]
            xAx[i * 3 + 1] = yAx[i * 3 + 2] * zAx[i * 3] - yAx[i * 3] * zAx[i * 3 + 2]
            xAx[i * 3 + 2] = yAx[i * 3] * zAx[i * 3 + 1] - yAx[i * 3 + 1] * zAx[i * 3]
            normalize(xAx, i)
        }
        val pos = FloatArray(csN * spN * 3)
        val tmp = FloatArray(3)
        for (i in 0 until spN) {
            val sx = if (scale != null && scale.size >= 2) (if (scale.size >= (i + 1) * 2) scale[i * 2] else scale[0]) else 1f
            val sz = if (scale != null && scale.size >= 2) (if (scale.size >= (i + 1) * 2) scale[i * 2 + 1] else scale[1]) else 1f
            var rot: Mat4? = null
            if (orientation != null && orientation.size >= 4) {
                val o = if (orientation.size >= (i + 1) * 4) i * 4 else 0
                if (orientation[o + 3] != 0f) rot = Mat4.rotationAxis(orientation[o + 3], orientation[o], orientation[o + 1], orientation[o + 2])
            }
            for (k in 0 until csN) {
                var cx = crossSection[k * 2] * sx; var cy = 0f; var cz = crossSection[k * 2 + 1] * sz
                if (rot != null) { rot.transformDir(cx, cy, cz, tmp); cx = tmp[0]; cy = tmp[1]; cz = tmp[2] }
                val o = (i * csN + k) * 3
                pos[o] = spine[i * 3] + cx * xAx[i * 3] + cy * yAx[i * 3] + cz * zAx[i * 3]
                pos[o + 1] = spine[i * 3 + 1] + cx * xAx[i * 3 + 1] + cy * yAx[i * 3 + 1] + cz * zAx[i * 3 + 1]
                pos[o + 2] = spine[i * 3 + 2] + cx * xAx[i * 3 + 2] + cy * yAx[i * 3 + 2] + cz * zAx[i * 3 + 2]
            }
        }
        val idx = IntList((spN - 1) * (csN - 1) * 6 + csN * 6)
        for (i in 0 until spN - 1) for (k in 0 until csN - 1) {
            val a = i * csN + k; val b = a + 1; val c = a + csN; val d = c + 1
            if (ccw) { idx.add(a, b, d); idx.add(a, d, c) } else { idx.add(a, d, b); idx.add(a, c, d) }
        }
        if (beginCap || endCap) {
            val closedCs = crossSection[0] == crossSection[(csN - 1) * 2] && crossSection[1] == crossSection[(csN - 1) * 2 + 1]
            val n = if (closedCs) csN - 1 else csN
            if (n >= 3) {
                val capTris = IntList(n * 3)
                // triangulate in 2D using the first ring's 3D points (planar)
                val poly = IntArray(n) { it }
                MeshOps.triangulatePolygon(poly, pos, capTris)
                if (beginCap) { var t = 0; while (t < capTris.size) { if (ccw) idx.add(capTris[t], capTris[t + 2], capTris[t + 1]) else idx.add(capTris[t], capTris[t + 1], capTris[t + 2]); t += 3 } }
                if (endCap) { val base = (spN - 1) * csN; var t = 0; while (t < capTris.size) { if (ccw) idx.add(base + capTris[t], base + capTris[t + 1], base + capTris[t + 2]) else idx.add(base + capTris[t], base + capTris[t + 2], base + capTris[t + 1]); t += 3 } }
            }
        }
        if (idx.size == 0) return null
        return Mesh(pos, null, idx.toArray())
    }

    private fun len(a: FloatArray, i: Int) = sqrt(a[i * 3] * a[i * 3] + a[i * 3 + 1] * a[i * 3 + 1] + a[i * 3 + 2] * a[i * 3 + 2])
    private fun dot(a: FloatArray, i: Int, b: FloatArray, j: Int) = a[i * 3] * b[j * 3] + a[i * 3 + 1] * b[j * 3 + 1] + a[i * 3 + 2] * b[j * 3 + 2]
    private fun neg(a: FloatArray, i: Int) { a[i * 3] = -a[i * 3]; a[i * 3 + 1] = -a[i * 3 + 1]; a[i * 3 + 2] = -a[i * 3 + 2] }
    private fun copy(a: FloatArray, from: Int, b: FloatArray, to: Int) { b[to * 3] = a[from * 3]; b[to * 3 + 1] = a[from * 3 + 1]; b[to * 3 + 2] = a[from * 3 + 2] }
    private fun normalize(a: FloatArray, i: Int) { val l = len(a, i); if (l > 1e-20f) { a[i * 3] /= l; a[i * 3 + 1] /= l; a[i * 3 + 2] /= l } }

    /** Transform composition of VRML/X3D Transform: T * C * R * SR * S * -SR * -C. */
    fun transformMatrix(translation: FloatArray?, rotation: FloatArray?, scale: FloatArray?, scaleOrientation: FloatArray?, center: FloatArray?): Mat4 {
        var m = Mat4.identity()
        if (translation != null && translation.size >= 3) m = m.times(Mat4.translation(translation[0], translation[1], translation[2]))
        val hasCenter = center != null && center.size >= 3 && (center[0] != 0f || center[1] != 0f || center[2] != 0f)
        if (hasCenter) m = m.times(Mat4.translation(center!![0], center[1], center[2]))
        if (rotation != null && rotation.size >= 4 && rotation[3] != 0f) m = m.times(Mat4.rotationAxis(rotation[3], rotation[0], rotation[1], rotation[2]))
        val hasSo = scaleOrientation != null && scaleOrientation.size >= 4 && scaleOrientation[3] != 0f
        if (scale != null && scale.size >= 3 && (scale[0] != 1f || scale[1] != 1f || scale[2] != 1f)) {
            if (hasSo) m = m.times(Mat4.rotationAxis(scaleOrientation!![3], scaleOrientation[0], scaleOrientation[1], scaleOrientation[2]))
            m = m.times(Mat4.scale(scale[0], scale[1], scale[2]))
            if (hasSo) m = m.times(Mat4.rotationAxis(-scaleOrientation!![3], scaleOrientation[0], scaleOrientation[1], scaleOrientation[2]))
        }
        if (hasCenter) m = m.times(Mat4.translation(-center!![0], -center[1], -center[2]))
        return m
    }
}
