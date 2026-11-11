package com.universal3d.viewer.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.universal3d.viewer.model.Bounds
import com.universal3d.viewer.model.Model
import com.universal3d.viewer.model.Node
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * OpenGL ES 3.0 scene renderer: background, grid, model (solid / edges / wireframe / x-ray),
 * clipping plane, bounding box, axes, measurement overlay and the navigation triad.
 */
class SceneRenderer(val settings: RenderSettings, private val requestRender: () -> Unit) : GLSurfaceView.Renderer {
    val camera = Camera()

    @Volatile var model: Model? = null
        private set

    /** Called (on the GL thread) when GL initialisation or drawing fails; the host shows it to the user. */
    @Volatile var onRenderError: ((String) -> Unit)? = null
    /** Non-null when the GL programs could not be created; frames then only clear to the background colour. */
    @Volatile var renderError: String? = null
        private set
    private var drawErrorReported = false
    private var pendingModel: Model? = null
    private var modelDirty = false
    private val gpu = ArrayList<GpuMesh>()
    private val gpuByNode = HashMap<Int, GpuMesh>()

    private var meshProg: GlProgram? = null
    private var lineProg: GlProgram? = null
    private var bgProg: GlProgram? = null
    private val bgQuad = LineBatch()
    private val grid = LineBatch()
    private val overlay = LineBatch()
    private val triad = LineBatch()
    private var gridKey = ""
    private var gridMinorCount = 0

    /** Measurement points in world space (0, 1 or 2). */
    @Volatile var measurePoints: FloatArray = FloatArray(0)
    /** Nodes whose feature edges were recomputed and need re-upload. */
    private val edgeUpdates = ArrayList<Node>()
    private var contextGeneration = 0

    val modelBounds: Bounds get() = model?.bounds ?: Bounds()

    // ---- public API (any thread; GL work is deferred to onDrawFrame) ----
    fun setModel(m: Model?) { synchronized(this) { pendingModel = m; modelDirty = true }; requestRender() }
    fun edgesUpdated(node: Node) { synchronized(edgeUpdates) { edgeUpdates.add(node) }; requestRender() }

    fun clipPlane(): FloatArray? {
        val m = model ?: return null
        val axis = settings.clipAxis
        if (axis == ClipAxis.NONE) return null
        val b = m.bounds
        val t = settings.clipOffset.coerceIn(0f, 1f)
        val pad = b.diagonal * 0.0005f
        val (lo, hi) = when (axis) { ClipAxis.X -> b.minX to b.maxX; ClipAxis.Y -> b.minY to b.maxY; else -> b.minZ to b.maxZ }
        val off = lo - pad + (hi - lo + 2 * pad) * t
        val ax = if (axis == ClipAxis.X) 1f else 0f; val ay = if (axis == ClipAxis.Y) 1f else 0f; val az = if (axis == ClipAxis.Z) 1f else 0f
        // keep dot(p, n) + w >= 0
        return if (!settings.clipFlip) floatArrayOf(-ax, -ay, -az, off) else floatArrayOf(ax, ay, az, -off)
    }

    // ---- GLSurfaceView.Renderer ----
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        contextGeneration++
        meshProg = null; lineProg = null; bgProg = null
        try {
            meshProg = GlProgram(Shaders.MESH_VS, Shaders.MESH_FS)
            lineProg = GlProgram(Shaders.LINE_VS, Shaders.LINE_FS)
            bgProg = GlProgram(Shaders.BG_VS, Shaders.BG_FS)
            renderError = null
        } catch (t: Throwable) {
            // Never let a driver/shader problem kill the process: report it and keep the UI usable.
            meshProg = null; lineProg = null; bgProg = null
            val info = "GL_RENDERER=${GLES30.glGetString(GLES30.GL_RENDERER)} GL_VERSION=${GLES30.glGetString(GLES30.GL_VERSION)}"
            renderError = "3D rendering could not be initialised on this device.\n\n${t.message}\n\n$info"
            onRenderError?.invoke(renderError!!)
        }
        bgQuad.delete(); grid.delete(); overlay.delete(); triad.delete()
        bgQuad.set(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
        gridKey = ""
        // GPU buffers are lost with the context: re-upload the current model
        synchronized(this) { if (pendingModel == null) pendingModel = model; modelDirty = true }
        gpu.clear(); gpuByNode.clear()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        camera.setViewport(width, height)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (meshProg == null || lineProg == null || bgProg == null) {
            val c = settings.background.bottom
            GLES30.glClearColor(((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        try {
            drawFrameUnchecked()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) { synchronized(this) { pendingModel = null; modelDirty = true } }
            if (!drawErrorReported) { drawErrorReported = true; onRenderError?.invoke("Rendering failed: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun drawFrameUnchecked() {
        applyPendingModel()
        applyEdgeUpdates()
        val animating = camera.tick()
        camera.upAxis = settings.upAxis
        camera.projection = settings.projection
        camera.update()

        val w = camera.viewportW; val h = camera.viewportH
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawBackground()

        val m = model
        if (m != null && !m.isEmpty) {
            val clip = clipPlane()
            if (settings.showGrid) drawGrid(m.bounds)
            drawModel(m, clip)
            if (settings.showBoundingBox) drawBoundingBox(m.bounds)
            if (settings.showAxes) drawAxes(m.bounds)
            drawMeasurement()
        }
        if (settings.showTriad) drawTriad()
        if (animating) requestRender()
    }

    private fun applyPendingModel() {
        var m: Model?; var dirty: Boolean
        synchronized(this) { m = pendingModel; dirty = modelDirty; modelDirty = false; pendingModel = null }
        if (!dirty) return
        for (g in gpu) g.delete()
        gpu.clear(); gpuByNode.clear()
        model = m
        val mm = m ?: return
        for (node in mm.meshNodes) {
            val mesh = node.mesh ?: continue
            val g = GpuMesh(node, mesh); g.upload(); gpu.add(g); gpuByNode[node.id] = g
        }
        gridKey = ""
    }

    private fun applyEdgeUpdates() {
        val list: List<Node>
        synchronized(edgeUpdates) { if (edgeUpdates.isEmpty()) return; list = ArrayList(edgeUpdates); edgeUpdates.clear() }
        for (n in list) gpuByNode[n.id]?.updateEdges()
    }

    // ---- drawing helpers ----
    private fun drawBackground() {
        val p = bgProg ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        p.use()
        p.setColor("uTop", settings.background.top, 1f); p.setColor("uBottom", settings.background.bottom, 1f)
        bgQuad.bind() // xyz layout; the shader only reads xy
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun beginLines(clip: FloatArray?): GlProgram {
        val p = lineProg!!
        p.use()
        p.setMat4("uViewProj", camera.viewProj)
        if (clip != null) { p.set4("uClip", clip[0], clip[1], clip[2], clip[3]); p.set("uClipEnabled", 1) } else p.set("uClipEnabled", 0)
        p.set("uPoint", 0); p.set("uPointSize", 1f)
        return p
    }

    private fun drawModel(m: Model, clip: FloatArray?) {
        val mp = meshProg ?: return
        val mode = settings.viewMode
        val lightView = lightDirView()
        val bgLum = luminance(settings.background.top)
        if (mode != ViewMode.WIREFRAME) {
            mp.use()
            mp.setMat4("uViewProj", camera.viewProj); mp.setMat4("uView", camera.view)
            if (clip != null) { mp.set4("uClip", clip[0], clip[1], clip[2], clip[3]); mp.set("uClipEnabled", 1) } else mp.set("uClipEnabled", 0)
            mp.set3("uLightDirView", lightView[0], lightView[1], lightView[2])
            mp.set("uAmbient", settings.ambient); mp.set("uIntensity", settings.lightIntensity); mp.set("uSpecular", settings.specular)
            mp.set("uFlat", 0)
            val xray = mode == ViewMode.XRAY
            if (xray) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA); GLES30.glDepthMask(false) }
            if (mode == ViewMode.SOLID_EDGES) { GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(1f, 2f) }
            for (g in gpu) {
                if (!g.node.effectivelyVisible || g.triangleIndexCount == 0) continue
                val col = partColor(g.node)
                mp.setColor("uColor", col, if (xray) settings.xrayAlpha else 1f)
                mp.set("uHasNormals", if (g.hasNormals) 1 else 0)
                g.bindTriangles(); g.drawTriangles()
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            if (xray) { GLES30.glDepthMask(true) }
            run {
                // Solid + edges and X-ray draw every edge; plain Solid still draws parts that consist of edges only
                // (DXF/VRML line sets, STEP wire bodies) so they do not vanish.
                val lp = beginLines(clip)
                if (xray) { GLES30.glEnable(GLES30.GL_BLEND) }
                val edgeCol = if (xray) 0xFFFFFFFF.toInt() else if (bgLum > 0.6f) 0xFF202428.toInt() else settings.edgeColor
                lp.setColor("uColor", edgeCol, if (xray) 0.55f else 1f)
                GLES30.glLineWidth(1.5f)
                for (g in gpu) {
                    if (!g.node.effectivelyVisible) continue
                    if (mode == ViewMode.SOLID && g.triangleIndexCount > 0) continue
                    g.drawEdges()
                }
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            val lp = beginLines(clip)
            GLES30.glLineWidth(1f)
            for (g in gpu) {
                if (!g.node.effectivelyVisible) continue
                val col = partColor(g.node)
                lp.setColor("uColor", if (bgLum > 0.6f) darken(col) else lighten(col), 1f)
                g.drawWire()
            }
        }
    }

    private fun partColor(node: Node): Int {
        var c = if (node.color != 0) node.color else settings.defaultPartColor
        if (settings.selectedNodeId >= 0) {
            var sel = false; var n: Node? = node
            while (n != null) { if (n.id == settings.selectedNodeId) { sel = true; break }; n = n.parent }
            if (sel) c = blend(c, 0xFFFF8A2B.toInt(), 0.65f)
        }
        return c
    }

    private fun lightDirView(): FloatArray {
        // key light from upper-left-front of the camera; when not a headlight, fix it in world space
        if (settings.headlight) return floatArrayOf(-0.35f, 0.55f, 0.76f)
        val worldDir = if (settings.upAxis == UpAxis.Z) floatArrayOf(-0.4f, -0.5f, 0.77f) else floatArrayOf(-0.4f, 0.77f, 0.5f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, camera.view, 0, floatArrayOf(worldDir[0], worldDir[1], worldDir[2], 0f), 0)
        return out
    }

    private fun drawGrid(b: Bounds) {
        val up = settings.upAxis
        val key = "${b.minX},${b.minY},${b.minZ},${b.maxX},${b.maxY},${b.maxZ},$up"
        if (key != gridKey) { buildGrid(b); gridKey = key }
        val lp = beginLines(null)
        val bgLum = luminance(settings.background.bottom)
        val minor = if (bgLum > 0.6f) 0x33000000 else 0x2EFFFFFF
        val major = if (bgLum > 0.6f) 0x66000000 else 0x55FFFFFF
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1f)
        grid.bind()
        lp.setColor("uColor", minor); grid.drawLines(0, gridMinorCount)
        lp.setColor("uColor", major); grid.drawLines(gridMinorCount)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun buildGrid(b: Bounds) {
        val size = maxOf(b.maxSize, 1e-3f)
        val step = niceStep(size / 10f)
        val extent = step * ceilDiv(size * 1.6f, step)
        val cx = b.centerX; val cy = b.centerY; val cz = b.centerZ
        val minor = ArrayList<Float>(); val major = ArrayList<Float>()
        val n = (extent / step).toInt()
        val up = settings.upAxis
        val level = if (up == UpAxis.Z) b.minZ - size * 0.002f else b.minY - size * 0.002f
        // snap the grid origin to multiples of the step so lines are on round coordinates
        val ox = floor(cx / step) * step
        val oyz = if (up == UpAxis.Z) floor(cy / step) * step else floor(cz / step) * step
        for (i in -n..n) {
            val list = if (i % 5 == 0) major else minor
            val a = ox + i * step; val c = oyz + i * step
            if (up == UpAxis.Z) {
                list.addAll(listOf(a, oyz - extent, level, a, oyz + extent, level))
                list.addAll(listOf(ox - extent, c, level, ox + extent, c, level))
            } else {
                list.addAll(listOf(a, level, oyz - extent, a, level, oyz + extent))
                list.addAll(listOf(ox - extent, level, c, ox + extent, level, c))
            }
        }
        val data = FloatArray(minor.size + major.size)
        for (i in minor.indices) data[i] = minor[i]; for (i in major.indices) data[minor.size + i] = major[i]
        grid.set(data)
        gridMinorCount = minor.size / 3
    }

    private fun drawBoundingBox(b: Bounds) {
        val x0 = b.minX; val y0 = b.minY; val z0 = b.minZ; val x1 = b.maxX; val y1 = b.maxY; val z1 = b.maxZ
        val d = floatArrayOf(
            x0,y0,z0, x1,y0,z0, x1,y0,z0, x1,y1,z0, x1,y1,z0, x0,y1,z0, x0,y1,z0, x0,y0,z0,
            x0,y0,z1, x1,y0,z1, x1,y0,z1, x1,y1,z1, x1,y1,z1, x0,y1,z1, x0,y1,z1, x0,y0,z1,
            x0,y0,z0, x0,y0,z1, x1,y0,z0, x1,y0,z1, x1,y1,z0, x1,y1,z1, x0,y1,z0, x0,y1,z1)
        val lp = beginLines(null)
        overlay.set(d); overlay.bind()
        GLES30.glLineWidth(1.5f)
        lp.setColor("uColor", 0xFFFFC14D.toInt(), 1f)
        overlay.drawLines()
    }

    private fun drawAxes(b: Bounds) {
        val l = maxOf(b.maxSize, 1e-3f) * 0.35f
        val d = floatArrayOf(0f,0f,0f, l,0f,0f, 0f,0f,0f, 0f,l,0f, 0f,0f,0f, 0f,0f,l)
        val lp = beginLines(null)
        overlay.set(d); overlay.bind()
        GLES30.glLineWidth(2.5f)
        lp.setColor("uColor", 0xFFE5484D.toInt(), 1f); overlay.drawLines(0, 2)
        lp.setColor("uColor", 0xFF46A758.toInt(), 1f); overlay.drawLines(2, 2)
        lp.setColor("uColor", 0xFF3E8EF7.toInt(), 1f); overlay.drawLines(4, 2)
    }

    private fun drawMeasurement() {
        val pts = measurePoints
        if (pts.isEmpty()) return
        val lp = beginLines(null)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        overlay.set(pts); overlay.bind()
        if (pts.size >= 6) { GLES30.glLineWidth(3f); lp.setColor("uColor", 0xFFFFC14D.toInt(), 1f); overlay.drawLines(0, 2) }
        lp.set("uPoint", 1); lp.set("uPointSize", 26f * density)
        lp.setColor("uColor", 0xFFFF8A2B.toInt(), 1f)
        overlay.drawPoints()
        lp.set("uPoint", 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    @Volatile var density = 2.5f

    private fun drawTriad() {
        val lp = lineProg ?: return
        val size = (96 * density).toInt().coerceAtMost(minOf(camera.viewportW, camera.viewportH) / 3)
        val margin = (10 * density).toInt()
        GLES30.glViewport(margin, margin, size, size)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        // rotation-only view, orthographic
        val rot = camera.view.copyOf(); rot[12] = 0f; rot[13] = 0f; rot[14] = 0f
        val proj = FloatArray(16); Matrix.orthoM(proj, 0, -1.3f, 1.3f, -1.3f, 1.3f, -3f, 3f)
        val vp = FloatArray(16); Matrix.multiplyMM(vp, 0, proj, 0, rot, 0)
        lp.use(); lp.setMat4("uViewProj", vp); lp.set("uClipEnabled", 0); lp.set("uPoint", 0)
        triad.set(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,0f,0f, 0f,1f,0f, 0f,0f,0f, 0f,0f,1f, 1f,0f,0f, 0f,1f,0f, 0f,0f,1f))
        triad.bind()
        GLES30.glLineWidth(3f)
        lp.setColor("uColor", 0xFFE5484D.toInt(), 1f); triad.drawLines(0, 2)
        lp.setColor("uColor", 0xFF46A758.toInt(), 1f); triad.drawLines(2, 2)
        lp.setColor("uColor", 0xFF3E8EF7.toInt(), 1f); triad.drawLines(4, 2)
        lp.set("uPoint", 1); lp.set("uPointSize", 9f * density)
        lp.setColor("uColor", 0xFFE5484D.toInt(), 1f); triad.drawPoints(6, 1)
        lp.setColor("uColor", 0xFF46A758.toInt(), 1f); triad.drawPoints(7, 1)
        lp.setColor("uColor", 0xFF3E8EF7.toInt(), 1f); triad.drawPoints(8, 1)
        lp.set("uPoint", 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, camera.viewportW, camera.viewportH)
    }

    /** Screen positions of the triad axis tips (for the X/Y/Z labels drawn by the UI). */
    fun triadLabelPositions(): FloatArray {
        val size = (96 * density).coerceAtMost(minOf(camera.viewportW, camera.viewportH) / 3f)
        val margin = 10 * density
        val rot = camera.view
        val out = FloatArray(6)
        for (a in 0 until 3) {
            val x = rot[a * 4]; val y = rot[a * 4 + 1]
            // ortho range [-1.3, 1.3] maps to [0, size]; push the label slightly beyond the tip
            val sx = margin + (x * 1.15f / 1.3f * 0.5f + 0.5f) * size
            val sy = camera.viewportH - (margin + (y * 1.15f / 1.3f * 0.5f + 0.5f) * size)
            out[a * 2] = sx; out[a * 2 + 1] = sy
        }
        return out
    }

    companion object {
        fun niceStep(raw: Float): Float {
            if (raw <= 0f) return 1f
            val exp = floor(log10(raw.toDouble())).toInt()
            val base = 10.0.pow(exp).toFloat()
            val f = raw / base
            val nice = when { f < 1.5f -> 1f; f < 3.5f -> 2f; f < 7.5f -> 5f; else -> 10f }
            return nice * base
        }
        fun ceilDiv(a: Float, b: Float) = kotlin.math.ceil(a / b)
        fun luminance(argb: Int): Float = (0.299f * ((argb shr 16) and 0xff) + 0.587f * ((argb shr 8) and 0xff) + 0.114f * (argb and 0xff)) / 255f
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        fun lighten(c: Int) = blend(c, 0xFFFFFFFF.toInt(), 0.35f)
        fun darken(c: Int) = blend(c, 0xFF000000.toInt(), 0.45f)
    }
}
