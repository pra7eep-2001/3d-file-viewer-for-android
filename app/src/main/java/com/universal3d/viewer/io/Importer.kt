package com.universal3d.viewer.io

import com.universal3d.viewer.model.Model
import java.io.File

/** A local file to import. Content URIs are always copied to a cache file before importing. */
class ImportSource(val file: File, val displayName: String, val sizeBytes: Long = file.length()) {
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()
}

/** Progress and cancellation channel for background imports. */
interface ProgressSink {
    /** [fraction] in 0..1, or -1 when unknown. */
    fun progress(fraction: Float, message: String? = null)
    val isCancelled: Boolean
    fun checkCancelled() { if (isCancelled) throw ImportCancelledException() }

    object None : ProgressSink {
        override fun progress(fraction: Float, message: String?) {}
        override val isCancelled: Boolean get() = false
    }
}

/** Thrown for unsupported, corrupt or unreadable files. The message is shown to the user as-is. */
open class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ImportCancelledException : ImportException("Import cancelled")

interface Importer {
    /** Human readable format name, e.g. "STL". */
    val formatName: String
    /** Lower-case extensions handled, without dots. */
    val extensions: List<String>
    fun load(source: ImportSource, progress: ProgressSink): Model
}
