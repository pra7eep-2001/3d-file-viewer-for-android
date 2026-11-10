package com.universal3d.viewer.model

/**
 * Triangle mesh in model units. Geometry is never scaled on import.
 *
 * @param positions   xyz per vertex.
 * @param normals     optional xyz per vertex (same count as positions). When null the renderer flat-shades.
 * @param indices     triangle list (3 indices per triangle) into positions.
 * @param edgePositions optional dedicated polyline vertices (B-rep edges from CAD files); may be null.
 * @param edgeIndices  line list (2 indices per segment). Indexes [edgePositions] when that is non-null, otherwise [positions].
 */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
    var edgePositions: FloatArray? = null,
    var edgeIndices: IntArray? = null,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val edgeSegmentCount: Int get() = (edgeIndices?.size ?: 0) / 2

    val bounds: Bounds by lazy {
        val b = Bounds(); b.addAll(positions)
        val ep = edgePositions; if (ep != null && positions.isEmpty()) b.addAll(ep)
        b
    }

    /** Edge polyline vertices: dedicated array when present, else the mesh positions. */
    val edgeVertexSource: FloatArray get() = edgePositions ?: positions

    init {
        require(positions.size % 3 == 0) { "positions must be xyz triples" }
        require(normals == null || normals.size == positions.size) { "normals must match positions" }
        require(indices.size % 3 == 0) { "indices must be triangle triples" }
    }

    fun transformed(m: Mat4): Mesh {
        if (m.isIdentity) return this
        val p = m.transformPoints(positions)
        val n = normals?.let { m.transformNormals(it) }
        var idx = indices
        if (m.det3() < 0f) { // mirroring flips winding
            idx = indices.copyOf()
            var i = 0; while (i < idx.size) { val t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; i += 3 }
        }
        val ep = edgePositions?.let { m.transformPoints(it) }
        return Mesh(p, n, idx, ep, edgeIndices)
    }
}
