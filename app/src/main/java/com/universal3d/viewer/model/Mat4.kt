package com.universal3d.viewer.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Column-major 4x4 float matrix (OpenGL convention), pure Kotlin so importers and tests can use it. */
class Mat4(val m: FloatArray = FloatArray(16)) {
    companion object {
        fun identity(): Mat4 { val r = Mat4(); r.m[0] = 1f; r.m[5] = 1f; r.m[10] = 1f; r.m[15] = 1f; return r }
        fun translation(x: Float, y: Float, z: Float): Mat4 { val r = identity(); r.m[12] = x; r.m[13] = y; r.m[14] = z; return r }
        fun scale(x: Float, y: Float, z: Float): Mat4 { val r = Mat4(); r.m[0] = x; r.m[5] = y; r.m[10] = z; r.m[15] = 1f; return r }
        fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): Mat4 {
            val r = identity(); val a = r.m
            a[0] = 1 - 2 * (y * y + z * z); a[1] = 2 * (x * y + z * w); a[2] = 2 * (x * z - y * w)
            a[4] = 2 * (x * y - z * w); a[5] = 1 - 2 * (x * x + z * z); a[6] = 2 * (y * z + x * w)
            a[8] = 2 * (x * z + y * w); a[9] = 2 * (y * z - x * w); a[10] = 1 - 2 * (x * x + y * y)
            return r
        }
        fun rotationAxis(angleRad: Float, ax: Float, ay: Float, az: Float): Mat4 {
            val len = sqrt(ax * ax + ay * ay + az * az); if (len < 1e-12f) return identity()
            val x = ax / len; val y = ay / len; val z = az / len
            val c = cos(angleRad); val s = sin(angleRad); val t = 1 - c
            val r = identity(); val a = r.m
            a[0] = t * x * x + c; a[1] = t * x * y + s * z; a[2] = t * x * z - s * y
            a[4] = t * x * y - s * z; a[5] = t * y * y + c; a[6] = t * y * z + s * x
            a[8] = t * x * z + s * y; a[9] = t * y * z - s * x; a[10] = t * z * z + c
            return r
        }
        /** Column-major 16 floats as stored in glTF. */
        fun fromColumnMajor(v: FloatArray) = Mat4(v.copyOf(16))
        /** Row-major 16 floats (COLLADA / X3D / FBX text matrices). */
        fun fromRowMajor(v: FloatArray): Mat4 { val r = Mat4(); for (c in 0 until 4) for (rw in 0 until 4) r.m[c * 4 + rw] = v[rw * 4 + c]; return r }
    }

    /** this * other */
    fun times(o: Mat4): Mat4 {
        val r = Mat4(); val a = m; val b = o.m; val out = r.m
        for (c in 0 until 4) for (rw in 0 until 4) {
            out[c * 4 + rw] = a[rw] * b[c * 4] + a[4 + rw] * b[c * 4 + 1] + a[8 + rw] * b[c * 4 + 2] + a[12 + rw] * b[c * 4 + 3]
        }
        return r
    }

    fun transformPoint(x: Float, y: Float, z: Float, out: FloatArray, off: Int = 0) {
        val a = m
        val w = a[3] * x + a[7] * y + a[11] * z + a[15]
        val iw = if (w != 0f && w != 1f) 1f / w else 1f
        out[off] = (a[0] * x + a[4] * y + a[8] * z + a[12]) * iw
        out[off + 1] = (a[1] * x + a[5] * y + a[9] * z + a[13]) * iw
        out[off + 2] = (a[2] * x + a[6] * y + a[10] * z + a[14]) * iw
    }

    /** Transform a direction (ignores translation; not re-normalised). */
    fun transformDir(x: Float, y: Float, z: Float, out: FloatArray, off: Int = 0) {
        val a = m
        out[off] = a[0] * x + a[4] * y + a[8] * z
        out[off + 1] = a[1] * x + a[5] * y + a[9] * z
        out[off + 2] = a[2] * x + a[6] * y + a[10] * z
    }

    fun transformPoints(src: FloatArray, count: Int = src.size / 3): FloatArray {
        val dst = FloatArray(count * 3)
        for (i in 0 until count) transformPoint(src[i * 3], src[i * 3 + 1], src[i * 3 + 2], dst, i * 3)
        return dst
    }

    /** Transform normals with the inverse-transpose and renormalise. */
    fun transformNormals(src: FloatArray, count: Int = src.size / 3): FloatArray {
        val it = (inverse() ?: identity()).transposed()
        val dst = FloatArray(count * 3)
        for (i in 0 until count) {
            it.transformDir(src[i * 3], src[i * 3 + 1], src[i * 3 + 2], dst, i * 3)
            val l = sqrt(dst[i * 3] * dst[i * 3] + dst[i * 3 + 1] * dst[i * 3 + 1] + dst[i * 3 + 2] * dst[i * 3 + 2])
            if (l > 1e-20f) { dst[i * 3] /= l; dst[i * 3 + 1] /= l; dst[i * 3 + 2] /= l }
        }
        return dst
    }

    fun transposed(): Mat4 { val r = Mat4(); for (c in 0 until 4) for (rw in 0 until 4) r.m[c * 4 + rw] = m[rw * 4 + c]; return r }

    val isIdentity: Boolean get() {
        for (i in 0 until 16) { val e = if (i % 5 == 0) 1f else 0f; if (m[i] != e) return false }
        return true
    }

    /** Determinant of the upper-left 3x3 (negative => mirroring). */
    fun det3(): Float = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun inverse(): Mat4? {
        val a = m; val inv = FloatArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        var det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (det == 0f) return null
        det = 1f / det
        for (i in 0 until 16) inv[i] *= det
        return Mat4(inv)
    }

    fun copy() = Mat4(m.copyOf())
}
