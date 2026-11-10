package com.universal3d.viewer.render

import com.universal3d.viewer.model.Model
import com.universal3d.viewer.model.Node
import kotlin.math.abs
import kotlin.math.sqrt

/** Result of a ray pick: hit point, surface normal, node and triangle index. */
class PickResult(val x: Float, val y: Float, val z: Float, val nx: Float, val ny: Float, val nz: Float, val node: Node, val triangle: Int, val distance: Float)

/** CPU ray casting against visible meshes (brute force, Möller–Trumbore). Runs on a background thread. */
object Picker {
    fun pick(model: Model, ray: FloatArray, clip: FloatArray?, clipEnabled: Boolean): PickResult? {
        val ox = ray[0]; val oy = ray[1]; val oz = ray[2]; val dx = ray[3]; val dy = ray[4]; val dz = ray[5]
        var best: PickResult? = null
        var bestT = Float.POSITIVE_INFINITY
        for (node in model.meshNodes) {
            if (!node.effectivelyVisible) continue
            val mesh = node.mesh ?: continue
            // bounding box reject
            val b = mesh.bounds
            if (!rayBox(ox, oy, oz, dx, dy, dz, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, bestT)) continue
            val p = mesh.positions; val idx = mesh.indices
            var i = 0
            while (i < idx.size) {
                val a = idx[i] * 3; val bI = idx[i + 1] * 3; val c = idx[i + 2] * 3
                val ax = p[a]; val ay = p[a + 1]; val az = p[a + 2]
                val e1x = p[bI] - ax; val e1y = p[bI + 1] - ay; val e1z = p[bI + 2] - az
                val e2x = p[c] - ax; val e2y = p[c + 1] - ay; val e2z = p[c + 2] - az
                val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (abs(det) > 1e-12f) {
                    val inv = 1f / det
                    val tx = ox - ax; val ty = oy - ay; val tz = oz - az
                    val u = (tx * px + ty * py + tz * pz) * inv
                    if (u >= 0f && u <= 1f) {
                        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                        val v = (dx * qx + dy * qy + dz * qz) * inv
                        if (v >= 0f && u + v <= 1f) {
                            val t = (e2x * qx + e2y * qy + e2z * qz) * inv
                            if (t > 1e-6f && t < bestT) {
                                val hx = ox + dx * t; val hy = oy + dy * t; val hz = oz + dz * t
                                if (!(clipEnabled && clip != null && hx * clip[0] + hy * clip[1] + hz * clip[2] + clip[3] < 0f)) {
                                    var nx = e1y * e2z - e1z * e2y; var ny = e1z * e2x - e1x * e2z; var nz = e1x * e2y - e1y * e2x
                                    val l = sqrt(nx * nx + ny * ny + nz * nz); if (l > 0f) { nx /= l; ny /= l; nz /= l }
                                    bestT = t
                                    best = PickResult(hx, hy, hz, nx, ny, nz, node, i / 3, t)
                                }
                            }
                        }
                    }
                }
                i += 3
            }
        }
        return best
    }

    private fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, maxT: Float): Boolean {
        var tmin = 0f; var tmax = maxT
        for (axis in 0 until 3) {
            val o = if (axis == 0) ox else if (axis == 1) oy else oz
            val d = if (axis == 0) dx else if (axis == 1) dy else dz
            val lo = if (axis == 0) minX else if (axis == 1) minY else minZ
            val hi = if (axis == 0) maxX else if (axis == 1) maxY else maxZ
            if (abs(d) < 1e-20f) { if (o < lo || o > hi) return false }
            else {
                var t1 = (lo - o) / d; var t2 = (hi - o) / d
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                if (t1 > tmin) tmin = t1; if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
        }
        return true
    }
}
