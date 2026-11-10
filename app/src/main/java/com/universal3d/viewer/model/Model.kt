package com.universal3d.viewer.model

enum class ModelUnits(val label: String, val toMm: Double) {
    MILLIMETER("mm", 1.0), CENTIMETER("cm", 10.0), METER("m", 1000.0), INCH("in", 25.4), FOOT("ft", 304.8), UNKNOWN("units", 1.0);
}

/** A loaded 3D model: hierarchy of nodes, statistics and provenance. */
class Model(
    val roots: List<Node>,
    val formatName: String,
    val fileName: String,
    val fileSize: Long,
    /** Units declared by the file; UNKNOWN when the format carries none (STL, OBJ...). */
    val declaredUnits: ModelUnits = ModelUnits.UNKNOWN,
    /** Free-form notes produced by the importer (e.g. "textures ignored"). */
    val notes: List<String> = emptyList(),
) {
    val nodes: List<Node>
    val meshNodes: List<Node>
    val bounds: Bounds = Bounds()
    val triangleCount: Long
    val vertexCount: Long
    val edgeSegmentCount: Long

    init {
        val all = ArrayList<Node>()
        for (r in roots) r.walk { all.add(it) }
        all.forEachIndexed { i, n -> n.id = i }
        nodes = all
        meshNodes = all.filter { it.mesh != null }
        var t = 0L; var v = 0L; var e = 0L
        for (n in meshNodes) { val m = n.mesh!!; t += m.triangleCount; v += m.vertexCount; e += m.edgeSegmentCount; bounds.add(m.bounds) }
        triangleCount = t; vertexCount = v; edgeSegmentCount = e
    }

    val isEmpty: Boolean get() = meshNodes.isEmpty() || (triangleCount == 0L && edgeSegmentCount == 0L)

    companion object {
        /** Convenience for single-mesh formats. */
        fun single(mesh: Mesh, name: String, formatName: String, fileName: String, fileSize: Long, units: ModelUnits = ModelUnits.UNKNOWN, notes: List<String> = emptyList()): Model =
            Model(listOf(Node(name, mesh)), formatName, fileName, fileSize, units, notes)
    }
}
