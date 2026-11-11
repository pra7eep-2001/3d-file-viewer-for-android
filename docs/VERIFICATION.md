# Verification report

Latest build: **1.0.1** (see section 0). Sections 1–7 describe the 1.0.0 verification, all re-run for 1.0.1.

Environment: Linux x86_64 cloud container (4 cores, 15 GB RAM), no Android device/emulator, no Android SDK
(Google's download servers were blocked). Everything below was executed in that environment.

## 0. Version 1.0.1 – launch crash fix

**Symptom reported on a phone:** v1.0.0 installed, but tapping the icon showed nothing; the app closed immediately.

**Root cause:** the mesh shader program failed to link on real OpenGL ES drivers. The uniform `uHasNormals`
was declared in both the vertex and fragment shader without an explicit precision; GLSL ES 3.00 gives `int`
a default of `highp` in vertex shaders and `mediump` in fragment shaders, and requires a uniform shared by both
stages to have identical precision. The link error made `GlProgram` throw on the GL thread during
`onSurfaceCreated`, which killed the process before the first frame. The earlier checks validated each shader
on its own (`glslangValidator` without `-l`) and the Robolectric UI test does not create a GL context, so
neither could see it.

**Reproduced here** with `glslangValidator -l` ("Precision qualifiers must match: uHasNormals") and with Mesa's
real GLES 3.2 driver ("declarations for uniform `uHasNormals` have mismatching precision qualifiers").

**Fixed:**
* the vertex shader no longer declares the uniform; every shader declares `precision highp int` explicitly;
* GL initialisation and drawing are wrapped so a driver/shader problem shows an in-app "Graphics problem"
  dialog (with `GL_RENDERER`/`GL_VERSION`) instead of terminating the app;
* an uncaught-exception recorder stores any crash and shows it on the next start with Share/Copy buttons;
* the EGL config chooser has a minimal last-resort configuration;
* the harness below found that "fit to screen" only used the vertical field of view, cutting models off on
  portrait phones; fitting now uses the narrower of the horizontal and vertical fields of view.

**New verification layer – real renderer on a real GLES driver** (`tools/run-gl-harness.sh`): the app's
unmodified `SceneRenderer`, `Camera`, `GpuMesh`, `Picker` and importers run on Mesa llvmpipe (OpenGL ES 3.2)
through an `android.opengl.GLES30` shim that forwards every call and checks `glGetError()` after each one.
It follows the `GLSurfaceView` lifecycle (`onSurfaceCreated` → `onSurfaceChanged` → `onDrawFrame`), renders
18 scenes at phone resolution (start-up screen; bracket STL and the AS1 STEP assembly in solid, solid+edges,
wireframe, x-ray, front orthographic, Z section with bounding box, measurement + selection, white background;
landscape after a simulated GL context loss) and fails on any GL error, renderer-reported error or empty frame.
Result: **RESULT OK, 0 GL errors**; the PNGs were inspected visually. The shader sources were also extracted
from the final APK's `classes.dex` and link-tested on Mesa.

**Final APK:** `dist/Universal3DViewer-1.0.1.apk`, versionCode 2, 43.3 MB, SHA-256 `733da6553e0255a6f3f44a262e712358e57ed2103b5bb084a69dadbd9da6cea0`, signed with the same
certificate as 1.0.0 (installs as an update). Re-run for 1.0.1: renderer harness RESULT OK, 148 JVM tests 0 failures,
UI smoke test OK (3 tests), `apksigner verify` and `zipalign -c` OK.

**API surface scan:** every framework/JDK member referenced by the compiled app (681 references, 146 classes)
was checked against the public API text files of Android 10 (API 29) and Android 14 (API 34): no hidden or
JDK-only APIs; the only API-30+ calls (`Window.setDecorFitsSystemWindows`, `WindowInsets.getInsets/Type`) are
behind `SDK_INT >= 30` checks.

## 1. Build

| Step | Result |
|------|--------|
| `aapt2 compile` + `aapt2 link` (manifest, 60 resources, R.java) | OK |
| Kotlin compile (Kotlin 2.1.20, jvmTarget 17, `compileOnly` android.jar API 34) | OK, 0 errors |
| D8 (`--release --min-api 29`) | OK |
| Packaging + `zipalign -p 4` + `apksigner` (v1+v2+v3) | OK, `apksigner verify` passes |
| `aapt2 dump badging` | `package: name='com.universal3d.viewer' versionCode='1' versionName='1.0.0'`, `sdkVersion:'29'`, `targetSdkVersion:'35'`, `uses-gl-es: '0x30000'`, launchable activity `com.universal3d.viewer.ui.MainActivity`, `native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'` |
| `zipalign -c -p 4` | OK (native executables page-aligned, stored uncompressed) |
| Final APK | `dist/Universal3DViewer-1.0.0.apk`, 42 MB (2.6 MB dex + 20.6 MB arm64 converter + 21.8 MB x86_64 converter + 9 KB 32-bit stubs), SHA-256 `246818ece88fbba19331eb8c0f47a76edd7027f79afc1e3ba624bd956270dad0` |

## 2. JVM unit tests (`buildtools`, `gradle test`) — 148 tests, 0 failures

| Suite | Tests | Covers |
|-------|------:|--------|
| StlObjImporterTest | 5 | ASCII/binary STL, OBJ groups + MTL + lines, feature edges, concave triangulation |
| PlyImporterTest | 11 | ASCII, binary LE/BE, colours, edges, errors |
| OffImporterTest | 15 | OFF/COFF/NOFF variants, errors |
| GltfImporterTest | 18 | GLB, embedded/external glTF, TRS/matrix hierarchy, strips/fans, quantization, Draco rejection |
| ThreeDsImporterTest | 9 | chunks, materials, multiple objects, truncation |
| FbxImporterTest | 13 | binary (zlib arrays) + ASCII 7.x, hierarchy, materials, units |
| ThreeMfImporterTest | 8 | components/transforms, colours, units |
| AmfImporterTest | 8 | zip/plain, volumes, constellations, colours |
| DaeImporterTest | 6 | polylist/triangles, scene transforms, materials, units |
| X3dImporterTest | 7 | IFS/ITS, Transform, DEF/USE, primitives |
| VrmlImporterTest | 8 | tokenizer, Transform, DEF/USE, gzip, VRML 1.0 rejection |
| VtkImporterTest | 14 | legacy ASCII/binary, unstructured cells, XML vtp/vtu with zlib |
| UsdaImporterTest | 9 | prims, xformOps, displayColor, usdc rejection |
| DxfImporterTest | 11 | 3DFACE, polyface, INSERT/BLOCK, splines, units |
| TextFormatsRobustnessTest | 2 | truncation + random corruption of every bundled sample never crashes |
| U3dmImporterTest | 3 | converter output of `screw.step` and the AS1 assembly (28 nodes, names, colours) |
| LargeModelTest | 1 | 1,000,000-triangle binary STL (50 MB): import 0.3 s, weld to 501 501 vertices, feature edges 0.34 s, ray pick 23 ms, ~350 MB heap |

Real-world files exercised by the importer agents (downloaded from public repositories, not committed): Khronos glTF
samples (Box, Duck, Lantern, 2CylinderEngine, BoxVertexColors…), assimp test models (PLY, OFF, 3DS, FBX, AMF, 3MF,
COLLADA, X3D, DXF), three.js VRML models, VTK/pyvista datasets, OpenUSD tutorial `.usda` files.

## 3. Native STEP/IGES converter (Open CASCADE 7.6.3, static musl, Zig cross-compiler)

x86_64 host build and arm64 build from the same sources (`native/converter`). Results of the x86_64 binary on the
sample files (medium quality):

| File | Result | Nodes | Triangles | Edge segments | Time |
|------|--------|------:|----------:|--------------:|-----:|
| `screw.step` | OK, unit mm | 1 | 814 | 219 | 43 ms |
| `linkrods.step` | OK | 1 | 7 230 | 1 084 | 0.5 s |
| `as1-oc-214.stp` (assembly, colours) | OK, 28-node tree, 18 parts, 6 colours | 28 | 7 512 | 3 866 | 0.3 s |
| `bearing.iges` | OK | 1 | 13 966 | 6 712 | 0.4 s |
| `hammer.iges` | OK | 1 | 4 579 | 2 007 | 0.2 s |
| text file renamed `.step` | `ERROR STEP read failed`, exit 2 | | | | |
| truncated STEP | `ERROR STEP file is truncated`, exit 2 | | | | |
| missing file | `ERROR cannot open input file`, exit 4 | | | | |

ARM64 binary: see section 5 (run under `qemu-aarch64-static`).

## 4. UI smoke test (Robolectric 3.8, real Android framework classes, API 27 resources)

`MainActivitySmokeTest` — 3 tests, 0 failures:
* activity starts, layout inflates, empty state shown, default mode = Solid + edges
* every toolbar/rail/panel button: view modes, grid/axes/box toggles, projection, section HUD (axis chips, close),
  measure HUD, side panel tabs, formats dialog, overflow menu
* `ACTION_VIEW` of an STL file loads on the background thread, hides the empty state, fills title/subtitle, Parts list,
  recent files; unit formatting; configuration change to landscape

This run found and fixed two real defects before delivery: the window-inset code used an API 30 class (would have
crashed on Android 10) and `file://` URIs went through the content resolver.

## 5. ARM64 binary (`lib/arm64-v8a/libu3dconv.so`)

Cross-compiled with Zig 0.15.2 (`-target aarch64-linux-musl -static -O2`, 4 444 OCCT translation units, 23.5 min on 3 cores),
`ELF 64-bit LSB executable, ARM aarch64, statically linked, stripped`, 20.6 MB, 64 KiB max-page-size (16 KiB-page kernels OK).
Executed under `qemu-aarch64-static` on the same inputs:

| File | Exit | Nodes | Triangles | Edge segments | Converter time (emulated) |
|------|-----:|------:|----------:|--------------:|--------------------------:|
| `screw.step` | 0 | 1 | 814 | 219 | 0.7 s |
| `linkrods.step` | 0 | 1 | 7 212 | 1 084 | 5.3 s |
| `as1-oc-214.stp` | 0 | 28 (18 parts, colours, names identical to x86_64) | 7 512 | 3 866 | 1.7 s |
| `bearing.iges` | 0 | 1 | 13 754 | 6 712 | 2.2 s |
| `hammer.iges` | 0 | 1 | 4 535 | 2 007 | 1.1 s |
| text renamed `.step` | 2 (`ERROR STEP read failed`) | | | | |
| truncated STEP | 2 (`ERROR STEP file is truncated`) | | | | |

Node trees, names, colours and bounding boxes match the x86_64 build; triangle counts differ by < 2 % because
OCCT's mesher makes floating-point dependent decisions. The 32-bit stubs were executed under `qemu-arm-static`
and return exit 2 with the explanatory message.

## 6. Code review

An independent review pass over the Android code (renderer, camera, activity, loader, converter runner) produced
11 findings, all fixed before the final build: dialog shown on a finished activity (crash), unbounded wireframe
edge-map allocation on the GL thread (OOM), 32-bit process detection for the converter, empty-state view
covering the top bar, far-plane shrink after "fit to part", edge-only parts invisible in Solid mode, converter
cancel/timeout watchdog, pinch-zoom matrix race, stale cancel callback hiding a new load's overlay, rail margin
after rotation, and a leaf icon swallowing taps in the parts list.

## 7. Not verified

* No run on a physical device or emulator (none available): GPU rendering, touch gestures, the file picker and
  share-sheet flows, and real-device performance are untested. The GLSL ES 3.00 shaders were validated with
  `glslangValidator`; the EGL config chooser falls back from 4× MSAA to plain RGBA8/depth24/depth16.
* The Android Gradle Plugin build (`./gradlew assembleRelease`) could not be executed here (Google Maven blocked);
  the AGP files follow the standard AGP 8.7 layout and the same sources/resources build with the SDK-less pipeline.
