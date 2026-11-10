package com.universal3d.viewer.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.universal3d.viewer.cad.CadConverter
import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.MeshOps
import com.universal3d.viewer.model.Model
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads models on a background thread: resolves the content URI to a cache file, identifies the
 * format, runs the importer (or the CAD converter) and computes feature edges for solid+edges mode.
 */
class ModelLoader(private val context: Context) {
    interface Listener {
        fun onProgress(fraction: Float, message: String)
        fun onLoaded(result: LoadResult)
        fun onFailed(error: String, details: String?)
        fun onCancelled()
    }

    class LoadResult(val model: Model, val uri: Uri, val format: FormatInfo, val localFile: File, val loadMillis: Long)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "model-loader").also { it.priority = Thread.NORM_PRIORITY - 1 } }
    private val edgeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "edge-builder").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private var current: AtomicBoolean? = null
    var cadQuality = CadConverter.Quality.MEDIUM

    fun cancel() { current?.set(true) }

    fun load(uri: Uri, listener: Listener, onEdgesReady: (com.universal3d.viewer.model.Node) -> Unit) {
        current?.set(true)
        val cancelled = AtomicBoolean(false)
        current = cancelled
        val sink = object : ProgressSink {
            @Volatile var lastPost = 0L
            override fun progress(fraction: Float, message: String?) {
                val now = System.currentTimeMillis()
                if (now - lastPost < 60 && fraction < 0.999f) return
                lastPost = now
                main.post { if (!cancelled.get()) listener.onProgress(fraction, message ?: "") }
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }
        executor.execute {
            val start = System.currentTimeMillis()
            var local: File? = null
            try {
                sink.progress(0f, "Opening file")
                val (file, name, size) = copyToCache(uri, sink)
                local = file
                sink.progress(0.03f, "Identifying format")
                val format = FormatRegistry.identify(file, name)
                    ?: throw ImportException("Unrecognised file type \"${name.substringAfterLast('.', "?")}\". Supported: ${FormatRegistry.formats.filter { it.level != SupportLevel.UNSUPPORTED }.joinToString { it.extensions.first().uppercase() }}")
                if (format.level == SupportLevel.UNSUPPORTED) throw ImportException("${format.name} files are not supported. ${format.notes}")
                val source = ImportSource(file, name, size)
                val model: Model = if (format.level == SupportLevel.CONVERTER) {
                    val conv = CadConverter(context)
                    if (!conv.isAvailable) throw ImportException(conv.availabilityMessage())
                    conv.convert(source, format.name, cadQuality, sink)
                } else {
                    val importer = format.importer ?: throw ImportException("${format.name} import is not available")
                    importer.load(source, sink)
                }
                if (cancelled.get()) throw ImportCancelledException()
                if (model.isEmpty) throw ImportException("The file was read but contains no displayable geometry.")
                sink.progress(1f, "Done")
                val result = LoadResult(model, uri, format, file, System.currentTimeMillis() - start)
                main.post { if (!cancelled.get()) listener.onLoaded(result) }
                // Feature edges for meshes that have none (background, low priority)
                edgeExecutor.execute {
                    for (node in model.meshNodes) {
                        if (cancelled.get()) return@execute
                        val mesh = node.mesh ?: continue
                        if (mesh.edgeIndices != null || mesh.edgePositions != null) continue
                        if (mesh.triangleCount > 2_500_000) continue
                        val edges = try { MeshOps.featureEdges(mesh.positions, mesh.indices, 32f) } catch (e: OutOfMemoryError) { null } ?: continue
                        if (cancelled.get()) return@execute
                        mesh.edgeIndices = edges
                        main.post { if (!cancelled.get()) onEdgesReady(node) }
                    }
                }
            } catch (e: ImportCancelledException) {
                local?.delete(); main.post { listener.onCancelled() }
            } catch (e: ImportException) {
                local?.delete(); main.post { if (!cancelled.get()) listener.onFailed(e.message ?: "Import failed", e.cause?.toString()) }
            } catch (e: OutOfMemoryError) {
                local?.delete(); System.gc()
                main.post { if (!cancelled.get()) listener.onFailed("Not enough memory to load this model on this device. Try a lower tessellation quality or a decimated export.", null) }
            } catch (e: Throwable) {
                local?.delete(); main.post { if (!cancelled.get()) listener.onFailed("Unexpected error while reading the file: ${e.javaClass.simpleName}: ${e.message}", android.util.Log.getStackTraceString(e)) }
            }
        }
    }

    private data class Copied(val file: File, val name: String, val size: Long)

    /** Copies the URI content into the cache (needed for random access and for the native converter). */
    private fun copyToCache(uri: Uri, sink: ProgressSink): Copied {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model"
        var size = -1L
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) c.getString(ni)?.let { name = it }
                        val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {}
        } else if (uri.scheme == "file") { val f = File(uri.path ?: ""); name = f.name; size = f.length() }
        val dir = File(context.cacheDir, "models"); dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "bin" }
        val dest = File(dir, "model_${System.nanoTime()}.$ext")
        val ins = try {
            if (uri.scheme == "file") java.io.FileInputStream(File(uri.path ?: throw ImportException("Invalid file path")))
            else context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) { throw ImportException("Permission to read this file was denied. Please open it again through the file picker.", e) }
          catch (e: java.io.FileNotFoundException) { throw ImportException("The file could not be found: ${e.message}", e) }
            ?: throw ImportException("The file could not be opened.")
        ins.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 16); var total = 0L; var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); total += n
                    if (size > 0 && (total and 0xfffff) == 0L) sink.progress(0.03f * total / size, "Copying file"); sink.checkCancelled()
                }
                if (size < 0) size = total
            }
        }
        // glTF with external buffers: also copy sibling .bin files when the URI is a plain file path
        if (ext == "gltf" && uri.scheme == "file") {
            File(uri.path ?: "").parentFile?.listFiles()?.filter { it.name.endsWith(".bin", true) }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        }
        if (ext == "obj" && uri.scheme == "file") {
            File(uri.path ?: "").parentFile?.listFiles()?.filter { it.name.endsWith(".mtl", true) }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        }
        return Copied(dest, name, size)
    }
}
