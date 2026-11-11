package com.universal3d.viewer.render

import android.opengl.Matrix
import com.universal3d.viewer.model.Bounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Turntable orbit camera. Yaw rotates around the up axis, pitch tilts towards it.
 * All angles in radians. Supports smooth animated transitions.
 */
class Camera {
    var upAxis = UpAxis.Z
    var projection = Projection.PERSPECTIVE
    val fovDeg = 40f

    var targetX = 0f; var targetY = 0f; var targetZ = 0f
    var distance = 10f
    var yaw = Math.toRadians(-35.0).toFloat()
    var pitch = Math.toRadians(25.0).toFloat()
    var sceneRadius = 5f

    // animation
    private var animating = false
    private var animStart = 0L
    private var animDuration = 320L
    private val from = FloatArray(6); private val to = FloatArray(6)

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    var viewportW = 1; var viewportH = 1

    /** Camera position in world space (updated by [update]). */
    val eye = FloatArray(3)
    val right = FloatArray(3)
    val upVec = FloatArray(3)
    val forward = FloatArray(3)

    val isAnimating: Boolean get() = animating

    fun setViewport(w: Int, h: Int) { viewportW = max(1, w); viewportH = max(1, h) }

    /**
     * Distance at which a sphere of radius [r] fits the screen in both directions. On a portrait phone the
     * horizontal field of view is much narrower than the vertical one, so the limiting half-angle is the smaller
     * of the two. The same distance also fits the orthographic view (half-width = distance * tan(fovY/2) * aspect >= r).
     */
    fun fitDistance(r: Float): Float {
        val halfY = Math.toRadians(fovDeg / 2.0)
        val aspect = viewportW.toDouble() / viewportH
        val halfX = Math.atan(Math.tan(halfY) * aspect)
        return (r / Math.sin(minOf(halfX, halfY))).toFloat() * 1.05f
    }

    /** Frames [bounds]; [sceneBounds] (default: the same) keeps near/far planes and zoom limits sized for the whole model. */
    fun frame(bounds: Bounds, animate: Boolean = false, sceneBounds: Bounds = bounds) {
        if (bounds.isEmpty) return
        val r = max(bounds.diagonal * 0.5f, 1e-4f)
        val fitDist = fitDistance(r)
        setTarget(bounds.centerX, bounds.centerY, bounds.centerZ, fitDist, yaw, pitch, animate)
        sceneRadius = max(sceneBounds.diagonal * 0.5f, 1e-4f)
    }

    fun reset(bounds: Bounds, animate: Boolean = true) {
        yawTo = Math.toRadians(-35.0).toFloat(); pitchTo = Math.toRadians(25.0).toFloat()
        val r = max(bounds.diagonal * 0.5f, 1e-4f)
        val fitDist = fitDistance(r)
        setTarget(bounds.centerX, bounds.centerY, bounds.centerZ, fitDist, yawTo, pitchTo, animate)
        sceneRadius = r
    }
    private var yawTo = 0f; private var pitchTo = 0f

    fun standardView(v: StandardView, bounds: Bounds, animate: Boolean = true) {
        val (y, p) = when (v) {
            StandardView.FRONT -> 0f to 0f
            StandardView.BACK -> Math.PI.toFloat() to 0f
            StandardView.RIGHT -> (Math.PI / 2).toFloat() to 0f
            StandardView.LEFT -> (-Math.PI / 2).toFloat() to 0f
            StandardView.TOP -> 0f to (Math.PI / 2 - 1e-4).toFloat()
            StandardView.BOTTOM -> 0f to (-Math.PI / 2 + 1e-4).toFloat()
            StandardView.ISOMETRIC -> Math.toRadians(-45.0).toFloat() to Math.toRadians(35.264).toFloat()
        }
        val r = max(bounds.diagonal * 0.5f, 1e-4f)
        val fitDist = fitDistance(r)
        // choose the shortest yaw path
        var ny = y; while (ny - yaw > Math.PI) ny -= (2 * Math.PI).toFloat(); while (ny - yaw < -Math.PI) ny += (2 * Math.PI).toFloat()
        setTarget(bounds.centerX, bounds.centerY, bounds.centerZ, fitDist, ny, p, animate)
        sceneRadius = r
    }

    private fun setTarget(tx: Float, ty: Float, tz: Float, dist: Float, y: Float, p: Float, animate: Boolean) {
        if (!animate) { targetX = tx; targetY = ty; targetZ = tz; distance = dist; yaw = y; pitch = p; animating = false; return }
        from[0] = targetX; from[1] = targetY; from[2] = targetZ; from[3] = distance; from[4] = yaw; from[5] = pitch
        to[0] = tx; to[1] = ty; to[2] = tz; to[3] = dist; to[4] = y; to[5] = p
        animStart = System.nanoTime(); animating = true
    }

    /** Advances animation; returns true when a redraw is still needed. */
    fun tick(): Boolean {
        if (!animating) return false
        val t = ((System.nanoTime() - animStart) / 1e6 / animDuration).toFloat().coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        targetX = from[0] + (to[0] - from[0]) * s; targetY = from[1] + (to[1] - from[1]) * s; targetZ = from[2] + (to[2] - from[2]) * s
        distance = from[3] + (to[3] - from[3]) * s; yaw = from[4] + (to[4] - from[4]) * s; pitch = from[5] + (to[5] - from[5]) * s
        if (t >= 1f) animating = false
        return animating
    }

    fun orbit(dxPx: Float, dyPx: Float) {
        animating = false
        val s = (2 * Math.PI / max(viewportW, viewportH)).toFloat() * 1.2f
        yaw -= dxPx * s
        pitch = (pitch + dyPx * s).coerceIn((-Math.PI / 2 + 0.001).toFloat(), (Math.PI / 2 - 0.001).toFloat())
    }

    fun pan(dxPx: Float, dyPx: Float) {
        animating = false
        val worldPerPx = visibleHeight() / viewportH
        targetX -= (right[0] * dxPx - upVec[0] * dyPx) * worldPerPx
        targetY -= (right[1] * dxPx - upVec[1] * dyPx) * worldPerPx
        targetZ -= (right[2] * dxPx - upVec[2] * dyPx) * worldPerPx
    }

    fun zoom(factor: Float) {
        animating = false
        distance = (distance / factor).coerceIn(sceneRadius * 0.002f, sceneRadius * 200f)
    }

    /** Zooms keeping the world point under the screen position fixed. */
    fun zoomAt(factor: Float, px: Float, py: Float) {
        // right/up are unchanged by a zoom, so no matrix update is needed here (update() runs on the GL thread)
        val nx = (px / viewportW) * 2 - 1; val ny = 1 - (py / viewportH) * 2
        val h0 = visibleHeight() / 2
        zoom(factor)
        val dh = h0 - visibleHeight() / 2; val dw = dh * viewportW / viewportH
        targetX += right[0] * nx * dw + upVec[0] * ny * dh
        targetY += right[1] * nx * dw + upVec[1] * ny * dh
        targetZ += right[2] * nx * dw + upVec[2] * ny * dh
    }

    /** World-space height of the view at the target distance. */
    fun visibleHeight(): Float = 2f * distance * tan(Math.toRadians(fovDeg / 2.0)).toFloat()

    /** Recomputes eye/view/projection matrices. */
    fun update() {
        val cp = cos(pitch); val sp = sin(pitch); val cy = cos(yaw); val sy = sin(yaw)
        // direction from target to eye
        val dx: Float; val dy: Float; val dz: Float
        val ux: Float; val uy: Float; val uz: Float
        if (upAxis == UpAxis.Z) { dx = sy * cp; dy = -cy * cp; dz = sp; ux = 0f; uy = 0f; uz = 1f }
        else { dx = sy * cp; dy = sp; dz = cy * cp; ux = 0f; uy = 1f; uz = 0f }
        eye[0] = targetX + dx * distance; eye[1] = targetY + dy * distance; eye[2] = targetZ + dz * distance
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], targetX, targetY, targetZ, ux, uy, uz)
        // basis vectors from the view matrix rows
        right[0] = view[0]; right[1] = view[4]; right[2] = view[8]
        upVec[0] = view[1]; upVec[1] = view[5]; upVec[2] = view[9]
        forward[0] = -view[2]; forward[1] = -view[6]; forward[2] = -view[10]
        val aspect = viewportW.toFloat() / viewportH
        val r = max(sceneRadius, 1e-4f)
        val near = max(distance - r * 2.5f, max(distance * 0.002f, r * 0.0005f))
        val far = distance + r * 4f
        if (projection == Projection.PERSPECTIVE) Matrix.perspectiveM(proj, 0, fovDeg, aspect, near, far)
        else { val h = visibleHeight() / 2; Matrix.orthoM(proj, 0, -h * aspect, h * aspect, -h, h, near, far) }
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
    }

    /** Builds a world-space pick ray for a screen pixel; returns origin(3)+direction(3). */
    fun pickRay(px: Float, py: Float): FloatArray {
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, viewProj, 0)) return floatArrayOf(eye[0], eye[1], eye[2], forward[0], forward[1], forward[2])
        val nx = (px / viewportW) * 2 - 1; val ny = 1 - (py / viewportH) * 2
        val a = FloatArray(4); val b = FloatArray(4)
        Matrix.multiplyMV(a, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        Matrix.multiplyMV(b, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        for (i in 0 until 3) { a[i] /= a[3]; b[i] /= b[3] }
        var dx = b[0] - a[0]; var dy = b[1] - a[1]; var dz = b[2] - a[2]
        val l = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat(); dx /= l; dy /= l; dz /= l
        return floatArrayOf(a[0], a[1], a[2], dx, dy, dz)
    }

    /** Projects a world point to screen pixels; returns null when behind the camera. */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, viewProj, 0, floatArrayOf(x, y, z, 1f), 0)
        if (o[3] <= 1e-6f) return null
        return floatArrayOf((o[0] / o[3] * 0.5f + 0.5f) * viewportW, (1 - (o[1] / o[3] * 0.5f + 0.5f)) * viewportH)
    }
}
