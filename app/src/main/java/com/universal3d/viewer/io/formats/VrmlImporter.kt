package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*

/** TODO: implemented by the format agent. */
class VrmlImporter : Importer {
    override val formatName = "VRML"
    override val extensions = listOf<String>()
    override fun load(source: ImportSource, progress: ProgressSink): Model = throw ImportException("VRML import is not implemented yet")
}
