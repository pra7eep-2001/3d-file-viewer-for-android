#!/bin/sh
# One-shot build of u3dconv (static musl executables) for x86_64 (host test
# variant) and aarch64 (Android arm64-v8a, shipped as libu3dconv.so).
#
# Required environment:
#   OCCT_SRC   root of the OCCT 7.6.3 source tree (directory containing src/)
#   ZIG        the zig executable (e.g. .../site-packages/ziglang/zig), OR
#   ZIG_CMD    a full command prefix (e.g. "python -m ziglang")
# Optional:
#   BUILD_DIR  build directory (default /tmp/work/occt-build)
#   OUT_DIR    where the final binaries go (default $BUILD_DIR/out)
#   TARGETS    space-separated list (default "x86_64 aarch64")
#   JOBS       ninja parallelism (default: nproc)
#   JNILIBS    if set, the aarch64 binary is also copied to $JNILIBS/arm64-v8a/libu3dconv.so
#
# Steps: apply patches -> flat include dir -> gen_ninja.py -> ninja -> strip/copy.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OCCT_SRC="${OCCT_SRC:?set OCCT_SRC to the OCCT 7.6.3 source root}"
BUILD_DIR="${BUILD_DIR:-/tmp/work/occt-build}"
OUT_DIR="${OUT_DIR:-$BUILD_DIR/out}"
TARGETS="${TARGETS:-x86_64 aarch64}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

if [ -n "${ZIG_CMD:-}" ]; then
  ZIGBASE="$ZIG_CMD"
elif [ -n "${ZIG:-}" ]; then
  ZIGBASE="$ZIG"
else
  echo "set ZIG (path to zig binary) or ZIG_CMD (e.g. 'python -m ziglang')" >&2
  exit 1
fi

mkdir -p "$BUILD_DIR" "$OUT_DIR"

# 1. patches (idempotent)
"$HERE/apply_patches.sh" "$OCCT_SRC"

# 2. flat include directory with every OCCT header
INC="$BUILD_DIR/inc"
if [ ! -f "$INC/.complete" ]; then
  rm -rf "$INC"; mkdir -p "$INC"
  find "$OCCT_SRC/src" -maxdepth 2 \( -name '*.hxx' -o -name '*.lxx' -o -name '*.gxx' -o -name '*.pxx' -o -name '*.h' \) \
    -exec ln -sf {} "$INC/" \;
  touch "$INC/.complete"
fi

for T in $TARGETS; do
  CXX="$BUILD_DIR/zigcxx-$T.sh"
  AR="$BUILD_DIR/zigar-$T.sh"
  printf '#!/bin/sh\nexec %s c++ -target %s-linux-musl "$@"\n' "$ZIGBASE" "$T" > "$CXX"
  printf '#!/bin/sh\nexec %s ar "$@"\n' "$ZIGBASE" > "$AR"
  chmod +x "$CXX" "$AR"

  BDIR="$BUILD_DIR/$T"
  OUT="$OUT_DIR/u3dconv-$T"
  python3 "$HERE/gen_ninja.py" --src "$OCCT_SRC/src" --build "$BDIR" --inc "$INC" \
    --cxx "$CXX" --ar "$AR" --main "$HERE" --target "$T" --out "$OUT"
  START=$(date +%s)
  ninja -C "$BDIR" -j "$JOBS"
  END=$(date +%s)
  echo "built $OUT in $((END-START)) s, size $(stat -c %s "$OUT") bytes"
done

if [ -n "${JNILIBS:-}" ] && [ -f "$OUT_DIR/u3dconv-aarch64" ]; then
  mkdir -p "$JNILIBS/arm64-v8a"
  cp "$OUT_DIR/u3dconv-aarch64" "$JNILIBS/arm64-v8a/libu3dconv.so"
  echo "copied to $JNILIBS/arm64-v8a/libu3dconv.so"
fi
