package com.universal3d.viewer.cad

import android.content.Context
import android.os.Build
import android.util.Log
import com.universal3d.viewer.io.ImportException
import com.universal3d.viewer.io.ImportSource
import com.universal3d.viewer.io.ProgressSink
import com.universal3d.viewer.io.formats.U3dmImporter
import com.universal3d.viewer.model.Model
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled Open CASCADE converter (a static executable shipped as lib/arm64-v8a/libu3dconv.so)
 * as a sandboxed subprocess to tessellate STEP/IGES files into the U3DM mesh format, then imports that.
 * Running out-of-process isolates CAD-kernel crashes and memory spikes from the viewer.
 */
class CadConverter(private val context: Context) {
    enum class Quality(val arg: String, val label: String) { LOW("low", "Fast"), MEDIUM("medium", "Balanced"), HIGH("high", "Fine") }

    val executable: File? get() {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val f = File(dir, "libu3dconv.so")
        return if (f.exists()) f else null
    }

    /** The real converter ships for arm64-v8a and x86_64; 32-bit ABIs only get a stub that reports the limitation. */
    val isAvailable: Boolean get() = executable != null && android.os.Process.is64Bit()

    fun availabilityMessage(): String = when {
        !android.os.Process.is64Bit() -> "STEP/IGES import needs the 64-bit (arm64-v8a or x86_64) build of the app. This device reports: ${Build.SUPPORTED_ABIS.joinToString()}"
        executable == null -> "The CAD converter binary is missing from this build."
        else -> "CAD converter ready"
    }

    fun convert(source: ImportSource, formatName: String, quality: Quality, progress: ProgressSink): Model {
        val exe = executable ?: throw ImportException(availabilityMessage())
        val outDir = File(context.cacheDir, "cad"); outDir.mkdirs()
        val out = File(outDir, "${System.nanoTime()}.u3dm")
        val input = ensureExtension(source, formatName)
        progress.progress(0.02f, "Starting CAD kernel")
        val pb = ProcessBuilder(exe.absolutePath, input.absolutePath, out.absolutePath, "--quality", quality.arg)
        pb.redirectErrorStream(false)
        pb.directory(outDir)
        val proc = try { pb.start() } catch (e: Exception) { throw ImportException("Could not start the CAD converter: ${e.message}", e) }
        val errBuf = StringBuilder()
        val errThread = Thread {
            try { BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines -> lines.forEach { if (errBuf.length < 8000) errBuf.append(it).append('\n') } } } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
        val info = LinkedHashMap<String, String>()
        // Watchdog: destroying the process closes its pipes, which unblocks readLine() on cancel or timeout.
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        val watchdog = Thread {
            try { while (proc.isAlive) { if (progress.isCancelled || System.currentTimeMillis() > deadline) { proc.destroy(); break }; Thread.sleep(200) } } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("PROGRESS ") -> {
                            val f = line.substring(9).trim().toFloatOrNull() ?: continue
                            progress.progress(0.05f + 0.75f * f.coerceIn(0f, 1f), phaseLabel(f))
                        }
                        line.startsWith("INFO ") -> line.substring(5).trim().split(' ').forEach { kv -> val i = kv.indexOf('='); if (i > 0) info[kv.substring(0, i)] = kv.substring(i + 1) }
                    }
                    if (progress.isCancelled) { proc.destroy(); throw com.universal3d.viewer.io.ImportCancelledException() }
                }
            }
            if (!proc.waitFor(30, TimeUnit.MINUTES)) { proc.destroy(); throw ImportException("The CAD converter timed out") }
            errThread.join(2000)
            if (progress.isCancelled) throw com.universal3d.viewer.io.ImportCancelledException()
            if (System.currentTimeMillis() > deadline) throw ImportException("The CAD converter timed out after 30 minutes")
            val code = proc.exitValue()
            if (code != 0) {
                val err = errBuf.lines().lastOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR").orEmpty().trim()
                val msg = when (code) {
                    2 -> "This $formatName file could not be read (unsupported content or corrupt file). ${err}".trim()
                    3 -> "The $formatName file was read but contains no displayable geometry. $err".trim()
                    4 -> "The converter could not read or write files. $err".trim()
                    else -> if (code > 128 || code < 0) "The CAD kernel crashed while reading this file (signal ${code - 128}). The file may be corrupt or too complex for this device." else "CAD conversion failed (code $code). $err".trim()
                }
                Log.w("CadConverter", "exit=$code stderr=$errBuf")
                throw ImportException(msg)
            }
            if (!out.exists() || out.length() < 24) throw ImportException("The CAD converter produced no output")
            progress.progress(0.82f, "Loading tessellated geometry")
            val notes = ArrayList<String>()
            info["fileUnit"]?.let { notes.add("File unit: $it (converted to mm)") }
            info["xcaf"]?.let { if (it == "1") notes.add("Assembly structure and colours from file") }
            notes.add("Tessellation: ${quality.label}")
            val model = U3dmImporter().load(ImportSource(out, source.displayName, source.sizeBytes), progress, formatName, notes)
            return model
        } finally {
            watchdog.interrupt()
            try { proc.destroy() } catch (_: Exception) {}
            out.delete()
            if (input != source.file) input.delete()
        }
    }

    private fun phaseLabel(f: Float) = when { f < 0.5f -> "Reading CAD data"; f < 0.9f -> "Tessellating surfaces"; else -> "Writing mesh" }

    /** The converter detects the format by extension first; give it a file with a known extension. */
    private fun ensureExtension(source: ImportSource, formatName: String): File {
        val ext = source.file.name.substringAfterLast('.', "").lowercase()
        val wanted = if (formatName == "STEP") listOf("step", "stp") else listOf("iges", "igs")
        if (ext in wanted) return source.file
        val f = File(context.cacheDir, "cad/${System.nanoTime()}.${wanted[0]}")
        source.file.copyTo(f, overwrite = true)
        return f
    }
}
