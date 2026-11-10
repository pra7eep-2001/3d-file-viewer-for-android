package com.universal3d.viewer.model

/** Open-addressing hash map from Long to Int without boxing (used for vertex welding on large meshes). */
class LongIntMap(expected: Int = 1024) {
    private var keys: LongArray
    private var vals: IntArray
    private var used: BooleanArray
    private var mask: Int
    var size = 0
        private set

    init {
        var cap = 16; while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap); vals = IntArray(cap); used = BooleanArray(cap); mask = cap - 1
    }

    private fun hash(k: Long): Int { var h = k * -0x61c8864680b583ebL; h = h xor (h ushr 32); return h.toInt() and mask }

    /** Returns existing value for key, or inserts [value] and returns -1. */
    fun getOrPut(key: Long, value: Int): Int {
        if (size * 2 >= keys.size) grow()
        var i = hash(key)
        while (used[i]) { if (keys[i] == key) return vals[i]; i = (i + 1) and mask }
        used[i] = true; keys[i] = key; vals[i] = value; size++
        return -1
    }

    fun get(key: Long, default: Int = -1): Int {
        var i = hash(key)
        while (used[i]) { if (keys[i] == key) return vals[i]; i = (i + 1) and mask }
        return default
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        val cap = ok.size shl 1
        keys = LongArray(cap); vals = IntArray(cap); used = BooleanArray(cap); mask = cap - 1; size = 0
        for (i in ok.indices) if (ou[i]) getOrPut(ok[i], ov[i])
    }
}

/** Growable primitive float list for streaming importers. */
class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial); var size = 0
    fun add(v: Float) { if (size == data.size) data = data.copyOf(maxOf(16, data.size * 2)); data[size++] = v }
    fun add(x: Float, y: Float, z: Float) { ensure(3); data[size++] = x; data[size++] = y; data[size++] = z }
    fun ensure(n: Int) { if (size + n > data.size) { var c = maxOf(16, data.size * 2); while (c < size + n) c *= 2; data = data.copyOf(c) } }
    operator fun get(i: Int) = data[i]
    operator fun set(i: Int, v: Float) { data[i] = v }
    fun toArray(): FloatArray = data.copyOf(size)
    fun clear() { size = 0 }
}

/** Growable primitive int list. */
class IntList(initial: Int = 1024) {
    var data = IntArray(initial); var size = 0
    fun add(v: Int) { if (size == data.size) data = data.copyOf(maxOf(16, data.size * 2)); data[size++] = v }
    fun add(a: Int, b: Int) { ensure(2); data[size++] = a; data[size++] = b }
    fun add(a: Int, b: Int, c: Int) { ensure(3); data[size++] = a; data[size++] = b; data[size++] = c }
    fun ensure(n: Int) { if (size + n > data.size) { var c = maxOf(16, data.size * 2); while (c < size + n) c *= 2; data = data.copyOf(c) } }
    operator fun get(i: Int) = data[i]
    fun toArray(): IntArray = data.copyOf(size)
    fun clear() { size = 0 }
}
