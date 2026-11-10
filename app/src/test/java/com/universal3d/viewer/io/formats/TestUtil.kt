package com.universal3d.viewer.io.formats

import com.universal3d.viewer.io.ImportSource
import com.universal3d.viewer.io.ProgressSink
import com.universal3d.viewer.model.Model
import java.io.File
import java.nio.file.Files

object TestUtil {
    fun tempFile(name: String, content: ByteArray): File {
        val dir = Files.createTempDirectory("u3d-test").toFile()
        val f = File(dir, name); f.writeBytes(content); return f
    }
    fun tempFile(name: String, content: String) = tempFile(name, content.toByteArray())
    fun source(f: File) = ImportSource(f, f.name)
    fun sample(name: String): File {
        val dir = File(System.getProperty("samples.dir") ?: "app/src/test/resources")
        val f = File(dir, name)
        require(f.exists()) { "missing sample $f" }
        return f
    }
    fun load(importer: com.universal3d.viewer.io.Importer, f: File): Model = importer.load(source(f), ProgressSink.None)
}
