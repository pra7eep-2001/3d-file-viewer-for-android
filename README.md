# Universal 3D Viewer (Android)

An offline, engineering-oriented 3D model viewer for Android 10+ (arm64-v8a first; built and tuned for the
Samsung Galaxy S24 Ultra). Opens CAD and mesh files directly on the phone, including **STEP and IGES**
(tessellated on-device by Open CASCADE Technology), with CAD-style navigation, measurement, section views,
part hierarchy and a dark, minimal interface.

* **APK:** `dist/Universal3DViewer-1.0.0.apk` (signed, installable; see *Install* below)
* **Project:** standard Android Studio layout (`app/`), plus an SDK-less build pipeline (`tools/`, `buildtools/`)
* **Native CAD kernel:** `native/converter/` (OCCT 7.6.3 STEP/IGES → mesh converter, static ARM64 executable)

---

## Install

1. Copy `dist/Universal3DViewer-1.0.0.apk` to the phone (USB, Drive, WhatsApp "document", …) or install via ADB:
   ```bash
   adb install -r dist/Universal3DViewer-1.0.0.apk
   ```
2. Tap the APK in *My Files* / *Downloads*. Android asks to allow installs from that source ("Install unknown apps") —
   allow it once. The APK is signed with a self-signed debug certificate, so Play Protect may show a
   "unknown developer" notice; choose *Install anyway*.
3. Open **Universal 3D Viewer**, tap **Open model**, pick a file (Downloads, Documents, SD card, Drive…),
   or share a file to the app from WhatsApp / Telegram / Gmail / a file manager ("Open with" or "Share").

Requirements: Android 10 (API 29) or newer, OpenGL ES 3.0. STEP/IGES need a 64-bit device (arm64-v8a, i.e. every
current Samsung/Pixel/OnePlus phone, or x86_64 emulators). Everything else runs on any ABI. APK size: 42 MB.

The sample models in `samples/` are a good first test (`screw.step`, `as1-oc-214.stp` assembly, `bracket_*.stl`…).

---

## Features

**Viewing**
* One-finger orbit (turntable), two-finger pan, pinch zoom (zooms toward the fingers), double-tap to fit
* Fit, Reset, standard views (Front/Back/Left/Right/Top/Bottom/Isometric) with animated transitions
* Perspective ⇄ orthographic projection
* Solid, Solid + edges, Wireframe and X-ray (transparent) modes
* Six background presets (charcoal, black, graphite, slate blue, studio grey, white)
* Lighting controls: intensity, ambient, specular, headlight vs. fixed light
* Coordinate axes, ground grid with round spacing, bounding box, navigation triad (X/Y/Z)
* Z-up (CAD) or Y-up (glTF/FBX) orientation

**Engineering**
* Exact B-rep geometry from STEP/IGES tessellated by Open CASCADE with true surface normals and real
  B-rep edge curves (holes, slots, fillets and chamfers render as crisp edges, not mesh creases)
* Three CAD tessellation qualities (Fast / Balanced / Fine)
* Point-to-point distance measurement with ΔX/ΔY/ΔZ, picking on the real triangles
* Bounding box dimensions, min/max/center, diagonal
* File units vs. display units (mm, cm, m, in, ft) – geometry is **never** rescaled
* Section view: clipping plane on X/Y/Z with slider and flip
* Assembly tree with part names and colours; show/hide/isolate parts, fit view to a part, tap-to-select

**Files**
* Android file picker (SAF), "Open with" and "Share to" from any app, recent files list
* Format identification by extension *and* content sniffing (e.g. a STEP file named `.txt`, a GLB renamed `.gltf`)
* Streaming, allocation-free parsers for large STL/OBJ/PLY; vertex welding; feature-edge extraction in the background
* Loading progress with cancel; clear error messages for corrupt or unsupported files; CAD kernel runs in an
  isolated process so a crash inside Open CASCADE cannot take the app down

**Design**
* Dark charcoal/black theme, minimal top bar, right-hand camera rail, bottom mode toolbar, slide-in panel
  (Info / Parts / Display) that docks right in landscape and bottom in portrait; rotation is handled without
  reloading the model

---

## Supported formats

| Format | Ext. | Support | Notes |
|--------|------|---------|-------|
| STEP | .step .stp | ✅ CAD kernel | AP203/AP214/AP242 B-rep via OCCT; assembly tree, names, colours. 64-bit devices |
| IGES | .iges .igs | ✅ CAD kernel | Surfaces/solids via OCCT; names where present. 64-bit devices |
| STL | .stl | ✅ | ASCII + binary, welded, feature edges |
| OBJ | .obj | ✅ | groups/objects → parts, polygons triangulated, MTL diffuse colours (when `.mtl` is beside the file; textures ignored) |
| 3MF | .3mf | ✅ | mesh objects, components + transforms, base materials/colours, units |
| PLY | .ply | ✅ | ASCII, binary LE/BE; polygons; vertex colours averaged |
| AMF | .amf | ✅ | plain + zip; objects/volumes, colours, units |
| GLB / glTF 2.0 | .glb .gltf | ✅ / ◐ | node hierarchy, TRS/matrix, base colours; `.gltf` needs embedded buffers or `.bin` beside the file. No Draco/meshopt, no textures |
| FBX | .fbx | ◐ | binary + ASCII 7.x meshes, hierarchy, material colours; no animation/skinning/textures |
| 3DS | .3ds | ✅ | named objects, faces, material colours |
| COLLADA | .dae | ✅ | triangles/polylist/polygons, scene transforms, material colours |
| USD (ASCII) | .usda .usd | ◐ | Mesh prims, Xform ops, displayColor; `.usd` only when ASCII (`#usda`) |
| USDC | .usdc | ❌ | binary crate format not supported – export `.usda`/`.glb`/`.obj` |
| IFC | .ifc | ❌ | needs a BIM geometry kernel (IfcOpenShell), not available on-device |
| VRML 2.0/97 | .wrl | ✅ | IndexedFaceSet, Transform, Material, DEF/USE (VRML 1.0 not supported) |
| X3D | .x3d | ✅ | XML encoding: IndexedFaceSet/IndexedTriangleSet, Transform, Material |
| OFF | .off | ✅ | OFF/COFF/NOFF |
| DXF | .dxf | ◐ | 3DFACE, polyface/polygon meshes, MESH; LINE/POLYLINE/CIRCLE/ARC as edges; no ACIS solids |
| VTK | .vtk .vtp | ◐ | legacy ASCII/binary POLYDATA + UNSTRUCTURED_GRID surfaces |
| Alembic | .abc | ❌ | not supported – export OBJ/glTF |

✅ full · ◐ partial (see notes) · ❌ not supported (the app says so instead of showing wrong geometry).
The in-app **Supported formats** dialog shows the same table.

---

## Known limitations

* **STEP/IGES on 32-bit devices**: the Open CASCADE converter ships for arm64-v8a (the Galaxy S24 Ultra) and x86_64
  (emulators). On 32-bit devices the app installs and all mesh formats work, but STEP/IGES report "needs a 64-bit device".
* **Section view** has no capping faces (you look into the hollow shell at the cut), and picking ignores clipped-away geometry.
* **Wireframe** of meshes above ~4 million triangles falls back to feature edges; feature edges are skipped above 2.5 M triangles.
* **Textures, PBR materials, animation, skinning** are ignored in all formats (only base/diffuse colours are used).
* **Per-face colours** in STEP/3MF/PLY are reduced to one colour per part.
* **Very large files**: the app uses the Android "large heap"; practical limits on a 12 GB phone are roughly
  20–30 M triangles for STL/OBJ. STEP tessellation of huge assemblies at *Fine* quality can take minutes – the
  converter streams progress and can be cancelled.
* **glTF with external buffers** only works when the `.bin` sits next to the `.gltf` *and* the file is opened from a
  real path (content providers hand over a single file). Use `.glb` to be safe.
* The STEP/IGES reader converts to millimetres (OCCT default); the declared file unit is shown in the Info panel.
* **Not verified on physical hardware in this environment** (no device or emulator was available). What was verified:
  JVM unit tests of every importer with generated and real-world files, the ARM64 converter under `qemu-aarch64`,
  Robolectric UI tests of activity start-up / toolbar logic / STL load via intent, APK signature + badging.
  See *Testing* below.

---

## Project layout

```
app/                     Android application module (Kotlin, framework widgets only – no AndroidX)
  src/main/java/com/universal3d/viewer/
    model/               Mesh, Node, Model, Bounds, Mat4, MeshOps (weld, normals, feature edges, triangulation)
    io/                  Importer API, FastReader tokenizer, MiniJson, FormatRegistry (+ formats/* importers)
    cad/                 CadConverter – runs the Open CASCADE subprocess for STEP/IGES
    render/              GlView (gestures), SceneRenderer (GLES 3.0), Camera, shaders, Picker
    ui/                  MainActivity, SidePanel, Dialogs, Formatters
    app/                 ModelLoader (background loading), RecentFiles, ViewerApp
  src/main/jniLibs/arm64-v8a/libu3dconv.so   static OCCT converter executable
  src/test/              JUnit tests (importers, geometry, Robolectric UI smoke test)
native/converter/        C++ converter source, build scripts, OCCT patches, U3DM format spec
buildtools/              Gradle build used for host-side compilation/tests without the Android SDK
tools/build-apk.sh       SDK-less APK pipeline (aapt2 → kotlinc → D8 → zipalign → apksigner)
samples/                 test models
dist/                    the built APK
```

See `BUILDING.md` for both build routes and `native/converter/README.md` for the CAD kernel build.

---

## Testing

`docs/VERIFICATION.md` has the full record: build/badging/signature checks, 148 JVM tests (every importer,
robustness fuzzing, a 1 M-triangle STL), converter runs on the sample STEP/IGES files for x86_64 and for the
ARM64 binary under qemu, the Robolectric UI smoke test, and the list of things that could **not** be verified
without a device.
