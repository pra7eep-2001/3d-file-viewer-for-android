package com.universal3d.viewer.render

import com.universal3d.viewer.model.ModelUnits

enum class ViewMode(val label: String) { SOLID("Solid"), SOLID_EDGES("Solid + edges"), WIREFRAME("Wireframe"), XRAY("X-ray") }
enum class Projection(val label: String) { PERSPECTIVE("Perspective"), ORTHOGRAPHIC("Orthographic") }
enum class UpAxis(val label: String) { Z("Z up (CAD)"), Y("Y up (glTF/FBX)") }
enum class StandardView(val label: String) { FRONT("Front"), BACK("Back"), LEFT("Left"), RIGHT("Right"), TOP("Top"), BOTTOM("Bottom"), ISOMETRIC("Isometric") }
enum class ClipAxis(val label: String) { NONE("Off"), X("X"), Y("Y"), Z("Z") }

class BackgroundPreset(val name: String, val top: Int, val bottom: Int) {
    companion object {
        val ALL = listOf(
            BackgroundPreset("Charcoal", 0xFF2B2E33.toInt(), 0xFF151719.toInt()),
            BackgroundPreset("Black", 0xFF000000.toInt(), 0xFF000000.toInt()),
            BackgroundPreset("Graphite", 0xFF3A3D42.toInt(), 0xFF26282C.toInt()),
            BackgroundPreset("Slate blue", 0xFF2F3A4A.toInt(), 0xFF171C24.toInt()),
            BackgroundPreset("Studio grey", 0xFF8A8F96.toInt(), 0xFF4A4E55.toInt()),
            BackgroundPreset("White", 0xFFFFFFFF.toInt(), 0xFFE6E8EB.toInt()),
        )
    }
}

/** Mutable rendering preferences shared between the UI and the GL thread (fields are read each frame). */
class RenderSettings {
    @Volatile var viewMode = ViewMode.SOLID_EDGES
    @Volatile var projection = Projection.PERSPECTIVE
    @Volatile var upAxis = UpAxis.Z
    @Volatile var background = BackgroundPreset.ALL[0]
    @Volatile var showGrid = true
    @Volatile var showAxes = true
    @Volatile var showBoundingBox = false
    @Volatile var showTriad = true
    @Volatile var lightIntensity = 1.0f      // 0.2 .. 2.0
    @Volatile var ambient = 0.35f             // 0 .. 1
    @Volatile var specular = 0.35f            // 0 .. 1
    @Volatile var headlight = true            // light follows camera, else fixed in world
    @Volatile var clipAxis = ClipAxis.NONE
    @Volatile var clipOffset = 0.5f           // 0..1 across the bounding box along the clip axis
    @Volatile var clipFlip = false
    @Volatile var xrayAlpha = 0.32f
    @Volatile var defaultPartColor = 0xFFB4BCC8.toInt()
    @Volatile var edgeColor = 0xFF101216.toInt()
    @Volatile var selectedNodeId = -1
    @Volatile var displayUnits = ModelUnits.MILLIMETER
    /** Scale from model units to displayed units, decided by the UI from file units + user choice. */
    @Volatile var modelToDisplayScale = 1.0
}
