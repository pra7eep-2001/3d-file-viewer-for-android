#!/usr/bin/env python3
"""Parse and validate a U3DM (version 1) file written by u3dconv and print its
node tree.

Usage: check_u3dm.py <file.u3dm> [--verbose]
Exit code 0 when the file parses and is self-consistent, 1 otherwise.
"""
import math
import struct
import sys


class Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, fmt):
        size = struct.calcsize(fmt)
        if self.p + size > len(self.d):
            raise ValueError("unexpected end of file at offset %d" % self.p)
        v = struct.unpack_from(fmt, self.d, self.p)
        self.p += size
        return v if len(v) > 1 else v[0]

    def floats(self, n):
        size = 4 * n
        if self.p + size > len(self.d):
            raise ValueError("unexpected end of file at offset %d (need %d floats)" % (self.p, n))
        v = struct.unpack_from("<%df" % n, self.d, self.p)
        self.p += size
        return v

    def u32s(self, n):
        size = 4 * n
        if self.p + size > len(self.d):
            raise ValueError("unexpected end of file at offset %d (need %d u32)" % (self.p, n))
        v = struct.unpack_from("<%dI" % n, self.d, self.p)
        self.p += size
        return v


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    verbose = "--verbose" in sys.argv
    data = open(path, "rb").read()
    r = Reader(data)
    magic = r.take("<4s")
    if magic != b"U3DM":
        print("bad magic %r" % magic)
        return 1
    version = r.take("<I")
    flags = r.take("<I")
    unit = r.take("<d")
    node_count = r.take("<I")
    print("U3DM v%d flags=0x%x (colours=%d) unitToMm=%g nodes=%d size=%d bytes" % (
        version, flags, flags & 1, unit, node_count, len(data)))
    if version != 1:
        print("unsupported version")
        return 1

    nodes = []
    tot_v = tot_t = tot_ev = tot_e = 0
    xmin = [math.inf] * 3
    xmax = [-math.inf] * 3
    ok = True
    for i in range(node_count):
        nid = r.take("<i")
        parent = r.take("<i")
        name_len = r.take("<I")
        name = r.take("<%ds" % name_len).decode("utf-8", "replace")
        rgba = r.take("<4B")
        vc = r.take("<I")
        verts = r.floats(3 * vc)
        has_n = r.take("<B")
        normals = r.floats(3 * vc) if has_n else ()
        tic = r.take("<I")
        tris = r.u32s(tic)
        evc = r.take("<I")
        everts = r.floats(3 * evc)
        eic = r.take("<I")
        edges = r.u32s(eic)

        if nid != i:
            print("node %d: id mismatch (%d)" % (i, nid)); ok = False
        if parent >= i or parent < -1:
            print("node %d: parent %d not written before child" % (i, parent)); ok = False
        if tic % 3:
            print("node %d: triIndexCount %d not multiple of 3" % (i, tic)); ok = False
        if eic % 2:
            print("node %d: edgeIndexCount %d not multiple of 2" % (i, eic)); ok = False
        if tris and max(tris) >= vc:
            print("node %d: triangle index out of range" % i); ok = False
        if edges and max(edges) >= evc:
            print("node %d: edge index out of range" % i); ok = False
        for k in range(0, len(verts), 3):
            for a in range(3):
                v = verts[k + a]
                if not math.isfinite(v):
                    print("node %d: non-finite vertex" % i); ok = False; break
                xmin[a] = min(xmin[a], v); xmax[a] = max(xmax[a], v)
        if has_n:
            bad = 0
            for k in range(0, len(normals), 3):
                l = math.sqrt(sum(normals[k + a] ** 2 for a in range(3)))
                if abs(l - 1.0) > 1e-2:
                    bad += 1
            if bad:
                print("node %d: %d normals are not unit length" % (i, bad)); ok = False
        nodes.append((nid, parent, name, rgba, vc, has_n, tic // 3, evc, eic // 2))
        tot_v += vc; tot_t += tic // 3; tot_ev += evc; tot_e += eic // 2

    if r.p != len(data):
        print("trailing bytes: %d" % (len(data) - r.p)); ok = False

    # print tree
    children = {}
    for n in nodes:
        children.setdefault(n[1], []).append(n)

    def show(parent, depth):
        for n in children.get(parent, []):
            nid, _, name, rgba, vc, has_n, nt, evc, ne = n
            col = "#%02x%02x%02x a=%d" % rgba if rgba[3] else "no-colour"
            print("%s[%d] %r  colour=%s  verts=%d normals=%d tris=%d edgeVerts=%d edgeSegs=%d" % (
                "  " * depth, nid, name, col, vc, has_n, nt, evc, ne))
            show(nid, depth + 1)

    show(-1, 0)
    print("TOTAL vertices=%d triangles=%d edgeVertices=%d edgeSegments=%d" % (tot_v, tot_t, tot_ev, tot_e))
    if tot_v:
        print("BBOX min=(%.3f, %.3f, %.3f) max=(%.3f, %.3f, %.3f) size=(%.3f, %.3f, %.3f)" % (
            xmin[0], xmin[1], xmin[2], xmax[0], xmax[1], xmax[2],
            xmax[0] - xmin[0], xmax[1] - xmin[1], xmax[2] - xmin[2]))
    print("RESULT", "OK" if ok else "INVALID")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
