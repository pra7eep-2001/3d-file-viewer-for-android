# Verification report

Environment: Linux x86_64 cloud container (4 cores, 15 GB RAM), no Android device/emulator, no Android SDK
(Google's download servers were blocked). Everything below was executed in that environment.

## 1. Build

| Step | Result |
|------|--------|
| `aapt2 compile` + `aapt2 link` (manifest, 60 resources, R.java) | OK |
| Kotlin compile (Kotlin 2.1.20, jvmTarget 17, `compileOnly` android.jar API 34) | OK, 0 errors |
| D8 (`--release --min-api 29`) | OK |
| Packaging + `zipalign -p 4` + `apksigner` (v1+v2+v3) | OK, `apksigner verify` passes |
| `aapt2 dump badging` | `package: name='com.universal3d.viewer' versionCode='1' versionName='1.0.0'`, `sdkVersion:'29'`, `targetSdkVersion:'35'`, `uses-gl-es: '0x30000'`, launchable activity `com.universal3d.viewer.ui.MainActivity`, native-code arm64-v8a / armeabi-v7a / x86 / x86_64 |

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

## 5. ARM64 binary
(filled in below after the cross-compile finished)
