package com.universal3d.viewer.render

import android.opengl.GLES30
import com.universal3d.viewer.model.LongIntMap
import com.universal3d.viewer.model.Mesh
import com.universal3d.viewer.model.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GPU buffers for one node's mesh. Created and destroyed on the GL thread. */
class GpuMesh(val node: Node, val mesh: Mesh) {
    private val ids = IntArray(6) // 0 pos, 1 normal, 2 tri ibo, 3 edge ibo, 4 wire ibo, 5 edge positions vbo
    val triangleIndexCount = mesh.indices.size
    val hasNormals = mesh.normals != null
    var edgeIndexCount = 0; private set
    var wireIndexCount = 0; private set
    private var wireBuilt = false
    val edgesUseOwnPositions = mesh.edgePositions != null
    var uploaded = false; private set

    fun upload() {
        GLES30.glGenBuffers(6, ids, 0)
        uploadFloats(ids[0], mesh.positions)
        mesh.normals?.let { uploadFloats(ids[1], it) }
        uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2], mesh.indices)
        val ei = mesh.edgeIndices
        if (ei != null && ei.isNotEmpty()) { uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3], ei); edgeIndexCount = ei.size }
        mesh.edgePositions?.let { uploadFloats(ids[5], it) }
        uploaded = true
    }

    /** Replaces the edge index buffer (e.g. after feature edges were computed in the background). */
    fun updateEdges() {
        val ei = mesh.edgeIndices
        if (ei != null && ei.isNotEmpty()) { uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3], ei); edgeIndexCount = ei.size } else edgeIndexCount = 0
    }

    /** Lazily builds the unique-edge index list used for wireframe mode. */
    fun ensureWire(maxTriangles: Int = 1_500_000) {
        if (wireBuilt) return
        wireBuilt = true
        val idx = mesh.indices
        val tri = idx.size / 3
        if (tri == 0 || tri > maxTriangles) { // fall back to feature edges for huge meshes
            return
        }
        try {
            val seen = LongIntMap(tri * 2) // ~1.5 unique edges per triangle
            val out = IntArray(tri * 6); var n = 0
            var i = 0
            while (i < idx.size) {
                for (e in 0 until 3) {
                    val a = idx[i + e]; val b = idx[i + (e + 1) % 3]
                    if (a == b) continue
                    val lo = minOf(a, b); val hi = maxOf(a, b)
                    val key = (lo.toLong() shl 32) or (hi.toLong() and 0xffffffffL)
                    if (seen.getOrPut(key, 1) < 0) { out[n++] = lo; out[n++] = hi }
                }
                i += 3
            }
            uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[4], out, n)
            wireIndexCount = n
        } catch (e: OutOfMemoryError) { wireIndexCount = 0 } // drawWire() falls back to drawEdges()
    }

    fun bindTriangles() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        if (hasNormals) { GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1]); GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0) }
        else GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
    }

    fun drawTriangles() { if (triangleIndexCount > 0) GLES30.glDrawElements(GLES30.GL_TRIANGLES, triangleIndexCount, GLES30.GL_UNSIGNED_INT, 0) }

    fun drawEdges() {
        if (edgeIndexCount == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, if (edgesUseOwnPositions) ids[5] else ids[0])
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3])
        GLES30.glDrawElements(GLES30.GL_LINES, edgeIndexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun drawWire() {
        ensureWire()
        if (wireIndexCount == 0) { drawEdges(); return }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[4])
        GLES30.glDrawElements(GLES30.GL_LINES, wireIndexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun delete() { if (uploaded) GLES30.glDeleteBuffers(6, ids, 0); uploaded = false }

    companion object {
        fun uploadFloats(id: Int, data: FloatArray) {
            val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(data); bb.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, bb, GLES30.GL_STATIC_DRAW)
        }
        fun uploadInts(target: Int, id: Int, data: IntArray, count: Int = data.size) {
            val bb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            bb.asIntBuffer().put(data, 0, count); bb.position(0)
            GLES30.glBindBuffer(target, id)
            GLES30.glBufferData(target, count * 4, bb, GLES30.GL_STATIC_DRAW)
        }
    }
}

/** Small dynamic line/point buffer for overlays (grid, axes, bbox, measurement). */
class LineBatch {
    private val ids = IntArray(1)
    private var capacityFloats = 0
    var vertexCount = 0; private set
    private var created = false

    fun set(data: FloatArray, count: Int = data.size) {
        if (!created) { GLES30.glGenBuffers(1, ids, 0); created = true }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        val bb = ByteBuffer.allocateDirect(maxOf(count, 3) * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data, 0, count); bb.position(0)
        if (count > capacityFloats) { GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 4, bb, GLES30.GL_DYNAMIC_DRAW); capacityFloats = count }
        else GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 4, bb)
        vertexCount = count / 3
    }

    fun bind() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun drawLines(first: Int = 0, count: Int = vertexCount - first) { if (count > 0) GLES30.glDrawArrays(GLES30.GL_LINES, first, count) }
    fun drawPoints(first: Int = 0, count: Int = vertexCount - first) { if (count > 0) GLES30.glDrawArrays(GLES30.GL_POINTS, first, count) }
    fun delete() { if (created) GLES30.glDeleteBuffers(1, ids, 0); created = false; capacityFloats = 0 }
}
