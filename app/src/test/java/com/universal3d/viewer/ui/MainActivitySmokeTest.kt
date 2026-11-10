package com.universal3d.viewer.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import com.universal3d.viewer.R
import com.universal3d.viewer.render.ClipAxis
import com.universal3d.viewer.render.Projection
import com.universal3d.viewer.render.ViewMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import java.io.File

/**
 * UI smoke test run with Robolectric against the real framework (layout inflation, resources, clicks).
 * Rendering is not exercised (no EGL); runs on Robolectric 3.8 / API 27 because newer Robolectric needs androidx.test. See BUILDING.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = "../app/src/main/AndroidManifest.xml", resourceDir = "res", assetDir = "assets", packageName = "com.universal3d.viewer", application = com.universal3d.viewer.app.ViewerApp::class)
class MainActivitySmokeTest {

    @Test fun activityStartsWithEmptyState() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.emptyState).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.sidePanel).visibility)
        assertNotNull(a.glView)
        // mode buttons reflect the default
        assertTrue(a.findViewById<Button>(R.id.modeEdges).isSelected)
    }

    @Test fun toolbarButtonsChangeSettings() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<View>(R.id.modeWire).performClick(); assertEquals(ViewMode.WIREFRAME, a.glView.settings.viewMode)
        a.findViewById<View>(R.id.modeXray).performClick(); assertEquals(ViewMode.XRAY, a.glView.settings.viewMode)
        a.findViewById<View>(R.id.modeSolid).performClick(); assertEquals(ViewMode.SOLID, a.glView.settings.viewMode)
        val grid = a.glView.settings.showGrid
        a.findViewById<View>(R.id.toggleGrid).performClick(); assertEquals(!grid, a.glView.settings.showGrid)
        a.findViewById<View>(R.id.btnProjection).performClick(); assertEquals(Projection.ORTHOGRAPHIC, a.glView.settings.projection)
        a.findViewById<View>(R.id.btnSection).performClick(); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sectionHud).visibility); assertEquals(ClipAxis.Z, a.glView.settings.clipAxis)
        a.findViewById<View>(R.id.clipX).performClick(); assertEquals(ClipAxis.X, a.glView.settings.clipAxis)
        a.findViewById<View>(R.id.btnSectionClose).performClick(); assertEquals(ClipAxis.NONE, a.glView.settings.clipAxis)
        a.findViewById<View>(R.id.btnMeasure).performClick(); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.measureHud).visibility)
        a.findViewById<View>(R.id.btnMeasureClose).performClick(); assertEquals(View.GONE, a.findViewById<View>(R.id.measureHud).visibility)
        a.findViewById<View>(R.id.btnPanel).performClick(); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sidePanel).visibility)
        a.findViewById<View>(R.id.tabParts).performClick(); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.pageParts).visibility)
        a.findViewById<View>(R.id.tabDisplay).performClick(); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.pageDisplay).visibility)
        a.findViewById<View>(R.id.btnPanelClose).performClick(); assertEquals(View.GONE, a.findViewById<View>(R.id.sidePanel).visibility)
        a.findViewById<View>(R.id.btnFormats).performClick() // formats dialog builds without crashing
        a.findViewById<View>(R.id.btnMenu).performClick()
    }

    private fun collectText(v: android.view.View): String = when (v) {
        is android.widget.TextView -> v.text.toString()
        is android.view.ViewGroup -> (0 until v.childCount).joinToString(" | ") { collectText(v.getChildAt(it)) }
        else -> ""
    }

    @Test fun loadsStlThroughViewIntent() {
        val stl = File.createTempFile("cube", ".stl")
        stl.writeText("""
            solid t
            facet normal 0 0 1
            outer loop
            vertex 0 0 0
            vertex 10 0 0
            vertex 10 10 0
            endloop
            endfacet
            facet normal 0 0 1
            outer loop
            vertex 0 0 0
            vertex 10 10 0
            vertex 0 10 0
            endloop
            endfacet
            endsolid t
        """.trimIndent())
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(stl))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val a = controller.get()
        // the loader works on a background thread; pump the main looper until the model arrives
        var waited = 0
        while (a.currentModel() == null && waited < 20000) { Thread.sleep(50); ShadowLooper.idleMainLooper(); waited += 50 }
        val m = a.currentModel()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val dialogText = dialog?.let { d -> val sd = Shadows.shadowOf(d); "${sd.title} / ${sd.message} / ${(d.findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)?.let { vg -> collectText(vg) }}" }
        assertNotNull("model should load via ACTION_VIEW; dialog=$dialogText", m)
        assertEquals(2L, m!!.triangleCount)
        assertEquals(View.GONE, a.findViewById<View>(R.id.emptyState).visibility)
        assertTrue(a.findViewById<android.widget.TextView>(R.id.subtitle).text.contains("STL"))
        // info + parts panel populate from the model
        a.togglePanel(true)
        a.findViewById<View>(R.id.tabParts).performClick()
        assertEquals(1, a.findViewById<android.widget.ListView>(R.id.partsList).adapter.count)
        // measurement maths through the public path (units formatting)
        assertEquals("10.00 mm", Formatters.length(10.0, com.universal3d.viewer.model.ModelUnits.MILLIMETER, com.universal3d.viewer.model.ModelUnits.MILLIMETER))
        assertEquals("1.000 cm", Formatters.length(10.0, com.universal3d.viewer.model.ModelUnits.MILLIMETER, com.universal3d.viewer.model.ModelUnits.CENTIMETER))
        // recent files recorded
        assertEquals(1, (a.application as com.universal3d.viewer.app.ViewerApp).recent.list().size)
        // rotation: the activity handles configuration changes itself
        controller.configurationChange(android.content.res.Configuration(a.resources.configuration).apply { orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE })
        assertNotNull(Shadows.shadowOf(a).contentView)
    }
}
