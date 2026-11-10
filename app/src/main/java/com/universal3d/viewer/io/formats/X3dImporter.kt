package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.*
import com.universal3d.viewer.model.*

/** TODO: implemented by the format agent. */
class X3dImporter : Importer {
    override val formatName = "X3D"
    override val extensions = listOf<String>()
    override fun load(source: ImportSource, progress: ProgressSink): Model = throw ImportException("X3D import is not implemented yet")
}
