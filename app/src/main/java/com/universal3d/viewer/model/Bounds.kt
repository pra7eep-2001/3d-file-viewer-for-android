package com.universal3d.viewer.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned bounding box. Empty until the first point is added. */
class Bounds {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

    val isEmpty: Boolean get() = minX > maxX
    val sizeX: Float get() = if (isEmpty) 0f else maxX - minX
    val sizeY: Float get() = if (isEmpty) 0f else maxY - minY
    val sizeZ: Float get() = if (isEmpty) 0f else maxZ - minZ
    val centerX: Float get() = if (isEmpty) 0f else (minX + maxX) * 0.5f
    val centerY: Float get() = if (isEmpty) 0f else (minY + maxY) * 0.5f
    val centerZ: Float get() = if (isEmpty) 0f else (minZ + maxZ) * 0.5f
    val diagonal: Float get() = if (isEmpty) 0f else sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ)
    val maxSize: Float get() = max(sizeX, max(sizeY, sizeZ))

    fun add(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    fun add(other: Bounds) {
        if (other.isEmpty) return
        add(other.minX, other.minY, other.minZ); add(other.maxX, other.maxY, other.maxZ)
    }

    fun addAll(positions: FloatArray, count: Int = positions.size / 3) {
        var i = 0
        while (i < count * 3) { add(positions[i], positions[i + 1], positions[i + 2]); i += 3 }
    }

    /** Bounds of this box after transforming its 8 corners. */
    fun transformed(m: Mat4): Bounds {
        val r = Bounds()
        if (isEmpty) return r
        val xs = floatArrayOf(minX, maxX); val ys = floatArrayOf(minY, maxY); val zs = floatArrayOf(minZ, maxZ)
        val out = FloatArray(3)
        for (x in xs) for (y in ys) for (z in zs) { m.transformPoint(x, y, z, out); r.add(out[0], out[1], out[2]) }
        return r
    }

    fun copy(): Bounds { val b = Bounds(); b.add(this); return b }
    override fun toString() = if (isEmpty) "Bounds(empty)" else "Bounds([$minX,$minY,$minZ]..[$maxX,$maxY,$maxZ])"
}
