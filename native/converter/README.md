# u3dconv — STEP/IGES → tessellated mesh converter

`u3dconv` is a small command-line program built on Open CASCADE Technology
(OCCT) 7.6.3.  It reads a STEP (AP203/AP214/AP242) or IGES file, tessellates
it and writes a compact binary `U3DM` file with the assembly tree, per-node
triangle meshes (with exact surface normals) and the B-rep edge polylines.

It is compiled as a **fully static musl executable** so that it can ship
inside the Android APK as `lib/arm64-v8a/libu3dconv.so` (the file name only
has to look like a shared library so the packager extracts it; it is a plain
PIE/static executable that the app runs as a subprocess).  An x86_64 variant
of the same program is built for host-side testing.

```
u3dconv <input> <output.u3dm> [--quality low|medium|high] [--heal]
```

* Input format is detected by extension (`.step/.stp` → STEP, `.iges/.igs` →
  IGES) and by content sniffing (`ISO-10303-21` in the head → STEP; `S` in
  column 73 of an 80-column record → IGES).  Content wins over the extension.
  A STEP file without the trailing `END-ISO-10303-21;` is reported as
  truncated.
* `--quality` chooses the linear deflection as a fraction of the bounding-box
  diagonal (low 0.004, medium 0.0015, high 0.0005; clamped to ≥ 1e-4 mm) and
  the angular deflection (0.5 / 0.3 / 0.15 rad).  Default is `medium`.
* `--heal` runs `ShapeFix_Shape` on every leaf shape before meshing.
* Units: OCCT converts everything to millimetres while reading, so the file
  always carries `unitToMm = 1.0`.  The unit declared in the source file is
  reported as `INFO fileUnit=...`.

### stdout / stderr protocol

```
PROGRESS 0.05         read   (0.05 → 0.10)
PROGRESS 0.10..0.50   transfer to document (OCCT progress indicator)
PROGRESS 0.50..0.88   meshing (OCCT progress indicator, per face)
PROGRESS 0.90..1.00   write
INFO format=STEP
INFO xcaf=1
INFO fileUnit=MM
INFO faces=.. edges=..
INFO bbox=xmin,ymin,zmin,xmax,ymax,zmax
INFO quality=medium linDefl=.. angDefl=.. diag=..
INFO nodes=N triangles=T vertices=V edgeVertices=.. edgeSegments=.. colours=0|1
INFO timeMs=..
```

On failure a single `ERROR <message>` line is printed to stderr and the
output file is removed.  Exit codes:

| code | meaning |
|------|---------|
| 0 | success |
| 1 | other error (bad arguments, unexpected exception, out of memory) |
| 2 | parse/read failure, corrupt or unsupported content (incl. truncated STEP) |
| 3 | file read but it contains no geometry / no triangles could be produced |
| 4 | I/O error (cannot open input, cannot write output) |

All `Standard_Failure` and `std::exception`s are caught in `main`; OCCT's
signal handlers are installed with `OSD::SetSignal(false)` (no floating-point
trapping, which is glibc-only anyway).

## Reader path: XCAF

The converter uses the XDE/XCAF readers (`STEPCAFControl_Reader`,
`IGESCAFControl_Reader`, colour/name/layer modes on) and walks the
`XCAFDoc_ShapeTool` label tree:

* `GetFreeShapes` → root nodes; `GetComponents` → children; references are
  resolved with `IsReference`/`GetReferredShape`; locations are accumulated
  with `GetLocation`.
* Node name: `TDataStd_Name` of the label, else of the referred label, else
  `Part N`.
* Node colour: `XCAFDoc_ColorTool::GetColor` with `ColorSurf`, `ColorGen`,
  `ColorCurv` on the label, then on the referred label, then the first face
  colour found in the shape.  Nodes without any colour get alpha 0.
* Instances are flattened: a part used twice yields two nodes with duplicated
  (transformed) geometry.
* A leaf whose shape holds several solids gets one child per solid (`Solid N`)
  plus a `Free faces` child for faces outside any solid.

The non-XCAF path (`STEPControl_Reader`/`IGESControl_Reader`, names from
`StepRepr_RepresentationItem::Name()` via `XSControl_TransferReader::
EntityFromShapeResult`) is kept in `main.cpp` under `#ifdef U3DCONV_NO_XCAF`
as a fallback but is not used by the shipped binaries.

## Geometry extraction

For every face (`TopExp_Explorer`): `BRep_Tool::Triangulation(face, loc)`,
nodes transformed by `loc`, triangle winding flipped for
`TopAbs_REVERSED` faces so the front side is counter-clockwise.  Normals are
the exact surface normals evaluated with `GeomLProp_SLProps` at every UV node
(flipped for reversed faces, rotated by the surface location); nodes where
`IsNormalDefined()` is false (poles, degenerate points) get area-weighted
accumulated triangle normals.  Faces are *not* welded, so sharp edges are
preserved.

Edge polylines: for every unique edge (`TopExp::MapShapes`) the
`Poly_PolygonOnTriangulation` of one adjacent face is used, else
`BRep_Tool::Polygon3D`, else the curve is sampled with
`GCPnts_TangentialDeflection`.  They are stored per node as a separate vertex
array and a line list.

## U3DM file format (version 1, little-endian)

```
char[4]  'U','3','D','M'
u32      version = 1
u32      flags          bit0 = file carries colours
f64      unitToMm       always 1.0 (geometry is in mm)
u32      nodeCount
per node (parents are written before children; id == write order):
  i32  id
  i32  parentId         -1 for roots
  u32  nameLen, u8[nameLen]   UTF-8 name
  u8   r, g, b, a       a == 0 means "no colour assigned"
  u32  vertexCount,  f32[3*vertexCount]   xyz
  u8   hasNormals,   if 1: f32[3*vertexCount]
  u32  triIndexCount (multiple of 3), u32[triIndexCount]
  u32  edgeVertexCount, f32[3*edgeVertexCount]
  u32  edgeIndexCount (multiple of 2), u32[edgeIndexCount]
```

`check_u3dm.py <file.u3dm>` parses a file, validates indices/normals and
prints the node tree with names, colours and counts.

## How the binary is produced

Everything is driven by `build.sh`:

```sh
OCCT_SRC=/path/to/occt-V7_6_3 \
ZIG=/path/to/site-packages/ziglang/zig \      # or ZIG_CMD="python -m ziglang"
BUILD_DIR=/tmp/work/occt-build \
JNILIBS=app/src/main/jniLibs \
./native/converter/build.sh
```

1. `apply_patches.sh` applies `patches/*.patch` to the OCCT tree (idempotent).
2. A flat include directory with symlinks to every `*.hxx/*.lxx/*.gxx/*.pxx`
   of `src/` is created (OCCT headers include each other flatly).
3. `gen_ninja.py` writes a `build.ninja` per target that compiles every
   package of the toolkits below (sources taken from each package's `FILES`
   list), archives one `libTKxxx.a` per toolkit and links `main.cpp` against
   them inside `-Wl,--start-group … --end-group`.
4. `ninja` builds; the link uses `-static -s` (stripped).

Compiler: Zig 0.15.2's bundled clang, `zig c++ -target
{x86_64,aarch64}-linux-musl`, flags
`-std=c++17 -O2 -fPIC -DNDEBUG -DOCC_CONVERT_SIGNALS -DHAVE_FREEIMAGE=0
-DU3DCONV_NO_GRAPHIC3D -w` (`-mcpu=generic` for aarch64).  Exceptions and
RTTI stay enabled.  No TBB, no FreeImage, no OpenGL/X11/FreeType.

### Toolkits compiled

TKernel TKMath TKG2d TKG3d TKGeomBase TKBRep TKGeomAlgo TKTopAlgo TKPrim TKBO
TKBool TKShHealing TKMesh TKXSBase TKSTEPBase TKSTEPAttr TKSTEP209 TKSTEP
TKIGES TKCDF TKLCAF TKCAF TKXCAF (without package `XCAFPrs`) TKXDESTEP
TKXDEIGES.

Skipped sources: Windows/macOS-only files (`*_WNT.cxx`, …),
`OSD_Parallel_TBB.cxx`, `STEPCAFControl_Writer.cxx` (needs `XCAFPrs`, only
the reader is used).  No object from TKV3d/TKService is needed.

### Patches (`patches/`)

| patch | why |
|-------|-----|
| `01-musl-OSD_signal.patch` | `feenableexcept`/`fedisableexcept`/`fegetexcept` are glibc extensions; FPE trapping is compiled out when `__GLIBC__` is not defined. |
| `02-musl-OSD_MemInfo.patch` | `mallinfo()` is glibc-only; heap-usage counter compiled out on musl. |
| `03-musl-Standard_StackTrace.patch` | `execinfo.h`/`backtrace()` are glibc-only; `Standard::StackTrace()` returns false on musl. |
| `04-noviz-XCAFApp_Application.patch` | drops the registration of `XCAFPrs_Driver` (TKVCAF/TKV3d presentation driver) in the XCAF application constructor. |
| `05-noviz-XCAFDoc_VisMaterial.patch` | compiles out `FillMaterialAspect()`/`FillAspect()` (need `Graphic3d_Aspects`, textures) under `U3DCONV_NO_GRAPHIC3D` and inlines the two static `Graphic3d_PBRMaterial` helper formulas used by `ConvertToPbrMaterial()`. |

## Testing

```
python3 native/converter/check_u3dm.py out.u3dm
qemu-aarch64-static /tmp/work/occt-build/out/u3dconv-aarch64 in.step out.u3dm
```

See the "Results" section below for the measured numbers.

RESULTS_PLACEHOLDER
