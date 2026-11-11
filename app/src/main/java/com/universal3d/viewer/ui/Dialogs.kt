package com.universal3d.viewer.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.universal3d.viewer.R
import com.universal3d.viewer.io.FormatRegistry
import com.universal3d.viewer.io.SupportLevel

object Dialogs {
    fun dp(a: Activity, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.resources.displayMetrics).toInt()

    fun error(a: Activity, title: String, message: String, details: String?, onRetry: (() -> Unit)? = null) {
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 24f), dp(a, 12f), dp(a, 24f), 0) }
        content.addView(TextView(a).apply { text = message; setTextColor(a.getColor(R.color.text_primary)); textSize = 15f; setLineSpacing(0f, 1.15f) })
        val det = TextView(a).apply {
            text = details ?: ""; visibility = View.GONE; typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(a.getColor(R.color.text_secondary)); setPadding(0, dp(a, 12f), 0, 0); maxLines = 12; movementMethod = ScrollingMovementMethod()
        }
        content.addView(det)
        val b = AlertDialog.Builder(a).setTitle(title).setView(content).setPositiveButton(R.string.close, null)
        if (!details.isNullOrBlank()) b.setNeutralButton(R.string.details, null)
        if (onRetry != null) b.setNegativeButton(R.string.retry) { _, _ -> onRetry() }
        val d = b.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { det.visibility = if (det.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        d.show()
    }

    fun formats(a: Activity) {
        val scroll = ScrollView(a)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20f), dp(a, 8f), dp(a, 20f), dp(a, 8f)) }
        scroll.addView(list)
        val groups = listOf(SupportLevel.CONVERTER to "CAD (exact geometry via Open CASCADE)", SupportLevel.FULL to "Mesh formats", SupportLevel.PARTIAL to "Partially supported", SupportLevel.UNSUPPORTED to "Not supported")
        for ((level, heading) in groups) {
            val items = FormatRegistry.formats.filter { it.level == level }
            if (items.isEmpty()) continue
            list.addView(TextView(a).apply { text = heading; setTextColor(a.getColor(R.color.text_tertiary)); textSize = 11f; isAllCaps = true; letterSpacing = 0.08f; setPadding(0, dp(a, 14f), 0, dp(a, 6f)) })
            for (f in items) {
                val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(a, 6f), 0, dp(a, 6f)) }
                val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(TextView(a).apply { text = f.name; setTextColor(a.getColor(R.color.text_primary)); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                head.addView(TextView(a).apply {
                    text = f.extensions.joinToString(" ") { ".$it" }; setTextColor(a.getColor(R.color.accent_text)); textSize = 12f; setPadding(dp(a, 10f), 0, 0, 0)
                })
                row.addView(head)
                row.addView(TextView(a).apply { text = f.notes; setTextColor(a.getColor(R.color.text_secondary)); textSize = 12.5f; setLineSpacing(0f, 1.1f) })
                list.addView(row)
            }
        }
        AlertDialog.Builder(a).setTitle(R.string.supported_formats).setView(scroll).setPositiveButton(R.string.close, null).show()
    }

    fun about(a: Activity) {
        val msg = "Universal 3D Viewer 1.0.1\n\nOffline viewer for CAD and mesh files. STEP and IGES are tessellated on-device by Open CASCADE Technology 7.6.3 (LGPL 2.1 with exception), running as an isolated converter process. Everything else is parsed by built-in Kotlin importers.\n\nNavigation: one finger rotates, two fingers pan, pinch zooms, double-tap fits the model. Tap a part to select it; use Measure to pick two points.\n\nNo data leaves the device."
        AlertDialog.Builder(a).setTitle(R.string.about).setMessage(msg).setPositiveButton(R.string.close, null).show()
    }

    fun text(a: Activity, title: String, msg: String) { AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton(R.string.close, null).show() }
}
