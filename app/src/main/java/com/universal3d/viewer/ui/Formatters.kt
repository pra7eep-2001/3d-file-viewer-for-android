package com.universal3d.viewer.ui

import com.universal3d.viewer.model.ModelUnits
import java.text.DecimalFormat
import java.util.Locale

object Formatters {
    private val intFmt = DecimalFormat("#,###")

    fun count(n: Long): String = intFmt.format(n)
    fun count(n: Int): String = intFmt.format(n)

    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    }

    /** Formats a length given in model units for display in [display] units, assuming the file is in [file] units. */
    fun length(valueModelUnits: Double, file: ModelUnits, display: ModelUnits, decimals: Int = -1): String {
        val fileUnit = if (file == ModelUnits.UNKNOWN) ModelUnits.MILLIMETER else file
        val v = valueModelUnits * fileUnit.toMm / display.toMm
        val d = if (decimals >= 0) decimals else when {
            kotlin.math.abs(v) >= 1000 -> 1
            kotlin.math.abs(v) >= 10 -> 2
            kotlin.math.abs(v) >= 0.1 -> 3
            else -> 4
        }
        return String.format(Locale.US, "%.${d}f %s", v, display.label)
    }

    fun number(v: Double, decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", v)

    fun duration(ms: Long): String = if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)
}
