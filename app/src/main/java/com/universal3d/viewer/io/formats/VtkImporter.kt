package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*

/** TODO: implemented by the format agent. */
class VtkImporter : Importer {
    override val formatName = "VTK"
    override val extensions = listOf<String>()
    override fun load(source: ImportSource, progress: ProgressSink): Model = throw ImportException("VTK import is not implemented yet")
}
