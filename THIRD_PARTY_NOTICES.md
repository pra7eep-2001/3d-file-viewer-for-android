# Third-party notices

## Open CASCADE Technology 7.6.3

The STEP/IGES converter shipped in the APK (`lib/<abi>/libu3dconv.so`, built from `native/converter/`) is
statically linked with **Open CASCADE Technology (OCCT) 7.6.3**, https://dev.opencascade.org,
© OPEN CASCADE SAS, licensed under the **GNU Lesser General Public License version 2.1** with the
**Open CASCADE exception 1.0**. License texts: `third_party/opencascade/LICENSE_LGPL_21.txt` and
`third_party/opencascade/OCCT_LGPL_EXCEPTION.txt`.

Universal 3D Viewer makes use of facilities provided by the Open CASCADE Technology software.

LGPL compliance for the statically linked converter: the complete corresponding source is available —
OCCT 7.6.3 itself (the `opencascade_7.6.3+dfsg1.orig.tar.xz` source distribution), the five patches applied to
it (`native/converter/patches/`), the converter's own source (`native/converter/main.cpp`) and the scripts that
build and link it (`native/converter/build.sh`, `gen_ninja.py`). Anyone can rebuild the converter against a
modified OCCT with those files, as described in `native/converter/README.md`. The converter runs as a separate
process; the rest of the app does not link against OCCT.

## Sample files

* `samples/screw.step`, `samples/linkrods.step`, `samples/bearing.iges`, `samples/hammer.iges` are from the OCCT 7.6.3
  `data/` directory (LGPL 2.1 with the Open CASCADE exception, see above).
* `samples/as1-oc-214.stp` is the NIST "AS1" STEP test assembly as distributed with pythonocc-demos.
* Test resources under `app/src/test/resources/` list their origin in `app/src/test/resources/SAMPLES.md`.

## Build-time tools (not shipped in the APK)

Kotlin standard library (Apache 2.0, shipped in the APK as dex), Gradle, D8/R8, aapt2, apksigner, Zig,
Robolectric, JUnit, LWJGL and Mesa are used only to build or test the app.
