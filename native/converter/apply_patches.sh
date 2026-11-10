#!/bin/sh
# Apply the u3dconv patches to a pristine OCCT 7.6.3 source tree.
# Usage: apply_patches.sh <occt-source-root>   (the directory containing src/)
# Idempotent: a patch that is already applied is skipped.
set -e
SRC="${1:?usage: apply_patches.sh <occt-source-root>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
for p in "$HERE"/patches/*.patch; do
  if patch -p1 -d "$SRC" --dry-run -R -s -f < "$p" >/dev/null 2>&1; then
    echo "already applied: $(basename "$p")"
  else
    echo "applying: $(basename "$p")"
    patch -p1 -d "$SRC" -s < "$p"
  fi
done
