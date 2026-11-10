package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*

/** TODO: implemented by the format agent. */
class UsdaImporter : Importer {
    override val formatName = "USDA"
    override val extensions = listOf<String>()
    override fun load(source: ImportSource, progress: ProgressSink): Model = throw ImportException("USDA import is not implemented yet")
}
