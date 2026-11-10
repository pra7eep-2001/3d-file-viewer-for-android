package com.universal3d.viewer.io

import com.universal3d.viewer.io.formats.*
import java.io.File
import java.io.RandomAccessFile

enum class SupportLevel { FULL, PARTIAL, CONVERTER, UNSUPPORTED }

/** Static description of a file format, used for the compatibility table and error messages. */
class FormatInfo(
    val name: String,
    val extensions: List<String>,
    val level: SupportLevel,
    val notes: String,
    val importer: Importer? = null,
)

/** Maps extensions / magic bytes to importers. STEP and IGES are handled by the native converter (see cad package). */
object FormatRegistry {
    val stl = StlImporter(); val obj = ObjImporter(); val ply = PlyImporter(); val off = OffImporter()
    val threeMf = ThreeMfImporter(); val amf = AmfImporter(); val gltf = GltfImporter(); val dae = DaeImporter()
    val x3d = X3dImporter(); val vrml = VrmlImporter(); val dxf = DxfImporter(); val vtk = VtkImporter()
    val threeDs = ThreeDsImporter(); val fbx = FbxImporter(); val usda = UsdaImporter(); val u3dm = U3dmImporter()

    val formats: List<FormatInfo> = listOf(
        FormatInfo("STEP", listOf("step", "stp"), SupportLevel.CONVERTER, "Exact B-rep geometry tessellated by Open CASCADE; assembly tree, part names and colours. ARM64 devices only.", null),
        FormatInfo("IGES", listOf("iges", "igs"), SupportLevel.CONVERTER, "Surfaces and solids tessellated by Open CASCADE; names where present. ARM64 devices only.", null),
        FormatInfo("STL", listOf("stl"), SupportLevel.FULL, "ASCII and binary. Vertices are welded for edge display; no units in file.", stl),
        FormatInfo("OBJ", listOf("obj"), SupportLevel.FULL, "Polygons triangulated, groups/objects become parts, MTL diffuse colours when the .mtl file is next to the .obj (textures ignored).", obj),
        FormatInfo("3MF", listOf("3mf"), SupportLevel.FULL, "Mesh objects, components with transforms, base materials/colours, units.", threeMf),
        FormatInfo("PLY", listOf("ply"), SupportLevel.FULL, "ASCII, binary little/big endian; vertex colours averaged per mesh, polygons triangulated.", ply),
        FormatInfo("AMF", listOf("amf"), SupportLevel.FULL, "Plain and zip-compressed; objects, volumes, colours, units.", amf),
        FormatInfo("DXF", listOf("dxf"), SupportLevel.PARTIAL, "3DFACE, polyface/polygon meshes and MESH entities; LINE/3D POLYLINE/LWPOLYLINE/CIRCLE/ARC shown as edges. 2D drawings and solids (ACIS) are not supported.", dxf),
        FormatInfo("GLB", listOf("glb"), SupportLevel.FULL, "glTF 2.0 binary; node hierarchy, base colours. Draco/meshopt compression and textures are not supported.", gltf),
        FormatInfo("glTF", listOf("gltf"), SupportLevel.PARTIAL, "glTF 2.0 JSON with embedded (data URI) buffers, or external .bin files when they sit next to the .gltf file.", gltf),
        FormatInfo("FBX", listOf("fbx"), SupportLevel.PARTIAL, "Binary and ASCII FBX 7.x: meshes, hierarchy, material diffuse colours. Animation, skinning and textures ignored.", fbx),
        FormatInfo("3DS", listOf("3ds"), SupportLevel.FULL, "Named objects, faces, material colours and local transforms.", threeDs),
        FormatInfo("COLLADA", listOf("dae"), SupportLevel.FULL, "Geometry (triangles/polylist/polygons), scene node transforms, material colours.", dae),
        FormatInfo("USD (ASCII)", listOf("usda", "usd"), SupportLevel.PARTIAL, "usda text files: Mesh prims, Xform transforms, displayColor. .usd files are only readable when they are ASCII (#usda).", usda),
        FormatInfo("USDC", listOf("usdc"), SupportLevel.UNSUPPORTED, "Binary USD (crate) files are not supported. Export as .usda, .glb or .obj instead.", null),
        FormatInfo("IFC", listOf("ifc"), SupportLevel.UNSUPPORTED, "IFC needs a BIM geometry kernel (IfcOpenShell) which is not available on-device. Convert to STEP/OBJ/GLB.", null),
        FormatInfo("VRML", listOf("wrl", "vrml"), SupportLevel.FULL, "VRML 2.0 / 97: IndexedFaceSet, Transform, Material, DEF/USE.", vrml),
        FormatInfo("X3D", listOf("x3d"), SupportLevel.FULL, "X3D XML encoding: IndexedFaceSet/IndexedTriangleSet, Transform, Material.", x3d),
        FormatInfo("OFF", listOf("off"), SupportLevel.FULL, "OFF / COFF / NOFF polygon meshes.", off),
        FormatInfo("Alembic", listOf("abc"), SupportLevel.UNSUPPORTED, "Alembic (HDF5/Ogawa) is not supported. Export as OBJ or glTF.", null),
        FormatInfo("VTK", listOf("vtk", "vtp"), SupportLevel.PARTIAL, "Legacy VTK (ASCII/binary) POLYDATA and UNSTRUCTURED_GRID surfaces; XML .vtp with inline ASCII/binary data.", vtk),
        FormatInfo("U3DM", listOf("u3dm"), SupportLevel.FULL, "Universal 3D Viewer intermediate mesh (output of the CAD converter).", u3dm),
    )

    fun byExtension(ext: String): FormatInfo? = formats.firstOrNull { ext.lowercase() in it.extensions }

    val allExtensions: List<String> get() = formats.flatMap { it.extensions }

    /** Identifies the format of a file from its name and content. */
    fun identify(file: File, displayName: String): FormatInfo? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val head = ByteArray(512)
        var n = 0
        try { RandomAccessFile(file, "r").use { n = it.read(head) } } catch (e: Exception) { }
        val s = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1) else ""
        val byMagic = identifyByContent(s, head, n)
        val byExt = byExtension(ext)
        // Prefer content when it is unambiguous and the extension is missing/unknown or contradicts strongly.
        if (byExt == null) return byMagic
        if (byMagic != null && byMagic.name != byExt.name && byMagic.level != SupportLevel.UNSUPPORTED) {
            // e.g. a STEP file saved as .txt, or a GLB renamed .gltf
            if (byExt.name == "glTF" && byMagic.name == "GLB") return byMagic
            if (byExt.name == "USD (ASCII)" || byMagic.name == "STEP" || byMagic.name == "IGES") return byMagic
        }
        return byExt
    }

    fun identifyByContent(s: String, head: ByteArray, n: Int): FormatInfo? {
        if (n >= 4 && head[0] == 'g'.code.toByte() && head[1] == 'l'.code.toByte() && head[2] == 'T'.code.toByte() && head[3] == 'F'.code.toByte()) return byName("GLB")
        if (n >= 4 && head[0] == 'U'.code.toByte() && head[1] == '3'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'M'.code.toByte()) return byName("U3DM")
        if (s.startsWith("ISO-10303-21")) return byName("STEP")
        if (n >= 80 && s.length >= 73 && s[72] == 'S' && s.substring(0, 72).all { it.code in 32..126 }) return byName("IGES")
        if (s.startsWith("#usda")) return byName("USD (ASCII)")
        if (s.startsWith("PXR-USDC")) return byName("USDC")
        if (s.startsWith("Kaydara FBX Binary")) return byName("FBX")
        if (s.startsWith("ply")) return byName("PLY")
        if (s.trimStart().startsWith("OFF") || s.trimStart().startsWith("COFF") || s.trimStart().startsWith("NOFF")) return byName("OFF")
        if (s.startsWith("#VRML")) return byName("VRML")
        if (s.startsWith("# vtk DataFile")) return byName("VTK")
        if (s.startsWith("ISO-10303")) return byName("STEP")
        val lower = s.lowercase()
        if (lower.contains("<collada")) return byName("COLLADA")
        if (lower.contains("<x3d")) return byName("X3D")
        if (lower.contains("<amf")) return byName("AMF")
        if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return null // zip container (3MF/AMF): rely on extension
        if (n >= 2 && head[0] == 0x4D.toByte() && head[1] == 0x4D.toByte()) return byName("3DS")
        if (s.startsWith("solid")) return byName("STL")
        if (lower.contains("\nv ") || lower.startsWith("v ") || lower.contains("\nf ") || lower.contains("mtllib")) return byName("OBJ")
        if (s.trimStart().startsWith("0") && s.contains("SECTION")) return byName("DXF")
        return null
    }

    fun byName(name: String): FormatInfo? = formats.firstOrNull { it.name == name }
}
