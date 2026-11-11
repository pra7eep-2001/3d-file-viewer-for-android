package com.universal3d.viewer.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ViewerApp : Application() {
    lateinit var loader: ModelLoader
        private set
    lateinit var recent: RecentFiles
        private set

    /** File where an uncaught exception from the previous run is stored (shown on the next launch). */
    val crashFile: File get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        loader = ModelLoader(this)
        recent = RecentFiles(this)
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
                crashFile.writeText(
                    "Universal 3D Viewer $version crash\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                    "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
                    "Thread: ${thread.name}\n\n$sw"
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }
}
