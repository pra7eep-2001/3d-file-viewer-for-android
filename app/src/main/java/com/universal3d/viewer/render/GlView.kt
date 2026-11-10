package com.universal3d.viewer.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * GLSurfaceView hosting [SceneRenderer] with CAD-style touch navigation:
 * one finger orbits, two fingers pan + pinch-zoom, double tap fits the model, single tap is reported
 * to [onTap] (used for part selection and measurement picking).
 */
class GlView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    val settings = RenderSettings()
    val renderer: SceneRenderer
    var onTap: ((Float, Float) -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onCameraChanged: (() -> Unit)? = null

    private var lastX = 0f; private var lastY = 0f
    private var lastDist = 0f; private var lastCx = 0f; private var lastCy = 0f
    private var mode = 0 // 0 none, 1 orbit, 2 two-finger

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap?.invoke(); return true }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser())
        preserveEGLContextOnPause = true
        renderer = SceneRenderer(settings) { requestRender() }
        renderer.density = resources.displayMetrics.density
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        val cam = renderer.camera
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; mode = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) { mode = 2; updateTwoFinger(e, true) }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1 && e.pointerCount == 1) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (abs(dx) + abs(dy) > 0f) { cam.orbit(dx, dy); lastX = e.x; lastY = e.y; changed() }
                } else if (mode == 2 && e.pointerCount >= 2) updateTwoFinger(e, false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 >= 2) updateTwoFinger(e, true, e.actionIndex)
                else if (e.pointerCount - 1 == 1) { // back to one finger: restart orbit from the remaining pointer
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx); mode = 1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun updateTwoFinger(e: MotionEvent, reset: Boolean, skipIndex: Int = -1) {
        var i0 = 0; var i1 = 1
        if (skipIndex == 0) { i0 = 1; i1 = 2 } else if (skipIndex == 1) { i0 = 0; i1 = 2 }
        if (i1 >= e.pointerCount) return
        val x0 = e.getX(i0); val y0 = e.getY(i0); val x1 = e.getX(i1); val y1 = e.getY(i1)
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val dist = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        if (!reset) {
            val cam = renderer.camera
            cam.pan(cx - lastCx, cy - lastCy)
            if (lastDist > 10f && dist > 10f) cam.zoomAt(dist / lastDist, cx, cy)
            changed()
        }
        lastCx = cx; lastCy = cy; lastDist = dist
    }

    private fun changed() { requestRender(); onCameraChanged?.invoke() }

    /** Chooses an RGBA8 / depth24 config with 4x MSAA when available. */
    private class MsaaConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attribsMsaa = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE)
            val attribsPlain = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE)
            val attribsDepth16 = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE)
            for (attribs in arrayOf(attribsMsaa, attribsPlain, attribsDepth16)) {
                val num = IntArray(1)
                if (!egl.eglChooseConfig(display, attribs, null, 0, num) || num[0] <= 0) continue
                val configs = arrayOfNulls<EGLConfig>(num[0])
                egl.eglChooseConfig(display, attribs, configs, num[0], num)
                configs[0]?.let { return it }
            }
            throw RuntimeException("No suitable OpenGL ES 3.0 configuration found")
        }
    }
}
