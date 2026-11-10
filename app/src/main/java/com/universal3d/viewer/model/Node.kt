package com.universal3d.viewer.model

/**
 * A node in the model hierarchy (assembly, sub-assembly, part or mesh group).
 * Mesh coordinates are already in model space (transforms are baked on import).
 */
class Node(
    val name: String,
    val mesh: Mesh? = null,
    /** ARGB colour; 0 means "no colour assigned" (viewer default is used). */
    val color: Int = 0,
) {
    var parent: Node? = null
        private set
    val children: MutableList<Node> = ArrayList()
    var visible: Boolean = true
    /** Assigned when the model is finalised; stable index used by the renderer and UI. */
    var id: Int = -1
        internal set
    val depth: Int get() { var d = 0; var p = parent; while (p != null) { d++; p = p.parent }; return d }

    fun add(child: Node): Node { child.parent = this; children.add(child); return child }

    /** Effective visibility: hidden if any ancestor is hidden. */
    val effectivelyVisible: Boolean get() { var n: Node? = this; while (n != null) { if (!n.visible) return false; n = n.parent }; return true }

    fun walk(action: (Node) -> Unit) { action(this); for (c in children) c.walk(action) }

    val hasGeometryInSubtree: Boolean get() { var r = false; walk { if (it.mesh != null) r = true }; return r }
}
